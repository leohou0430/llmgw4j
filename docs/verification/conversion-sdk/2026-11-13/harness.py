#!/usr/bin/env python3
"""T1.15 conversion SDK compatibility smoke (controlled upstream, dual engine).

Official openai SDK (chat + responses) and anthropic SDK (messages) call the
gateway through a SINGLE conversion route (upstream-protocol=chat). The controlled
mock ALWAYS answers in DeepSeek Chat shape at /v1/chat/completions regardless of
the client entry path; the gateway converts each entry to Chat upstream and
re-encodes the reply to the caller's entry protocol.

Every upstream request is asserted to be DeepSeek-Chat shaped (messages array,
model rewritten to deepseek-chat, no responses/messages shape leak) and to carry
the ROUTE credential (Bearer sdk-upstream) — never the caller key (sdk-client).
SDK retries are 0. LLMGW_TEST_JAR pins the tested JAR.

The pure helpers (route/command builders, mock response builders, upstream
violation detector) are importable WITHOUT the SDKs; the SDKs are imported lazily
inside main(). No-network unit tests: test_smoke_conversion_sdk.py.

Usage:
    python3 bin/smoke-conversion-sdk.py --jar llmgw-app/target/llmgw-app-0.3.1.jar --out DIR
    LLMGW_TEST_JAR=/path/to.jar python3 bin/smoke-conversion-sdk.py --out DIR
"""
import argparse
import hashlib
import json
import os
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

CALLER_KEY = "sdk-client"
UPSTREAM_KEY = "sdk-upstream"
TARGET_MODEL = "deepseek-chat"
ENTRY_PATHS = {"chat": "/v1/chat/completions", "responses": "/v1/responses", "messages": "/v1/messages"}
TEXT = "Hello world"
REASONING = "Let me think"


# --------------------------------------------------------------------------- #
# Pure helpers (no SDK imports)
# --------------------------------------------------------------------------- #
def conversion_route_args(mock_port):
    fields = {
        "id": "conv", "protocol": "*", "model": "*", "target-model": TARGET_MODEL,
        "target-url": f"http://127.0.0.1:{mock_port}/v1", "api-key": UPSTREAM_KEY,
        "provider": "openai", "upstream-protocol": "chat",
    }
    return [f"--gateway.routes[0].{k}={v}" for k, v in fields.items()]


def gateway_command(executable, engine, port, mock_port, is_binary=False):
    cmd = ([str(executable)] if is_binary else ["java", "-jar", str(executable)])
    cmd += [
        "--spring.profiles.active=" + engine, f"--server.port={port}", "--server.address=127.0.0.1",
        "--gateway.mock.enabled=false", "--gateway.keys-store-path=", "--gateway.governance.snapshot-path=",
        "--gateway.api-keys[0].id=sdk", "--gateway.api-keys[0].token=" + CALLER_KEY,
        "--gateway.api-keys[0].quota-tokens=1000000",
        "--gateway.governance.rate-limit-tokens-per-second=1000000",
        "--gateway.governance.rate-limit-burst=1000000",
    ]
    cmd += conversion_route_args(mock_port)
    return cmd


def chat_completion(tools=False, thinking=False):
    message = {"role": "assistant", "content": TEXT}
    if thinking:
        message["reasoning_content"] = REASONING
    if tools:
        message["content"] = None
        message["tool_calls"] = [{"id": "call_1", "type": "function",
                                  "function": {"name": "get_weather", "arguments": '{"city":"SF"}'}}]
    return {"id": "chat_1", "object": "chat.completion", "created": 1, "model": TARGET_MODEL,
            "choices": [{"index": 0, "message": message, "finish_reason": "tool_calls" if tools else "stop"}],
            "usage": {"prompt_tokens": 7, "completion_tokens": 1, "total_tokens": 8}}


