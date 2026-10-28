from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from urllib.parse import urlsplit

from mcp import Client, StdioServerParameters

from solo_acceptance_mcp.bridge import MAX_LONG
from solo_acceptance_mcp.server import build_server
from tests.helpers import BridgeStub, CAPABILITIES, PASSWORD, TOKEN, envelope


CONTROL_PATH = "/solo/acceptance/v1/actor/module/control"
CONTROL_ARGUMENTS = {
    "session_id", "expected_generation", "expected_module_generation", "operation", "request_id",
}
TOOLS = {
    "game_capabilities", "game_observe", "actor_open", "actor_close", "game_act",
    "actor_control", "operation_get", "operation_cancel",
}


def arguments(operation: str = "pause", request_id: str = "control-fixture-1") -> dict:
    return {
        "session_id": "session-control-fixture", "expected_generation": 7,
        "expected_module_generation": 11, "operation": operation, "request_id": request_id,
    }


class ActorControlProtocolStub(BridgeStub):
    """Supply HTTP facts without pretending a protocol test runs native cleanup."""

    def __init__(self) -> None:
        super().__init__()
        self.control_status = "QUEUED"

    def default_response(self, request: dict) -> tuple[int, object]:
        path = urlsplit(request["path"]).path
        if path == CONTROL_PATH:
            value = request["body"]
            return 200, envelope({
                "operation_id": "operation-control-fixture", "kind": "MODULE_CONTROL",
                "status": self.control_status, "request_id": value["requestId"],
                "session_id": value["data"]["session_id"], "result": {},
            })
        return super().default_response(request)


class ActorControlProtocolTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.stub = ActorControlProtocolStub().start()

    async def asyncTearDown(self) -> None:
        await asyncio.to_thread(self.stub.stop)

    async def test_catalog_declares_only_fixed_control_inputs_and_mutation_annotations(self) -> None:
        async with Client(build_server(self.stub.config())) as client:
            tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        self.assertEqual(TOOLS, set(tools))
        tool = tools["actor_control"]
        self.assertFalse(tool.annotations.read_only_hint)
        self.assertTrue(tool.annotations.idempotent_hint)
        self.assertTrue(tool.annotations.destructive_hint)
        self.assertEqual(CONTROL_ARGUMENTS, set(tool.input_schema["required"]))
        self.assertEqual(CONTROL_ARGUMENTS, set(tool.input_schema["properties"]))
        self.assertFalse(tool.input_schema["additionalProperties"])
        self.assertEqual({"pause", "resume"}, set(tool.input_schema["properties"]["operation"]["enum"]))
        for field in ["expected_generation", "expected_module_generation"]:
            self.assertEqual(1, tool.input_schema["properties"][field]["minimum"])
            self.assertEqual(MAX_LONG, tool.input_schema["properties"][field]["maximum"])

    async def test_client_catalog_does_not_promote_old_backend_capabilities(self) -> None:
        async with Client(build_server(self.stub.config())) as client:
            response = await client.call_tool("game_capabilities", {})
        self.assertFalse(response.is_error)
        self.assertEqual(CAPABILITIES, response.structured_content)
        self.assertNotIn("actor_control", response.structured_content["enabled_tools"])

    async def test_pending_module_states_are_forwarded_without_implying_pause_or_resume_completion(self) -> None:
        async with Client(build_server(self.stub.config())) as client:
            for status in ["QUEUED", "RUNNING", "CANCEL_REQUESTED"]:
                with self.subTest(status=status):
                    self.stub.control_status = status
                    response = await client.call_tool("actor_control", arguments(request_id="control-" + status))
                    self.assertFalse(response.is_error)
                    self.assertEqual(status, response.structured_content["status"])
                    self.assertEqual({}, response.structured_content["result"])
                    self.assertEqual("control-" + status, response.structured_content["request_id"])
        self.assertEqual(1, self.stub.login_count)
        self.assertEqual(3, self.stub.resource_count)

    async def test_missing_unknown_and_unbounded_inputs_are_rejected_before_any_http_without_echoing_values(self) -> None:
        valid = arguments()
        invalid = [{key: value for key, value in valid.items() if key != missing} for missing in CONTROL_ARGUMENTS]
        for field in ["expected_generation", "expected_module_generation"]:
            invalid.extend([{**valid, field: value} for value in [True, False, 1.0, "1", 0, -1, MAX_LONG + 1]])
        invalid.extend([
            {**valid, "session_id": "../" + PASSWORD},
            {**valid, "request_id": PASSWORD + "\n" + TOKEN},
            {**valid, "operation": PASSWORD},
            {**valid, "operation": "unload"},
            {**valid, "operation": "attach"},
            {**valid, "operation": None},
        ])
        for field in ["runtime_id", "character_id", "module", "handler", "code", "expected_context"]:
            invalid.append({**valid, field: PASSWORD + TOKEN})
        async with Client(build_server(self.stub.config())) as client:
            for case in invalid:
                response = await client.call_tool("actor_control", case)
                self.assertTrue(response.is_error, case.keys())
                self.assertNotIn(PASSWORD, response.model_dump_json())
                self.assertNotIn(TOKEN, response.model_dump_json())
        self.assertEqual([], self.stub.requests)

    async def test_backend_rejections_preserve_recovery_id_status_and_unknown_result_without_private_body(self) -> None:
        for status, code, unknown in [
            (403, "ACCESS_DENIED", False), (409, "OPERATION_CONFLICT", False),
            (410, "SESSION_EXPIRED", False), (429, "CAPACITY_REACHED", False),
            (503, "SERVER_OFFLINE", True),
        ]:
            with self.subTest(status=status):
                self.stub.responder = lambda request, status=status: (
                    self.stub.default_response(request) if request["path"] == "/auth/v1/login"
                    else (status, {"message": PASSWORD + TOKEN, "requestId": "private-backend-id"})
                )
                before = self.stub.resource_count
                async with Client(build_server(self.stub.config())) as client:
                    response = await client.call_tool("actor_control", arguments(request_id="control-rejected-" + str(status)))
                self.assertTrue(response.is_error)
                data = response.structured_content
                self.assertEqual(code, data["error"]["code"])
                self.assertEqual(status, data["error"]["http_status"])
                self.assertFalse(data["error"]["retryable"])
                self.assertEqual(unknown, data["error"]["outcome_unknown"])
                self.assertEqual("control-rejected-" + str(status), data["request_id"])
                self.assertEqual(before + 1, self.stub.resource_count)
                self.assertNotIn(PASSWORD, response.model_dump_json())
                self.assertNotIn(TOKEN, response.model_dump_json())
                self.assertNotIn("private-backend-id", response.model_dump_json())

    async def test_official_stdio_pause_resume_preserve_both_generations_request_ids_and_http_envelope(self) -> None:
        temporary_root = Path(__file__).resolve().parents[1] / ".test-tmp"
        temporary_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temporary_root) as directory:
            config = self.stub.write_config(Path(directory) / "private.json")
            params = StdioServerParameters(
                command=sys.executable,
                args=["-m", "solo_acceptance_mcp", "--config", str(config)],
                cwd=Path(__file__).resolve().parents[1],
                # SDK stdio starts with an allowlisted environment; select this worktree
                # explicitly when reusing a venv installed from the deployment checkout.
                env={"PYTHONPATH": os.pathsep.join([
                    str(Path(__file__).resolve().parents[1] / "src"),
                    str(Path(__file__).resolve().parents[1]),
                ])},
            )
            async with Client(params, read_timeout_seconds=10) as client:
                paused = await client.call_tool("actor_control", arguments("pause", "stdio-pause"))
                resumed = await client.call_tool("actor_control", arguments("resume", "stdio-resume"))
        self.assertTrue(all(not response.is_error for response in [paused, resumed]))
        self.assertEqual(["QUEUED", "QUEUED"], [response.structured_content["status"] for response in [paused, resumed]])
        controls = [request for request in self.stub.requests if request["path"] == CONTROL_PATH]
        self.assertEqual(2, len(controls))
        for operation, request in zip(["pause", "resume"], controls):
            self.assertEqual("POST", request["method"])
            self.assertEqual("Bearer " + TOKEN + "-1", request["headers"]["authorization"])
            self.assertEqual({
                "requestId": "stdio-" + operation,
                "data": {
                    "session_id": "session-control-fixture", "expected_generation": 7,
                    "expected_module_generation": 11, "operation": operation,
                },
            }, request["body"])
        self.assertEqual(1, self.stub.login_count)
        public = json.dumps([response.structured_content for response in [paused, resumed]])
        self.assertNotIn(PASSWORD, public)
        self.assertNotIn(TOKEN, public)


if __name__ == "__main__":
    unittest.main()
