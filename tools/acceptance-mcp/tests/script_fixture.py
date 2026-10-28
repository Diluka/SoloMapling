from __future__ import annotations

from copy import deepcopy
import json
import socket

from tests.helpers import BridgeStub, envelope


RUN_PATH = "/solo/acceptance/v1/script/run"
HELP_PATH = "/solo/acceptance/v1/script/help"
CANCEL_PATH = "/solo/acceptance/v1/script/cancel"
SCRIPT_ARGS = {
    "session_id": "session-script-fixture", "expected_generation": 7,
    "request_id": "script:fixture-1", "kind": "npc", "operation": "start",
    "context_id": None, "options": {"npc_id": 1032110}, "entry": None, "args": [],
    "source": "console.log(parameters.label); ({sum: parameters.a + parameters.b})",
    "parameters": {"label": "原生脚本协议桩", "a": 3, "b": 4}, "timeout_ms": 1000,
}
SCRIPT_HELP = {
    "schema_version": 1, "language": "javascript", "maximum_contexts_per_module": 1,
    "native_environments": [{"kind": "npc", "globals": ["cm"], "host_object": "NPCConversationManager"}],
    "contexts": [{"context_id": "native-context-fixture-1", "kind": "npc", "context_state": "OPEN"}],
}


def script_operation(request_id: str, *, status: str = "SUCCEEDED", error: dict | None = None,
                     closed: bool = False) -> dict:
    """Protocol receipt only; this fixture does not execute a game script or prove engine cleanup."""
    return {
        "schema_version": 1, "operation_id": "operation-script-fixture", "kind": "SCRIPT",
        "request_id": request_id, "session_id": SCRIPT_ARGS["session_id"], "session_generation": 7,
        "runtime_generation": "generation-script-fixture", "status": status,
        "result": {"result": {"sum": 7, "array": [None, True, "中文"]},
                   "stdout": "原生脚本协议桩\n", "stderr": "", "output_truncated": False,
                   "context_id": "native-context-fixture-1", "context_state": "CLOSED" if closed else "OPEN",
                   "engine_exited": True, "context_closed": closed,
                   "invocation_settled": True, "work_settled": True},
        "error": error,
    }


class ScriptStub(BridgeStub):
    def __init__(self) -> None:
        self.script_status = "SUCCEEDED"
        self.script_error: dict | None = None
        self.disconnect_requests = False
        super().__init__()
        owner = self
        original_handler = self.http.RequestHandlerClass

        class DisconnectableHandler(original_handler):
            def respond(self) -> None:
                if self.path in {RUN_PATH, CANCEL_PATH} and owner.disconnect_requests:
                    body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                    owner.requests.append({
                        "method": self.command, "path": self.path,
                        "headers": {key.lower(): value for key, value in self.headers.items()},
                        "body": json.loads(body),
                    })
                    owner.resource_count += 1
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    return
                super().respond()

        self.http.RequestHandlerClass = DisconnectableHandler

    def default_response(self, request: dict) -> tuple[int, object]:
        if request["path"] == RUN_PATH:
            return 200, envelope(script_operation(request["body"]["requestId"], status=self.script_status,
                                                  error=self.script_error,
                                                  closed=request["body"]["data"]["operation"] == "close"))
        if request["path"] == HELP_PATH:
            return 200, envelope(deepcopy(SCRIPT_HELP))
        if request["path"] == CANCEL_PATH:
            return 200, envelope(script_operation(request["body"]["data"]["target_request_id"],
                                                  status="CANCELLED", closed=True))
        return super().default_response(request)
