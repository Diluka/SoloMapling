from __future__ import annotations

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
from threading import Thread
from typing import Any, Callable

from solo_acceptance_mcp.bridge import BridgeConfig


USERNAME = "fixture-admin"
PASSWORD = "fixture-password-never-return"
TOKEN = "fixture-jwt-never-return"


def envelope(data: Any) -> dict[str, Any]:
    return {"code": 20000, "data": data}


CAPABILITIES = {
    "schema_version": 1,
    "service": "solomapling-acceptance",
    "implementation_round": "R1",
    "server_online": True,
    "runtime_generation": "generation-fixture-1",
    "enabled_tools": ["game_capabilities", "game_observe"],
    "source": {"available": True, "soloGitRevision": "a" * 40, "beidouRevision": "b" * 40},
    "actions": [],
    "recipes": [],
}
SCENE_CONTEXT = {
    "scene_ref": "scene-fixture-1",
    "world_id": 0,
    "channel_id": 1,
    "map_id": 100000000,
    "map_instance_key": "map-fixture-1",
    "runtime_key": "runtime-fixture-1",
}
WORLD = {
    "schema_version": 1,
    "runtime_generation": "generation-fixture-1",
    "worlds": [{"world_id": 0, "channels": [{"channel_id": 1, "loaded_scene_count": 1}]}],
    "scenes": {"total_count": 1, "truncated": False, "items": [SCENE_CONTEXT]},
}
SCENE = {
    "schema_version": 1,
    "runtime_generation": "generation-fixture-1",
    "context": SCENE_CONTEXT,
    "players": {"total_count": 0, "truncated": False, "items": []},
}


class BridgeStub:
    """A real local HTTP boundary; it never contacts a game server or database."""

    def __init__(self) -> None:
        self.requests: list[dict[str, Any]] = []
        self.login_count = 0
        self.resource_count = 0
        self.responder: Callable[[dict[str, Any]], tuple[int, Any]] = self.default_response
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                self.respond()

            def do_POST(self) -> None:
                self.respond()

            def respond(self) -> None:
                body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                request = {
                    "method": self.command,
                    "path": self.path,
                    "headers": {key.lower(): value for key, value in self.headers.items()},
                    "body": json.loads(body) if body else None,
                }
                owner.requests.append(request)
                if self.path == "/auth/v1/login":
                    owner.login_count += 1
                else:
                    owner.resource_count += 1
                status, payload = owner.responder(request)
                content = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(content)))
                self.end_headers()
                try:
                    self.wfile.write(content)
                except (BrokenPipeError, ConnectionResetError):
                    pass  # Expected when a timeout test closes the client.

            def log_message(self, *args: Any) -> None:
                pass

        self.http = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.http.daemon_threads = True
        self.thread = Thread(target=self.http.serve_forever, daemon=True)

    def start(self) -> BridgeStub:
        self.thread.start()
        return self

    def stop(self) -> None:
        self.http.shutdown()
        self.http.server_close()
        self.thread.join(timeout=2)

    @property
    def base_url(self) -> str:
        return "http://127.0.0.1:" + str(self.http.server_port)

    def config(self, *, timeout: float = 2) -> BridgeConfig:
        return BridgeConfig(
            username=USERNAME,
            password=PASSWORD,
            base_url=self.base_url,
            timeout_seconds=timeout,
        )

    def write_config(self, path: Path, *, timeout: float = 2) -> Path:
        config = self.config(timeout=timeout)
        path.write_text(json.dumps({
            "username": config.username,
            "password": config.password,
            "base_url": config.base_url,
            "timeout_seconds": config.timeout_seconds,
        }))
        path.chmod(0o600)
        return path

    def default_response(self, request: dict[str, Any]) -> tuple[int, Any]:
        if request["path"] == "/auth/v1/login":
            return 200, envelope({"token": TOKEN + "-" + str(self.login_count)})
        if request["path"] == "/solo/acceptance/v1/capabilities":
            return 200, envelope(CAPABILITIES)
        if request["path"] == "/solo/acceptance/v1/observe":
            return 200, envelope(WORLD if request["body"]["data"]["scope"] == "world" else SCENE)
        return 404, {"message": "not found"}

