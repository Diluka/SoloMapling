"""Authenticated observation, actor session, and administrator command client.

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
import anyio


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
ACTION_NAMES = (
    "chat", "npc_center_open", "npc_cancel", "npc_reply", "inventory_move",
    "move", "stop", "attack", "pickup", "drop", "use_item", "equip", "unequip",
)
INVENTORY_TYPES = frozenset({"EQUIP", "USE", "SETUP", "ETC", "CASH"})
EQUIPMENT_SLOTS = (*range(-1, -14, -1), *range(-15, -20, -1), -49, -50)
IDENTIFIER = re.compile(r"[A-Za-z0-9_.-]{1,128}")
PROFILE_IDENTIFIER = re.compile(r"[A-Za-z0-9_.-]{1,64}")
REQUEST_IDENTIFIER = re.compile(r"[A-Za-z0-9_.:-]{1,128}")
SCRIPT_FUNCTION_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,63}")
NATIVE_SCRIPT_ENTRY = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]{0,127}")
SCRIPT_MAX_SOURCE_UNITS = 65_536
SCRIPT_MAX_PARAMETERS_BYTES = 16_384
SCRIPT_MAX_TIMEOUT_MS = 5_000
SCRIPT_CLEANUP_SECONDS = 5.0
NATIVE_SCRIPT_KINDS = frozenset({"npc", "quest", "portal", "reactor", "event"})
NATIVE_SCRIPT_OPERATIONS = frozenset({"start", "invoke", "close"})
NATIVE_SCRIPT_FIELDS = frozenset({"kind", "operation", "context_id", "options", "entry", "args"})


def validate_identifier(value: str, label: str, pattern: re.Pattern[str] = IDENTIFIER) -> None:
    if not isinstance(value, str) or value in {".", ".."} or pattern.fullmatch(value) is None:
        raise BridgeError("INVALID_ARGUMENTS", label + " 必须是有效的标识符。")


def validate_integer(value: int, label: str, minimum: int, maximum: int) -> None:
    if type(value) is not int or not minimum <= value <= maximum:
        raise BridgeError("INVALID_ARGUMENTS", label + " 必须是允许范围内的整数。")


def validate_script_target(session_id: str, expected_generation: int) -> None:
    validate_identifier(session_id, "session_id")
    validate_integer(expected_generation, "expected_generation", 1, MAX_LONG)


def validate_script_run(
    session_id: str, expected_generation: int, request_id: str, source: str,
    parameters: dict[str, Any] | None = None, timeout_ms: int = 1000,
    environment: dict[str, Any] | None = None,
) -> None:
    validate_script_target(session_id, expected_generation)
    validate_identifier(request_id, "request_id", REQUEST_IDENTIFIER)
    if (not isinstance(source, str) or (environment is None and not source.strip())
            or sum(2 if ord(char) > 0xFFFF else 1 for char in source) > SCRIPT_MAX_SOURCE_UNITS
            or any(0xD800 <= ord(char) <= 0xDFFF for char in source)):
        raise BridgeError("INVALID_ARGUMENTS", "source 必须是有界 Unicode 脚本，纯脚本模式不得为空。")
    validate_integer(timeout_ms, "timeout_ms", 1, SCRIPT_MAX_TIMEOUT_MS)
    if parameters is None:
        parameters = {}
    if not isinstance(parameters, dict):
        raise BridgeError("INVALID_ARGUMENTS", "parameters 必须是 JSON 对象。")
    size = _validate_script_json(parameters, "parameters")
    if environment is not None:
        size += validate_script_environment(environment, source)
    if size > SCRIPT_MAX_PARAMETERS_BYTES:
        raise BridgeError("INVALID_ARGUMENTS", "parameters、options 和 args 超过允许的 JSON 总大小。")


def _validate_script_json(value: Any, label: str) -> int:
    original = value
    pending = [(value, 0)]
    visited = 0
    while pending:
        value, depth = pending.pop()
        visited += 1
        if depth > 16 or visited > SCRIPT_MAX_PARAMETERS_BYTES:
            raise BridgeError("INVALID_ARGUMENTS", label + " 超过允许的 JSON 大小或嵌套深度。")
        if isinstance(value, dict):
            if any(not isinstance(key, str) for key in value):
                raise BridgeError("INVALID_ARGUMENTS", label + " 对象的键必须是字符串。")
            pending.extend((child, depth + 1) for child in value.values())
        elif isinstance(value, list):
            pending.extend((child, depth + 1) for child in value)
        elif value is not None and type(value) not in {str, int, float, bool}:
            raise BridgeError("INVALID_ARGUMENTS", label + " 只能包含 JSON 值。")
    try:
        encoded = json.dumps(original, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
    except (ValueError, TypeError, UnicodeError, RecursionError):
        raise BridgeError("INVALID_ARGUMENTS", label + " 必须是可序列化的有限 JSON 值。") from None
    if len(encoded) > SCRIPT_MAX_PARAMETERS_BYTES:
        raise BridgeError("INVALID_ARGUMENTS", label + " 超过允许的 JSON 大小。")
    return len(encoded)


def validate_script_environment(environment: dict[str, Any], source: str) -> int:
    if (not isinstance(environment, dict) or not {"kind", "operation"} <= set(environment)
            or set(environment) - NATIVE_SCRIPT_FIELDS):
        raise BridgeError("INVALID_ARGUMENTS", "environment 必须包含原生脚本 kind 和 operation，且不得包含额外字段。")
    kind, operation = environment["kind"], environment["operation"]
    if (not isinstance(kind, str) or kind not in NATIVE_SCRIPT_KINDS
            or not isinstance(operation, str) or operation not in NATIVE_SCRIPT_OPERATIONS):
        raise BridgeError("INVALID_ARGUMENTS", "environment.kind 或 operation 不受支持。")
    if operation == "start":
        if "context_id" in environment:
            raise BridgeError("INVALID_ARGUMENTS", "start 不接受已有 context_id。")
    else:
        validate_identifier(environment.get("context_id"), "environment.context_id")
    if "entry" in environment and (
        not isinstance(environment["entry"], str) or NATIVE_SCRIPT_ENTRY.fullmatch(environment["entry"]) is None
    ):
        raise BridgeError("INVALID_ARGUMENTS", "environment.entry 必须是有效入口名。")
    options, args = environment.get("options", {}), environment.get("args", [])
    if not isinstance(options, dict) or not isinstance(args, list):
        raise BridgeError("INVALID_ARGUMENTS", "environment.options 必须是 JSON 对象，args 必须是 JSON 列表。")
    if operation == "invoke" and source.strip() and "entry" in environment:
        raise BridgeError("INVALID_ARGUMENTS", "invoke 不得同时提交 source 和 entry。")
    if operation == "close" and (source != "" or options or args or "entry" in environment):
        raise BridgeError("INVALID_ARGUMENTS", "close 只接受空 source 和已有 context_id，不执行 options、entry 或 args。")
    return _validate_script_json(options, "environment.options") + _validate_script_json(args, "environment.args")


def validate_script_help(session_id: str, expected_generation: int, names: list[str] | None = None) -> None:
    validate_script_target(session_id, expected_generation)
    if names is None:
        names = []
    if (not isinstance(names, list) or len(names) > 64
            or any(not isinstance(name, str)
                   or SCRIPT_FUNCTION_NAME.fullmatch(name) is None for name in names)):
        raise BridgeError("INVALID_ARGUMENTS", "names 必须是最多64项的有效函数名列表。")


def validate_action(action: str, parameters: dict[str, Any], expected_context: dict[str, Any]) -> None:
    if not isinstance(action, str) or action not in ACTION_NAMES:
        raise BridgeError("INVALID_ARGUMENTS", "action 必须是已实现的受控角色动作；实际开放项以 game_capabilities 为准。")
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
    elif action in {"npc_center_open", "stop"}:
        if parameters:
            raise BridgeError("INVALID_ARGUMENTS", action + " parameters 必须为空对象。")
    elif action == "npc_cancel":
        if set(parameters) != {"dialogue_version"}:
            raise BridgeError("INVALID_ARGUMENTS", "npc_cancel parameters 必须且只能包含 dialogue_version。")
        validate_integer(parameters["dialogue_version"], "dialogue_version", 1, MAX_LONG)
    elif action == "inventory_move":
        if set(parameters) != {"inventory_type", "source_slot", "target_slot"}:
            raise BridgeError("INVALID_ARGUMENTS", "inventory_move parameters 必须且只能包含 inventory_type、source_slot 和 target_slot。")
        inventory_type = parameters["inventory_type"]
        if not isinstance(inventory_type, str) or inventory_type not in INVENTORY_TYPES:
            raise BridgeError("INVALID_ARGUMENTS", "inventory_type 只支持 EQUIP、USE、SETUP、ETC 或 CASH。")
        validate_integer(parameters["source_slot"], "source_slot", 1, 127)
        validate_integer(parameters["target_slot"], "target_slot", 1, 127)
        if parameters["source_slot"] == parameters["target_slot"]:
            raise BridgeError("INVALID_ARGUMENTS", "source_slot 和 target_slot 必须不同。")
    elif action == "move":
        if set(parameters) != {"direction", "duration_ms"}:
            raise BridgeError("INVALID_ARGUMENTS", "move parameters 必须且只能包含 direction 和 duration_ms。")
        direction = parameters["direction"]
        if not isinstance(direction, str) or direction not in {"LEFT", "RIGHT"}:
            raise BridgeError("INVALID_ARGUMENTS", "direction 只支持 LEFT 或 RIGHT。")
        validate_integer(parameters["duration_ms"], "duration_ms", 100, 3000)
    elif action in {"attack", "pickup"}:
        field = "target_oid" if action == "attack" else "object_id"
        if set(parameters) != {field}:
            raise BridgeError("INVALID_ARGUMENTS", action + " parameters 必须且只能包含 " + field + "。")
        validate_integer(parameters[field], field, 1, MAX_INT)
    elif action == "drop":
        if set(parameters) != {"inventory_type", "source_slot", "quantity"}:
            raise BridgeError("INVALID_ARGUMENTS", "drop parameters 必须且只能包含 inventory_type、source_slot 和 quantity。")
        inventory_type = parameters["inventory_type"]
        if not isinstance(inventory_type, str) or inventory_type not in INVENTORY_TYPES:
            raise BridgeError("INVALID_ARGUMENTS", "inventory_type 只支持 EQUIP、USE、SETUP、ETC 或 CASH。")
        validate_integer(parameters["source_slot"], "source_slot", 1, 127)
        validate_integer(parameters["quantity"], "quantity", 1, 32767)
    elif action == "use_item":
        if set(parameters) != {"source_slot"}:
            raise BridgeError("INVALID_ARGUMENTS", "use_item parameters 必须且只能包含 source_slot。")
        validate_integer(parameters["source_slot"], "source_slot", 1, 127)
    elif action in {"equip", "unequip"}:
        if set(parameters) != {"source_slot", "target_slot"}:
            raise BridgeError("INVALID_ARGUMENTS", action + " parameters 必须且只能包含 source_slot 和 target_slot。")
        positive = "source_slot" if action == "equip" else "target_slot"
        equipped = "target_slot" if action == "equip" else "source_slot"
        validate_integer(parameters[positive], positive, 1, 127)
        validate_integer(parameters[equipped], equipped, -50, -1)
        if parameters[equipped] not in EQUIPMENT_SLOTS:
            raise BridgeError("INVALID_ARGUMENTS", equipped + " 必须是原生普通装备位，不能是现金位或未开放槽位。")
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
    validate_action(action, parameters, expected_context)


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

    async def script_run(
        self, session_id: str, expected_generation: int, *, request_id: str,
        source: str, parameters: dict[str, Any] | None = None, timeout_ms: int = 1000,
        environment: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        validate_script_run(session_id, expected_generation, request_id, source, parameters, timeout_ms, environment)
        # Round-trip the validated input so callers cannot change the fingerprint
        # while the run and its cancellation cross independent HTTP connections.
        data = {
            "session_id": session_id, "expected_generation": expected_generation,
            "source": source, "parameters": json.loads(json.dumps(parameters or {}, ensure_ascii=False)),
            "timeout_ms": timeout_ms,
        }
        if environment is not None:
            data["environment"] = json.loads(json.dumps(environment, ensure_ascii=False))
        # Keep run + cleanup within the host tool's 45-second window even when
        # ordinary observation HTTP requests use the configurable 30 seconds.
        budget = timeout_ms / 1000 + SCRIPT_CLEANUP_SECONDS
        request = asyncio.create_task(self._mutation(
            "/solo/acceptance/v1/script/run", data, request_id, timeout_seconds=budget,
        ))
        try:
            try:
                return await asyncio.wait_for(asyncio.shield(request), timeout=budget)
            except TimeoutError:
                raise BridgeError("HTTP_TIMEOUT", "脚本请求超时，请按原 request_id 核对结果。",
                                  request_id=request_id, outcome_unknown=True) from None
        except asyncio.CancelledError:
            # MCP SDK cancellation is level-triggered. Shield the *independent*
            # cancel request and wait for the backend's actual settlement; merely
            # cancelling the awaiting HTTP task would leave an engine running.
            await self._stop_script_request(data, request_id, request, budget)
            raise
        except BridgeError as failure:
            if failure.outcome_unknown:
                await self._stop_script_request(data, request_id, request, budget)
            raise

    async def _stop_script_request(
        self, data: dict[str, Any], request_id: str,
        request: asyncio.Task[dict[str, Any]], budget: float,
    ) -> None:
        with anyio.move_on_after(budget + 1, shield=True):
            try:
                # The full original fingerprint closes the cancel-before-run
                # acceptance race. A late run must read that same checkpoint.
                await self._mutation(
                    "/solo/acceptance/v1/script/cancel",
                    {**data, "target_request_id": request_id}, str(uuid4()),
                    timeout_seconds=budget,
                )
            except BridgeError:
                # The original transport failure remains unknown. The backend
                # still owns the execution deadline; do not invent cleanup proof.
                pass
            finally:
                with anyio.move_on_after(1, shield=True):
                    try:
                        await asyncio.shield(request)
                    except (BridgeError, asyncio.CancelledError):
                        pass
        if not request.done():
            request.cancel()
        # Consume transport exceptions even after the caller no longer waits.
        with anyio.move_on_after(1, shield=True):
            try:
                await request
            except (BridgeError, asyncio.CancelledError):
                pass

    async def script_help(
        self, session_id: str, expected_generation: int, *, names: list[str] | None = None,
    ) -> dict[str, Any]:
        validate_script_help(session_id, expected_generation, names)
        return await self._authorized_request(
            "POST", "/solo/acceptance/v1/script/help", {"requestId": str(uuid4()), "data": {
                "session_id": session_id, "expected_generation": expected_generation,
                "names": list(names or []),
            }},
        )

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

    async def _mutation(
        self, path: str, data: dict[str, Any], request_id: str, *, timeout_seconds: float | None = None,
    ) -> dict[str, Any]:
        try:
            response = await self._authorized_request(
                "POST", path, {"requestId": request_id, "data": data}, timeout_seconds=timeout_seconds,
            )
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
        self, method: str, path: str, body: dict[str, Any] | None = None,
        *, timeout_seconds: float | None = None,
    ) -> dict[str, Any]:
        token = await self._token_for_request()
        response = await self._send(method, path, body, token, timeout_seconds=timeout_seconds)
        if response.status_code == 401:
            token = await self._token_for_request(stale_token=token)
            response = await self._send(method, path, body, token, timeout_seconds=timeout_seconds)
        return self._decode(response)

    async def _send(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        token: str | None = None,
        *, timeout_seconds: float | None = None,
    ) -> httpx.Response:
        headers = {"Accept": "application/json"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        try:
            # Bound response memory even when a reverse proxy returns a large error body.
            options = {} if timeout_seconds is None else {"timeout": timeout_seconds}
            async with self._http.stream(method, path, json=body, headers=headers, **options) as response:
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
        except (ValueError, UnicodeError, RecursionError):
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
        pending: list[Any] = [data]
        while pending:
            value = pending.pop()
            if isinstance(value, str):
                if any(0xD800 <= ord(char) <= 0xDFFF for char in value):
                    raise BridgeError("INVALID_RESPONSE", "验收桥响应包含无效 Unicode。")
            elif isinstance(value, dict):
                pending.extend(value.keys())
                pending.extend(value.values())
            elif isinstance(value, list):
                pending.extend(value)
            elif isinstance(value, float) and not math.isfinite(value):
                raise BridgeError("INVALID_RESPONSE", "验收桥响应包含非有限 JSON 数值。")
        return data

