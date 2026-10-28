"""Authenticated client for observation and dedicated acceptance actor sessions.

Credentials and response error bodies stay absent from public errors. Mutation
requests retain caller-supplied IDs and are never replayed after transport errors.
"""
from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import json
import math
from pathlib import Path
import re
from typing import Any
from urllib.parse import urlsplit
from uuid import uuid4

import httpx


class ConfigurationError(ValueError):
    """Safe configuration failure: its text never contains file contents."""


@dataclass(frozen=True)
class BridgeConfig:
    username: str = field(repr=False)
    password: str = field(repr=False)
    base_url: str = "http://127.0.0.1:8686"
    timeout_seconds: float = 10.0

    def __post_init__(self) -> None:
        if not isinstance(self.username, str) or not self.username.strip():
            raise ConfigurationError("配置必须包含非空 username。")
        if not isinstance(self.password, str) or not self.password:
            raise ConfigurationError("配置必须包含非空 password。")
        if not isinstance(self.base_url, str):
            raise ConfigurationError("base_url 必须是 HTTP 或 HTTPS 服务地址。")
        if self.base_url != self.base_url.strip() or any(ord(char) < 32 for char in self.base_url):
            raise ConfigurationError("base_url 不能包含空白控制字符。")
        try:
            address = urlsplit(self.base_url)
            # Accessing port also rejects malformed port syntax.
            _ = address.port
        except ValueError:
            raise ConfigurationError("base_url 不是有效的服务地址。") from None
        if (
            address.scheme not in {"http", "https"}
            or not address.hostname
            or address.username is not None
            or address.password is not None
            or address.path not in {"", "/"}
            or address.query
            or address.fragment
        ):
            raise ConfigurationError("base_url 必须是无凭据、路径、查询或片段的 HTTP/HTTPS 地址。")
        timeout = self.timeout_seconds
        if (
            isinstance(timeout, bool)
            or not isinstance(timeout, (int, float))
            or not math.isfinite(timeout)
            or not 0.1 <= timeout <= 30
        ):
            raise ConfigurationError("timeout_seconds 必须在 0.1～30 秒之间。")
        object.__setattr__(self, "base_url", self.base_url.rstrip("/"))


def load_config(path: Path) -> BridgeConfig:
    try:
        if path.stat().st_size > 65_536:
            raise ConfigurationError("配置文件过大。")
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise ConfigurationError("配置文件无法读取，或不是有效的 UTF-8 JSON。") from None
    if not isinstance(raw, dict) or set(raw) - {
        "username", "password", "base_url", "timeout_seconds"
    }:
        raise ConfigurationError("配置必须是仅包含受支持字段的 JSON 对象。")
    try:
        return BridgeConfig(**raw)
    except TypeError:
        raise ConfigurationError("配置缺少 username 或 password。") from None


class BridgeError(Exception):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        http_status: int | None = None,
        retryable: bool = False,
        backend_code: int | None = None,
        request_id: str | None = None,
        outcome_unknown: bool = False,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.http_status = http_status
        self.retryable = retryable
        self.backend_code = backend_code
        self.request_id = request_id
        self.outcome_unknown = outcome_unknown

    def public_data(self) -> dict[str, Any]:
        error: dict[str, Any] = {
            "code": self.code,
            "message": self.message,
            "retryable": self.retryable,
        }
        if self.http_status is not None:
            error["http_status"] = self.http_status
        if self.backend_code is not None:
            error["backend_code"] = self.backend_code
        if self.request_id is not None:
            error["outcome_unknown"] = self.outcome_unknown
        data: dict[str, Any] = {"error": error}
        if self.request_id is not None:
            data["request_id"] = self.request_id
        return data


