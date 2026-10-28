from __future__ import annotations

import asyncio
from copy import deepcopy
import time
import unittest
from urllib.parse import urlsplit
from uuid import uuid4

from solo_acceptance_mcp.smoke import require_capabilities
from tests import test_chat_protocol as chat_protocol
from tests.helpers import PASSWORD, TOKEN, envelope
from tests.test_chat_protocol import ACT_PATH, TOOLS, ChatProtocolStub, capabilities
from tests.test_inventory_move_protocol import inventory_capabilities
from tests.test_npc_protocol import npc_capabilities
from tests.test_npc_reply_protocol import reply_capabilities


BASE_ACTIONS = ["chat", "npc_center_open", "npc_cancel", "npc_reply", "inventory_move"]
PARAMETERS = {
    "move": {"direction": "LEFT", "duration_ms": 100},
    "stop": {},
    "attack": {"target_oid": 1},
    "pickup": {"object_id": 1},
    "drop": {"inventory_type": "USE", "source_slot": 1, "quantity": 1},
    "use_item": {"source_slot": 1},
    "equip": {"source_slot": 1, "target_slot": -11},
    "unequip": {"source_slot": -11, "target_slot": 1},
}
ACTIONS = BASE_ACTIONS + list(PARAMETERS)
EQUIPPED_SLOTS = [*range(-1, -14, -1), *range(-15, -20, -1), -49, -50]
INTEGER_BOUNDS = {
    "move": {"duration_ms": (100, 3000)},
    "stop": {},
    "attack": {"target_oid": (1, 2**31 - 1)},
    "pickup": {"object_id": (1, 2**31 - 1)},
    "drop": {"source_slot": (1, 127), "quantity": (1, 32767)},
    "use_item": {"source_slot": (1, 127)},
    "equip": {"source_slot": (1, 127), "target_slot": (-50, -1)},
    "unequip": {"source_slot": (-50, -1), "target_slot": (1, 127)},
}


def basic_capabilities(actions: list[str] | None = None) -> dict:
    value = inventory_capabilities()
    value["actions"] = ACTIONS[:] if actions is None else actions[:]
    value["actor_control"]["actions"] = value["actions"][:]
    return value


class BasicActionsProtocolStub(ChatProtocolStub):
    """Only a local HTTP boundary; no movement, combat, inventory or persistence is simulated."""

    def __init__(self) -> None:
        self.current_action = "move"
        super().__init__()

    def operation(self, status: str | None = None) -> dict:
        value = super().operation(status)
        value["action"] = self.current_action
        value["result"] = {"protocol_fixture": True, "opaque_native_result": {"status_label": value["status"]}}
        value["error"] = {"reason": "protocol_fixture_reason"} if value["status"] == "FAILED" else None
        return value

    def default_response(self, request: dict) -> tuple[int, object]:
        path = urlsplit(request["path"]).path
        if path == "/solo/acceptance/v1/capabilities":
            return 200, envelope(basic_capabilities())
        if path == ACT_PATH:
            self.current_action = request["body"]["data"]["action"]
        return super().default_response(request)