def chat_chunks(tools=False, thinking=False):
    chunks = []
    if thinking:
        chunks.append({"id": "chat_1", "object": "chat.completion.chunk", "created": 1, "model": TARGET_MODEL,
                       "choices": [{"index": 0, "delta": {"role": "assistant", "reasoning_content": REASONING},
                                    "finish_reason": None}]})
    if tools:
        chunks.append({"id": "chat_1", "object": "chat.completion.chunk", "created": 1, "model": TARGET_MODEL,
                       "choices": [{"index": 0, "delta": {"role": "assistant", "tool_calls": [
                           {"index": 0, "id": "call_1", "type": "function",
                            "function": {"name": "get_weather", "arguments": '{"city":'}}]}, "finish_reason": None}]})
        chunks.append({"id": "chat_1", "object": "chat.completion.chunk", "created": 1, "model": TARGET_MODEL,
                       "choices": [{"index": 0, "delta": {"tool_calls": [
                           {"index": 0, "function": {"arguments": '"SF"}'}}]}, "finish_reason": None}]})
        finish = "tool_calls"
    else:
        chunks.append({"id": "chat_1", "object": "chat.completion.chunk", "created": 1, "model": TARGET_MODEL,
                       "choices": [{"index": 0, "delta": {"role": "assistant", "content": TEXT}, "finish_reason": None}]})
        finish = "stop"
    chunks.append({"id": "chat_1", "object": "chat.completion.chunk", "created": 1, "model": TARGET_MODEL,
                   "choices": [{"index": 0, "delta": {}, "finish_reason": finish}],
                   "usage": {"prompt_tokens": 7, "completion_tokens": 1, "total_tokens": 8}})
    return chunks


def sse_payload(chunks):
    return "".join("data: " + json.dumps(c) + "\n\n" for c in chunks) + "data: [DONE]\n\n"


def upstream_violations(row):
    problems = []
    body = row.get("body", {})
    headers = row.get("headers", {})
    if "messages" not in body:
        problems.append("upstream not chat shape: no messages array")
    if body.get("model") != TARGET_MODEL:
        problems.append(f"upstream model not rewritten to {TARGET_MODEL}: {body.get('model')}")
    if "input" in body or "instructions" in body:
        problems.append("upstream leaked responses shape (input/instructions)")
    if headers.get("authorization") != "Bearer " + UPSTREAM_KEY:
        problems.append("upstream credential is not the route key (Bearer sdk-upstream)")
    if CALLER_KEY in json.dumps(row):
        problems.append("caller credential sdk-client leaked upstream")
    return problems


def _is_continuation(body):
    for m in body.get("messages", []) or []:
        if m.get("role") == "tool" or m.get("reasoning_content"):
            return True
        content = m.get("content")
        if isinstance(content, list):
            for block in content:
                if isinstance(block, dict) and block.get("type") in ("tool_result", "thinking"):
                    return True
    items = body.get("input")
    if isinstance(items, list):
        for it in items:
            if isinstance(it, dict) and it.get("type") in ("function_call_output", "reasoning"):
                return True
    return False


def _wants_thinking(body):
    return "mock-think" in json.dumps(body)


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


