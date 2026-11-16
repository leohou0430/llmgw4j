#!/usr/bin/env python3
"""Deterministic generator for the T1.13b versioned acceptance fixtures.

Three entries (chat/responses/messages) x six scenarios, machine-readable expected
structure assertions, shared decidable tool definitions and a top-level manifest
whose sha256/bytes are computed from the bytes actually written. No randomness, no
timestamps, no personal information. The committed images/ bytes are the source of
truth: this script only READS images/ and never regenerates or modifies it.

Usage:  python3 generate.py [--entries chat,responses,messages]
"""
import argparse
import base64
import hashlib
import hmac
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent

FIXTURE_SCHEMA = "llmgw-acceptance-fixture/v1"
MANIFEST_SCHEMA = "llmgw-acceptance-manifest/v1"
TOOL_SCHEMA = "llmgw-acceptance-tool/v1"

KEY_UTF8 = "llmgw-acceptance-fixture-key"
TENANT = "fixture-tenant"
PROJECT = "fixture-project"
REASONING = "Let me think"
TEXT = "Hello world"
ITEM_IDS = {"responses": "rs_fixture_1", "messages": "th_fixture_1"}
TOOL_ID = "call_mock_1"
TOOL_NAME = "get_weather"
TOOL_ARGS = {"city": "SF"}
TOOL_ARGS_JSON = '{"city":"SF"}'
TOOL_RESULT = {"city": "SF", "temperature_c": 18, "condition": "sunny"}
TOOL_RESULT_TEXT = json.dumps(TOOL_RESULT, separators=(",", ":"))
PROMPT_TOKENS = 7
CACHED_TOKENS = 3
REASONING_TOKENS = 2
CHUNKS = 4
MODEL = "mock-model"

# s7 drives the REAL controllers, whose continuation key/project come from the test harness
# (ConversionContractTest.startMock + PermitChain), not from the s4 fixture envelope above.
DRIVE_KEY_UTF8 = "contract-test-continuation-key"
DRIVE_PROJECT = "fixture-tenant"
CROSS_PROJECT = "other-project"
CROSS_ITEM_IDS = {"responses": "rs_cross_project_1", "messages": "th_cross_project_1"}
GRANT_HEADER = "X-LLMGW-Allow-Degradation"
EXTERNAL_IMAGE_URL = "https://example.invalid/probe.png"
FILE_ID = "file_fixture_1"
UNKNOWN_FIELD = "capability_unknown_field"
NOT_SUPPORTED = "capability_not_supported"
NOT_AUTHORIZED = "capability_degradation_not_authorized"
CONTINUATION_INVALID = "capability_continuation_invalid"
INVALID_VALUE = "capability_invalid_value"
INVALID_REQUEST_ERROR = "invalid_request_error"
# A deterministic entry-codec INVALID_VALUE trigger: image/bmp is outside the inline-image whitelist
# (jpeg/png/gif/webp). The reason is a static literal (no JDK exception text), so it pins stably.
BMP_DATA_URL = "data:image/bmp;base64,QUJD"
BMP_REASON = "unsupported image media_type: image/bmp (allowed: image/jpeg, image/png, image/gif, image/webp)"

SLUGS = {
    1: "text-multiturn",
    2: "stream-text-usage",
    3: "tool-call-continuation",
    4: "thinking-continuation",
    5: "image-understanding",
    6: "cancel-recover",
    7: "contract-negatives",
}
TITLES = {
    1: "非流式文本+多轮",
    2: "流式文本+usage",
    3: "工具调用→客户端执行→结果续轮",
    4: "思考输出+携带状态续轮",
    5: "图片理解（内联）",
    6: "取消后释放并恢复",
    7: "受控合同反例+授权降级正例",
}
STEP_NAMES = {3: ("initial", "continuation"), 4: ("initial", "continuation"), 6: ("cancel", "recovery")}
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

S3_NOTES = ("Marker hygiene: the continuation history carries no mock-deepseek-tools marker so the "
            "controlled upstream answers text after the tool result; the plain mock-deepseek marker "
            "lives only in the active turn. Responses continuation uses input items (function_call + "
            "function_call_output); previous_response_id is rejected by the gateway (no conversation storage).")
S4_NOTES = ("Step 1 streams thinking; step 2 carries the reasoning state back. Responses/Messages tokens "
            "are pre-minted with the documented fixture key/project (manifest.continuation_envelope); "
            "T1.14 may substitute the runtime-minted token from step 1 because verify() binds projectId + "
            "reasoning text, not the runtime item id. Chat carries native reasoning_content plaintext.")
S6_NOTES = ("mock-stall-mid-30000 silences the upstream after the first frame so the client cancel is "
            "deterministic; the recovery step proves permits released and the route healthy.")
S7_NOTES = ("Controlled contract NEGATIVES (spec 3.1/3.2): every error step must be rejected pre-header with "
            "the pinned status + stable capability code + field path + reason, in the ENTRY's own error shape, "
            "with no success terminal, zero active permits and zero outbound upstream requests. The mock marker "
            "is present in every request only so the authorized/unauthorized degradation pair stays byte-identical; "
            "error steps never reach the wire (upstream_requests=0). The last step is the explicit authorization "
            "POSITIVE: the same request plus the grant header must succeed and really degrade. Cross-project tokens "
            "are minted with manifest.continuation_envelope.drive_key_utf8 and cross_project_id so the HMAC passes "
            "and the PROJECT BINDING branch is the one that fails. Corrupt/truncated SSE negatives are already "
            "covered by ConversionEngineIntegrationTest / ConversionServiceTest / MockUpstreamDeepSeekTest. "
            "File references (input_file / file_id) are denied by the ENGINE ownership guard BEFORE conversion: "
            "the drive answers 404 with the gateway-generic body (expected.resource_denial), while the entry "
            "codec's own 400 capability rejection stays pinned as expected.error and is asserted at codec level.")


