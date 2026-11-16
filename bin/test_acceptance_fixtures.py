"""T1.13b acceptance fixture validation: manifest integrity, determinism, no PII, markers, tools, envelopes.

Wire-format exactness against the real entry codecs is proven by the Java test
llmgw-app/src/test/java/io/github/leohou0430/llmgw/app/AcceptanceFixtureWireFormatTest.java.
"""
import base64
import hashlib
import hmac
import json
import re
import unittest
from pathlib import Path

FIXTURES = Path(__file__).resolve().parent.parent / "tools" / "acceptance" / "stage1" / "fixtures"
PROBE_SHA256 = "f24b0899ce1bb9147836beb6812c9ab7778a864f39c44b14d32548e4d91228d9"
ENTRIES = ("chat", "responses", "messages")
SLUGS = {
    1: "text-multiturn",
    2: "stream-text-usage",
    3: "tool-call-continuation",
    4: "thinking-continuation",
    5: "image-understanding",
    6: "cancel-recover",
    7: "contract-negatives",
}
MULTI_STEP = {3: ("initial", "continuation"), 4: ("initial", "continuation"), 6: ("cancel", "recovery")}
S7_STEP_NAMES = {
    "chat": ("unknown-field", "not-supported-store", "not-supported-builtin-tool",
             "expected-unsupported-external-image", "invalid-image-media-type",
             "degradation-unauthorized", "degradation-authorized"),
    "responses": ("unknown-field", "previous-response-id", "store", "background", "builtin-tool",
                  "expected-unsupported-external-image", "invalid-image-media-type", "input-file", "file-id",
                  "cross-project-continuation", "degradation-unauthorized", "degradation-authorized"),
    "messages": ("unknown-field", "redacted-thinking", "server-tool", "private-block",
                 "expected-unsupported-external-image", "invalid-image-media-type", "file-id",
                 "cross-project-signature", "degradation-unauthorized", "degradation-authorized"),
}
ERROR_CODES = {"capability_unknown_field", "capability_not_supported",
               "capability_degradation_not_authorized", "capability_continuation_invalid",
               "capability_invalid_value"}
ERROR_PIN_KEYS = {"status", "code", "param", "reason", "error_type", "finished", "active_permits",
                  "upstream_requests"}
DEGRADATION_KEYS = {"id", "field_path", "header", "outbound_field", "outbound_equals"}
RESOURCE_DENIAL_KEYS = {"status", "message", "error_type", "finished", "active_permits", "upstream_requests"}
GRANT_HEADER = "X-LLMGW-Allow-Degradation"
KNOWN_DEGRADATION_IDS = {"thinking.budget_to_effort", "thinking.display_raw", "tool_choice.force_to_auto",
                         "response_format.schema_to_object", "tool_call.legacy_function"}
FIXTURE_SCHEMA = "llmgw-acceptance-fixture/v1"
MANIFEST_SCHEMA = "llmgw-acceptance-manifest/v1"
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
PHONE = re.compile(r"\+?\d[\d ()-]{7,}\d")
PII_KEYS = {"email", "phone", "address", "first_name", "last_name", "ssn", "password", "secret"}
BASE64ISH = re.compile(r"^[A-Za-z0-9+/=]+$")


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def scenario_file(entry, scenario):
    return FIXTURES / entry / f"s{scenario}-{SLUGS[scenario]}.json"


def walk_strings(node):
    if isinstance(node, dict):
        for key, value in node.items():
            yield key
            yield from walk_strings(value)
    elif isinstance(node, list):
        for value in node:
            yield from walk_strings(value)
    elif isinstance(node, str):
        yield node


def is_base64_payload(value):
    if value.startswith("data:image/") or value.startswith("llmgw1:"):
        return True
    return len(value) > 200 and bool(BASE64ISH.match(value))


