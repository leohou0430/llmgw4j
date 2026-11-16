"""T1.15 conversion SDK smoke harness unittest (NO network, no SDK deps at import).

The harness must be importable WITHOUT openai/anthropic; the official SDKs are
imported lazily inside the live-send path only. These tests never open a socket
and never call the live path. They pin the pure helpers: conversion-route command
building, the controlled DeepSeek-Chat mock response builders, and the upstream
shape/credential violation detector.

Run: python3 -m unittest discover -s bin -p 'test_smoke_conversion_sdk.py'
"""
import importlib.util
import json
import sys
import unittest
from pathlib import Path

BIN = Path(__file__).resolve().parent
HARNESS_PATH = BIN / "smoke-conversion-sdk.py"


def _load_harness():
    spec = importlib.util.spec_from_file_location("smoke_conversion_sdk", HARNESS_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules["smoke_conversion_sdk"] = module
    spec.loader.exec_module(module)
    return module


mod = _load_harness()


class LazyImportTests(unittest.TestCase):
    def test_harness_imports_without_sdks(self):
        # loading the harness must not pull the official SDKs into the process
        self.assertNotIn("openai", sys.modules)
        self.assertNotIn("anthropic", sys.modules)

    def test_constants(self):
        self.assertEqual(mod.CALLER_KEY, "sdk-client")
        self.assertEqual(mod.UPSTREAM_KEY, "sdk-upstream")
        self.assertEqual(mod.TARGET_MODEL, "deepseek-chat")
        self.assertEqual(set(mod.ENTRY_PATHS), {"chat", "responses", "messages"})


class RouteCommandTests(unittest.TestCase):
    def test_conversion_route_is_single_chat_upstream(self):
        args = mod.conversion_route_args(9999)
        joined = "\n".join(args)
        self.assertIn("--gateway.routes[0].upstream-protocol=chat", joined)
        self.assertIn("--gateway.routes[0].target-model=deepseek-chat", joined)
        self.assertIn("--gateway.routes[0].protocol=*", joined)
        self.assertIn("--gateway.routes[0].model=*", joined)
        self.assertIn("--gateway.routes[0].provider=openai", joined)
        self.assertIn("--gateway.routes[0].api-key=sdk-upstream", joined)
        self.assertIn("--gateway.routes[0].target-url=http://127.0.0.1:9999/v1", joined)
        # exactly one route (index 0 only)
        self.assertFalse(any("routes[1]" in a for a in args))

    def test_gateway_command_disables_mock_and_pins_engine(self):
        cmd = mod.gateway_command("/tmp/x.jar", "webflux", 8080, 9999)
        self.assertEqual(cmd[0], "java")
        self.assertIn("-jar", cmd)
        self.assertIn("--spring.profiles.active=webflux", cmd)
        self.assertIn("--gateway.mock.enabled=false", cmd)
        self.assertIn("--gateway.api-keys[0].token=sdk-client", cmd)
        self.assertIn("--gateway.routes[0].upstream-protocol=chat", cmd)
        # the caller key is the gateway api-key, never the upstream route key
        self.assertIn("--gateway.routes[0].api-key=sdk-upstream", cmd)

    def test_binary_command_uses_executable_directly(self):
        cmd = mod.gateway_command("/tmp/x", "vt", 8080, 9999, is_binary=True)
        self.assertEqual(cmd[0], "/tmp/x")
        self.assertNotIn("-jar", cmd)
        self.assertIn("--spring.profiles.active=vt", cmd)


class MockBuilderTests(unittest.TestCase):
    def test_chat_completion_is_deepseek_chat_shape(self):
        body = mod.chat_completion()
        self.assertEqual(body["object"], "chat.completion")
        self.assertEqual(body["model"], "deepseek-chat")
        self.assertEqual(body["choices"][0]["message"]["content"], "Hello world")
        self.assertEqual(body["choices"][0]["finish_reason"], "stop")
        self.assertIn("usage", body)

    def test_chat_completion_tools(self):
        body = mod.chat_completion(tools=True)
        message = body["choices"][0]["message"]
        self.assertEqual(body["choices"][0]["finish_reason"], "tool_calls")
        self.assertEqual(message["tool_calls"][0]["function"]["name"], "get_weather")
        self.assertEqual(json.loads(message["tool_calls"][0]["function"]["arguments"]), {"city": "SF"})

    def test_chat_completion_thinking(self):
        body = mod.chat_completion(thinking=True)
        self.assertEqual(body["choices"][0]["message"]["reasoning_content"], "Let me think")

    def test_chat_chunks_terminal_usage_and_done(self):
        chunks = mod.chat_chunks()
        self.assertTrue(all(c["object"] == "chat.completion.chunk" for c in chunks))
        self.assertEqual(chunks[-1]["choices"][0]["finish_reason"], "stop")
        self.assertEqual(chunks[-1]["usage"]["total_tokens"], 8)
        payload = mod.sse_payload(chunks)
        self.assertTrue(payload.endswith("data: [DONE]\n\n"))
        self.assertEqual(payload.count("data: [DONE]"), 1)

    def test_chat_chunks_tools_and_thinking(self):
        tool_chunks = mod.chat_chunks(tools=True)
        self.assertEqual(tool_chunks[-1]["choices"][0]["finish_reason"], "tool_calls")
        think_chunks = mod.chat_chunks(thinking=True)
        self.assertTrue(any("reasoning_content" in c["choices"][0]["delta"] for c in think_chunks))


class UpstreamViolationTests(unittest.TestCase):
    def _good_row(self):
        return {"body": {"model": "deepseek-chat", "messages": [{"role": "user", "content": "hi"}]},
                "headers": {"authorization": "Bearer sdk-upstream"}}

    def test_good_chat_upstream_has_no_violations(self):
        self.assertEqual(mod.upstream_violations(self._good_row()), [])

    def test_responses_shape_leak_detected(self):
        row = self._good_row()
        row["body"] = {"model": "deepseek-chat", "input": "hi", "instructions": "x"}
        self.assertTrue(mod.upstream_violations(row))

    def test_missing_messages_detected(self):
        row = self._good_row()
        row["body"] = {"model": "deepseek-chat"}
        self.assertTrue(mod.upstream_violations(row))

    def test_model_not_rewritten_detected(self):
        row = self._good_row()
        row["body"]["model"] = "audit-model"
        self.assertTrue(mod.upstream_violations(row))

    def test_caller_credential_leak_detected(self):
        row = self._good_row()
        row["headers"]["authorization"] = "Bearer sdk-client"
        self.assertTrue(mod.upstream_violations(row))

    def test_wrong_upstream_key_detected(self):
        row = self._good_row()
        row["headers"]["authorization"] = "Bearer something-else"
        self.assertTrue(mod.upstream_violations(row))


if __name__ == "__main__":
    unittest.main()