def mint(item_id):
    return mint_with(KEY_UTF8, PROJECT, item_id)


def mint_with(key_utf8, project_id, item_id):
    payload = json.dumps(
        {"projectId": project_id, "itemId": item_id,
         "reasoningSha256": hashlib.sha256(REASONING.encode("utf-8")).hexdigest()},
        separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    encoded = base64.urlsafe_b64encode(payload).decode("ascii").rstrip("=")
    mac = hmac.new(key_utf8.encode("utf-8"), ("llmgw1:" + encoded).encode("utf-8"), hashlib.sha256).digest()
    return "llmgw1:" + encoded + "." + base64.urlsafe_b64encode(mac).decode("ascii").rstrip("=")


def probe_image():
    manifest = json.loads((HERE / "images" / "manifest.json").read_text(encoding="utf-8"))
    image = manifest["images"][0]
    data = (HERE / "images" / image["file"]).read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest != image["sha256"] or base64.b64encode(data).decode("ascii") != image["base64"]:
        raise SystemExit("committed images/ bytes drifted from images/manifest.json; refusing to regenerate")
    return image["base64"], digest


def tool_definitions():
    add_params = {"type": "object", "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}},
                  "required": ["a", "b"], "additionalProperties": False}
    add_desc = "Deterministic integer addition for controlled acceptance runs."
    weather_params = {"type": "object", "properties": {"city": {"type": "string"}},
                      "required": ["city"], "additionalProperties": False}
    weather_desc = 'Mock-fixed weather tool: the controlled upstream always calls it with {"city":"SF"}.'

    def wire(name, desc, params):
        return {
            "chat": {"type": "function", "function": {"name": name, "description": desc,
                                                      "parameters": params, "strict": False}},
            "responses": {"type": "function", "name": name, "description": desc,
                          "parameters": params, "strict": False},
            "messages": {"name": name, "description": desc, "input_schema": params},
        }

    add = {"schema": TOOL_SCHEMA, "name": "add_numbers", "description": add_desc, "parameters": add_params,
           "known_result": {"arguments": {"a": 17, "b": 25}, "result": 42, "result_text": "42"},
           "wire": wire("add_numbers", add_desc, add_params)}
    weather = {"schema": TOOL_SCHEMA, "name": TOOL_NAME, "description": weather_desc, "parameters": weather_params,
               "mock_fixed_call": {"id": TOOL_ID, "arguments": TOOL_ARGS},
               "known_result": {"arguments": TOOL_ARGS, "result": TOOL_RESULT, "result_text": TOOL_RESULT_TEXT},
               "wire": wire(TOOL_NAME, weather_desc, weather_params)}
    return {"tools/add-numbers.json": add, "tools/get-weather.json": weather}


def usage(entry, thinking, stream):
    completion = CHUNKS if stream else 1
    if entry == "chat":
        node = {"prompt_tokens": PROMPT_TOKENS, "completion_tokens": completion,
                "total_tokens": PROMPT_TOKENS + completion,
                "prompt_tokens_details": {"cached_tokens": CACHED_TOKENS}}
        if thinking:
            node["completion_tokens_details"] = {"reasoning_tokens": REASONING_TOKENS}
        else:
            node["absent_fields"] = ["usage.completion_tokens_details"]
    elif entry == "responses":
        node = {"input_tokens": PROMPT_TOKENS, "output_tokens": completion,
                "total_tokens": PROMPT_TOKENS + completion,
                "input_tokens_details": {"cached_tokens": CACHED_TOKENS}}
        if thinking:
            node["output_tokens_details"] = {"reasoning_tokens": REASONING_TOKENS}
        else:
            node["absent_fields"] = ["usage.output_tokens_details"]
    else:
        node = {"input_tokens": PROMPT_TOKENS - CACHED_TOKENS, "cache_read_input_tokens": CACHED_TOKENS,
                "cache_creation_input_tokens": 0, "output_tokens": completion}
        absent = ["usage.total_tokens"]
        if thinking:
            node["output_tokens_details"] = {"thinking_tokens": REASONING_TOKENS}
        else:
            absent.append("usage.output_tokens_details")
        node["absent_fields"] = absent
    return node


def terminal(entry, stream, kind, thinking):
    finish = "tool_calls" if kind == "tool" else "stop"
    if entry == "chat":
        if not stream:
            node = {"object": "chat.completion", "choices_len": 1, "message_role": "assistant",
                    "finish_reason": finish}
            if kind == "tool":
                node["content_null"] = True
            return node
        return {"object": "chat.completion.chunk", "finish_reason": finish,
                "done_literal": "data: [DONE]", "done_count": 1,
                "usage_chunk_after_terminal": True, "usage_chunk_choices_len": 0}
    if entry == "responses":
        types = ["reasoning", "message"] if thinking else ["function_call"] if kind == "tool" else ["message"]
        if not stream:
            node = {"object": "response", "status": "completed", "output_item_types": types}
            if thinking:
                node["reasoning_encrypted_content_prefix"] = "llmgw1:"
            if kind == "tool":
                node["function_call"] = {"call_id": TOOL_ID, "name": TOOL_NAME, "arguments": TOOL_ARGS_JSON}
            return node
        return {"event": "response.completed", "count": 1, "response_status": "completed",
                "output_item_types": types, "sequence_number": "monotonic_from_0",
                "done_marker": False, "usage_location": "response.usage"}
    stop = "tool_use" if kind == "tool" else "end_turn"
    blocks = ["thinking", "text"] if thinking else ["tool_use"] if kind == "tool" else ["text"]
    if not stream:
        node = {"type": "message", "role": "assistant", "stop_reason": stop,
                "stop_sequence_null": True, "content_block_types": blocks}
        if thinking:
            node["thinking_signature_prefix"] = "llmgw1:"
        return node
    return {"message_stop_count": 1, "stop_reason_event": "message_delta", "stop_reason": stop,
            "done_marker": False, "usage_location": "message_delta.usage",
            "block_indices": "contiguous_from_0"}


def sse_block(entry, thinking):
    if entry == "chat":
        node = {"framing": "data-only", "first_delta_role": "assistant"}
        if thinking:
            node["reasoning_delta_concat"] = REASONING
            node["reasoning_before_content"] = True
        node["content_delta_concat"] = TEXT
        node["terminal_chunk"] = {"finish_reason": "stop", "delta_empty": True}
        node["usage_chunk"] = {"after_terminal": True, "choices_len": 0}
        node["done"] = {"literal": "data: [DONE]", "count": 1, "last": True}
        return node
    if entry == "responses":
        node = {"framing": "event+data", "event_type_equals_data_type": True,
                "sequence_number": "monotonic_from_0",
                "preamble": ["response.created", "response.in_progress"]}
        if thinking:
            node["reasoning_item"] = {"open": "response.output_item.added",
                                      "delta_event": "response.reasoning_text.delta",
                                      "done_text": REASONING, "close": "response.output_item.done",
                                      "encrypted_content_prefix": "llmgw1:", "summary_empty": True}
        node["message_item"] = {"open": "response.output_item.added",
                                "delta_event": "response.output_text.delta",
                                "done_text": TEXT, "close": "response.output_item.done"}
        node["terminal"] = {"event": "response.completed", "count": 1,
                            "response_status": "completed", "usage_location": "response.usage"}
        node["done_marker"] = False
        return node
    node = {"framing": "event+data", "event_type_equals_data_type": True,
            "preamble": ["message_start"],
            "message_start_usage": {"input_tokens": 0, "output_tokens": 0},
            "block_indices": "contiguous_from_0"}
    if thinking:
        node["thinking_block"] = {"start_type": "thinking", "delta_type": "thinking_delta",
                                  "delta_concat": REASONING, "signature_delta_prefix": "llmgw1:",
                                  "closed": True}
    node["text_block"] = {"start_type": "text", "delta_type": "text_delta",
                          "delta_concat": TEXT, "closed": True}
    node["terminal"] = {"message_delta_stop_reason": "end_turn", "message_stop_count": 1,
                        "usage_location": "message_delta.usage"}
    node["done_marker"] = False
    return node


def expected(entry, stream, kind="text", thinking=False, cancel=False, image=None):
    if cancel:
        return {"transport": "sse",
                "cancel": {"trigger": "after_first_content_frame", "stall_marker": "mock-stall-mid-30000",
                           "assertions": {"first_frame_received": True, "active_permits_after_cancel": 0,
                                          "no_success_terminal_after_cancel": True,
                                          "upstream_connection_released": True}}}
    node = {"transport": "sse" if stream else "json",
            "terminal": terminal(entry, stream, kind, thinking)}
    if kind == "text":
        node["text"] = {"equals": TEXT}
    if thinking:
        node["thinking"] = {"equals": REASONING, "before_text": True}
    if kind == "tool":
        node["tool_calls"] = [{"id": TOOL_ID, "name": TOOL_NAME, "arguments": TOOL_ARGS}]
    node["usage"] = usage(entry, thinking, stream)
    if stream:
        node["sse"] = sse_block(entry, thinking)
    if image:
        node["image"] = image
    return node


def fixture(entry, scenario, steps, markers, notes=None, continuation=None):
    node = {"schema": FIXTURE_SCHEMA, "id": f"{entry}-s{scenario}-{SLUGS[scenario]}",
            "entry": entry, "scenario": scenario, "slug": SLUGS[scenario], "title": TITLES[scenario],
            "mock": {"chunks": CHUNKS}, "markers": markers, "steps": steps}
    if notes:
        node["notes"] = notes
    if continuation:
        node["continuation"] = continuation
    return node


def step(name, request, exp, headers=None, degradation=None):
    node = {"name": name, "request": request, "expected": exp}
    if headers:
        node["headers"] = headers
    if degradation:
        node["degradation"] = degradation
    return node


def error_expected(code, param, reason, error_type=None):
    node = {"status": 400, "code": code, "param": param, "reason": reason}
    if error_type:
        node["error_type"] = error_type
    # finished=[false]: both controllers call GatewayPipeline.finish(chain, context, false) BEFORE
    # writeConversionError, so the ledger records exactly one FAILED finish and releases the permit.
    node["finished"] = [False]
    node["active_permits"] = 0
    node["upstream_requests"] = 0
    return {"error": node}


def negative(name, request, code, param, reason, error_type=None):
    return step(name, request, error_expected(code, param, reason, error_type))


def resource_denied(name, request, code, param, reason, error_type=None):
    """A file reference is denied by the ENGINE ownership guard BEFORE conversion runs.

    The drive therefore answers 404 with the gateway-generic body (no capability code, no field path,
    not entry-shaped); the entry codec's own 400 capability rejection stays pinned as `error` and is
    asserted at codec level. Both layers are real contract facts, so both are versioned here.
    """
    exp = error_expected(code, param, reason, error_type)
    exp["resource_denial"] = {"status": 404, "message": "referenced file not found on this route",
                              "error_type": "gateway_error", "finished": [False],
                              "active_permits": 0, "upstream_requests": 0}
    return step(name, request, exp)


def authorized(name, request, exp, degradation_id, field_path, outbound_field, outbound_equals):
    return step(name, request, exp, headers={GRANT_HEADER: degradation_id},
                degradation={"id": degradation_id, "field_path": field_path, "header": GRANT_HEADER,
                             "outbound_field": outbound_field, "outbound_equals": outbound_equals})


def chat_s7():
    base = {"model": MODEL,
            "messages": [{"role": "user", "content": "mock-deepseek: What is the capital of France?"}],
            "max_tokens": 1024, "stream": False}
    schema_format = {"type": "json_schema", "json_schema": {
        "name": "answer", "strict": True,
        "schema": {"type": "object", "properties": {"answer": {"type": "string"}},
                   "required": ["answer"], "additionalProperties": False}}}
    degrade_request = {**base, "response_format": schema_format}
    return fixture("chat", 7, [
        negative("unknown-field", {**base, "top_k": 5},
                 UNKNOWN_FIELD, "top_k", "未知请求字段: top_k", INVALID_REQUEST_ERROR),
        negative("not-supported-store", {**base, "store": True},
                 NOT_SUPPORTED, "store", "网关不引入对话存储", INVALID_REQUEST_ERROR),
        negative("not-supported-builtin-tool", {**base, "tools": [{"type": "web_search"}]},
                 NOT_SUPPORTED, "tools[0]", "仅支持 function 工具", INVALID_REQUEST_ERROR),
        negative("expected-unsupported-external-image", {**base, "messages": [{"role": "user", "content": [
            {"type": "text", "text": "mock-deepseek: Describe the probe image."},
            {"type": "image_url", "image_url": {"url": EXTERNAL_IMAGE_URL}}]}]},
                 NOT_SUPPORTED, "messages[0].content[1].image_url.url",
                 "第一阶段仅支持内联 base64 data URL，不支持外部图片 URL", INVALID_REQUEST_ERROR),
        negative("invalid-image-media-type", {**base, "messages": [{"role": "user", "content": [
            {"type": "text", "text": "mock-deepseek: Describe the probe image."},
            {"type": "image_url", "image_url": {"url": BMP_DATA_URL}}]}]},
                 INVALID_VALUE, "messages[0].content[1].image_url.url", BMP_REASON, INVALID_REQUEST_ERROR),
        negative("degradation-unauthorized", degrade_request,
                 NOT_AUTHORIZED, "response_format", "需授权降级 response_format.schema_to_object",
                 INVALID_REQUEST_ERROR),
        authorized("degradation-authorized", degrade_request, expected("chat", False),
                   "response_format.schema_to_object", "response_format", "response_format.type", "json_object"),
    ], ["mock-deepseek"], notes=S7_NOTES)


def responses_s7():
    base = {"model": MODEL,
            "input": [{"type": "message", "role": "user",
                       "content": "mock-deepseek: What is the capital of France?"}],
            "max_output_tokens": 1024, "stream": False}
    weather = tool_definitions()["tools/get-weather.json"]["wire"]["responses"]
    cross = mint_with(DRIVE_KEY_UTF8, CROSS_PROJECT, CROSS_ITEM_IDS["responses"])
    degrade_request = {**base, "tools": [weather], "tool_choice": "required",
                       "reasoning": {"effort": "medium"}}
    return fixture("responses", 7, [
        negative("unknown-field", {**base, "top_k": 5},
                 UNKNOWN_FIELD, "top_k", "未知请求字段: top_k", INVALID_REQUEST_ERROR),
        negative("previous-response-id", {**base, "previous_response_id": "resp_fixture_1"},
                 NOT_SUPPORTED, "previous_response_id", "网关不引入对话存储，无法按响应 id 续轮",
                 INVALID_REQUEST_ERROR),
        negative("store", {**base, "store": True},
                 NOT_SUPPORTED, "store", "网关不引入对话存储", INVALID_REQUEST_ERROR),
        negative("background", {**base, "background": True},
                 NOT_SUPPORTED, "background", "不支持后台执行", INVALID_REQUEST_ERROR),
        negative("builtin-tool", {**base, "tools": [{"type": "web_search"}]},
                 NOT_SUPPORTED, "tools[0]", "服务端内建工具一律拒绝，仅支持 function 工具", INVALID_REQUEST_ERROR),
        negative("expected-unsupported-external-image", {**base, "input": [
            {"type": "message", "role": "user", "content": [
                {"type": "input_text", "text": "mock-deepseek: Describe the probe image."},
                {"type": "input_image", "image_url": EXTERNAL_IMAGE_URL}]}]},
                 NOT_SUPPORTED, "input[0].content[1].image_url",
                 "第一阶段仅支持内联 base64 data URL，不支持外部图片 URL", INVALID_REQUEST_ERROR),
        negative("invalid-image-media-type", {**base, "input": [
            {"type": "message", "role": "user", "content": [
                {"type": "input_text", "text": "mock-deepseek: Describe the probe image."},
                {"type": "input_image", "image_url": BMP_DATA_URL}]}]},
                 INVALID_VALUE, "input[0].content[1].image_url", BMP_REASON, INVALID_REQUEST_ERROR),
        resource_denied("input-file", {**base, "input": [{"type": "message", "role": "user", "content": [
            {"type": "input_text", "text": "mock-deepseek: Summarize the file."},
            {"type": "input_file", "file_id": FILE_ID}]}]},
                        NOT_SUPPORTED, "input[0].content[1]", "文件输入属阶段 2/3，第一阶段仅内联图片",
                        INVALID_REQUEST_ERROR),
        resource_denied("file-id", {**base, "input": [{"type": "message", "role": "user", "content": [
            {"type": "input_text", "text": "mock-deepseek: Describe the file image."},
            {"type": "input_image", "file_id": FILE_ID}]}]},
                        NOT_SUPPORTED, "input[0].content[1].file_id",
                        "第一阶段仅支持内联图片，file 引用属阶段 2/3", INVALID_REQUEST_ERROR),
        negative("cross-project-continuation", {**base, "input": [
            {"type": "message", "role": "user", "content": "mock-deepseek: Explain the answer."},
            {"type": "reasoning", "id": CROSS_ITEM_IDS["responses"], "summary": [],
             "content": [{"type": "reasoning_text", "text": REASONING}],
             "encrypted_content": cross, "status": "completed"}]},
                 CONTINUATION_INVALID, "continuation", "续轮信封不属于当前项目", INVALID_REQUEST_ERROR),
        negative("degradation-unauthorized", degrade_request,
                 NOT_AUTHORIZED, "tool_choice", "需授权降级 tool_choice.force_to_auto", INVALID_REQUEST_ERROR),
        authorized("degradation-authorized", degrade_request, expected("responses", False),
                   "tool_choice.force_to_auto", "tool_choice", "tool_choice", "auto"),
    ], ["mock-deepseek"], notes=S7_NOTES,
        continuation={"token_source": "preminted_drive_key_cross_project",
                      "envelope_ref": "manifest.continuation_envelope",
                      "item_id": CROSS_ITEM_IDS["responses"]})


def messages_s7():
    base = {"model": MODEL,
            "messages": [{"role": "user", "content": "mock-deepseek: What is the capital of France?"}],
            "max_tokens": 1024, "stream": False}
    cross = mint_with(DRIVE_KEY_UTF8, CROSS_PROJECT, CROSS_ITEM_IDS["messages"])
    degrade_request = {**base, "thinking": {"type": "enabled", "budget_tokens": 1024}}
    return fixture("messages", 7, [
        negative("unknown-field", {**base, "temperature": 0.7},
                 UNKNOWN_FIELD, "temperature", "未知请求字段: temperature"),
        negative("redacted-thinking", {**base, "messages": [
            base["messages"][0],
            {"role": "assistant", "content": [{"type": "redacted_thinking", "data": "opaque"}]}]},
                 NOT_SUPPORTED, "messages[1].content[0]", "私有加密思考块不可表达"),
        negative("server-tool", {**base, "tools": [{"type": "web_search_20250305", "name": "web_search"}]},
                 NOT_SUPPORTED, "tools[0]", "服务端工具不可表达: web_search_20250305"),
        negative("private-block", {**base, "messages": [
            base["messages"][0],
            {"role": "assistant", "content": [
                {"type": "server_tool_use", "id": "srvtoolu_fixture_1", "name": "web_search", "input": {}}]}]},
                 NOT_SUPPORTED, "messages[1].content[0]", "私有/服务端内容块不可表达: server_tool_use"),
        negative("expected-unsupported-external-image", {**base, "messages": [{"role": "user", "content": [
            {"type": "text", "text": "mock-deepseek: Describe the probe image."},
            {"type": "image", "source": {"type": "url", "url": EXTERNAL_IMAGE_URL}}]}]},
                 NOT_SUPPORTED, "messages[0].content[1].source.url",
                 "第一阶段仅支持内联图片，外部 URL 属阶段 2/3"),
        negative("invalid-image-media-type", {**base, "messages": [{"role": "user", "content": [
            {"type": "text", "text": "mock-deepseek: Describe the probe image."},
            {"type": "image", "source": {"type": "base64", "media_type": "image/bmp", "data": "QUJD"}}]}]},
                 INVALID_VALUE, "messages[0].content[1].source.media_type", BMP_REASON),
        resource_denied("file-id", {**base, "messages": [{"role": "user", "content": [
            {"type": "text", "text": "mock-deepseek: Describe the file image."},
            {"type": "image", "source": {"type": "file", "file_id": FILE_ID}}]}]},
                        NOT_SUPPORTED, "messages[0].content[1].source.file_id",
                        "第一阶段仅支持内联图片，file 引用属阶段 2/3"),
        negative("cross-project-signature", {**base, "messages": [
            {"role": "user", "content": "mock-deepseek: Explain the answer."},
            {"role": "assistant", "content": [
                {"type": "thinking", "thinking": REASONING, "signature": cross}]}]},
                 CONTINUATION_INVALID, "continuation", "续轮信封不属于当前项目"),
        negative("degradation-unauthorized", degrade_request,
                 NOT_AUTHORIZED, "thinking.budget_tokens", "需授权降级 thinking.budget_to_effort"),
        authorized("degradation-authorized", degrade_request, expected("messages", False),
                   "thinking.budget_to_effort", "thinking.budget_tokens", "reasoning_effort", "low"),
    ], ["mock-deepseek"], notes=S7_NOTES,
        continuation={"token_source": "preminted_drive_key_cross_project",
                      "envelope_ref": "manifest.continuation_envelope",
                      "item_id": CROSS_ITEM_IDS["messages"]})


def chat_scenarios(b64, sha):
    data_url = "data:image/png;base64," + b64
    weather = tool_definitions()["tools/get-weather.json"]["wire"]["chat"]
    add = tool_definitions()["tools/add-numbers.json"]["wire"]["chat"]
    tools = [weather, add]
    image = {"accepted": True, "media_type": "image/png", "sha256": sha}
    return {
        1: fixture("chat", 1, [step("initial", {
            "model": MODEL,
            "messages": [{"role": "system", "content": "You are a concise assistant."},
                         {"role": "user", "content": "What is the capital of France?"},
                         {"role": "assistant", "content": "The capital of France is Paris."},
                         {"role": "user", "content": "mock-deepseek: And what is the population?"}],
            "max_tokens": 1024, "stream": False}, expected("chat", False))], ["mock-deepseek"]),
        2: fixture("chat", 2, [step("initial", {
            "model": MODEL,
            "messages": [{"role": "user", "content": "mock-deepseek: Tell me about the probe."}],
            "max_tokens": 1024, "stream": True, "stream_options": {"include_usage": True}},
            expected("chat", True))], ["mock-deepseek"]),
        3: fixture("chat", 3, [
            step("initial", {
                "model": MODEL,
                "messages": [{"role": "user", "content": "mock-deepseek-tools: What is the weather in SF?"}],
                "tools": tools, "tool_choice": "auto", "reasoning_effort": "none",
                "max_tokens": 1024, "stream": False}, expected("chat", False, kind="tool")),
            step("continuation", {
                "model": MODEL,
                "messages": [{"role": "user", "content": "What is the weather in SF?"},
                             {"role": "assistant", "content": None,
                              "tool_calls": [{"id": TOOL_ID, "type": "function",
                                              "function": {"name": TOOL_NAME, "arguments": TOOL_ARGS_JSON}}]},
                             {"role": "tool", "tool_call_id": TOOL_ID, "content": TOOL_RESULT_TEXT},
                             {"role": "user", "content": "mock-deepseek: Answer using the tool result."}],
                "tools": tools, "tool_choice": "auto", "reasoning_effort": "none",
                "max_tokens": 1024, "stream": False}, expected("chat", False))],
            ["mock-deepseek-tools", "mock-deepseek"], notes=S3_NOTES),
        4: fixture("chat", 4, [
            step("initial", {
                "model": MODEL,
                "messages": [{"role": "user", "content": "mock-deepseek-thinking: Explain the answer."}],
                "reasoning_effort": "medium", "max_tokens": 1024, "stream": True,
                "stream_options": {"include_usage": True}}, expected("chat", True, thinking=True)),
            step("continuation", {
                "model": MODEL,
                "messages": [{"role": "user", "content": "mock-deepseek-thinking: Explain the answer."},
                             {"role": "assistant", "reasoning_content": REASONING, "content": TEXT},
                             {"role": "user", "content": "mock-deepseek-thinking: Continue."}],
                "reasoning_effort": "medium", "max_tokens": 1024, "stream": False},
                expected("chat", False, thinking=True))],
            ["mock-deepseek-thinking"], notes=S4_NOTES,
            continuation={"token_source": "native_plaintext", "envelope_ref": None}),
        5: fixture("chat", 5, [step("initial", {
            "model": MODEL,
            "messages": [{"role": "user", "content": [
                {"type": "text", "text": "mock-deepseek: Describe the probe image."},
                {"type": "image_url", "image_url": {"url": data_url, "detail": "auto"}}]}],
            "max_tokens": 1024, "stream": False}, expected("chat", False, image=image))], ["mock-deepseek"]),
        6: fixture("chat", 6, [
            step("cancel", {
                "model": MODEL,
                "messages": [{"role": "user", "content": "mock-deepseek mock-stall-mid-30000: cancel probe."}],
                "max_tokens": 1024, "stream": True}, expected("chat", True, cancel=True)),
            step("recovery", {
                "model": MODEL,
                "messages": [{"role": "user", "content": "mock-deepseek: recovery probe."}],
                "max_tokens": 1024, "stream": True, "stream_options": {"include_usage": True}},
                expected("chat", True))],
            ["mock-deepseek", "mock-stall-mid-30000"], notes=S6_NOTES),
        7: chat_s7(),
    }


def responses_scenarios(b64, sha):
    data_url = "data:image/png;base64," + b64
    weather = tool_definitions()["tools/get-weather.json"]["wire"]["responses"]
    add = tool_definitions()["tools/add-numbers.json"]["wire"]["responses"]
    tools = [weather, add]
    token = mint(ITEM_IDS["responses"])
    image = {"accepted": True, "media_type": "image/png", "sha256": sha}
    return {
        1: fixture("responses", 1, [step("initial", {
            "model": MODEL, "instructions": "You are a concise assistant.",
            "input": [{"type": "message", "role": "user", "content": "What is the capital of France?"},
                      {"type": "message", "role": "assistant",
                       "content": [{"type": "output_text", "text": "The capital of France is Paris."}]},
                      {"type": "message", "role": "user", "content": "mock-deepseek: And what is the population?"}],
            "max_output_tokens": 1024, "stream": False}, expected("responses", False))], ["mock-deepseek"]),
        2: fixture("responses", 2, [step("initial", {
            "model": MODEL,
            "input": [{"type": "message", "role": "user", "content": "mock-deepseek: Tell me about the probe."}],
            "max_output_tokens": 1024, "stream": True}, expected("responses", True))], ["mock-deepseek"]),
        3: fixture("responses", 3, [
            step("initial", {
                "model": MODEL,
                "input": [{"type": "message", "role": "user",
                           "content": "mock-deepseek-tools: What is the weather in SF?"}],
                "tools": tools, "tool_choice": "auto", "reasoning": {"effort": "none"},
                "max_output_tokens": 1024, "stream": False}, expected("responses", False, kind="tool")),
            step("continuation", {
                "model": MODEL,
                "input": [{"type": "message", "role": "user", "content": "What is the weather in SF?"},
                          {"type": "function_call", "id": "fc_fixture_1", "call_id": TOOL_ID,
                           "name": TOOL_NAME, "arguments": TOOL_ARGS_JSON, "status": "completed"},
                          {"type": "function_call_output", "call_id": TOOL_ID, "output": TOOL_RESULT_TEXT},
                          {"type": "message", "role": "user",
                           "content": "mock-deepseek: Answer using the tool result."}],
                "tools": tools, "tool_choice": "auto", "reasoning": {"effort": "none"},
                "max_output_tokens": 1024, "stream": False}, expected("responses", False))],
            ["mock-deepseek-tools", "mock-deepseek"], notes=S3_NOTES),
        4: fixture("responses", 4, [
            step("initial", {
                "model": MODEL,
                "input": [{"type": "message", "role": "user",
                           "content": "mock-deepseek-thinking: Explain the answer."}],
                "reasoning": {"effort": "medium"}, "max_output_tokens": 1024, "stream": True},
                expected("responses", True, thinking=True)),
            step("continuation", {
                "model": MODEL,
                "input": [{"type": "message", "role": "user",
                           "content": "mock-deepseek-thinking: Explain the answer."},
                          {"type": "reasoning", "id": ITEM_IDS["responses"], "summary": [],
                           "content": [{"type": "reasoning_text", "text": REASONING}],
                           "encrypted_content": token, "status": "completed"},
                          {"type": "message", "role": "assistant",
                           "content": [{"type": "output_text", "text": TEXT}], "status": "completed"},
                          {"type": "message", "role": "user", "content": "mock-deepseek-thinking: Continue."}],
                "reasoning": {"effort": "medium"}, "max_output_tokens": 1024, "stream": False},
                expected("responses", False, thinking=True))],
            ["mock-deepseek-thinking"], notes=S4_NOTES,
            continuation={"token_source": "preminted_fixture_key", "envelope_ref": "manifest.continuation_envelope",
                          "item_id": ITEM_IDS["responses"]}),
        5: fixture("responses", 5, [step("initial", {
            "model": MODEL,
            "input": [{"type": "message", "role": "user", "content": [
                {"type": "input_text", "text": "mock-deepseek: Describe the probe image."},
                {"type": "input_image", "image_url": data_url, "detail": "auto"}]}],
            "max_output_tokens": 1024, "stream": False}, expected("responses", False, image=image))],
            ["mock-deepseek"]),
        6: fixture("responses", 6, [
            step("cancel", {
                "model": MODEL,
                "input": [{"type": "message", "role": "user",
                           "content": "mock-deepseek mock-stall-mid-30000: cancel probe."}],
                "max_output_tokens": 1024, "stream": True}, expected("responses", True, cancel=True)),
            step("recovery", {
                "model": MODEL,
                "input": [{"type": "message", "role": "user", "content": "mock-deepseek: recovery probe."}],
                "max_output_tokens": 1024, "stream": True}, expected("responses", True))],
            ["mock-deepseek", "mock-stall-mid-30000"], notes=S6_NOTES),
        7: responses_s7(),
    }


def messages_scenarios(b64, sha):
    weather = tool_definitions()["tools/get-weather.json"]["wire"]["messages"]
    add = tool_definitions()["tools/add-numbers.json"]["wire"]["messages"]
    tools = [weather, add]
    token = mint(ITEM_IDS["messages"])
    image = {"accepted": True, "media_type": "image/png", "sha256": sha}
    return {
        1: fixture("messages", 1, [step("initial", {
            "model": MODEL, "system": "You are a concise assistant.",
            "messages": [{"role": "user", "content": "What is the capital of France?"},
                         {"role": "assistant", "content": "The capital of France is Paris."},
                         {"role": "user", "content": "mock-deepseek: And what is the population?"}],
            "max_tokens": 1024, "stream": False}, expected("messages", False))], ["mock-deepseek"]),
        2: fixture("messages", 2, [step("initial", {
            "model": MODEL,
            "messages": [{"role": "user", "content": "mock-deepseek: Tell me about the probe."}],
            "max_tokens": 1024, "stream": True}, expected("messages", True))], ["mock-deepseek"]),
        3: fixture("messages", 3, [
            step("initial", {
                "model": MODEL,
                "messages": [{"role": "user", "content": "mock-deepseek-tools: What is the weather in SF?"}],
                "tools": tools, "tool_choice": {"type": "auto"}, "thinking": {"type": "disabled"},
                "max_tokens": 1024, "stream": False}, expected("messages", False, kind="tool")),
            step("continuation", {
                "model": MODEL,
                "messages": [{"role": "user", "content": "What is the weather in SF?"},
                             {"role": "assistant", "content": [
                                 {"type": "tool_use", "id": TOOL_ID, "name": TOOL_NAME, "input": TOOL_ARGS}]},
                             {"role": "user", "content": [
                                 {"type": "tool_result", "tool_use_id": TOOL_ID, "content": TOOL_RESULT_TEXT},
                                 {"type": "text", "text": "mock-deepseek: Answer using the tool result."}]}],
                "tools": tools, "tool_choice": {"type": "auto"}, "thinking": {"type": "disabled"},
                "max_tokens": 1024, "stream": False}, expected("messages", False))],
            ["mock-deepseek-tools", "mock-deepseek"], notes=S3_NOTES),
        4: fixture("messages", 4, [
            step("initial", {
                "model": MODEL,
                "messages": [{"role": "user", "content": "mock-deepseek-thinking: Explain the answer."}],
                "thinking": {"type": "adaptive"}, "max_tokens": 1024, "stream": True},
                expected("messages", True, thinking=True)),
            step("continuation", {
                "model": MODEL,
                "messages": [{"role": "user", "content": "mock-deepseek-thinking: Explain the answer."},
                             {"role": "assistant", "content": [
                                 {"type": "thinking", "thinking": REASONING, "signature": token},
                                 {"type": "text", "text": TEXT}]},
                             {"role": "user", "content": "mock-deepseek-thinking: Continue."}],
                "thinking": {"type": "adaptive"}, "max_tokens": 1024, "stream": False},
                expected("messages", False, thinking=True))],
            ["mock-deepseek-thinking"], notes=S4_NOTES,
            continuation={"token_source": "preminted_fixture_key", "envelope_ref": "manifest.continuation_envelope",
                          "item_id": ITEM_IDS["messages"]}),
        5: fixture("messages", 5, [step("initial", {
            "model": MODEL,
            "messages": [{"role": "user", "content": [
                {"type": "text", "text": "mock-deepseek: Describe the probe image."},
                {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": b64}}]}],
            "max_tokens": 1024, "stream": False}, expected("messages", False, image=image))], ["mock-deepseek"]),
        6: fixture("messages", 6, [
            step("cancel", {
                "model": MODEL,
                "messages": [{"role": "user", "content": "mock-deepseek mock-stall-mid-30000: cancel probe."}],
                "max_tokens": 1024, "stream": True}, expected("messages", True, cancel=True)),
            step("recovery", {
                "model": MODEL,
                "messages": [{"role": "user", "content": "mock-deepseek: recovery probe."}],
                "max_tokens": 1024, "stream": True}, expected("messages", True))],
            ["mock-deepseek", "mock-stall-mid-30000"], notes=S6_NOTES),
        7: messages_s7(),
    }


def write_json(relative, node):
    path = HERE / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(node, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def build_manifest(entries, scenarios_by_entry):
    index = []
    for entry in entries:
        for scenario in sorted(scenarios_by_entry[entry]):
            index.append({"entry": entry, "scenario": scenario,
                          "file": f"{entry}/s{scenario}-{SLUGS[scenario]}.json",
                          "steps": [s["name"] for s in scenarios_by_entry[entry][scenario]["steps"]]})
    files = []
    for path in sorted(HERE.rglob("*")):
        if not path.is_file() or path == HERE / "manifest.json" or "__pycache__" in path.parts:
            continue
        data = path.read_bytes()
        files.append({"path": str(path.relative_to(HERE)), "bytes": len(data),
                      "sha256": hashlib.sha256(data).hexdigest()})
    return {
        "schema": MANIFEST_SCHEMA,
        "description": ("T1.13b/T1.14e versioned acceptance fixtures: 3 entries x 7 scenarios, deterministic, "
                        "no personal information. Controlled upstream only; not real acceptance."),
        "fixture_schema": FIXTURE_SCHEMA,
        "entries": list(entries),
        "scenarios": [{"index": i, "slug": SLUGS[i], "title": TITLES[i]} for i in sorted(SLUGS)],
        "index": index,
        "tools": ["tools/add-numbers.json", "tools/get-weather.json"],
        "images_manifest": "images/manifest.json",
        "continuation_envelope": {"key_utf8": KEY_UTF8, "tenant_id": TENANT, "project_id": PROJECT,
                                  "reasoning_text": REASONING, "item_ids": ITEM_IDS,
                                  "drive_key_utf8": DRIVE_KEY_UTF8, "drive_project_id": DRIVE_PROJECT,
                                  "cross_project_id": CROSS_PROJECT, "cross_item_ids": CROSS_ITEM_IDS},
        "mock_constants": {"text": TEXT, "reasoning": REASONING,
                           "tool": {"id": TOOL_ID, "name": TOOL_NAME, "arguments": TOOL_ARGS},
                           "usage": {"prompt_tokens": PROMPT_TOKENS, "cached_tokens": CACHED_TOKENS,
                                     "reasoning_tokens": REASONING_TOKENS},
                           "tool_result": TOOL_RESULT},
        "assertion_vocabulary": {
            "transport": "json = non-streaming body; sse = streaming frames",
            "terminal": "entry-specific terminal shape pins (object/status/finish_reason/stop_reason/event counts)",
            "text.equals / thinking.equals": "fixed controlled-upstream text, never random wording",
            "tool_calls": "expected tool call id/name/arguments (arguments is a JSON object)",
            "usage": "exact token values from the controlled upstream; absent_fields lists paths that MUST be absent (unknown != 0)",
            "sse": "framing and event-lifecycle pins; done_marker false means no data:[DONE] terminator",
            "cancel": "cancel trigger and post-cancel release/recovery assertions",
            "image": "inline image acceptance pin with the probe sha256",
            "mock.chunks": "required controlled-upstream chunk count for streaming usage values",
            "error": "s7 pre-header rejection pins: status + stable capability code + param (field path) + "
                     "reason; error_type is the OpenAI-shaped wire type (chat/responses only); finished is the "
                     "governance ledger ([false] = one FAILED finish), active_permits must return to 0 and "
                     "upstream_requests must be 0 (the transport is never touched). No success terminal may appear.",
            "headers": "optional per-step inbound headers; s7 uses the degradation grant header. Absent means "
                       "the production default: an EMPTY grant.",
            "degradation": "s7 authorized-positive pins: the degradation id, its audited field path, the grant "
                           "header name and the outbound field/value that proves the degradation really happened",
        },
        "files": files,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--entries", default="chat,responses,messages",
                        help="comma-separated entries to generate (default: all three)")
    args = parser.parse_args()
    entries = [e.strip() for e in args.entries.split(",") if e.strip()]
    for entry in entries:
        if entry not in ("chat", "responses", "messages"):
            raise SystemExit(f"unknown entry: {entry}")
    b64, sha = probe_image()
    for relative, node in tool_definitions().items():
        write_json(relative, node)
    builders = {"chat": chat_scenarios, "responses": responses_scenarios, "messages": messages_scenarios}
    scenarios_by_entry = {}
    for entry in entries:
        scenarios = builders[entry](b64, sha)
        scenarios_by_entry[entry] = scenarios
        for scenario, node in scenarios.items():
            write_json(f"{entry}/s{scenario}-{SLUGS[scenario]}.json", node)
    write_json("manifest.json", build_manifest(entries, scenarios_by_entry))
    print(f"generated {len(entries)} entries x {len(SLUGS)} scenarios; manifest lists "
          f"{len(build_manifest(entries, scenarios_by_entry)['files'])} files")


if __name__ == "__main__":
    main()