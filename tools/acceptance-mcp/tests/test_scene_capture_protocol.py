from __future__ import annotations

import asyncio
import json
from pathlib import Path
import tempfile
from threading import Event
import unittest
from unittest.mock import patch

from mcp import Client

from solo_acceptance_mcp.server import build_server
from tests.helpers import BridgeStub, envelope
from tests import test_chat_protocol as chat_protocol


SNAPSHOT_PATH = "/solo/acceptance/v1/scene/snapshot"
RECORD_PATH = "/solo/acceptance/v1/scene/record"
CAPTURE_ID = "capture-fixture-1"
SCENE_REF = "scene-capture-fixture"


class CaptureStub(BridgeStub):
    """Real local HTTP stream; the sampler/stop receipts are controlled collaborators, not game evidence."""

    def __init__(self) -> None:
        self.finish = Event()
        self.stopped = Event()
        self.finish.set()
        self.snapshot = {
            "schema_version": 1, "identity": {"scene_ref": SCENE_REF, "map_instance_key": "map-capture-fixture"},
            "static_scene": {"footholds": []},
            "frame": {"native": {},
                      "simulation": {"available": False}, "events": [], "coverage": {"complete": False}},
        }
        super().__init__()
        owner = self
        original_handler = self.http.RequestHandlerClass

        class CaptureHandler(original_handler):
            def respond(self) -> None:
                if self.path != RECORD_PATH:
                    super().respond()
                    return
                request = {"method": self.command, "path": self.path,
                           "headers": {key.lower(): value for key, value in self.headers.items()},
                           "body": json.loads(self.rfile.read(int(self.headers["Content-Length"])))}
                owner.requests.append(request)
                owner.resource_count += 1
                self.close_connection = True
                self.send_response(200)
                self.send_header("Content-Type", "application/x-ndjson")
                self.end_headers()
                try:
                    for record in [{"type": "header", "capture_id": CAPTURE_ID,
                                    "identity": {"scene_ref": SCENE_REF}},
                                   {"type": "frame", "seq": 1, **owner.snapshot["frame"]}]:
                        self.wfile.write((json.dumps(record) + "\n").encode())
                        self.wfile.flush()
                    owner.finish.wait(timeout=5)
                    owner.stopped.set()
                    self.wfile.write((json.dumps(owner.ending()) + "\n").encode())
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    pass
                finally:
                    owner.stopped.set()

        self.http.RequestHandlerClass = CaptureHandler

    def ending(self) -> dict:
        return {"type": "end", "capture_id": CAPTURE_ID, "frame_count": 1, "elapsed_ms": 100,
                "reason": "duration_elapsed", "sampling_stopped": True, "subscription_released": True,
                "recording_complete": True, "file_complete": True, "coverage": {"complete": False}}

    def default_response(self, request: dict) -> tuple[int, object]:
        if request["path"] == SNAPSHOT_PATH:
            return 200, envelope(self.snapshot)
        if request["path"] == RECORD_PATH + "/" + CAPTURE_ID + "/cancel":
            self.finish.set()
            return 200, envelope({"capture_id": CAPTURE_ID, "sampling_stopped": self.stopped.is_set(),
                                  "subscription_released": self.stopped.is_set()})
        if request["path"] == RECORD_PATH + "/" + CAPTURE_ID:
            return 200, envelope({"capture_id": CAPTURE_ID, "sampling_stopped": self.stopped.is_set(),
                                  "subscription_released": self.stopped.is_set()})
        return super().default_response(request)