def validate_observation(
    scope: str, scene_ref: str | None, limit: int, *, session_id: str | None = None,
    expected_generation: int | None = None, since_cursor: int = 0,
) -> None:
    if not isinstance(scope, str) or scope not in {"world", "scene", "actor"}:
        raise BridgeError("INVALID_ARGUMENTS", "scope 只支持 world、scene 或 actor。")
    if isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= 500:
        raise BridgeError("INVALID_ARGUMENTS", "limit 必须是 1～500 之间的整数。")
    if scene_ref is not None and (
        not isinstance(scene_ref, str)
        or not scene_ref.strip()
        or len(scene_ref) > 256
        or any(ord(char) < 32 for char in scene_ref)
    ):
        raise BridgeError("INVALID_ARGUMENTS", "scene_ref 必须是有效的场景引用。")
    if scope == "scene" and scene_ref is None:
        raise BridgeError("INVALID_ARGUMENTS", "观察 scene 需要先从 world 观察取得 scene_ref。")
    if scope == "world" and scene_ref is not None:
        raise BridgeError("INVALID_ARGUMENTS", "world 观察不接受 scene_ref。")
    validate_integer(since_cursor, "since_cursor", 0, MAX_LONG)
    if scope == "actor":
        if scene_ref is not None or session_id is None or expected_generation is None:
            raise BridgeError("INVALID_ARGUMENTS", "actor 观察需要 session_id 和 expected_generation，不接受 scene_ref。")
        validate_identifier(session_id, "session_id")
        validate_integer(expected_generation, "expected_generation", 1, MAX_LONG)
    elif session_id is not None or expected_generation is not None or since_cursor != 0:
        raise BridgeError("INVALID_ARGUMENTS", "world 和 scene 观察不接受角色会话参数或非零包游标。")


MAX_LONG = 2**63 - 1
MAX_INT = 2**31 - 1
IDENTIFIER = re.compile(r"[A-Za-z0-9_.-]{1,128}")
PROFILE_IDENTIFIER = re.compile(r"[A-Za-z0-9_.-]{1,64}")
REQUEST_IDENTIFIER = re.compile(r"[A-Za-z0-9_.:-]{1,128}")


def validate_identifier(value: str, label: str, pattern: re.Pattern[str] = IDENTIFIER) -> None:
    if not isinstance(value, str) or value in {".", ".."} or pattern.fullmatch(value) is None:
        raise BridgeError("INVALID_ARGUMENTS", label + " 必须是有效的标识符。")


def validate_integer(value: int, label: str, minimum: int, maximum: int) -> None:
    if type(value) is not int or not minimum <= value <= maximum:
        raise BridgeError("INVALID_ARGUMENTS", label + " 必须是允许范围内的整数。")


def validate_chat_action(action: str, parameters: dict[str, Any], expected_context: dict[str, Any]) -> None:
    if not isinstance(action, str) or action not in {"chat", "npc_center_open", "npc_cancel", "npc_reply", "inventory_move"}:
        raise BridgeError("INVALID_ARGUMENTS", "action 只支持 chat、npc_center_open、npc_cancel、npc_reply 或 inventory_move。")
    if not isinstance(parameters, dict):
        raise BridgeError("INVALID_ARGUMENTS", "parameters 必须为当前动作对应的对象。")
    if action == "chat":
        if set(parameters) != {"text"}:
            raise BridgeError("INVALID_ARGUMENTS", "chat parameters 必须且只能包含 text。")
        text = parameters["text"]
        if (not isinstance(text, str) or not text or text.isspace() or text[0] in {"@", "/"}
                or sum(2 if ord(char) > 0xFFFF else 1 for char in text) > 127
                or any(0xD800 <= ord(char) <= 0xDFFF for char in text)):
            raise BridgeError("INVALID_ARGUMENTS", "text 必须是1～127个 UTF-16 单元的非空白普通聊天，首字符不能为 @ 或 /。")
    elif action == "npc_center_open":
        if parameters:
            raise BridgeError("INVALID_ARGUMENTS", "npc_center_open parameters 必须为空对象。")
    elif action == "npc_cancel":
        if set(parameters) != {"dialogue_version"}:
            raise BridgeError("INVALID_ARGUMENTS", "npc_cancel parameters 必须且只能包含 dialogue_version。")
        validate_integer(parameters["dialogue_version"], "dialogue_version", 1, MAX_LONG)
    elif action == "inventory_move":
        if set(parameters) != {"inventory_type", "source_slot", "target_slot"}:
            raise BridgeError("INVALID_ARGUMENTS", "inventory_move parameters 必须且只能包含 inventory_type、source_slot 和 target_slot。")
        inventory_type = parameters["inventory_type"]
        if not isinstance(inventory_type, str) or inventory_type not in {"EQUIP", "USE", "SETUP", "ETC", "CASH"}:
            raise BridgeError("INVALID_ARGUMENTS", "inventory_type 只支持 EQUIP、USE、SETUP、ETC 或 CASH。")
        validate_integer(parameters["source_slot"], "source_slot", 1, 127)
        validate_integer(parameters["target_slot"], "target_slot", 1, 127)
        if parameters["source_slot"] == parameters["target_slot"]:
            raise BridgeError("INVALID_ARGUMENTS", "source_slot 和 target_slot 必须不同。")
    else:
        kind = parameters.get("kind")
        if not isinstance(kind, str) or kind not in {"select", "decline"}:
            raise BridgeError("INVALID_ARGUMENTS", "npc_reply 当前只支持 select 或 decline。")
        expected = {"dialogue_version", "kind", "selection"} if kind == "select" else {"dialogue_version", "kind"}
        if set(parameters) != expected:
            raise BridgeError("INVALID_ARGUMENTS", "npc_reply 参数必须与 kind 严格匹配。")
        validate_integer(parameters["dialogue_version"], "dialogue_version", 1, MAX_LONG)
        if kind == "select":
            validate_integer(parameters["selection"], "selection", 0, MAX_INT)
    if not isinstance(expected_context, dict) or set(expected_context) != {"scene_ref", "runtime_generation"}:
        raise BridgeError("INVALID_ARGUMENTS", "expected_context 必须且只能包含 scene_ref 和 runtime_generation。")
    for key in ["scene_ref", "runtime_generation"]:
        validate_identifier(expected_context[key], key)


