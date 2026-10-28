from __future__ import annotations

import asyncio
from copy import deepcopy
import json
from pathlib import Path
import tempfile
from threading import Barrier
import unittest

from mcp import Client

from solo_acceptance_mcp.server import build_server
from solo_acceptance_mcp.smoke import IMPLEMENTED_TOOLS, run_smoke
from tests.helpers import BridgeStub, CAPABILITIES, PASSWORD, TOKEN


class ProtocolTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.stub = BridgeStub().start()

    async def asyncTearDown(self) -> None:
        await asyncio.to_thread(self.stub.stop)

    async def test_implemented_tools_keep_observation_read_only_and_bounded(self) -> None:
        async with Client(build_server(self.stub.config())) as client:
            tools = (await client.list_tools()).tools
            self.assertEqual(sorted(IMPLEMENTED_TOOLS), sorted(tool.name for tool in tools))
            for tool in [tool for tool in tools if tool.name in {"game_capabilities", "game_observe"}]:
                self.assertTrue(tool.annotations.read_only_hint)
                self.assertFalse(tool.annotations.destructive_hint)
                self.assertTrue(tool.annotations.idempotent_hint)
            observe = next(tool for tool in tools if tool.name == "game_observe")
            props = observe.input_schema["properties"]
            self.assertEqual(["world", "scene", "actor"], props["scope"]["enum"])
            self.assertEqual(1, props["limit"]["minimum"])
            self.assertEqual(500, props["limit"]["maximum"])
            response = await client.call_tool("game_capabilities", {})
            self.assertFalse(response.is_error)
            self.assertEqual(CAPABILITIES, response.structured_content)
            self.assertEqual(CAPABILITIES, json.loads(response.content[0].text))

    async def test_tool_error_uses_is_error_and_safe_structured_content(self) -> None:
        self.stub.responder = lambda request: (403, {"message": PASSWORD + TOKEN})
        async with Client(build_server(self.stub.config())) as client:
            response = await client.call_tool("game_capabilities", {})
            self.assertTrue(response.is_error)
            self.assertEqual("ACCESS_DENIED", response.structured_content["error"]["code"])
            self.assertNotIn(PASSWORD, response.model_dump_json())
            self.assertNotIn(TOKEN, response.model_dump_json())

    async def test_invalid_arguments_do_not_reach_http(self) -> None:
        async with Client(build_server(self.stub.config())) as client:
            for args in [{"scope": "unsupported"}, {"scope": "scene"}, {"limit": 501}, {"limit": True}]:
                with self.subTest(args=args):
                    response = await client.call_tool("game_observe", args)
                    self.assertTrue(response.is_error)
            self.assertEqual([], self.stub.requests)

    async def test_official_client_real_stdio_initialization_and_observation(self) -> None:
        temporary_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        temporary_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temporary_root) as directory:
            config = self.stub.write_config(Path(directory) / "private.json")
            report = await run_smoke(config, limit=17)
        self.assertTrue(report["passed"])
        self.assertEqual("stdio", report["transport"])
        self.assertFalse(report["model_called"])
        self.assertFalse(report["actor_control_verified"])
        self.assertEqual("scene-fixture-1", report["scene"]["context"]["scene_ref"])
        self.assertEqual(1, self.stub.login_count)
        self.assertEqual(3, self.stub.resource_count)
        self.assertNotIn(PASSWORD, json.dumps(report))
        self.assertNotIn(TOKEN, json.dumps(report))

    async def run_negative_smoke(self, change) -> None:
        def reply(request):
            status, payload = self.stub.default_response(request)
            payload = deepcopy(payload)
            if request["path"] != "/auth/v1/login":
                change(request, payload["data"])
            return status, payload
        self.stub.responder = reply
        temporary_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        temporary_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temporary_root) as directory:
            config = self.stub.write_config(Path(directory) / "private.json")
            with self.assertRaises(RuntimeError):
                await run_smoke(config)

    async def test_stdio_smoke_rejects_missing_source_identity(self) -> None:
        for invalid_source in [None, {}, {"available": False}, {"available": True, "soloGitRevision": "", "beidouRevision": "b" * 40}]:
            with self.subTest(source=invalid_source):
                await self.run_negative_smoke(lambda request, data: data.update(source=invalid_source)
                                              if request["path"].endswith("capabilities") else None)

    async def test_stdio_smoke_rejects_missing_runtime_generation(self) -> None:
        # Missing values must not become a successful None == None comparison.
        for target in ["capabilities", "world", "scene", "both_observations"]:
            def change(request, data):
                is_observe = request["path"].endswith("observe")
                if (
                    target == "capabilities" and request["path"].endswith("capabilities")
                    or target == "both_observations" and is_observe
                    or target in {"world", "scene"} and is_observe and request["body"]["data"]["scope"] == target
                ):
                    data.pop("runtime_generation", None)
            with self.subTest(target=target):
                await self.run_negative_smoke(change)

    async def test_stdio_smoke_rejects_generation_change_between_calls(self) -> None:
        await self.run_negative_smoke(lambda request, data: data.update(runtime_generation="replacement-world")
                                      if request["path"].endswith("observe") else None)

    async def test_stdio_smoke_checks_all_selected_scene_identity_fields(self) -> None:
        changes = {"scene_ref": "other-ref", "world_id": 1, "channel_id": 2, "map_id": 200000000,
                   "map_instance_key": "other-map-instance", "runtime_key": "other-runtime"}
        for key, value in changes.items():
            with self.subTest(field=key):
                await self.run_negative_smoke(
                    lambda request, data, key=key, value=value: data["context"].update({key: value})
                    if request["path"].endswith("observe") and request["body"]["data"]["scope"] == "scene" else None
                )

    async def test_stdio_smoke_does_not_use_scene_refs_outside_the_scene_list(self) -> None:
        def change(request, data):
            if request["path"].endswith("observe") and request["body"]["data"]["scope"] == "world":
                data["scenes"]["items"] = []
                data["unrelated_metadata"] = {"scene_ref": "scene-fixture-1"}
        await self.run_negative_smoke(change)


    async def test_observation_refresh_preserves_request_and_concurrent_calls_share_new_token(self) -> None:
        expired_requests = Barrier(2)

        def reject_old_token(request):
            if request["headers"].get("authorization") == "Bearer " + TOKEN + "-2":
                expired_requests.wait(timeout=2)
                return 401, {"message": PASSWORD + TOKEN}
            return self.stub.default_response(request)

        def reject_first_token(request):
            if request["headers"].get("authorization") == "Bearer " + TOKEN + "-1":
                return 401, {"message": PASSWORD + TOKEN}
            return self.stub.default_response(request)

        self.stub.responder = reject_first_token
        async with Client(build_server(self.stub.config())) as client:
            observation = await client.call_tool("game_observe", {"limit": 17})
            self.assertFalse(observation.is_error)
            observations = [request for request in self.stub.requests if request["path"].endswith("observe")]
            self.assertEqual(2, len(observations))
            self.assertEqual(observations[0]["body"], observations[1]["body"])
            self.assertNotEqual(observations[0]["headers"]["authorization"],
                                observations[1]["headers"]["authorization"])
            self.assertEqual(2, self.stub.login_count)
            self.stub.responder = reject_old_token
            responses = await asyncio.gather(client.call_tool("game_capabilities", {}),
                                             client.call_tool("game_capabilities", {}))
            self.assertTrue(all(not response.is_error for response in responses))
        self.assertEqual(3, self.stub.login_count)

    async def test_invalid_login_results_never_reach_game_resources_or_expose_credentials(self) -> None:
        for payload, code in [({"code": 50001, "message": PASSWORD + TOKEN}, "BACKEND_REJECTED"),
                              ({"code": 20000, "data": {"token": "bad\ntoken"}}, "INVALID_RESPONSE")]:
            with self.subTest(code=code):
                self.stub.requests.clear()
                self.stub.responder = lambda request: (200, payload)
                async with Client(build_server(self.stub.config())) as client:
                    response = await client.call_tool("game_capabilities", {})
                self.assertTrue(response.is_error)
                self.assertEqual(code, response.structured_content["error"]["code"])
                self.assertNotIn(PASSWORD, response.model_dump_json())
                self.assertNotIn(TOKEN, response.model_dump_json())
                self.assertTrue(all(request["path"] == "/auth/v1/login" for request in self.stub.requests))

    async def test_untrusted_http_responses_are_bounded_sanitized_and_keep_http_failures(self) -> None:
        cases = [(200, PASSWORD.encode(), "INVALID_RESPONSE"),
                 (200, {"code": 20000, "data": []}, "INVALID_RESPONSE"),
                 (200, {"code": 20000, "data": {"large": "x" * (4 * 1024 * 1024)}}, "INVALID_RESPONSE"),
                 (302, {"message": PASSWORD + TOKEN}, "HTTP_ERROR")]
        async with Client(build_server(self.stub.config())) as client:
            for status, payload, code in cases:
                with self.subTest(code=code, status=status):
                    self.stub.responder = lambda request: (
                        self.stub.default_response(request) if request["path"] == "/auth/v1/login"
                        else (status, payload)
                    )
                    before = self.stub.resource_count
                    response = await client.call_tool("game_capabilities", {})
                    self.assertTrue(response.is_error)
                    self.assertEqual(code, response.structured_content["error"]["code"])
                    self.assertNotIn(PASSWORD, response.model_dump_json())
                    self.assertNotIn(TOKEN, response.model_dump_json())
                    self.assertEqual(before + 1, self.stub.resource_count)