def mint_token(key_utf8, project_id, item_id, reasoning_text):
    payload = json.dumps(
        {"projectId": project_id, "itemId": item_id,
         "reasoningSha256": hashlib.sha256(reasoning_text.encode("utf-8")).hexdigest()},
        separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    encoded = base64.urlsafe_b64encode(payload).decode("ascii").rstrip("=")
    mac = hmac.new(key_utf8.encode("utf-8"), ("llmgw1:" + encoded).encode("utf-8"), hashlib.sha256).digest()
    return "llmgw1:" + encoded + "." + base64.urlsafe_b64encode(mac).decode("ascii").rstrip("=")


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.manifest = load(FIXTURES / "manifest.json")

    def test_manifest_schema_and_index_complete(self):
        self.assertEqual(MANIFEST_SCHEMA, self.manifest["schema"])
        self.assertEqual(list(ENTRIES), self.manifest["entries"])
        self.assertEqual(7, len(self.manifest["scenarios"]))
        index = self.manifest["index"]
        self.assertEqual(21, len(index))
        pairs = {(row["entry"], row["scenario"]) for row in index}
        self.assertEqual({(e, s) for e in ENTRIES for s in SLUGS}, pairs)
        for row in index:
            self.assertEqual(f"{row['entry']}/s{row['scenario']}-{SLUGS[row['scenario']]}.json", row["file"])
            fixture = load(FIXTURES / row["file"])
            self.assertEqual(row["steps"], [step["name"] for step in fixture["steps"]])

    def test_manifest_file_hashes_match_actual_bytes(self):
        files = self.manifest["files"]
        self.assertTrue(files)
        for row in files:
            path = FIXTURES / row["path"]
            self.assertTrue(path.is_file(), f"missing fixture file: {row['path']}")
            data = path.read_bytes()
            self.assertEqual(row["bytes"], len(data), f"byte count drift: {row['path']}")
            self.assertEqual(row["sha256"], hashlib.sha256(data).hexdigest(), f"sha256 drift: {row['path']}")

    def test_manifest_covers_every_fixture_file(self):
        listed = {row["path"] for row in self.manifest["files"]}
        actual = {str(p.relative_to(FIXTURES)) for p in FIXTURES.rglob("*")
                  if p.is_file() and p != FIXTURES / "manifest.json" and "__pycache__" not in p.parts}
        self.assertEqual(actual, listed, "manifest must list every fixture file exactly once (manifest itself excluded)")
        self.assertEqual(len(listed), len(self.manifest["files"]))

    def test_images_submanifest_referenced_and_pinned(self):
        self.assertEqual("images/manifest.json", self.manifest["images_manifest"])
        listed = {row["path"]: row for row in self.manifest["files"]}
        for path in ("images/manifest.json", "images/generate.py", "images/llmgw-probe.png"):
            self.assertIn(path, listed)
        self.assertEqual(PROBE_SHA256, listed["images/llmgw-probe.png"]["sha256"])


class EntryFixtureChecks:
    ENTRY = None

    def setUp(self):
        self.fixtures = {s: load(scenario_file(self.ENTRY, s)) for s in SLUGS}

    def test_seven_scenario_files_exist_and_parse(self):
        self.assertEqual(set(SLUGS), set(self.fixtures))
        for scenario, fixture in self.fixtures.items():
            self.assertEqual(FIXTURE_SCHEMA, fixture["schema"])
            self.assertIsInstance(fixture["steps"], list)
            self.assertTrue(fixture["steps"])

    def test_fixture_metadata_consistent(self):
        for scenario, fixture in self.fixtures.items():
            self.assertEqual(self.ENTRY, fixture["entry"])
            self.assertEqual(scenario, fixture["scenario"])
            self.assertEqual(f"{self.ENTRY}-s{scenario}-{SLUGS[scenario]}", fixture["id"])
            self.assertEqual(SLUGS[scenario], fixture["slug"])
            self.assertTrue(fixture["title"])
            self.assertEqual(4, fixture["mock"]["chunks"])
            self.assertTrue(fixture["markers"])

    def test_steps_and_continuation_shape(self):
        for scenario, fixture in self.fixtures.items():
            names = [step["name"] for step in fixture["steps"]]
            expected = list(S7_STEP_NAMES[self.ENTRY]) if scenario == 7 \
                else list(MULTI_STEP.get(scenario, ("initial",)))
            self.assertEqual(expected, names, f"scenario {scenario} step names")
            for step in fixture["steps"]:
                self.assertIsInstance(step["request"], dict)
                self.assertIsInstance(step["expected"], dict)
                self.assertEqual("mock-model", step["request"]["model"])

    def test_requests_carry_required_markers(self):
        for scenario, fixture in self.fixtures.items():
            for step in fixture["steps"]:
                raw = json.dumps(step["request"], ensure_ascii=False)
                if scenario == 3 and step["name"] == "continuation":
                    # Marker hygiene: history must not re-trigger tool mode; the active turn carries plain text mode.
                    self.assertNotIn("mock-deepseek-tools", raw)
                    self.assertIn("mock-deepseek", raw)
                elif scenario == 3:
                    self.assertIn("mock-deepseek-tools", raw)
                elif scenario == 4:
                    self.assertIn("mock-deepseek-thinking", raw)
                elif scenario == 6 and step["name"] == "cancel":
                    self.assertIn("mock-deepseek", raw)
                    self.assertIn("mock-stall-mid-", raw)
                else:
                    self.assertIn("mock-deepseek", raw)

    def test_expected_structure_present(self):
        for scenario, fixture in self.fixtures.items():
            for step in fixture["steps"]:
                expected = step["expected"]
                if scenario == 7:
                    self.assert_s7_expected(step, expected)
                    continue
                self.assertIn(expected["transport"], ("json", "sse"))
                if scenario == 6 and step["name"] == "cancel":
                    self.assertIn("cancel", expected)
                    self.assertEqual(0, expected["cancel"]["assertions"]["active_permits_after_cancel"])
                    continue
                self.assertIn("terminal", expected)
                self.assertIn("usage", expected)
                stream = expected["transport"] == "sse"
                self.assertEqual(stream, step["request"].get("stream", False))
                if stream:
                    self.assertIn("sse", expected)
                if scenario in (1, 2, 5) or (scenario == 3 and step["name"] == "continuation") \
                        or (scenario == 6 and step["name"] == "recovery"):
                    self.assertEqual("Hello world", expected["text"]["equals"])
                if scenario == 4:
                    self.assertEqual("Let me think", expected["thinking"]["equals"])
                    self.assertTrue(expected["thinking"]["before_text"])
                if scenario == 3 and step["name"] == "initial":
                    call = expected["tool_calls"][0]
                    self.assertEqual("call_mock_1", call["id"])
                    self.assertEqual("get_weather", call["name"])
                    self.assertEqual({"city": "SF"}, call["arguments"])

    def assert_s7_expected(self, step, expected):
        name = step["name"]
        if name == "degradation-authorized":
            self.assertNotIn("error", expected)
            self.assertEqual("json", expected["transport"])
            self.assertIn("terminal", expected)
            self.assertIn("usage", expected)
            self.assertEqual("Hello world", expected["text"]["equals"])
            self.assertFalse(step["request"].get("stream", False))
            degradation = step["degradation"]
            self.assertEqual(DEGRADATION_KEYS, set(degradation))
            self.assertIn(degradation["id"], KNOWN_DEGRADATION_IDS)
            self.assertEqual(GRANT_HEADER, degradation["header"])
            self.assertTrue(degradation["field_path"])
            self.assertTrue(degradation["outbound_field"])
            self.assertTrue(degradation["outbound_equals"])
            self.assertEqual({GRANT_HEADER: degradation["id"]}, step["headers"])
            return
        self.assertEqual({"error", "resource_denial"} if "resource_denial" in expected else {"error"},
                         set(expected), f"s7 {name}: a negative pins an error (+ the engine denial if pre-empted)")
        self.assertNotIn("headers", step, f"s7 {name}: the default grant is EMPTY, no header may be sent")
        self.assertNotIn("degradation", step)
        if "resource_denial" in expected:
            denial = expected["resource_denial"]
            self.assertEqual(RESOURCE_DENIAL_KEYS, set(denial))
            self.assertEqual(404, denial["status"])
            self.assertEqual("referenced file not found on this route", denial["message"])
            self.assertEqual("gateway_error", denial["error_type"])
            self.assertEqual([False], denial["finished"])
            self.assertEqual(0, denial["active_permits"])
            self.assertEqual(0, denial["upstream_requests"])
            self.assertIn("file_id", json.dumps(step["request"]),
                          f"s7 {name}: only a file reference may be pre-empted by the ownership guard")
        error = expected["error"]
        self.assertTrue(set(error) <= ERROR_PIN_KEYS,
                        f"s7 {name}: unknown error pin {set(error) - ERROR_PIN_KEYS}")
        self.assertEqual(400, error["status"])
        self.assertIn(error["code"], ERROR_CODES)
        self.assertTrue(error["param"])
        self.assertTrue(error["reason"])
        self.assertEqual([False], error["finished"])
        self.assertEqual(0, error["active_permits"])
        self.assertEqual(0, error["upstream_requests"])
        if self.ENTRY == "messages":
            self.assertNotIn("error_type", error, "messages has no OpenAI-shaped error type")
        else:
            self.assertEqual("invalid_request_error", error["error_type"])

    def test_s7_degradation_pair_differs_only_by_the_grant_header(self):
        steps = {s["name"]: s for s in self.fixtures[7]["steps"]}
        unauthorized = steps["degradation-unauthorized"]
        authorized = steps["degradation-authorized"]
        self.assertEqual(unauthorized["request"], authorized["request"],
                         "the grant header must be the ONLY difference between the pair")
        degradation_id = authorized["degradation"]["id"]
        self.assertEqual("capability_degradation_not_authorized", unauthorized["expected"]["error"]["code"])
        self.assertEqual("需授权降级 " + degradation_id, unauthorized["expected"]["error"]["reason"])
        self.assertEqual(authorized["degradation"]["field_path"], unauthorized["expected"]["error"]["param"])

    def test_s7_covers_the_required_negative_categories(self):
        codes = {s["expected"]["error"]["code"] for s in self.fixtures[7]["steps"] if "error" in s["expected"]}
        required = {"capability_unknown_field", "capability_not_supported",
                    "capability_degradation_not_authorized", "capability_invalid_value"}
        if self.ENTRY != "chat":
            required.add("capability_continuation_invalid")
        self.assertEqual(required, codes, f"{self.ENTRY} s7 negative category coverage")

    def test_no_personal_information(self):
        for scenario, fixture in self.fixtures.items():
            for value in walk_strings(fixture):
                if is_base64_payload(value):
                    continue
                self.assertIsNone(EMAIL.search(value), f"email-like PII in {self.ENTRY} s{scenario}: {value[:80]}")
                self.assertIsNone(PHONE.search(value), f"phone-like PII in {self.ENTRY} s{scenario}: {value[:80]}")
                self.assertNotIn(value.lower(), PII_KEYS)

    def test_no_dynamic_placeholders(self):
        for scenario, fixture in self.fixtures.items():
            raw = json.dumps(fixture, ensure_ascii=False)
            self.assertNotIn("{{", raw)
            self.assertNotIn("TODO", raw)
            for key in ("timestamp", "created_at", "random", "nonce"):
                self.assertNotIn(f'"{key}"', raw)


class ChatFixtureTest(EntryFixtureChecks, unittest.TestCase):
    ENTRY = "chat"


class ResponsesFixtureTest(EntryFixtureChecks, unittest.TestCase):
    ENTRY = "responses"


class MessagesFixtureTest(EntryFixtureChecks, unittest.TestCase):
    ENTRY = "messages"


class ImageFixtureTest(unittest.TestCase):
    def test_probe_png_sha256_pinned(self):
        data = (FIXTURES / "images" / "llmgw-probe.png").read_bytes()
        self.assertEqual(PROBE_SHA256, hashlib.sha256(data).hexdigest())
        self.assertEqual(230, len(data))

    def test_images_manifest_base64_matches_png_bytes(self):
        manifest = load(FIXTURES / "images" / "manifest.json")
        image = manifest["images"][0]
        data = (FIXTURES / "images" / "llmgw-probe.png").read_bytes()
        self.assertEqual(base64.b64encode(data).decode("ascii"), image["base64"])
        self.assertEqual(PROBE_SHA256, image["sha256"])

    def test_s5_requests_embed_probe_base64_exactly(self):
        b64 = load(FIXTURES / "images" / "manifest.json")["images"][0]["base64"]
        data_url = "data:image/png;base64," + b64
        chat = load(scenario_file("chat", 5))["steps"][0]["request"]
        parts = chat["messages"][0]["content"]
        image = next(p for p in parts if p["type"] == "image_url")
        self.assertEqual(data_url, image["image_url"]["url"])
        responses = load(scenario_file("responses", 5))["steps"][0]["request"]
        parts = responses["input"][0]["content"]
        image = next(p for p in parts if p["type"] == "input_image")
        self.assertEqual(data_url, image["image_url"])
        messages = load(scenario_file("messages", 5))["steps"][0]["request"]
        parts = messages["messages"][0]["content"]
        image = next(p for p in parts if p["type"] == "image")
        self.assertEqual({"type": "base64", "media_type": "image/png", "data": b64}, image["source"])
        for entry in ("chat", "responses", "messages"):
            expected = load(scenario_file(entry, 5))["steps"][0]["expected"]
            self.assertTrue(expected["image"]["accepted"])
            self.assertEqual(PROBE_SHA256, expected["image"]["sha256"])
        self.assertEqual(PROBE_SHA256, hashlib.sha256(base64.b64decode(b64)).hexdigest())


class ToolFixtureTest(unittest.TestCase):
    def test_add_numbers_known_result_is_decidable(self):
        tool = load(FIXTURES / "tools" / "add-numbers.json")
        self.assertEqual("add_numbers", tool["name"])
        known = tool["known_result"]
        args = known["arguments"]
        self.assertEqual(args["a"] + args["b"], known["result"])
        self.assertEqual(str(known["result"]), known["result_text"])
        self.assertEqual("object", tool["parameters"]["type"])
        self.assertEqual(["a", "b"], tool["parameters"]["required"])

    def test_tool_wire_shapes_match_entry_contracts(self):
        for name in ("add-numbers", "get-weather"):
            tool = load(FIXTURES / "tools" / f"{name}.json")
            wire = tool["wire"]
            self.assertEqual({"chat", "responses", "messages"}, set(wire))
            chat = wire["chat"]
            self.assertEqual("function", chat["type"])
            self.assertEqual(tool["name"], chat["function"]["name"])
            self.assertEqual(tool["parameters"], chat["function"]["parameters"])
            responses = wire["responses"]
            self.assertEqual("function", responses["type"])
            self.assertEqual(tool["name"], responses["name"])
            self.assertEqual(tool["parameters"], responses["parameters"])
            messages = wire["messages"]
            self.assertEqual(tool["name"], messages["name"])
            self.assertEqual(tool["parameters"], messages["input_schema"])

    def test_get_weather_matches_mock_fixed_call(self):
        tool = load(FIXTURES / "tools" / "get-weather.json")
        self.assertEqual("call_mock_1", tool["mock_fixed_call"]["id"])
        self.assertEqual({"city": "SF"}, tool["mock_fixed_call"]["arguments"])
        self.assertEqual({"city": "SF"}, tool["known_result"]["arguments"])

    def test_s3_requests_declare_both_tools(self):
        for entry in ENTRIES:
            fixture = load(scenario_file(entry, 3))
            for step in fixture["steps"]:
                tools = step["request"]["tools"]
                names = {t["function"]["name"] if entry == "chat" else t["name"] for t in tools}
                self.assertEqual({"get_weather", "add_numbers"}, names, entry)
                for tool in tools:
                    if entry == "chat":
                        self.assertEqual("function", tool["type"])
                        self.assertIn("parameters", tool["function"])
                    elif entry == "responses":
                        self.assertEqual("function", tool["type"])
                        self.assertIn("parameters", tool)
                    else:
                        self.assertIn("input_schema", tool)


class EnvelopeFixtureTest(unittest.TestCase):
    def setUp(self):
        self.manifest = load(FIXTURES / "manifest.json")
        self.envelope = self.manifest["continuation_envelope"]

    def test_continuation_tokens_are_deterministic_and_valid(self):
        for entry, item_key in (("responses", "responses"), ("messages", "messages")):
            fixture = load(scenario_file(entry, 4))
            continuation = fixture["steps"][1]["request"]
            token = self.extract_token(entry, continuation)
            expected = mint_token(self.envelope["key_utf8"], self.envelope["project_id"],
                                  self.envelope["item_ids"][item_key], self.envelope["reasoning_text"])
            self.assertEqual(expected, token, f"{entry} continuation token must be the deterministic fixture mint")
            self.assertTrue(token.startswith("llmgw1:"))
            body = token[len("llmgw1:"):]
            payload_b64, mac_b64 = body.split(".")
            payload = json.loads(base64.urlsafe_b64decode(payload_b64 + "=="))
            self.assertEqual(self.envelope["project_id"], payload["projectId"])
            self.assertEqual(self.envelope["item_ids"][item_key], payload["itemId"])
            self.assertEqual(hashlib.sha256(self.envelope["reasoning_text"].encode()).hexdigest(),
                             payload["reasoningSha256"])
            self.assertNotIn("=", token)

    def test_chat_thinking_continuation_uses_native_reasoning_content(self):
        fixture = load(scenario_file("chat", 4))
        continuation = fixture["steps"][1]["request"]
        assistant = next(m for m in continuation["messages"] if m["role"] == "assistant")
        self.assertEqual("Let me think", assistant["reasoning_content"])
        self.assertNotIn("encrypted_content", json.dumps(continuation))
        self.assertNotIn("signature", json.dumps(continuation))

    def test_s7_cross_project_tokens_are_minted_with_the_drive_key_and_a_foreign_project(self):
        for entry in ("responses", "messages"):
            fixture = load(scenario_file(entry, 7))
            step = next(s for s in fixture["steps"] if s["name"].startswith("cross-project"))
            token = self.extract_token(entry, step["request"])
            expected = mint_token(self.envelope["drive_key_utf8"], self.envelope["cross_project_id"],
                                  self.envelope["cross_item_ids"][entry], self.envelope["reasoning_text"])
            self.assertEqual(expected, token, f"{entry} s7 cross-project token must be the deterministic mint")
            payload = json.loads(base64.urlsafe_b64decode(token[len("llmgw1:"):].split(".")[0] + "=="))
            self.assertEqual(self.envelope["cross_project_id"], payload["projectId"])
            self.assertNotEqual(self.envelope["drive_project_id"], payload["projectId"],
                                "the token must be foreign to the drive project, otherwise it would verify")
            self.assertEqual("capability_continuation_invalid", step["expected"]["error"]["code"])
            self.assertEqual("continuation", step["expected"]["error"]["param"])
            self.assertEqual("续轮信封不属于当前项目", step["expected"]["error"]["reason"])

    @staticmethod
    def extract_token(entry, request):
        if entry == "responses":
            item = next(i for i in request["input"] if i.get("type") == "reasoning")
            return item["encrypted_content"]
        block = next(b for m in request["messages"] if m["role"] == "assistant"
                     for b in m["content"] if b["type"] == "thinking")
        return block["signature"]


if __name__ == "__main__":
    unittest.main()