def validate_action_request(session_id: str, expected_generation: int, request_id: str, action: str,
                            parameters: dict[str, Any], expected_context: dict[str, Any]) -> None:
    validate_identifier(session_id, "session_id")
    validate_integer(expected_generation, "expected_generation", 1, MAX_LONG)
    validate_identifier(request_id, "request_id", REQUEST_IDENTIFIER)
    validate_chat_action(action, parameters, expected_context)


def validate_command_help(command: str | None = None) -> None:
    if command is not None and (not isinstance(command, str) or command != "give"):
        raise BridgeError("INVALID_ARGUMENTS", "command 当前只支持 give。")


def validate_command_request(profile_id: str, request_id: str, command: str,
                             parameters: dict[str, Any], expected_command_generation: str) -> None:
    validate_identifier(profile_id, "profile_id", PROFILE_IDENTIFIER)
    validate_identifier(request_id, "request_id", REQUEST_IDENTIFIER)
    validate_identifier(expected_command_generation, "expected_command_generation")
    if not isinstance(command, str) or command != "give":
        raise BridgeError("INVALID_ARGUMENTS", "command 当前只支持 give。")
    if not isinstance(parameters, dict) or set(parameters) != {"item_id", "quantity"}:
        raise BridgeError("INVALID_ARGUMENTS", "give parameters 必须且只能包含 item_id 和 quantity。")
    validate_integer(parameters["item_id"], "item_id", 1, MAX_INT)
    validate_integer(parameters["quantity"], "quantity", 1, 32767)