class SceneCaptureProtocolTests(unittest.IsolatedAsyncioTestCase):
    stdio_client = chat_protocol.ChatProtocolTests.stdio_client

    async def asyncSetUp(self) -> None:
        self.stub = CaptureStub().start()
        self.directory = tempfile.TemporaryDirectory()
        self.output = Path(self.directory.name) / "capture.ndjson"

    async def asyncTearDown(self) -> None:
        self.stub.finish.set()
        await asyncio.to_thread(self.stub.stop)
        self.directory.cleanup()

    async def test_existing_file_and_missing_directory_fail_before_sampling(self) -> None:
        self.output.write_text("preserve evidence")
        async with self.stdio_client() as client:
            existing = await client.call_tool("scene_record", {"scene_ref": SCENE_REF, "output_file": str(self.output)})
            missing = await client.call_tool("scene_snapshot", {"scene_ref": SCENE_REF,
                                                               "output_file": str(self.output / "missing.json")})
        self.assertEqual("OUTPUT_FILE_EXISTS", existing.structured_content["error"]["code"])
        self.assertEqual("OUTPUT_FILE_UNAVAILABLE", missing.structured_content["error"]["code"])
        self.assertEqual("preserve evidence", self.output.read_text())
        self.assertEqual([], self.stub.requests)

    async def test_stdio_record_flushes_before_completion_and_uses_duration_budget(self) -> None:
        self.stub.finish.clear()
        async with self.stdio_client(timeout=0.1) as client:
            request = asyncio.create_task(client.call_tool("scene_record", {
                "scene_ref": SCENE_REF, "output_file": str(self.output), "duration_ms": 1000, "interval_ms": 100,
            }))
            async with asyncio.timeout(5):
                while not self.output.exists() or len(self.output.read_text().splitlines()) < 2:
                    await asyncio.sleep(0.02)
            self.assertFalse(request.done())
            await asyncio.sleep(0.15)  # Longer than ordinary HTTP reads, shorter than this recording's budget.
            self.stub.finish.set()
            response = await request
        self.assertFalse(response.is_error)
        records = [json.loads(line) for line in self.output.read_text().splitlines()]
        self.assertEqual(["header", "frame", "end"], [value["type"] for value in records])
        self.assertTrue(records[-1]["sampling_stopped"])
        self.assertTrue(records[-1]["subscription_released"])
        self.assertEqual(1, response.structured_content["frame_count"])
        self.assertEqual(str(self.output), response.structured_content["output_file"])
        self.assertNotIn("output_file", next(request for request in self.stub.requests
                                             if request["path"] == RECORD_PATH)["body"]["data"])

    async def test_sdk_cancellation_waits_for_backend_stop_receipt(self) -> None:
        self.stub.finish.clear()
        async with self.stdio_client() as client:
            request = asyncio.create_task(client.call_tool("scene_record", {"scene_ref": SCENE_REF,
                                                                          "output_file": str(self.output)}))
            async with asyncio.timeout(5):
                while not self.output.exists() or len(self.output.read_text().splitlines()) < 2:
                    await asyncio.sleep(0.02)
            request.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await request
            async with asyncio.timeout(5):
                while not any(value["path"].endswith("/cancel") for value in self.stub.requests) or not self.stub.stopped.is_set():
                    await asyncio.sleep(0.02)
        self.assertTrue(self.stub.stopped.is_set())
        self.assertEqual(["header", "frame"], [json.loads(line)["type"] for line in self.output.read_text().splitlines()])

    async def test_output_failure_requests_stop_and_keeps_the_original_failure(self) -> None:
        self.stub.finish.clear()
        real_open = open

        class FailingOutput:
            def __init__(inner):
                inner.file = real_open(self.output, "x", encoding="utf-8")

            def close(inner):
                inner.file.close()
                raise OSError("the filesystem also fails while closing")

            def write(inner, text):
                if '"type": "frame"' in text:
                    raise OSError("controlled filesystem failure")
                return inner.file.write(text)

            def flush(inner):
                inner.file.flush()

        async with Client(build_server(self.stub.config())) as client:
            with patch("solo_acceptance_mcp.server.open", return_value=FailingOutput()):
                response = await client.call_tool("scene_record", {"scene_ref": SCENE_REF, "output_file": str(self.output)})
        self.assertEqual("OUTPUT_WRITE_FAILED", response.structured_content["error"]["code"])
        self.assertEqual("CONFIRMED", response.structured_content["backend_stop"])
        self.assertEqual(["header"], [json.loads(line)["type"] for line in self.output.read_text().splitlines()])

    async def test_large_snapshot_requires_file_without_trimming_data(self) -> None:
        self.stub.snapshot["frame"]["native"]["detail"] = "x" * (4 * 1024 * 1024)
        async with self.stdio_client() as client:
            response = await client.call_tool("scene_snapshot", {"scene_ref": SCENE_REF})
            self.assertEqual("SCENE_FILE_REQUIRED", response.structured_content["error"]["code"])
            response = await client.call_tool("scene_snapshot", {"scene_ref": SCENE_REF, "output_file": str(self.output)})
        self.assertFalse(response.is_error)
        self.assertTrue(response.structured_content["snapshot_written"])
        saved = json.loads(self.output.read_text())
        self.assertEqual(SCENE_REF, saved["identity"]["scene_ref"])
        self.assertEqual(4 * 1024 * 1024, len(saved["frame"]["native"]["detail"]))
