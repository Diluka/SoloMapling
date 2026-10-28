from __future__ import annotations

from copy import deepcopy
import json
import socket
from typing import Callable, Iterable

from tests.helpers import BridgeStub, envelope


STREAM_PATH = "/solo/acceptance/v1/script/stream"
RUN_PATH = STREAM_PATH + "?since_output=0"
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
        self.stream_responder: Callable[[dict], Iterable[dict | bytes]] | None = None
        super().__init__()
        owner = self
        original_handler = self.http.RequestHandlerClass

        class DisconnectableHandler(original_handler):
            def respond(self) -> None:
                if self.path.startswith(STREAM_PATH) or self.path == CANCEL_PATH and owner.disconnect_requests:
                    body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                    request = {
                        "method": self.command, "path": self.path,
                        "headers": {key.lower(): value for key, value in self.headers.items()},
                        "body": json.loads(body),
                    }
                    owner.requests.append(request)
                    owner.resource_count += 1
                    self.close_connection = True
                    if owner.disconnect_requests:
                        self.connection.shutdown(socket.SHUT_RDWR)
                        return
                    if owner.stream_responder is not None:
                        events = owner.stream_responder(request)
                    else:
                        status, payload = owner.responder(request)
                        if status != 200 or not isinstance(payload, dict) or payload.get("code") != 20000:
                            content = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
                            self.send_response(status)
                            self.send_header("Content-Type", "application/json")
                            self.send_header("Content-Length", str(len(content)))
                            self.end_headers()
                            self.wfile.write(content)
                            return
                        operation = payload["data"]
                        events = owner.operation_events(request, operation)
                    self.send_response(200)
                    self.send_header("Content-Type", "application/x-ndjson")
                    self.send_header("Connection", "close")
                    self.end_headers()
                    try:
                        for event in events:
                            content = event if isinstance(event, bytes) else (json.dumps(event) + "\n").encode()
                            self.wfile.write(content)
                            self.wfile.flush()
                    except (BrokenPipeError, ConnectionResetError):
                        pass
                    return
                super().respond()

        self.http.RequestHandlerClass = DisconnectableHandler

    def default_response(self, request: dict) -> tuple[int, object]:
        if request["path"].startswith(STREAM_PATH):
            return 200, envelope(script_operation(request["body"]["requestId"], status=self.script_status,
                                                  error=self.script_error,
                                                  closed=request["body"]["data"]["operation"] == "close"))
        if request["path"] == HELP_PATH:
            return 200, envelope(deepcopy(SCRIPT_HELP))
        if request["path"] == CANCEL_PATH:
            return 200, envelope(script_operation(request["body"]["data"]["target_request_id"],
                                                  status="CANCELLED", closed=True))
        return super().default_response(request)

    @staticmethod
    def operation_events(request: dict, operation: dict) -> Iterable[dict]:
        association = {"request_id": request["body"]["requestId"], "operation_id": operation["operation_id"]}
        yield {"type": "started", **association}
        sequence = 0
        for channel in ("stdout", "stderr"):
            text = operation["result"].get(channel, "")
            if text:
                sequence += 1
                yield {"type": "output", **association, "seq": sequence, "channel": channel,
                       "text": text, "output_truncated": operation["result"].get("output_truncated", False)}
        yield {"type": "result", "operation": operation}