class BridgeClient:
    MAX_RESPONSE_BYTES = 4 * 1024 * 1024

    def __init__(self, config: BridgeConfig) -> None:
        self._config = config
        self._token: str | None = None
        self._auth_lock = asyncio.Lock()
        self._http = httpx.AsyncClient(
            base_url=config.base_url,
            timeout=config.timeout_seconds,
            follow_redirects=False,
            trust_env=False,
        )

    async def __aenter__(self) -> BridgeClient:
        return self

    async def __aexit__(self, *unused: object) -> None:
        await self.close()

    async def close(self) -> None:
        self._token = None
        await self._http.aclose()

    async def capabilities(self) -> dict[str, Any]:
        return await self._authorized_request("GET", "/solo/acceptance/v1/capabilities")

    async def command_help(self, command: str | None = None) -> dict[str, Any]:
        validate_command_help(command)
        path = "/solo/acceptance/v1/command/help"
        if command is not None:
            path += "?command=" + command
        return await self._authorized_request("GET", path)

    async def command(
        self, profile_id: str, *, request_id: str, command: str, parameters: dict[str, Any],
        expected_command_generation: str,
    ) -> dict[str, Any]:
        validate_command_request(profile_id, request_id, command, parameters, expected_command_generation)
        try:
            return await self._mutation("/solo/acceptance/v1/command/execute", {
                "profile_id": profile_id, "command": command, "parameters": dict(parameters),
                "expected_command_generation": expected_command_generation,
            }, request_id)
        except BridgeError as failure:
            if failure.http_status != 409:
                raise
            raise BridgeError(
                "COMMAND_CONFLICT", "请求与指令进程代次或历史请求冲突，请保留原 request_id 和原指令代次核对结果，不自动重发。",
                http_status=409, retryable=False, backend_code=failure.backend_code,
                request_id=request_id, outcome_unknown=failure.outcome_unknown,
            ) from None

    async def observe(
        self, scope: str = "world", scene_ref: str | None = None, limit: int = 100,
        *, session_id: str | None = None, expected_generation: int | None = None,
        since_cursor: int = 0,
    ) -> dict[str, Any]:
        validate_observation(scope, scene_ref, limit, session_id=session_id,
                             expected_generation=expected_generation, since_cursor=since_cursor)
        if scope == "actor":
            try:
                return await self._authorized_request(
                    "POST", "/solo/acceptance/v1/actor/observe",
                    {"requestId": str(uuid4()), "data": {
                        "session_id": session_id, "expected_generation": expected_generation,
                        "since_cursor": since_cursor, "limit": limit,
                    }},
                )
            except BridgeError as failure:
                if failure.http_status != 410:
                    raise
                raise BridgeError(
                    "SESSION_EXPIRED",
                    "角色会话已失效，请重新取得有效的 session_id 和 session_generation（作为 expected_generation）；"
                    "需要重新打开角色时，用新 request_id 调用 actor_open 并确认成功。",
                    http_status=410, retryable=False, backend_code=failure.backend_code,
                ) from None
        data: dict[str, Any] = {"scope": scope, "limit": limit}
        if scene_ref is not None:
            data["scene_ref"] = scene_ref
        return await self._authorized_request(
            "POST", "/solo/acceptance/v1/observe", {"requestId": str(uuid4()), "data": data}
        )

    async def actor_open(
        self, profile_id: str, *, request_id: str,
        world: int | None = None, channel: int | None = None,
    ) -> dict[str, Any]:
        validate_identifier(profile_id, "profile_id", PROFILE_IDENTIFIER)
        validate_identifier(request_id, "request_id", REQUEST_IDENTIFIER)
        if world is not None:
            validate_integer(world, "world", 0, 255)
        if channel is not None:
            validate_integer(channel, "channel", 1, 255)
        data: dict[str, Any] = {"profile_id": profile_id}
        if world is not None:
            data["world"] = world
        if channel is not None:
            data["channel"] = channel
        return await self._mutation("/solo/acceptance/v1/actor/open", data, request_id)

    async def actor_close(
        self, session_id: str, expected_generation: int, *, request_id: str,
    ) -> dict[str, Any]:
        validate_identifier(session_id, "session_id")
        validate_integer(expected_generation, "expected_generation", 1, MAX_LONG)
        validate_identifier(request_id, "request_id", REQUEST_IDENTIFIER)
        return await self._mutation(
            "/solo/acceptance/v1/actor/close",
            {"session_id": session_id, "expected_generation": expected_generation}, request_id,
        )

    async def act(
        self, session_id: str, expected_generation: int, *, request_id: str,
        action: str, parameters: dict[str, Any], expected_context: dict[str, Any],
    ) -> dict[str, Any]:
        validate_action_request(session_id, expected_generation, request_id, action, parameters, expected_context)
        return await self._mutation("/solo/acceptance/v1/actor/act", {
            "session_id": session_id, "expected_generation": expected_generation, "action": action,
            "parameters": dict(parameters), "expected_context": dict(expected_context),
        }, request_id)

    async def operation_get(
        self, operation_id: str, *, since_cursor: int = 0, limit: int = 100,
    ) -> dict[str, Any]:
        validate_identifier(operation_id, "operation_id")
        validate_integer(since_cursor, "since_cursor", 0, MAX_LONG)
        validate_integer(limit, "limit", 1, 500)
        path = ("/solo/acceptance/v1/operation/" + operation_id
                + "?since_cursor=" + str(since_cursor) + "&limit=" + str(limit))
        return await self._authorized_request("GET", path)

    async def operation_cancel(self, operation_id: str, *, request_id: str) -> dict[str, Any]:
        validate_identifier(operation_id, "operation_id")
        validate_identifier(request_id, "request_id", REQUEST_IDENTIFIER)
        return await self._mutation("/solo/acceptance/v1/operation/" + operation_id + "/cancel", {}, request_id)

    async def _mutation(self, path: str, data: dict[str, Any], request_id: str) -> dict[str, Any]:
        try:
            response = await self._authorized_request("POST", path, {"requestId": request_id, "data": data})
            if response.get("request_id", request_id) != request_id:
                raise BridgeError("INVALID_RESPONSE", "验收桥返回的请求标识不一致。")
            # A generated server operation ID does not replace the caller's deduplication key.
            return {**response, "request_id": request_id}
        except BridgeError as failure:
            code, message = failure.code, failure.message
            if failure.http_status == 409:
                code, message = "OPERATION_CONFLICT", "请求与当前角色会话或操作状态冲突。"
            elif failure.http_status == 410:
                code, message = "SESSION_EXPIRED", "角色会话或操作已失效。"
            unknown = failure.code in {"HTTP_TIMEOUT", "HTTP_UNAVAILABLE", "INVALID_RESPONSE"}
            unknown = unknown or failure.http_status is not None and failure.http_status >= 500
            raise BridgeError(
                code, message, http_status=failure.http_status,
                retryable=False, backend_code=failure.backend_code,
                request_id=request_id, outcome_unknown=unknown,
            ) from None

    async def _token_for_request(self, stale_token: str | None = None) -> str:
        async with self._auth_lock:
            # Concurrent callers that got the same 401 share a single refresh.
            if self._token is not None and (
                stale_token is None or self._token != stale_token
            ):
                return self._token
            self._token = None
            response = await self._send(
                "POST",
                "/auth/v1/login",
                {
                    "requestId": str(uuid4()),
                    "data": {
                        "username": self._config.username,
                        "password": self._config.password,
                    },
                },
            )
            data = self._decode(response)
            token = data.get("token")
            if not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9._~-]{1,16384}", token):
                raise BridgeError("INVALID_RESPONSE", "管理端未返回有效的访问令牌。")
            self._token = token
            return token

    async def _authorized_request(
        self, method: str, path: str, body: dict[str, Any] | None = None
    ) -> dict[str, Any]:
        token = await self._token_for_request()
        response = await self._send(method, path, body, token)
        if response.status_code == 401:
            token = await self._token_for_request(stale_token=token)
            response = await self._send(method, path, body, token)
        return self._decode(response)

    async def _send(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        token: str | None = None,
    ) -> httpx.Response:
        headers = {"Accept": "application/json"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        try:
            # Bound response memory even when a reverse proxy returns a large error body.
            async with self._http.stream(method, path, json=body, headers=headers) as response:
                content = bytearray()
                async for part in response.aiter_bytes():
                    content.extend(part)
                    if len(content) > self.MAX_RESPONSE_BYTES:
                        raise BridgeError("INVALID_RESPONSE", "验收桥响应超过允许大小。")
                await response.aclose()
                return httpx.Response(response.status_code, content=bytes(content))
        except httpx.TimeoutException:
            raise BridgeError("HTTP_TIMEOUT", "验收桥请求超时。", retryable=True) from None
        except httpx.RequestError:
            raise BridgeError("HTTP_UNAVAILABLE", "无法连接验收桥。", retryable=True) from None

    @staticmethod
    def _decode(response: httpx.Response) -> dict[str, Any]:
        status = response.status_code
        if status != 200:
            if status == 401:
                code, message = "AUTHENTICATION_FAILED", "管理端鉴权失败。"
            elif status == 403:
                code, message = "ACCESS_DENIED", "当前管理账号无权调用验收桥。"
            elif status == 404:
                code, message = "BRIDGE_UNAVAILABLE", "验收桥未启用或此接口不可用。"
            elif status == 410:
                code, message = "STALE_SCENE", "场景引用已失效，请重新观察 world。"
            elif status == 409:
                code, message = "STALE_OBSERVATION", "读取期间游戏运行代次变化，请重新观察 world。"
            elif status == 429:
                code, message = "CAPACITY_REACHED", "验收操作或角色会话已达到当前限额。"
            elif status == 400:
                code, message = "INVALID_ARGUMENTS", "验收桥拒绝了请求参数。"
            elif status == 503:
                code, message = "SERVER_OFFLINE", "游戏后端当前不可观察。"
            else:
                code, message = "HTTP_ERROR", "验收桥返回非成功 HTTP 状态。"
            raise BridgeError(code, message, http_status=status, retryable=status in {502, 503, 504})
        try:
            result = response.json()
        except (ValueError, UnicodeError):
            raise BridgeError("INVALID_RESPONSE", "验收桥未返回有效 JSON。") from None
        if not isinstance(result, dict):
            raise BridgeError("INVALID_RESPONSE", "验收桥返回了不支持的数据结构。")
        if result.get("code") != 20000:
            backend_code = result.get("code")
            raise BridgeError(
                "BACKEND_REJECTED",
                "管理端或验收桥拒绝了请求。",
                backend_code=backend_code if type(backend_code) is int else None,
            )
        data = result.get("data")
        if not isinstance(data, dict):
            raise BridgeError("INVALID_RESPONSE", "验收桥响应缺少结构化数据对象。")
        return data