# --------------------------------------------------------------------------- #
# Live harness (lazy SDK imports)
# --------------------------------------------------------------------------- #
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    artifact = parser.add_mutually_exclusive_group()
    artifact.add_argument("--jar", type=Path)
    artifact.add_argument("--binary", type=Path)
    parser.add_argument("--engine", choices=["webflux", "vt"])
    parser.add_argument("--out", type=Path, required=True)
    options = parser.parse_args()

    import anthropic  # lazy: only the live path needs the official clients
    import httpx2  # anthropic 2.8.0 default client trips an httpx2 mount-parse bug; pass an explicit client
    import openai

    jar_env = os.environ.get("LLMGW_TEST_JAR")
    if options.binary:
        executable = options.binary.resolve()
        is_binary = True
    elif options.jar:
        executable = options.jar.resolve()
        is_binary = False
    elif jar_env:
        executable = Path(jar_env).resolve()
        is_binary = False
    else:
        executable = Path("llmgw-app/target/llmgw-app-0.3.1.jar").resolve()
        is_binary = False
    if not executable.exists():
        raise SystemExit(f"artifact not found: {executable}")

    out = options.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    (out / "harness.py").write_bytes(Path(__file__).read_bytes())
    report = {
        ("binary_sha256" if is_binary else "jar_sha256"): hashlib.sha256(executable.read_bytes()).hexdigest(),
        "harness_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "versions": {"openai": openai.__version__, "anthropic": anthropic.__version__},
        "checks": [],
    }
    observed = []

    def save():
        (out / "conversion-sdk-results.json").write_text(json.dumps(report, indent=2) + "\n")

    class Mock(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def respond(self, body, media="application/json"):
            data = body if isinstance(body, bytes) else json.dumps(body).encode()
            self.send_response(200)
            self.send_header("Content-Type", media)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            try:
                for offset in range(0, len(data), 17):
                    self.wfile.write(data[offset:offset + 17])
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass

        def do_POST(self):
            raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            body = json.loads(raw)
            observed.append({"path": self.path, "body": body,
                             "headers": {k.lower(): v for k, v in self.headers.items()}})
            continuation = _is_continuation(body)
            tools = bool(body.get("tools")) and not continuation
            thinking = _wants_thinking(body) and not continuation
            if body.get("stream"):
                self.respond(sse_payload(chat_chunks(tools=tools, thinking=thinking)).encode(),
                             media="text/event-stream")
            else:
                self.respond(chat_completion(tools=tools, thinking=thinking))

        def do_GET(self):
            self.respond(b"{}")

    server = ThreadingHTTPServer(("127.0.0.1", 0), Mock)
    mock_port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    process = None

    def check(engine, name, action):
        row = {"engine": engine, "name": name}
        report["checks"].append(row)
        try:
            action()
            row["passed"] = True
            print("PASS", engine, name, flush=True)
        except Exception as error:  # noqa: BLE001
            row.update(passed=False, error=repr(error))
            print("FAIL", engine, name, repr(error), flush=True)
        save()

    def stop():
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()

    def equal(actual, expected):
        assert actual == expected, (actual, expected)

    def assert_upstream_clean(since):
        rows = observed[since:]
        assert rows, "no upstream request was captured"
        for row in rows:
            problems = upstream_violations(row)
            assert not problems, (row["path"], problems)

    weather_tool_chat = {"type": "function", "function": {"name": "get_weather",
                         "parameters": {"type": "object", "properties": {"city": {"type": "string"}}}}}
    weather_tool_responses = {"type": "function", "name": "get_weather",
                              "parameters": {"type": "object", "properties": {"city": {"type": "string"}}}}
    weather_tool_messages = {"name": "get_weather", "input_schema": {"type": "object",
                             "properties": {"city": {"type": "string"}}}}
    image_png = ("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQDJ/pLvAAAAAElFTkSuQmCC")
    data_url = "data:image/png;base64," + image_png

    def run_chat(engine, oa, check):
        base = {"model": "audit-model", "max_tokens": 1024}

        def s1():
            since = len(observed)
            value = oa.chat.completions.create(messages=[
                {"role": "system", "content": "You are concise."},
                {"role": "user", "content": "Capital of France?"},
                {"role": "assistant", "content": "Paris."},
                {"role": "user", "content": "And the population?"}], **base)
            equal(value.choices[0].message.content, TEXT)
            assert_upstream_clean(since)
        check(engine, "chat-s1-text-multiturn", s1)

        def s2():
            since = len(observed)
            with oa.chat.completions.create(messages=[{"role": "user", "content": "stream"}],
                                            stream=True, stream_options={"include_usage": True}, **base) as events:
                items = list(events)
            equal("".join(c.choices[0].delta.content or "" for c in items if c.choices), TEXT)
            equal(items[-1].usage.total_tokens, 8)
            assert_upstream_clean(since)
        check(engine, "chat-s2-stream-usage", s2)

        def s3():
            since = len(observed)
            first = oa.chat.completions.create(messages=[{"role": "user", "content": "weather?"}],
                                               tools=[weather_tool_chat], **base)
            call = first.choices[0].message.tool_calls[0]
            equal(call.function.name, "get_weather")
            second = oa.chat.completions.create(messages=[
                {"role": "user", "content": "weather?"},
                {"role": "assistant", "tool_calls": [{"id": call.id, "type": "function",
                 "function": {"name": "get_weather", "arguments": call.function.arguments}}]},
                {"role": "tool", "tool_call_id": call.id, "content": '{"temperature_c":18}'}],
                tools=[weather_tool_chat], **base)
            equal(second.choices[0].message.content, TEXT)
            assert_upstream_clean(since)
        check(engine, "chat-s3-tool-continuation", s3)

        def s4():
            since = len(observed)
            first = oa.chat.completions.create(messages=[{"role": "user", "content": "mock-think: reason"}], **base)
            equal(first.choices[0].message.reasoning_content, REASONING)
            second = oa.chat.completions.create(messages=[
                {"role": "user", "content": "mock-think: reason"},
                {"role": "assistant", "content": TEXT, "reasoning_content": REASONING},
                {"role": "user", "content": "continue"}], **base)
            equal(second.choices[0].message.content, TEXT)
            assert_upstream_clean(since)
        check(engine, "chat-s4-thinking-continuation", s4)

        def s5():
            since = len(observed)
            value = oa.chat.completions.create(messages=[{"role": "user", "content": [
                {"type": "text", "text": "what is this?"},
                {"type": "image_url", "image_url": {"url": data_url}}]}], **base)
            equal(value.choices[0].message.content, TEXT)
            assert any("image_url" in json.dumps(r["body"]) for r in observed[since:])
            assert_upstream_clean(since)
        check(engine, "chat-s5-image", s5)

        def s6():
            since = len(observed)
            with oa.chat.completions.create(messages=[{"role": "user", "content": "cancel probe"}],
                                            stream=True, **base) as events:
                for chunk in events:
                    if chunk.choices and chunk.choices[0].delta.content:
                        break  # cancel after first content frame
            recovered = oa.chat.completions.create(messages=[{"role": "user", "content": "recovery"}], **base)
            equal(recovered.choices[0].message.content, TEXT)
            assert_upstream_clean(since)
        check(engine, "chat-s6-cancel-recover", s6)

    def run_responses(engine, oa, check):
        def s1():
            since = len(observed)
            value = oa.responses.create(model="audit-model", max_output_tokens=1024,
                                        input=[{"type": "message", "role": "user", "content": "Capital of France?"}])
            equal(value.output_text, TEXT)
            assert_upstream_clean(since)
        check(engine, "responses-s1-text", s1)

        def s2():
            since = len(observed)
            with oa.responses.create(model="audit-model", max_output_tokens=1024,
                                     input="stream", stream=True) as events:
                items = list(events)
            equal("".join(e.delta for e in items if e.type == "response.output_text.delta"), TEXT)
            assert_upstream_clean(since)
        check(engine, "responses-s2-stream", s2)

        def s3():
            since = len(observed)
            first = oa.responses.create(model="audit-model", max_output_tokens=1024,
                                        input="weather?", tools=[weather_tool_responses])
            calls = [o for o in first.output if o.type == "function_call"]
            equal(calls[0].name, "get_weather")
            second = oa.responses.create(model="audit-model", max_output_tokens=1024,
                                         input=[{"type": "message", "role": "user", "content": "weather?"},
                                                {"type": "function_call", "call_id": calls[0].call_id,
                                                 "name": "get_weather", "arguments": calls[0].arguments},
                                                {"type": "function_call_output", "call_id": calls[0].call_id,
                                                 "output": '{"temperature_c":18}'}],
                                         tools=[weather_tool_responses])
            equal(second.output_text, TEXT)
            assert_upstream_clean(since)
        check(engine, "responses-s3-tool-continuation", s3)

        def s4():
            since = len(observed)
            first = oa.responses.create(model="audit-model", max_output_tokens=1024,
                                        input=[{"type": "message", "role": "user", "content": "mock-think: reason"}])
            assert any(o.type == "reasoning" for o in first.output), "responses reasoning item missing"
            assert_upstream_clean(since)
        check(engine, "responses-s4-thinking", s4)

        def s5():
            since = len(observed)
            value = oa.responses.create(model="audit-model", max_output_tokens=1024,
                                        input=[{"type": "message", "role": "user", "content": [
                                            {"type": "input_text", "text": "what is this?"},
                                            {"type": "input_image", "image_url": data_url}]}])
            equal(value.output_text, TEXT)
            assert_upstream_clean(since)
        check(engine, "responses-s5-image", s5)

        def s6():
            since = len(observed)
            with oa.responses.create(model="audit-model", max_output_tokens=1024,
                                     input="cancel probe", stream=True) as events:
                for event in events:
                    if event.type == "response.output_text.delta":
                        break
            recovered = oa.responses.create(model="audit-model", max_output_tokens=1024, input="recovery")
            equal(recovered.output_text, TEXT)
            assert_upstream_clean(since)
        check(engine, "responses-s6-cancel-recover", s6)

    def run_messages(engine, ant, check):
        def s1():
            since = len(observed)
            value = ant.messages.create(model="audit-model", max_tokens=1024, system="You are concise.",
                                        messages=[{"role": "user", "content": "Capital of France?"}])
            equal(value.content[0].text, TEXT)
            assert_upstream_clean(since)
        check(engine, "messages-s1-text", s1)

        def s2():
            since = len(observed)
            with ant.messages.stream(model="audit-model", max_tokens=1024,
                                     messages=[{"role": "user", "content": "stream"}]) as events:
                equal("".join(events.text_stream), TEXT)
                equal(events.get_final_message().usage.output_tokens, 1)
            assert_upstream_clean(since)
        check(engine, "messages-s2-stream-usage", s2)

        def s3():
            since = len(observed)
            first = ant.messages.create(model="audit-model", max_tokens=1024,
                                        messages=[{"role": "user", "content": "weather?"}],
                                        tools=[weather_tool_messages])
            tool_use = next(b for b in first.content if b.type == "tool_use")
            equal(tool_use.name, "get_weather")
            second = ant.messages.create(model="audit-model", max_tokens=1024,
                                         messages=[{"role": "user", "content": "weather?"},
                                                   {"role": "assistant", "content": [tool_use]},
                                                   {"role": "user", "content": [
                                                       {"type": "tool_result", "tool_use_id": tool_use.id,
                                                        "content": '{"temperature_c":18}'}]}],
                                         tools=[weather_tool_messages])
            equal(second.content[0].text, TEXT)
            assert_upstream_clean(since)
        check(engine, "messages-s3-tool-continuation", s3)

        def s4():
            since = len(observed)
            first = ant.messages.create(model="audit-model", max_tokens=1024,
                                        messages=[{"role": "user", "content": "mock-think: reason"}])
            assert any(b.type == "thinking" for b in first.content), "messages thinking block missing"
            assert_upstream_clean(since)
        check(engine, "messages-s4-thinking", s4)

        def s5():
            since = len(observed)
            value = ant.messages.create(model="audit-model", max_tokens=1024,
                                        messages=[{"role": "user", "content": [
                                            {"type": "text", "text": "what is this?"},
                                            {"type": "image", "source": {"type": "base64",
                                             "media_type": "image/png", "data": image_png}}]}])
            equal(value.content[0].text, TEXT)
            assert_upstream_clean(since)
        check(engine, "messages-s5-image", s5)

        def s6():
            since = len(observed)
            with ant.messages.stream(model="audit-model", max_tokens=1024,
                                     messages=[{"role": "user", "content": "cancel probe"}]) as events:
                for _ in events.text_stream:
                    break
            recovered = ant.messages.create(model="audit-model", max_tokens=1024,
                                            messages=[{"role": "user", "content": "recovery"}])
            equal(recovered.content[0].text, TEXT)
            assert_upstream_clean(since)
        check(engine, "messages-s6-cancel-recover", s6)

    try:
        engines = [options.engine] if options.engine else ["webflux", "vt"]
        for engine in engines:
            port = free_port()
            observed.clear()
            with tempfile.TemporaryDirectory(prefix="llmgw-conv-sdk-") as temp, (out / (engine + ".log")).open("w") as log:
                cmd = gateway_command(executable, engine, port, mock_port, is_binary=is_binary)
                report.setdefault("commands", {})[engine] = cmd
                save()
                process = subprocess.Popen(cmd, cwd=temp, stdout=log, stderr=subprocess.STDOUT)
                try:
                    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                    for _ in range(160):
                        if process.poll() is not None:
                            raise RuntimeError("gateway startup failed")
                        try:
                            with opener.open(f"http://127.0.0.1:{port}/actuator/health", timeout=1) as result:
                                if result.status == 200:
                                    break
                        except OSError:
                            pass
                        time.sleep(0.25)
                    else:
                        raise RuntimeError("gateway readiness timeout")
                    with ExitStack() as stack:
                        oa = stack.enter_context(openai.OpenAI(api_key=CALLER_KEY,
                                                 base_url=f"http://127.0.0.1:{port}/v1", max_retries=0, timeout=10))
                        ant_http = httpx2.Client(timeout=10)
                        stack.callback(ant_http.close)
                        ant = stack.enter_context(anthropic.Anthropic(api_key=CALLER_KEY,
                                                  base_url=f"http://127.0.0.1:{port}", max_retries=0,
                                                  http_client=ant_http))
                        run_chat(engine, oa, check)
                        run_responses(engine, oa, check)
                        run_messages(engine, ant, check)
                    sample = observed[0] if observed else {}
                    report.setdefault("upstream_samples", {})[engine] = {
                        "path": sample.get("path"),
                        "body_keys": sorted(sample.get("body", {}).keys()),
                        "model": sample.get("body", {}).get("model"),
                        "authorization": sample.get("headers", {}).get("authorization"),
                        "has_messages": "messages" in sample.get("body", {}),
                        "upstream_requests": len(observed),
                    }
                finally:
                    stop()
        report["complete"] = all(r.get("passed") for r in report["checks"])
    except BaseException as error:
        report["failure"] = repr(error)
        raise
    finally:
        stop()
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
        save()
    if not report["complete"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()