class BasicActionsProtocolTests(unittest.IsolatedAsyncioTestCase):
    stdio_client = chat_protocol.ChatProtocolTests.stdio_client
    action_requests = chat_protocol.ChatProtocolTests.action_requests

    async def asyncSetUp(self) -> None:
        self.stub = BasicActionsProtocolStub().start()
        self.args = {
            "session_id": self.stub.session_id,
            "expected_generation": 7,
            "request_id": "basic:" + uuid4().hex,
            "action": "move",
            "parameters": PARAMETERS["move"].copy(),
            "expected_context": {"scene_ref": "scene-" + uuid4().hex,
                                 "runtime_generation": self.stub.runtime_generation},
        }

    async def asyncTearDown(self) -> None:
        await asyncio.to_thread(self.stub.stop)

    def action_args(self, action: str, parameters: dict | None = None, *, suffix: str = "") -> dict:
        return {**self.args, "action": action, "request_id": self.args["request_id"] + ":" + action + suffix,
                "parameters": PARAMETERS[action].copy() if parameters is None else parameters}

    async def test_schema_binds_all_eight_basic_actions_to_strict_independent_models(self) -> None:
        async with self.stdio_client() as client:
            tools = {tool.name: tool for tool in (await client.list_tools()).tools}
        self.assertEqual(TOOLS, set(tools))
        schema = tools["game_act"].input_schema
        self.assertEqual(ACTIONS, schema["properties"]["action"]["enum"])
        self.assertEqual(set(self.args), set(schema["required"]))
        self.assertIs(False, schema["additionalProperties"])
        tagged = {branch["properties"]["action"]["const"]: branch["properties"]["parameters"]
                  for branch in schema["allOf"][0]["oneOf"]}
        self.assertEqual(set(ACTIONS), set(tagged))
        for action, parameters in PARAMETERS.items():
            with self.subTest(action=action):
                model = schema["$defs"][tagged[action]["$ref"].split("/")[-1]]
                self.assertIs(False, model["additionalProperties"])
                self.assertEqual(set(parameters), set(model.get("properties", {})))
                self.assertEqual(set(parameters), set(model.get("required", [])))
                for field, (minimum, maximum) in INTEGER_BOUNDS[action].items():
                    number = model["properties"][field]
                    self.assertEqual("integer", number["type"])
                    if (action, field) in {("equip", "target_slot"), ("unequip", "source_slot")}:
                        self.assertEqual(set(EQUIPPED_SLOTS), set(number["enum"]))
                        self.assertEqual(20, len(number["enum"]))
                    else:
                        self.assertEqual((minimum, maximum), (number["minimum"], number["maximum"]))
                if action == "move":
                    self.assertEqual(["LEFT", "RIGHT"], model["properties"]["direction"]["enum"])
                if action == "drop":
                    self.assertEqual(["EQUIP", "USE", "SETUP", "ETC", "CASH"],
                                     model["properties"]["inventory_type"]["enum"])
        self.assertEqual([], self.stub.requests)

    async def test_every_basic_action_and_accepted_boundaries_preserve_jwt_request_id_and_context(self) -> None:
        cases = [(action, parameters.copy()) for action, parameters in PARAMETERS.items()]
        for action, fields in INTEGER_BOUNDS.items():
            for field, (_, maximum) in fields.items():
                cases.append((action, {**PARAMETERS[action], field: maximum}))
        cases.append(("move", {"direction": "RIGHT", "duration_ms": 3000}))
        cases.extend(("drop", {**PARAMETERS["drop"], "inventory_type": kind})
                     for kind in ["EQUIP", "USE", "SETUP", "ETC", "CASH"])
        cases.extend(("equip", {**PARAMETERS["equip"], "target_slot": slot}) for slot in EQUIPPED_SLOTS)
        cases.extend(("unequip", {**PARAMETERS["unequip"], "source_slot": slot}) for slot in EQUIPPED_SLOTS)
        async with self.stdio_client() as client:
            for index, (action, parameters) in enumerate(cases):
                with self.subTest(action=action, parameters=parameters):
                    args = self.action_args(action, parameters, suffix=":" + str(index))
                    response = await client.call_tool("game_act", args)
                    self.assertFalse(response.is_error)
                    self.assertEqual({**self.stub.operation(), "request_id": args["request_id"]}, response.structured_content)
                    request = self.action_requests()[-1]
                    self.assertEqual("POST", request["method"])
                    self.assertEqual("Bearer " + TOKEN + "-1", request["headers"]["authorization"])
                    self.assertEqual({"requestId": args["request_id"],
                                      "data": {key: value for key, value in args.items() if key != "request_id"}}, request["body"])
                    self.assertNotIn(PASSWORD, response.model_dump_json())
                    self.assertNotIn(TOKEN, response.model_dump_json())
        self.assertEqual(len(cases), len(self.action_requests()))
        self.assertEqual(1, self.stub.login_count)

    async def test_invalid_basic_parameters_fail_before_authentication_without_echoing_private_input(self) -> None:
        private = PASSWORD + TOKEN + "private-basic-operation"
        cases = []
        for action, valid in PARAMETERS.items():
            cases.extend((action, value) for value in [None, False, [], "parameters"])
            if valid:
                cases.append((action, {}))
            cases.extend((action, {key: value for key, value in valid.items() if key != missing}) for missing in valid)
            cases.extend((action, {**valid, field: private})
                         for field in ["character_id", "raw_packet", "script", "damage", "skill_id"])
            for field, (minimum, maximum) in INTEGER_BOUNDS[action].items():
                cases.extend((action, {**valid, field: value})
                             for value in [None, True, False, 1.0, "1", [], {}, minimum - 1, maximum + 1, private])
        cases.extend(("move", {**PARAMETERS["move"], "direction": value})
                     for value in [None, True, 1, [], {}, "left", "UP", "RIGHT ", private])
        cases.extend(("drop", {**PARAMETERS["drop"], "inventory_type": value})
                     for value in [None, True, 1, [], {}, "use", "EQUIPPED", "USE ", private])
        cases.extend(("equip", {**PARAMETERS["equip"], "target_slot": slot})
                     for slot in [-14, -20, -48, -51, -101])
        cases.extend(("unequip", {**PARAMETERS["unequip"], "source_slot": slot})
                     for slot in [-14, -20, -48, -51, -101])
        async with self.stdio_client() as client:
            for index, (action, parameters) in enumerate(cases):
                with self.subTest(action=action, case=index):
                    response = await client.call_tool("game_act", {**self.action_args(action), "parameters": parameters})
                    self.assertTrue(response.is_error)
                    self.assertEqual("INVALID_ARGUMENTS", response.structured_content["error"]["code"])
                    for secret in [PASSWORD, TOKEN, private]:
                        self.assertNotIn(secret, response.model_dump_json())
        self.assertEqual([], self.stub.requests)

    async def test_basic_actions_reject_cross_action_parameters_and_missing_scene_or_identity(self) -> None:
        cases = []
        for action in PARAMETERS:
            cases.extend({**self.action_args(action), "parameters": parameters.copy()}
                         for other, parameters in PARAMETERS.items() if action != other)
            cases.extend({key: value for key, value in self.action_args(action).items() if key != field}
                         for field in ["session_id", "expected_generation", "request_id", "expected_context"])
            cases.extend({**self.action_args(action), "expected_context": value}
                         for value in [None, {}, {"scene_ref": "scene-only"},
                                       {**self.args["expected_context"], "map_id": 10000}])
        async with self.stdio_client() as client:
            for index, args in enumerate(cases):
                with self.subTest(case=index, action=args.get("action")):
                    response = await client.call_tool("game_act", args)
                    self.assertTrue(response.is_error)
        self.assertEqual([], self.stub.requests)

    async def test_backend_status_and_opaque_native_facts_are_forwarded_for_each_basic_action(self) -> None:
        states = ["QUEUED", "RUNNING", "CANCEL_REQUESTED", "CANCELLED", "FAILED", "INDETERMINATE", "SUCCEEDED"]
        async with self.stdio_client() as client:
            for action in PARAMETERS:
                for state in states:
                    with self.subTest(action=action, state=state):
                        self.stub.status = state
                        response = await client.call_tool("game_act", self.action_args(action))
                        self.assertFalse(response.is_error)
                        self.assertEqual(state, response.structured_content["status"])
                        self.assertEqual(self.stub.operation()["result"], response.structured_content["result"])
                        self.assertEqual(self.stub.operation()["error"], response.structured_content["error"])
        self.assertEqual(len(PARAMETERS) * len(states), len(self.action_requests()))

    async def test_timeout_and_connection_loss_keep_original_id_without_replaying_any_basic_mutation(self) -> None:
        for action in PARAMETERS:
            for mode in ["timeout", "disconnect"]:
                with self.subTest(action=action, mode=mode):
                    self.stub.requests.clear()
                    self.stub.disconnect_action = mode == "disconnect"

                    def respond(request: dict) -> tuple[int, object]:
                        if urlsplit(request["path"]).path == ACT_PATH and mode == "timeout":
                            time.sleep(0.25)
                        return self.stub.default_response(request)

                    self.stub.responder = respond
                    args = self.action_args(action)
                    async with self.stdio_client(timeout=0.1 if mode == "timeout" else 2) as client:
                        response = await client.call_tool("game_act", args)
                    self.assertTrue(response.is_error)
                    self.assertEqual(args["request_id"], response.structured_content["request_id"])
                    self.assertTrue(response.structured_content["error"]["outcome_unknown"])
                    self.assertFalse(response.structured_content["error"]["retryable"])
                    self.assertEqual(1, len(self.action_requests()))

    async def test_401_refresh_preserves_identical_basic_payload_and_request_id_once(self) -> None:
        for action in PARAMETERS:
            with self.subTest(action=action):
                self.stub.requests.clear()
                before_logins = self.stub.login_count

                def respond(request: dict) -> tuple[int, object]:
                    if urlsplit(request["path"]).path == ACT_PATH and len(self.action_requests()) == 1:
                        return 401, {"message": PASSWORD + TOKEN}
                    return self.stub.default_response(request)

                self.stub.responder = respond
                args = self.action_args(action)
                async with self.stdio_client() as client:
                    response = await client.call_tool("game_act", args)
                self.assertFalse(response.is_error)
                requests = self.action_requests()
                self.assertEqual(2, len(requests))
                self.assertEqual(before_logins + 2, self.stub.login_count)
                self.assertEqual(requests[0]["body"], requests[1]["body"])
                self.assertEqual(args["request_id"], requests[1]["body"]["requestId"])
                self.assertNotEqual(requests[0]["headers"]["authorization"], requests[1]["headers"]["authorization"])

    async def test_operation_cancel_is_one_request_and_does_not_locally_stop_replay_or_close(self) -> None:
        self.stub.status = "RUNNING"
        async with self.stdio_client() as client:
            for action in PARAMETERS:
                with self.subTest(action=action):
                    self.stub.requests.clear()
                    accepted = await client.call_tool("game_act", self.action_args(action))
                    self.assertEqual("RUNNING", accepted.structured_content["status"])
                    cancel_args = {"operation_id": self.stub.operation_id, "request_id": "cancel:" + action}
                    cancelled = await client.call_tool("operation_cancel", cancel_args)
                    self.assertFalse(cancelled.is_error)
                    self.assertEqual("CANCEL_REQUESTED", cancelled.structured_content["status"])
                    queried = await client.call_tool("operation_get", {"operation_id": self.stub.operation_id})
                    self.assertFalse(queried.is_error)
                    self.assertEqual("RUNNING", queried.structured_content["status"])
                    mutations = [request for request in self.stub.requests if request["method"] == "POST"
                                 and request["path"] != "/auth/v1/login"]
                    self.assertEqual(2, len(mutations))
                    self.assertEqual(1, len(self.action_requests()))
                    self.assertEqual(self.stub.operation_id + "/cancel", mutations[-1]["path"].split("/operation/")[1])
                    self.assertEqual({"requestId": cancel_args["request_id"], "data": {}}, mutations[-1]["body"])
                    self.assertFalse(any(request["path"].endswith("/actor/close") for request in self.stub.requests))

    async def test_registered_basic_schema_does_not_promote_existing_five_action_backend(self) -> None:
        self.stub.responder = lambda request: (
            (200, envelope(inventory_capabilities())) if urlsplit(request["path"]).path == "/solo/acceptance/v1/capabilities"
            else self.stub.default_response(request))
        async with self.stdio_client() as client:
            tools = {tool.name: tool for tool in (await client.list_tools()).tools}
            caps = await client.call_tool("game_capabilities", {})
        self.assertTrue(set(PARAMETERS) <= set(tools["game_act"].input_schema["properties"]["action"]["enum"]))
        self.assertEqual(BASE_ACTIONS, caps.structured_content["actions"])
        self.assertFalse(set(PARAMETERS) & set(caps.structured_content["actions"]))

    def test_capability_consumer_preserves_old_sets_and_accepts_only_known_basic_extensions(self) -> None:
        accepted = [capabilities(TOOLS), npc_capabilities(), reply_capabilities(), inventory_capabilities(), basic_capabilities()]
        accepted.extend(basic_capabilities(BASE_ACTIONS + [action]) for action in PARAMETERS)
        for caps in accepted:
            with self.subTest(actions=caps["actions"]):
                self.assertEqual(sorted(TOOLS), require_capabilities(caps, sorted(TOOLS), actors=True))
        invalid = [basic_capabilities(ACTIONS + ["skill"]), basic_capabilities(ACTIONS + ["move"]),
                   basic_capabilities(["chat", "move"])]
        mismatched = deepcopy(basic_capabilities())
        mismatched["actor_control"]["actions"] = BASE_ACTIONS[:]
        invalid.append(mismatched)
        for caps in invalid:
            with self.subTest(actions=caps["actions"]), self.assertRaises(RuntimeError):
                require_capabilities(caps, sorted(TOOLS), actors=True)

    async def test_same_id_parameter_conflict_is_left_to_backend_and_is_not_retried(self) -> None:
        seen = {}

        def respond(request: dict) -> tuple[int, object]:
            if urlsplit(request["path"]).path == ACT_PATH:
                body = request["body"]
                prior = seen.setdefault(body["requestId"], body["data"])
                if prior != body["data"]:
                    return 409, {"reason": "request_id_conflict", "message": PASSWORD + TOKEN}
            return self.stub.default_response(request)

        self.stub.responder = respond
        async with self.stdio_client() as client:
            for action in PARAMETERS:
                with self.subTest(action=action):
                    args = self.action_args(action)
                    original = await client.call_tool("game_act", args)
                    self.assertFalse(original.is_error)
                    changed_parameters = {
                        "move": {**PARAMETERS["move"], "direction": "RIGHT"},
                        "attack": {"target_oid": 2}, "pickup": {"object_id": 2},
                        "drop": {**PARAMETERS["drop"], "quantity": 2}, "use_item": {"source_slot": 2},
                        "equip": {"source_slot": 2, "target_slot": -11},
                        "unequip": {"source_slot": -11, "target_slot": 2},
                    }
                    changed = ({**args, "parameters": changed_parameters[action]} if action in changed_parameters
                               else {**args, "expected_context": {**args["expected_context"], "scene_ref": "other-scene"}})
                    response = await client.call_tool("game_act", changed)
                    self.assertTrue(response.is_error)
                    self.assertEqual(args["request_id"], response.structured_content["request_id"])
                    self.assertEqual("OPERATION_CONFLICT", response.structured_content["error"]["code"])
                    self.assertFalse(response.structured_content["error"]["retryable"])
                    self.assertFalse(response.structured_content["error"]["outcome_unknown"])
                    self.assertEqual(2, len([request for request in self.action_requests()
                                            if request["body"]["requestId"] == args["request_id"]]))
                    self.assertNotIn(PASSWORD, response.model_dump_json())
                    self.assertNotIn(TOKEN, response.model_dump_json())
