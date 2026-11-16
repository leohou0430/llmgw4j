package io.github.leohou0430.llmgw.conversion.responses;

import io.github.leohou0430.llmgw.conversion.AppliedDegradation;
import io.github.leohou0430.llmgw.conversion.CapabilityContext;
import io.github.leohou0430.llmgw.conversion.CapabilityException;
import io.github.leohou0430.llmgw.conversion.ContentPart;
import io.github.leohou0430.llmgw.conversion.ContinuationEnvelope;
import io.github.leohou0430.llmgw.conversion.DegradationAudit;
import io.github.leohou0430.llmgw.conversion.DegradationGrant;
import io.github.leohou0430.llmgw.conversion.ImagePart;
import io.github.leohou0430.llmgw.conversion.Role;
import io.github.leohou0430.llmgw.conversion.SemanticMessage;
import io.github.leohou0430.llmgw.conversion.SemanticRequest;
import io.github.leohou0430.llmgw.conversion.SemanticResponse;
import io.github.leohou0430.llmgw.conversion.SemanticEvent;
import io.github.leohou0430.llmgw.conversion.StopReason;
import io.github.leohou0430.llmgw.conversion.StreamEncoder;
import io.github.leohou0430.llmgw.conversion.TerminalEvent;
import io.github.leohou0430.llmgw.conversion.TextDelta;
import io.github.leohou0430.llmgw.conversion.TextPart;
import io.github.leohou0430.llmgw.conversion.ThinkingDelta;
import io.github.leohou0430.llmgw.conversion.ThinkingPart;
import io.github.leohou0430.llmgw.conversion.ToolCallDelta;
import io.github.leohou0430.llmgw.conversion.ToolCallPart;
import io.github.leohou0430.llmgw.conversion.ToolResultPart;
import io.github.leohou0430.llmgw.conversion.ToolSpec;
import io.github.leohou0430.llmgw.conversion.UpstreamException;
import io.github.leohou0430.llmgw.conversion.Usage;
import io.github.leohou0430.llmgw.conversion.UsageEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 映射矩阵 §5.2/§6.3/§9/§10.2/§11 驱动的 Responses 入口合同测试。 */
class ResponsesEntryCodecTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String UNKNOWN = CapabilityException.UNKNOWN_FIELD;
    private static final String NOT_SUPPORTED = CapabilityException.NOT_SUPPORTED;
    private static final String NOT_AUTHORIZED = CapabilityException.DEGRADATION_NOT_AUTHORIZED;
    private static final String INVALID = CapabilityException.INVALID_VALUE;
    private static final String DISPLAY_RAW = "thinking.display_raw";
    private static final String FORCE_TO_AUTO = "tool_choice.force_to_auto";
    private static final String SCHEMA_TO_OBJECT = "response_format.schema_to_object";
    /** 与 ChatEntryCodec 的图片容量守卫同值（矩阵 §4.4/§8）：单图 32 MiB、总量 64 MiB、≤600 张。 */
    private static final long MAX_IMAGE_BYTES = 32L * 1024 * 1024;
    private static final long MAX_TOTAL_IMAGE_BYTES = 64L * 1024 * 1024;
    /**
     * T1.9 独立真值：自有小图 llmgw-probe.png 的 sha256，取自
     * tools/acceptance/stage1/fixtures/images/manifest.json（固化字面量，不在测试内重算期望值）。
     */
    private static final String PROBE_SHA256 =
            "f24b0899ce1bb9147836beb6812c9ab7778a864f39c44b14d32548e4d91228d9";

    private final ResponsesEntryCodec codec = new ResponsesEntryCodec();

    // ========== 测试脚手架 ==========

    private static String fixture(String name) {
        try (InputStream in = ResponsesEntryCodecTest.class.getResourceAsStream("/responses/" + name)) {
            assertNotNull(in, "missing fixture: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read fixture " + name, e);
        }
    }

    private static Set<String> names(JsonNode node) {
        Set<String> fields = new java.util.HashSet<>();
        node.properties().forEach(entry -> fields.add(entry.getKey()));
        return fields;
    }

    private static JsonNode fixtureJson(String name) {
        return MAPPER.readTree(fixture(name));
    }

    /** T1.9：字节保真校验用 sha256（小写 hex）。 */
    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final ContinuationEnvelope ENVELOPE =
            new ContinuationEnvelope("responses-entry-test-key".getBytes(StandardCharsets.UTF_8));

    private static CapabilityContext ctx(String... allowed) {
        return new CapabilityContext("tenant-1", "project-1",
                new DegradationAudit(new DegradationGrant(Set.of(allowed))), ENVELOPE);
    }

    /** 用测试密钥铸造续轮信封（projectId 固定 project-1，与 ctx() 一致）。 */
    private static String mint(String itemId, String reasoning) {
        return ENVELOPE.mint("project-1", itemId, reasoning);
    }

    /** 跨项目 ctx：同一密钥、不同 projectId，用于验证信封的项目绑定。 */
    private static CapabilityContext ctxForProject(String projectId) {
        return new CapabilityContext("tenant-1", projectId,
                new DegradationAudit(DegradationGrant.empty()), ENVELOPE);
    }

    /** 构造含单个 reasoning 条目的续轮请求；encryptedContent 为 null 时省略该键。 */
    private static String reasoningRequest(String reasoningText, String encryptedContent) {
        ObjectNode root = baseRequest();
        ArrayNode input = root.putArray("input");
        ObjectNode item = input.addObject().put("type", "reasoning").put("id", "rs_1");
        item.putArray("summary");
        item.putArray("content").addObject().put("type", "reasoning_text").put("text", reasoningText);
        if (encryptedContent != null) {
            item.put("encrypted_content", encryptedContent);
        }
        return MAPPER.writeValueAsString(root);
    }

    private SemanticRequest decode(String json) {
        return codec.decode(json, ctx());
    }

    private static ObjectNode baseRequest() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", "client-model");
        root.put("input", "hi");
        return root;
    }

    private static String withField(String name, JsonNode value) {
        ObjectNode root = baseRequest();
        root.set(name, value);
        return MAPPER.writeValueAsString(root);
    }

    private static String withField(String name, String rawJsonValue) {
        return withField(name, MAPPER.readTree(rawJsonValue));
    }

    private static void assertViolation(CapabilityException error, String code, String fieldPath) {
        assertEquals(400, error.status(), error.getMessage());
        assertEquals(1, error.violations().size(), error.getMessage());
        assertEquals(code, error.violations().get(0).code(), error.getMessage());
        assertEquals(fieldPath, error.violations().get(0).fieldPath(), error.getMessage());
        assertFalse(error.violations().get(0).reason().isBlank());
    }

    private CapabilityException failure(String json) {
        return assertThrows(CapabilityException.class, () -> codec.decode(json, ctx()));
    }

    private CapabilityException failure(String json, CapabilityContext context) {
        return assertThrows(CapabilityException.class, () -> codec.decode(json, context));
    }

    /** Responses 的 input_image.image_url 是字符串（不是 Chat 的 {url,detail} 对象），detail 平级。 */
    private static String imageRequest(String role, String url, String detail) {
        ObjectNode root = baseRequest();
        ArrayNode input = root.putArray("input");
        ObjectNode message = input.addObject().put("type", "message").put("role", role);
        ObjectNode image = message.putArray("content").addObject().put("type", "input_image");
        if (url != null) {
            image.put("image_url", url);
        }
        if (detail != null) {
            image.put("detail", detail);
        }
        return MAPPER.writeValueAsString(root);
    }

    /** 多图请求：按顺序内联 data URL，用于容量守卫（张数/单图/总量）测试。 */
    private static String imagesRequest(String... urls) {
        ObjectNode root = baseRequest();
        ArrayNode content = root.putArray("input").addObject()
                .put("type", "message").put("role", "user").putArray("content");
        for (String url : urls) {
            content.addObject().put("type", "input_image").put("image_url", url);
        }
        return MAPPER.writeValueAsString(root);
    }

    private static String base64OfBytes(long bytes) {
        return Base64.getEncoder().encodeToString(new byte[(int) bytes]);
    }

    private static String inlineImage(long bytes) {
        return "data:image/png;base64," + base64OfBytes(bytes);
    }

    /** baseRequest 的语义基线：忽略类字段不得改变其中任何一项。 */
    private static SemanticRequest expectedBase() {
        return new SemanticRequest("client-model",
                List.of(new SemanticMessage(Role.USER, List.of(new TextPart("hi")))),
                null, null, null, null, false, null, List.of(), null, null, null, null, null);
    }

    /** 流式请求：includeUsage=null 表示不带 stream_options。 */
    private static String streamRequest(Boolean includeUsage) {
        ObjectNode root = baseRequest();
        root.put("stream", true);
        if (includeUsage != null) {
            root.putObject("stream_options").put("include_usage", includeUsage);
        }
        return MAPPER.writeValueAsString(root);
    }

    // ========== §5.2 E：instructions / input(str) ==========

    @Test
    void decodeStringInputPrependsInstructionsAsSystemMessage() {
        SemanticRequest req = decode(fixture("decode-string-input.json"));

        assertEquals("client-model", req.model());
        assertEquals(List.of(Role.SYSTEM, Role.USER),
                req.messages().stream().map(SemanticMessage::role).toList());
        assertEquals(List.of(new TextPart("be helpful")), req.messages().get(0).parts());
        assertEquals(List.of(new TextPart("hello")), req.messages().get(1).parts());
        assertEquals(256, req.maxOutputTokens().intValue());
        assertEquals("user-1", req.userId());
        assertFalse(req.stream());
        assertTrue(req.tools().isEmpty());
        assertNull(req.toolChoice());
        assertNull(req.thinkingEnabled());
        assertNull(req.thinkingEffort());
        assertNull(req.includeUsage());
        assertNull(req.responseFormat());
    }

    @Test
    void decodeWithoutInstructionsKeepsOnlyTheUserMessage() {
        SemanticRequest req = decode(MAPPER.writeValueAsString(baseRequest()));
        assertEquals(List.of(Role.USER), req.messages().stream().map(SemanticMessage::role).toList());
        assertEquals(List.of(new TextPart("hi")), req.messages().get(0).parts());
    }

    // ========== §5.2 E：input 条目（message 角色 / input_text） ==========

    @Test
    void decodeInputItemsMapsMessageRolesAndTextParts() {
        SemanticRequest req = decode(fixture("decode-input-items.json"));

        assertEquals(List.of(Role.SYSTEM, Role.SYSTEM, Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER),
                req.messages().stream().map(SemanticMessage::role).toList());
        assertEquals(List.of(new TextPart("be helpful")), req.messages().get(0).parts());
        assertEquals(List.of(new TextPart("prefer metric units")), req.messages().get(1).parts(),
                "developer→system，保留指令优先级");
        assertEquals(List.of(new TextPart("stay concise")), req.messages().get(2).parts());
        assertEquals(List.of(new TextPart("hello"), new TextPart("world")), req.messages().get(3).parts(),
                "分段顺序保持");
        assertEquals(List.of(new TextPart("hi there")), req.messages().get(4).parts(),
                "assistant 条目（SDK 续轮回传 output_text）必须可解码");
        assertEquals(List.of(new TextPart("next")), req.messages().get(5).parts());
    }

    @Test
    void decodeMessageItemWithoutTypeIsAnEasyInputMessage() {
        String json = """
                {"model":"m","input":[{"role":"user","content":"hi"},{"role":"system","content":"rule"}]}
                """;
        SemanticRequest req = decode(json);
        assertEquals(List.of(Role.USER, Role.SYSTEM), req.messages().stream().map(SemanticMessage::role).toList());
        assertEquals(List.of(new TextPart("hi")), req.messages().get(0).parts());
    }

    @Test
    void decodeInputItemUnknownNestedFieldFailsWithFullPath() {
        assertViolation(failure(withField("input",
                        "[{\"type\":\"message\",\"role\":\"user\",\"content\":\"hi\",\"name\":\"alice\"}]")),
                UNKNOWN, "input[0].name");
        assertViolation(failure(withField("input",
                        "[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"hi\",\"bogus\":1}]}]")),
                UNKNOWN, "input[0].content[0].bogus");
        assertViolation(failure(withField("input", "[{\"role\":\"bogus\",\"content\":\"hi\"}]")),
                INVALID, "input[0].role");
        assertViolation(failure(withField("input", "[{\"type\":\"message\",\"content\":\"hi\"}]")),
                INVALID, "input[0].role");
        assertViolation(failure(withField("input", "[]")), INVALID, "input");
        assertViolation(failure(withField("input", "7")), INVALID, "input");
    }

    // ========== §5.2 E + §7：续轮条目（reasoning / function_call / function_call_output） ==========

    @Test
    void decodeContinuationGroupsAssistantTurnWithReasoningAndToolCall() {
        ObjectNode root = (ObjectNode) MAPPER.readTree(fixture("decode-continuation.json"));
        String token = mint("rs_1", "need the weather tool");
        ((ObjectNode) root.path("input").get(1)).put("encrypted_content", token);
        SemanticRequest req = decode(MAPPER.writeValueAsString(root));

        assertEquals(List.of(Role.USER, Role.ASSISTANT, Role.TOOL, Role.USER),
                req.messages().stream().map(SemanticMessage::role).toList());
        assertEquals(List.of(new TextPart("weather in Paris?")), req.messages().get(0).parts());
        // 同一 assistant 轮的 reasoning + function_call 必须合并：上游 reasoning_content 与 tool_calls 同级（矩阵 §4.3）。
        assertEquals(List.of(
                        new ThinkingPart("need the weather tool", token),
                        new ToolCallPart("call_1", "get_weather", "{\"city\":\"Paris\"}")),
                req.messages().get(1).parts());
        assertEquals(List.of(new ToolResultPart("call_1", "{\"temp\":21}", false)), req.messages().get(2).parts());
        assertEquals(List.of(new TextPart("and in Tokyo?")), req.messages().get(3).parts());
    }

    @Test
    void decodeReasoningItemVerifiesEnvelopeAndRestoresContinuationToken() {
        String token = mint("rs_1", "first second");
        String json = """
                {"model":"m","input":[{"type":"message","role":"user","content":"hi"},
                  {"type":"reasoning","id":"rs_1","summary":[],
                   "content":[{"type":"reasoning_text","text":"first "},{"type":"reasoning_text","text":"second"}],
                   "encrypted_content":"%s"}]}
                """.formatted(token);
        SemanticRequest req = decode(json);
        ThinkingPart thinking = (ThinkingPart) req.messages().get(1).parts().get(0);
        assertEquals("first second", thinking.reasoning());
        assertEquals(token, thinking.continuationToken(), "校验通过的信封令牌须写入 continuationToken（矩阵 §7.3）");
    }

    @Test
    void decodeReasoningItemTamperedEnvelopeIsContinuationInvalid() {
        String token = mint("rs_1", "real reasoning");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        CapabilityException error = failure(reasoningRequest("real reasoning", tampered));
        assertEquals(400, error.status());
        assertEquals(CapabilityException.CONTINUATION_INVALID, error.violations().get(0).code());
    }

    @Test
    void decodeReasoningItemCrossProjectEnvelopeIsContinuationInvalid() {
        String token = mint("rs_1", "secret reasoning");
        String json = reasoningRequest("secret reasoning", token);
        CapabilityException error = assertThrows(CapabilityException.class,
                () -> codec.decode(json, ctxForProject("project-2")));
        assertEquals(400, error.status());
        assertEquals(CapabilityException.CONTINUATION_INVALID, error.violations().get(0).code());
    }

    @Test
    void decodeReasoningItemReasoningDigestMismatchIsContinuationInvalid() {
        String token = mint("rs_1", "original");
        CapabilityException error = failure(reasoningRequest("tampered plaintext", token));
        assertEquals(400, error.status());
        assertEquals(CapabilityException.CONTINUATION_INVALID, error.violations().get(0).code());
    }

    @Test
    void decodeReasoningItemMissingEnvelopeIsContinuationInvalid() {
        CapabilityException error = failure(reasoningRequest("plain reasoning", null));
        assertEquals(400, error.status());
        assertEquals(CapabilityException.CONTINUATION_INVALID, error.violations().get(0).code());
        assertEquals("input[0].encrypted_content", error.violations().get(0).fieldPath());
    }

    @Test
    void decodeReasoningItemNullEnvelopeIsContinuationInvalid() {
        ObjectNode root = baseRequest();
        ArrayNode input = root.putArray("input");
        ObjectNode item = input.addObject().put("type", "reasoning").put("id", "rs_1");
        item.putArray("summary");
        item.putArray("content").addObject().put("type", "reasoning_text").put("text", "plain");
        item.putNull("encrypted_content");
        CapabilityException error = failure(MAPPER.writeValueAsString(root));
        assertEquals(400, error.status());
        assertEquals(CapabilityException.CONTINUATION_INVALID, error.violations().get(0).code());
    }

    @Test
    void decodeReasoningItemWithoutPlaintextIsContinuationInvalid() {
        String noContent = """
                {"model":"m","input":[{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"llmgw1:p.s"}]}
                """;
        assertViolation(failure(noContent), CapabilityException.CONTINUATION_INVALID, "input[0].content");
        String emptyContent = """
                {"model":"m","input":[{"type":"reasoning","id":"rs_1","summary":[],"content":[]}]}
                """;
        assertViolation(failure(emptyContent), CapabilityException.CONTINUATION_INVALID, "input[0].content");
    }

    @Test
    void decodeReasoningItemRejectsSummaryTextAndUnknownKeys() {
        String summary = """
                {"model":"m","input":[{"type":"reasoning","id":"rs_1",
                  "summary":[{"type":"summary_text","text":"fake summary"}],
                  "content":[{"type":"reasoning_text","text":"real"}]}]}
                """;
        assertViolation(failure(summary), NOT_SUPPORTED, "input[0].summary");
        String bogus = """
                {"model":"m","input":[{"type":"reasoning","id":"rs_1","summary":[],"bogus":1,
                  "content":[{"type":"reasoning_text","text":"real"}]}]}
                """;
        assertViolation(failure(bogus), UNKNOWN, "input[0].bogus");
        String wrongPart = """
                {"model":"m","input":[{"type":"reasoning","id":"rs_1","summary":[],
                  "content":[{"type":"summary_text","text":"real"}]}]}
                """;
        assertViolation(failure(wrongPart), NOT_SUPPORTED, "input[0].content[0]");
    }

    @Test
    void decodeFunctionCallGuardsCallIdNameAndArguments() {
        String missingCallId = """
                {"model":"m","input":[{"type":"function_call","name":"ping","arguments":"{}"}]}
                """;
        assertViolation(failure(missingCallId), INVALID, "input[0].call_id");
        String badName = """
                {"model":"m","input":[{"type":"function_call","call_id":"c","name":"get weather","arguments":"{}"}]}
                """;
        assertViolation(failure(badName), INVALID, "input[0].name");
        String objectArguments = """
                {"model":"m","input":[{"type":"function_call","call_id":"c","name":"ping","arguments":{}}]}
                """;
        assertViolation(failure(objectArguments), INVALID, "input[0].arguments");
        String unknownKey = """
                {"model":"m","input":[{"type":"function_call","call_id":"c","name":"ping","arguments":"{}","bogus":1}]}
                """;
        assertViolation(failure(unknownKey), UNKNOWN, "input[0].bogus");
    }

    @Test
    void decodeFunctionCallOutputAcceptsStringAndTextPartArray() {
        String array = """
                {"model":"m","input":[{"type":"function_call_output","call_id":"call_1",
                  "output":[{"type":"input_text","text":"part one"},{"type":"input_text","text":"part two"}]}]}
                """;
        assertEquals(List.of(new ToolResultPart("call_1", "part onepart two", false)),
                decode(array).messages().get(0).parts());
        String missingCallId = """
                {"model":"m","input":[{"type":"function_call_output","output":"{}"}]}
                """;
        assertViolation(failure(missingCallId), INVALID, "input[0].call_id");
        String imageOutput = """
                {"model":"m","input":[{"type":"function_call_output","call_id":"c",
                  "output":[{"type":"input_image","image_url":"data:image/png;base64,QUJD"}]}]}
                """;
        assertViolation(failure(imageOutput), NOT_SUPPORTED, "input[0].output[0]");
    }

    @Test
    void decodeItemReferenceAndBuiltInToolItemsAreNotSupported() {
        List<String> rejected = List.of("item_reference", "file_search_call", "web_search_call",
                "code_interpreter_call", "computer_call", "computer_call_output", "mcp_call", "mcp_list_tools",
                "mcp_approval_request", "mcp_approval_response", "local_shell_call", "local_shell_call_output",
                "shell_call", "shell_call_output", "apply_patch_call", "apply_patch_call_output",
                "image_generation_call", "custom_tool_call", "custom_tool_call_output", "compaction",
                "compaction_trigger", "tool_search_call", "tool_search_output", "additional_tools",
                "configuration_update", "program", "program_output");
        for (String type : rejected) {
            String json = withField("input", "[{\"type\":\"" + type + "\",\"id\":\"x\"}]");
            assertViolation(failure(json), NOT_SUPPORTED, "input[0]");
        }
    }

    // ========== §5.2 E + §4.4：图片（内联 data URL / 容量守卫） ==========

    @Test
    void decodeUserImageParsesDataUrlMediaTypeBase64AndDetail() {
        List<ContentPart> parts = decode(fixture("decode-image.json")).messages().get(0).parts();
        assertEquals(3, parts.size());
        assertEquals(new TextPart("look"), parts.get(0));
        assertEquals(new ImagePart("image/png", "iVBORw0KGgo=", null, "high"), parts.get(1));
        assertEquals(new ImagePart("image/jpeg", "/9j/4AAQSkZJRg==", null, null), parts.get(2));
    }

    // T1.9 需求 a：真实自有小图内联解码字节保真（base64 逐字符 + 解码字节 sha256 == 固化真值）。
    @Test
    void decodeInlineImagePreservesRealPngBytes() {
        String json = fixture("decode-image-real.json");
        String inputUrl = MAPPER.readTree(json).path("input").get(0).path("content").get(0)
                .path("image_url").asString();
        String inputBase64 = inputUrl.substring("data:image/png;base64,".length());
        List<ContentPart> parts = decode(json).messages().get(0).parts();
        ImagePart image = assertInstanceOf(ImagePart.class, parts.get(0));
        assertEquals("image/png", image.mediaType());
        // 解码产物必须逐字符等于输入 base64（不得重编码/截断/损坏）。
        assertEquals(inputBase64, image.base64Data());
        // 独立真值：解码后的字节 sha256 必须等于 manifest 固化值（证明就是那张自有小图）。
        assertEquals(PROBE_SHA256, sha256Hex(Base64.getDecoder().decode(image.base64Data())));
    }

    @Test
    void decodeImageRejectsIllegalMediaTypeBase64AndDetail() {
        String path = "input[0].content[0].image_url";
        assertViolation(failure(imageRequest("user", "data:image/bmp;base64,QUJD", null)), INVALID, path);
        assertViolation(failure(imageRequest("user", "data:image/png;base64,@@@", null)), INVALID, path);
        assertViolation(failure(imageRequest("user", "data:image/png;base64,", null)), INVALID, path);
        assertViolation(failure(imageRequest("user", "data:image/png,QUJD", null)), INVALID, path);
        assertViolation(failure(imageRequest("user", "data:image/png;base64", null)), INVALID, path);
        assertViolation(failure(imageRequest("user", "QUJD", null)), INVALID, path);
        assertViolation(failure(imageRequest("user", null, null)), INVALID, path);
        assertViolation(failure(imageRequest("user", "data:image/png;base64,QUJD", "ultra")),
                INVALID, "input[0].content[0].detail");
        for (String detail : List.of("low", "high", "auto", "original")) {
            assertEquals(detail, ((ImagePart) decode(imageRequest("user", "data:image/png;base64,QUJD", detail))
                    .messages().get(0).parts().get(0)).detail());
        }
    }

    @Test
    void decodeImageRejectsExternalUrlBecauseStage1IsInlineOnly() {
        assertViolation(failure(imageRequest("user", "https://example.com/a.png", null)),
                NOT_SUPPORTED, "input[0].content[0].image_url");
    }

    @Test
    void decodeInputImageFileIdAndInputFileAreNotSupported() {
        String fileId = """
                {"model":"m","input":[{"type":"message","role":"user",
                  "content":[{"type":"input_image","file_id":"file-1","detail":"auto"}]}]}
                """;
        assertViolation(failure(fileId), NOT_SUPPORTED, "input[0].content[0].file_id");
        String file = """
                {"model":"m","input":[{"type":"message","role":"user",
                  "content":[{"type":"input_file","file_id":"file-1","filename":"a.pdf"}]}]}
                """;
        assertViolation(failure(file), NOT_SUPPORTED, "input[0].content[0]");
    }

    @Test
    void decodeImageOnlyAllowedInUserMessages() {
        for (String role : List.of("system", "developer", "assistant")) {
            assertViolation(failure(imageRequest(role, "data:image/png;base64,QUJD", null)),
                    INVALID, "input[0].content[0]");
        }
    }

    @Test
    void decodeImageCountAboveUpstreamLimitIsRejected() {
        ObjectNode root = baseRequest();
        ArrayNode content = root.putArray("input").addObject()
                .put("type", "message").put("role", "user").putArray("content");
        for (int i = 0; i < 601; i++) {
            content.addObject().put("type", "input_image").put("image_url", "data:image/png;base64,QUJD");
        }
        assertViolation(failure(MAPPER.writeValueAsString(root)), INVALID, "input");
    }

    @Test
    void decodeImageAbovePerImageByteCapIsRejected() {
        String urlPath = "input[0].content[0].image_url";
        // 上限含等号：恰好 32 MiB 必须放行。
        List<ContentPart> atCap = decode(imagesRequest(inlineImage(MAX_IMAGE_BYTES))).messages().get(0).parts();
        assertEquals(1, atCap.size());
        assertEquals("image/png", assertInstanceOf(ImagePart.class, atCap.get(0)).mediaType());

        CapabilityException over = failure(imagesRequest(inlineImage(MAX_IMAGE_BYTES + 1)));
        assertViolation(over, INVALID, urlPath);
        assertEquals("inline image exceeds 32 MiB", over.violations().get(0).reason());
    }

    @Test
    void decodeImageByteCapIsGuardedBeforeBase64Decoding() {
        // 超限且 base64 字符非法：若先解码会报 base64 非法，容量守卫必须前置（不为超大图片分配解码缓冲）。
        long oversized = MAX_IMAGE_BYTES + 1024;
        String garbage = "@".repeat((int) (oversized / 3 * 4));
        CapabilityException error = failure(imagesRequest("data:image/png;base64," + garbage));
        assertViolation(error, INVALID, "input[0].content[0].image_url");
        assertEquals("inline image exceeds 32 MiB", error.violations().get(0).reason());
    }

    @Test
    void decodeTotalImageBytesAboveCapIsRejected() {
        long half = MAX_TOTAL_IMAGE_BYTES / 2;
        String atCap = inlineImage(half);
        // 两张各自合规的图片合计恰好 64 MiB：放行。
        assertEquals(2, decode(imagesRequest(atCap, atCap)).messages().get(0).parts().size());
        // 再加 1 字节 → 总量超限，字段路径归 input。
        CapabilityException error = failure(imagesRequest(atCap, atCap, "data:image/png;base64,QQ=="));
        assertViolation(error, INVALID, "input");
        assertEquals("inline images exceed 64 MiB in total", error.violations().get(0).reason());
    }

    @Test
    void decodeImageUnknownKeysFailWithFullPath() {
        String json = """
                {"model":"m","input":[{"type":"message","role":"user",
                  "content":[{"type":"input_image","image_url":"data:image/png;base64,QUJD","bogus":1}]}]}
                """;
        assertViolation(failure(json), UNKNOWN, "input[0].content[0].bogus");
        String audio = """
                {"model":"m","input":[{"type":"message","role":"user",
                  "content":[{"type":"input_audio","input_audio":{"data":"QUJD","format":"wav"}}]}]}
                """;
        assertViolation(failure(audio), NOT_SUPPORTED, "input[0].content[0]");
    }

    // ========== §5.2 全量分类：R / I / 未知 ==========

    @Test
    void rejectFieldsFailWithNotSupportedAndHttp400() {
        fixtureJson("reject-fields.json").properties().forEach(entry ->
                assertViolation(failure(withField(entry.getKey(), entry.getValue())), NOT_SUPPORTED, entry.getKey()));
    }

    @Test
    void ignoreFieldsLeaveSemanticsUntouched() {
        fixtureJson("ignore-fields.json").properties().forEach(entry ->
                assertEquals(expectedBase(), decode(withField(entry.getKey(), entry.getValue())),
                        "忽略类字段不得改变语义: " + entry.getKey()));
    }

    @Test
    void unknownFieldsFailWithUnknownFieldCode() {
        fixtureJson("unknown-fields.json").properties().forEach(entry ->
                assertViolation(failure(withField(entry.getKey(), entry.getValue())), UNKNOWN, entry.getKey()));
    }

    /** §1.1 钉住字段集必须逐个被分类：任何一项落未知分支即说明矩阵漏实现。 */
    @Test
    void everyPinnedFieldIsClassifiedNotUnknown() {
        List<String> pinned = List.of("access_programs", "background", "context_management", "conversation",
                "image", "include", "input", "instructions", "max_output_tokens", "max_tool_calls", "metadata",
                "model", "moderation", "parallel_tool_calls", "previous_response_id", "prompt", "prompt_cache_key",
                "prompt_cache_options", "prompt_cache_retention", "reasoning", "safety_identifier", "service_tier",
                "store", "stream", "stream_options", "temperature", "text", "tool_choice", "tools", "top_logprobs",
                "top_p", "truncation", "user");
        // I 类字段丢弃任意值都不改变生成语义，必须直接放行。
        // temperature 在默认思考模式下属 I（非思考模式才是 E，由 thinkingModeSplits… 单独覆盖）。
        Set<String> ignorable = Set.of("metadata", "parallel_tool_calls", "prompt_cache_key",
                "prompt_cache_retention", "service_tier", "top_logprobs", "temperature");
        for (String name : pinned) {
            String json = withField(name, "{\"probe\":1}");
            if (ignorable.contains(name)) {
                assertEquals(expectedBase(), decode(json), "忽略类字段不得改变语义: " + name);
                continue;
            }
            CapabilityException error = assertThrows(CapabilityException.class, () -> codec.decode(json, ctx()),
                    "钉住字段必须被判定（E 校验 / R 拒绝 / D 授权），不得静默通过: " + name);
            var violation = error.violations().get(0);
            assertFalse(UNKNOWN.equals(violation.code()) && name.equals(violation.fieldPath()),
                    "钉住字段必须被分类，不得落未知分支: " + name);
        }
    }

    /** 全部 E/I 字段同时出现：分类互不干扰，语义只取 E 项（R 项由 reject 矩阵单独覆盖）。 */
    @Test
    void allEquivalentAndIgnorableFieldsDecodeTogether() {
        SemanticRequest req = decode(fixture("decode-all-equivalent-ignorable.json"));
        assertEquals("client-model", req.model());
        assertEquals(List.of(Role.SYSTEM, Role.USER), req.messages().stream().map(SemanticMessage::role).toList());
        assertEquals(512, req.maxOutputTokens().intValue());
        assertEquals(Boolean.TRUE, req.thinkingEnabled());
        assertEquals("high", req.thinkingEffort());
        assertNull(req.temperature(), "思考模式 temperature 属 I");
        assertEquals(0.95d, req.topP());
        assertEquals("json_object", req.responseFormat());
        assertEquals(List.of(new ToolSpec("ping", null, "{\"type\":\"object\"}")), req.tools());
        assertEquals("auto", req.toolChoice());
        assertTrue(req.stream());
        assertEquals(Boolean.TRUE, req.includeUsage());
        assertEquals("u-1", req.userId());
    }

    @Test
    void unknownNestedFieldsFailWithTheirFullPath() {
        assertViolation(failure(withField("reasoning", "{\"effort\":\"high\",\"bogus\":1}")),
                UNKNOWN, "reasoning.bogus");
        assertViolation(failure(withField("text", "{\"format\":{\"type\":\"text\"},\"bogus\":1}")),
                UNKNOWN, "text.bogus");
        assertViolation(failure(withField("stream_options", "{\"include_usage\":true,\"bogus\":1}")),
                UNKNOWN, "stream_options.bogus");
    }

    // ========== §5.2 E：max_output_tokens / user / safety_identifier ==========

    @Test
    void decodeMaxOutputTokensGuardsRange() {
        assertEquals(393216, decode(withField("max_output_tokens", "393216")).maxOutputTokens().intValue());
        assertNull(decode(withField("max_output_tokens", "null")).maxOutputTokens());
        for (String bad : List.of("0", "-1", "393217", "\"256\"", "1.5")) {
            assertViolation(failure(withField("max_output_tokens", bad)), INVALID, "max_output_tokens");
        }
    }

    @Test
    void decodeUserAndSafetyIdentifierNormalizeToUserId() {
        assertEquals("u-1", decode(withField("user", "\"u-1\"")).userId());
        assertEquals("u-1", decode(withField("safety_identifier", "\"u-1\"")).userId());
        ObjectNode both = baseRequest();
        both.put("user", "u-1");
        both.put("safety_identifier", "u-1");
        assertEquals("u-1", decode(MAPPER.writeValueAsString(both)).userId());
        ObjectNode conflict = baseRequest();
        conflict.put("user", "u-1");
        conflict.put("safety_identifier", "u-2");
        assertViolation(failure(MAPPER.writeValueAsString(conflict)), INVALID, "safety_identifier");
        String tooLong = "\"" + "x".repeat(513) + "\"";
        assertViolation(failure(withField("user", tooLong)), INVALID, "user");
        // 512 个多字节字符远超 512 UTF-8 字节：守卫按字节而非字符数。
        assertViolation(failure(withField("user", "\"" + "中".repeat(200) + "\"")), INVALID, "user");
    }

    @Test
    void decodeRequiresModelAndInput() {
        assertViolation(failure("{\"input\":\"hi\"}"), INVALID, "model");
        assertViolation(failure("{\"model\":\"m\"}"), INVALID, "input");
        assertViolation(failure("{\"model\":\"  \",\"input\":\"hi\"}"), INVALID, "model");
        assertViolation(failure("{\"model\":7,\"input\":\"hi\"}"), INVALID, "model");
        assertViolation(failure("not json"), INVALID, "request");
        assertViolation(failure("[]"), INVALID, "request");
        assertViolation(failure(""), INVALID, "request");
    }

    // ========== §5.2 E：stream / stream_options（include_usage 无损） ==========

    @Test
    void decodeStreamOptionsCarriesIncludeUsageLosslessly() {
        assertEquals(Boolean.FALSE, decode(streamRequest(false)).includeUsage(), "false 不得丢成 null/true");
        assertEquals(Boolean.TRUE, decode(streamRequest(true)).includeUsage());
        assertNull(decode(streamRequest(null)).includeUsage(), "缺键＝未指定");
        assertTrue(decode(streamRequest(true)).stream());
        assertFalse(decode(MAPPER.writeValueAsString(baseRequest())).stream());
    }

    @Test
    void decodeStreamOptionsRequiresStreamTrue() {
        ObjectNode root = baseRequest();
        root.putObject("stream_options").put("include_usage", true);
        assertViolation(failure(MAPPER.writeValueAsString(root)), INVALID, "stream_options");
        assertViolation(failure(withField("stream", "\"yes\"")), INVALID, "stream");
        assertViolation(failure(streamRequest(true).replace("\"include_usage\":true", "\"include_usage\":\"yes\"")),
                INVALID, "stream_options.include_usage");
        assertViolation(failure(withField("stream_options", "7")), INVALID, "stream_options");
    }

    // ========== §5.2 + §4.2：思考模式决定 temperature / top_p 分类 ==========

    @Test
    void thinkingModeSplitsTemperatureAndTopPClassification() {
        // effort=none → 非思考：temperature 有效（E），top_p 被上游固定 1.0（I）。
        SemanticRequest nonThinking = decode("""
                {"model":"m","input":"hi","reasoning":{"effort":"none"},"temperature":0.7,"top_p":0.9}
                """);
        assertEquals(Boolean.FALSE, nonThinking.thinkingEnabled());
        assertEquals("none", nonThinking.thinkingEffort());
        assertEquals(0.7d, nonThinking.temperature());
        assertNull(nonThinking.topP(), "非思考模式 top_p 属 I，不得进入语义");

        // effort=high → 思考：temperature 被上游忽略（I），top_p 有效（E）。
        SemanticRequest thinking = decode(fixture("decode-thinking.json"));
        assertEquals(Boolean.TRUE, thinking.thinkingEnabled());
        assertEquals("high", thinking.thinkingEffort());
        assertNull(thinking.temperature(), "思考模式 temperature 属 I，不得进入语义");
        assertEquals(0.9d, thinking.topP());

        // 省略 reasoning → 上游默认思考：不伪造开关，thinkingEnabled=null，分类同思考模式。
        SemanticRequest defaulted = decode("""
                {"model":"m","input":"hi","temperature":0.7,"top_p":0.9}
                """);
        assertNull(defaulted.thinkingEnabled());
        assertNull(defaulted.thinkingEffort());
        assertNull(defaulted.temperature());
        assertEquals(0.9d, defaulted.topP());
    }

    @Test
    void decodeEffortAcceptsPinnedValuesAndRejectsOthers() {
        for (String effort : List.of("none", "minimal", "low", "medium", "high", "xhigh", "max")) {
            SemanticRequest req = decode(withField("reasoning", "{\"effort\":\"" + effort + "\"}"));
            assertEquals(effort, req.thinkingEffort());
            assertEquals(!"none".equals(effort), req.thinkingEnabled());
        }
        assertViolation(failure(withField("reasoning", "{\"effort\":\"ultra\"}")), INVALID, "reasoning.effort");
        assertViolation(failure(withField("reasoning", "{\"effort\":7}")), INVALID, "reasoning.effort");
        assertNull(decode(withField("reasoning", "{\"effort\":null}")).thinkingEffort());
        assertViolation(failure(withField("reasoning", "7")), INVALID, "reasoning");
        // 采样越界前置 400，不把非法值丢给上游。
        assertViolation(failure(withField("reasoning", "{\"effort\":\"none\"}")
                .replace("\"input\":\"hi\"", "\"input\":\"hi\",\"temperature\":2.5")), INVALID, "temperature");
        assertViolation(failure("{\"model\":\"m\",\"input\":\"hi\",\"top_p\":1.5}"), INVALID, "top_p");
    }

    // ========== §5.2 R/D：reasoning.summary / generate_summary / context / mode ==========

    @Test
    void reasoningSummaryRequiresDisplayRawGrant() {
        String json = withField("reasoning", "{\"effort\":\"high\",\"summary\":\"auto\"}");
        assertViolation(failure(json), NOT_AUTHORIZED, "reasoning.summary");

        CapabilityContext granted = ctx(DISPLAY_RAW);
        SemanticRequest req = codec.decode(json, granted);
        assertEquals(List.of(new AppliedDegradation("reasoning.summary", DISPLAY_RAW,
                        "上游无摘要模型，固定回传原文推理而非摘要")),
                granted.audit().applied());
        assertEquals("high", req.thinkingEffort());

        String legacy = withField("reasoning", "{\"generate_summary\":\"auto\"}");
        assertViolation(failure(legacy), NOT_AUTHORIZED, "reasoning.generate_summary");
        CapabilityContext legacyGranted = ctx(DISPLAY_RAW);
        codec.decode(legacy, legacyGranted);
        assertEquals("reasoning.generate_summary", legacyGranted.audit().applied().get(0).fieldPath());
    }

    @Test
    void reasoningContextAndModeAreRejected() {
        assertViolation(failure(withField("reasoning", "{\"context\":\"all_turns\"}")),
                NOT_SUPPORTED, "reasoning.context");
        assertViolation(failure(withField("reasoning", "{\"mode\":\"auto\"}")), NOT_SUPPORTED, "reasoning.mode");
    }

    // ========== §5.2 E/R/D：text.format ==========

    @Test
    void textFormatMapsTextAndJsonObjectEquivalently() {
        assertEquals("text", decode(withField("text", "{\"format\":{\"type\":\"text\"}}")).responseFormat());
        assertEquals("json_object",
                decode(withField("text", "{\"format\":{\"type\":\"json_object\"}}")).responseFormat());
        assertNull(decode(MAPPER.writeValueAsString(baseRequest())).responseFormat());
        assertViolation(failure(withField("text", "{\"format\":{\"type\":\"bogus\"}}")),
                INVALID, "text.format.type");
        assertViolation(failure(withField("text", "{\"format\":7}")), INVALID, "text.format");
        assertViolation(failure(withField("text", "7")), INVALID, "text");
        assertViolation(failure(withField("text", "{\"format\":{\"type\":\"text\",\"bogus\":1}}")),
                UNKNOWN, "text.format.bogus");
    }

    @Test
    void jsonSchemaFormatRequiresSchemaToObjectGrant() {
        String json = fixture("decode-schema-format.json");
        assertViolation(failure(json), NOT_AUTHORIZED, "text.format");

        CapabilityContext granted = ctx(SCHEMA_TO_OBJECT);
        SemanticRequest req = codec.decode(json, granted);
        assertEquals("json_object", req.responseFormat(), "降级后仅保证 json_object，不强制 schema");
        assertEquals(List.of(new AppliedDegradation("text.format", SCHEMA_TO_OBJECT,
                "不强制 JSON Schema，仅保证 json_object")), granted.audit().applied());
    }

    // ========== §5.2 E/R：tools（Responses 扁平 function 形态） ==========

    @Test
    void decodeFunctionToolsUseFlatResponsesShape() {
        SemanticRequest req = decode(fixture("decode-tools.json"));
        assertEquals(2, req.tools().size());
        assertEquals("get_weather", req.tools().get(0).name());
        assertEquals("查询天气", req.tools().get(0).description());
        assertEquals(MAPPER.readTree("""
                {"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}
                """), MAPPER.readTree(req.tools().get(0).parametersJsonSchema()));
        assertEquals(new ToolSpec("ping", null, null), req.tools().get(1));
        assertTrue(req.tools().stream().noneMatch(t -> t.name().contains("function")),
                "Responses 的 function 工具是扁平形态，不得照搬 Chat 的嵌套 function 对象");
    }

    @Test
    void decodeToolsRejectBuiltInStrictAndDuplicates() {
        for (String type : List.of("file_search", "web_search", "web_search_preview", "code_interpreter",
                "computer_use_preview", "mcp", "local_shell", "image_generation", "custom")) {
            assertViolation(failure(withField("tools", "[{\"type\":\"" + type + "\"}]")), NOT_SUPPORTED, "tools[0]");
        }
        assertViolation(failure(withField("tools",
                        "[{\"type\":\"function\",\"name\":\"ping\",\"strict\":true}]")),
                NOT_SUPPORTED, "tools[0].strict");
        assertViolation(failure(withField("tools",
                        "[{\"type\":\"function\",\"name\":\"ping\"},{\"type\":\"function\",\"name\":\"ping\"}]")),
                INVALID, "tools[1].name");
        assertViolation(failure(withField("tools", "[{\"type\":\"function\"}]")), INVALID, "tools[0].name");
        assertViolation(failure(withField("tools", "[{\"type\":\"function\",\"name\":\"get weather\"}]")),
                INVALID, "tools[0].name");
        assertViolation(failure(withField("tools", "[{\"type\":\"function\",\"name\":\"ping\",\"parameters\":7}]")),
                INVALID, "tools[0].parameters");
        // Chat 的嵌套形态在 Responses 属未知字段，不得猜测映射。
        assertViolation(failure(withField("tools",
                        "[{\"type\":\"function\",\"function\":{\"name\":\"ping\"}}]")), UNKNOWN, "tools[0].function");
        assertViolation(failure(withField("tools", "7")), INVALID, "tools");
    }

    // ========== §5.2 E/R/D：tool_choice ==========

    @Test
    void toolChoiceNoneAutoAndForcedAreEquivalentWhenNotThinking() {
        String nonThinking = "{\"effort\":\"none\"}";
        assertEquals("none", decode(withField("reasoning", nonThinking)
                .replace("\"input\":\"hi\"", "\"input\":\"hi\",\"tool_choice\":\"none\"")).toolChoice());
        assertEquals("auto", decode(withField("reasoning", nonThinking)
                .replace("\"input\":\"hi\"", "\"input\":\"hi\",\"tool_choice\":\"auto\"")).toolChoice());
        assertEquals("required", decode(withField("reasoning", nonThinking)
                .replace("\"input\":\"hi\"", "\"input\":\"hi\",\"tool_choice\":\"required\"")).toolChoice());
        // Responses 具名形态是扁平 {type:function,name}，不是 Chat 的 {type:function,function:{name}}。
        assertEquals("tool:get_weather", decode(fixture("decode-tools.json")).toolChoice());
    }

    @Test
    void forcedToolChoiceInThinkingModeRequiresGrant() {
        String required = withField("tool_choice", "\"required\"");
        assertViolation(failure(required), NOT_AUTHORIZED, "tool_choice");
        CapabilityContext granted = ctx(FORCE_TO_AUTO);
        assertEquals("auto", codec.decode(required, granted).toolChoice());
        assertEquals(List.of(new AppliedDegradation("tool_choice", FORCE_TO_AUTO,
                "思考模式不强制工具调用，required/具名退化为 auto")), granted.audit().applied());

        String named = withField("tool_choice", "{\"type\":\"function\",\"name\":\"ping\"}");
        assertViolation(failure(named), NOT_AUTHORIZED, "tool_choice");
        CapabilityContext namedGranted = ctx(FORCE_TO_AUTO);
        assertEquals("auto", codec.decode(named, namedGranted).toolChoice());
        assertEquals(1, namedGranted.audit().applied().size());
    }

    @Test
    void toolChoiceBuiltInMcpCustomAndAllowedAreRejected() {
        for (String choice : List.of("{\"type\":\"allowed_tools\",\"tools\":[{\"type\":\"function\",\"name\":\"ping\"}]}",
                "{\"type\":\"mcp\",\"server_label\":\"s\",\"tool_name\":\"t\"}",
                "{\"type\":\"custom\",\"name\":\"c\"}",
                "{\"type\":\"file_search\"}",
                "{\"type\":\"web_search_preview\"}")) {
            assertViolation(failure(withField("tool_choice", choice)), NOT_SUPPORTED, "tool_choice");
        }
        assertViolation(failure(withField("tool_choice", "\"bogus\"")), INVALID, "tool_choice");
        assertViolation(failure(withField("tool_choice", "7")), INVALID, "tool_choice");
        assertViolation(failure(withField("tool_choice", "{\"type\":\"function\"}")), INVALID, "tool_choice.name");
        assertViolation(failure(withField("tool_choice", "{\"type\":\"function\",\"name\":\"ping\",\"bogus\":1}")),
                UNKNOWN, "tool_choice.bogus");
    }

    // ========== §5.2 I/R：include ==========

    @Test
    void includeEncryptedContentIsIgnorableAndOtherValuesAreRejected() {
        assertEquals(expectedBase(), decode(withField("include", "[\"reasoning.encrypted_content\"]")));
        assertEquals(expectedBase(), decode(withField("include", "[]")));
        for (String value : List.of("file_search_call.results", "web_search_call.action.sources",
                "message.input_image.image_url", "computer_call_output.output.image_url",
                "code_interpreter_call.outputs", "message.output_text.logprobs")) {
            assertViolation(failure(withField("include", "[\"" + value + "\"]")), NOT_SUPPORTED, "include[0]");
        }
        // 混合：合法项不掩盖非法项，路径必须指向具体下标。
        assertViolation(failure(withField("include",
                        "[\"reasoning.encrypted_content\",\"file_search_call.results\"]")),
                NOT_SUPPORTED, "include[1]");
        assertViolation(failure(withField("include", "\"reasoning.encrypted_content\"")), INVALID, "include");
        assertViolation(failure(withField("include", "[7]")), INVALID, "include[0]");
    }

    // ========== §11：每请求审计隔离 ==========

    @Test
    void perRequestAuditIsolationOnSharedCodec() {
        CapabilityContext granted = ctx(FORCE_TO_AUTO, SCHEMA_TO_OBJECT, DISPLAY_RAW);
        CapabilityContext denied = ctx();
        String json = """
                {"model":"m","input":"hi","tool_choice":"required",
                 "reasoning":{"summary":"auto"},"text":{"format":{"type":"json_schema","name":"a","schema":{}}}}
                """;
        codec.decode(json, granted);
        assertEquals(3, granted.audit().applied().size());
        assertTrue(denied.audit().applied().isEmpty());
        assertViolation(failure(json, denied), NOT_AUTHORIZED, "tool_choice");
        assertTrue(denied.audit().applied().isEmpty(), "拒绝路径不得留痕");
        assertEquals(3, granted.audit().applied().size(), "另一个请求不得污染已留痕的实例");
        // 共享单例上的第二次授权请求必须重新计数，不累积上一请求的留痕。
        CapabilityContext second = ctx(FORCE_TO_AUTO, SCHEMA_TO_OBJECT, DISPLAY_RAW);
        codec.decode(json, second);
        assertEquals(3, second.audit().applied().size());
    }

    // ========== §5.2/§9：encodeResponse ==========

    private static SemanticResponse response(List<ContentPart> parts, StopReason stop, Usage usage) {
        return new SemanticResponse("resp_1", List.of(new SemanticMessage(Role.ASSISTANT, parts)), stop, usage);
    }

    private JsonNode encode(SemanticResponse resp, String model) {
        return MAPPER.readTree(codec.encodeResponse(resp, model, ctx()));
    }

    @Test
    void encodeResponseBuildsReasoningMessageAndFunctionCallItems() {
        SemanticResponse resp = response(List.of(
                new ThinkingPart("CoT-TEXT", null),
                new TextPart("answer"),
                new ToolCallPart("call_1", "get_weather", "{\"city\":\"Paris\"}")),
                StopReason.TOOL_CALL, new Usage(5L, 7L, 4L, 2L));
        JsonNode root = encode(resp, "deepseek-flash");

        assertEquals("resp_1", root.path("id").asString());
        assertEquals("response", root.path("object").asString());
        assertEquals("completed", root.path("status").asString());
        assertEquals("deepseek-flash", root.path("model").asString());
        JsonNode output = root.path("output");
        assertEquals(3, output.size(), "reasoning → message → function_call 顺序固定");
        assertEquals(List.of("reasoning", "message", "function_call"),
                List.of(output.get(0).path("type").asString(), output.get(1).path("type").asString(),
                        output.get(2).path("type").asString()));

        JsonNode reasoning = output.get(0);
        assertEquals("[]", reasoning.path("summary").toString(), "网关不伪造摘要（矩阵 §7.4）");
        assertEquals(1, reasoning.path("content").size());
        assertEquals("reasoning_text", reasoning.path("content").get(0).path("type").asString());
        assertEquals("CoT-TEXT", reasoning.path("content").get(0).path("text").asString());

        JsonNode message = output.get(1);
        assertEquals("assistant", message.path("role").asString());
        assertEquals("output_text", message.path("content").get(0).path("type").asString());
        assertEquals("answer", message.path("content").get(0).path("text").asString());
        assertEquals("[]", message.path("content").get(0).path("annotations").toString());

        JsonNode call = output.get(2);
        assertEquals("call_1", call.path("call_id").asString());
        assertEquals("get_weather", call.path("name").asString());
        assertEquals("{\"city\":\"Paris\"}", call.path("arguments").asString());

        JsonNode usage = root.path("usage");
        assertEquals(5, usage.path("input_tokens").asInt());
        assertEquals(7, usage.path("output_tokens").asInt());
        assertEquals(12, usage.path("total_tokens").asInt());
        assertEquals(2, usage.path("input_tokens_details").path("cached_tokens").asInt());
        assertEquals(4, usage.path("output_tokens_details").path("reasoning_tokens").asInt());
    }

    @Test
    void encodeResponsePureToolCallOmitsMessageItem() {
        // 纯 tool_call 轮（无 Text）不得发空 message 条目，与流式纯工具轮 parity（矩阵 §7）。
        JsonNode output = encode(response(List.of(
                new ToolCallPart("call_1", "get_weather", "{\"city\":\"SF\"}")),
                StopReason.TOOL_CALL, null), "m").path("output");
        assertEquals(1, output.size(), "纯 tool_call 轮只产 function_call 条目");
        assertEquals("function_call", output.get(0).path("type").asString());
        assertEquals("call_1", output.get(0).path("call_id").asString());
        assertEquals("get_weather", output.get(0).path("name").asString());
        assertEquals("{\"city\":\"SF\"}", output.get(0).path("arguments").asString());
    }

    @Test
    void encodeResponseReasoningPlusToolCallOmitsMessageItem() {
        // 推理 + 纯 tool_call（无 Text）：reasoning 条目保留，message 条目省略。
        JsonNode output = encode(response(List.of(
                new ThinkingPart("CoT-TEXT", null),
                new ToolCallPart("call_1", "get_weather", "{}")),
                StopReason.TOOL_CALL, null), "m").path("output");
        assertEquals(2, output.size());
        assertEquals(List.of("reasoning", "function_call"),
                List.of(output.get(0).path("type").asString(), output.get(1).path("type").asString()));
    }

    @Test
    void encodeResponseMintsContinuationEnvelopeOnReasoningItem() {
        SemanticResponse resp = response(List.of(
                new ThinkingPart("CoT-TEXT", null),
                new TextPart("answer")), StopReason.COMPLETED, null);
        JsonNode reasoning = encode(resp, "m").path("output").get(0);
        assertEquals("reasoning", reasoning.path("type").asString());
        // 矩阵 §7.2/§7.4：summary 恒空、明文走 reasoning_text，绝不伪造摘要/供应商签名。
        assertEquals("[]", reasoning.path("summary").toString());
        assertEquals("CoT-TEXT", reasoning.path("content").get(0).path("text").asString());
        String token = reasoning.path("encrypted_content").asString();
        assertFalse(token.isBlank(), "encodeResponse 必须铸造续轮信封 encrypted_content（矩阵 §7.3）");
        assertTrue(token.startsWith("llmgw1:"), "信封令牌格式 llmgw1:<payload>.<hmac>");
        // 信封须绑定 project-1 + 推理原文摘要，完整性校验通过。
        ENVELOPE.verify(token, "project-1", "CoT-TEXT");
        // itemId 必须绑定本 reasoning 条目 id（信封 payload 白盒断言）。
        String body = token.substring("llmgw1:".length());
        JsonNode payload = MAPPER.readTree(Base64.getUrlDecoder().decode(body.substring(0, body.indexOf('.'))));
        assertEquals(reasoning.path("id").asString(), payload.path("itemId").asString());
        assertEquals("project-1", payload.path("projectId").asString());
    }

    @Test
    void encodeResponseReasoningRoundTripsThroughDecodeRestoringContinuationToken() {
        // 真实 encode→decode 往返：encodeResponse 铸的信封须能被同一项目 ctx 的 decode 校验并还原 continuationToken。
        SemanticResponse resp = response(List.of(
                new ThinkingPart("round trip reasoning", null),
                new TextPart("answer")), StopReason.COMPLETED, null);
        JsonNode reasoning = encode(resp, "m").path("output").get(0);
        String token = reasoning.path("encrypted_content").asString();

        ObjectNode root = baseRequest();
        root.putArray("input").add(reasoning);
        SemanticRequest req = codec.decode(MAPPER.writeValueAsString(root), ctx());
        ThinkingPart thinking = req.messages().stream()
                .flatMap(m -> m.parts().stream())
                .filter(p -> p instanceof ThinkingPart)
                .map(p -> (ThinkingPart) p)
                .findFirst().orElseThrow();
        assertEquals("round trip reasoning", thinking.reasoning());
        assertEquals(token, thinking.continuationToken(), "encode 铸的信封须原样还原到 continuationToken");
    }

    @Test
    void encodeResponseUsageOmitsUnknownAndNeverFakesZero() {
        // 仅输入已知：output/total/details 全部省略（null≠0）。
        JsonNode inputOnly = encode(response(List.of(new TextPart("a")), StopReason.COMPLETED,
                new Usage(5L, null, null, null)), "m").path("usage");
        assertEquals(5, inputOnly.path("input_tokens").asInt());
        assertTrue(inputOnly.path("output_tokens").isMissingNode());
        assertTrue(inputOnly.path("total_tokens").isMissingNode());
        assertTrue(inputOnly.path("input_tokens_details").isMissingNode());
        assertTrue(inputOnly.path("output_tokens_details").isMissingNode());

        // 仅输出已知：不得推算 total。
        JsonNode outputOnly = encode(response(List.of(new TextPart("a")), StopReason.COMPLETED,
                new Usage(null, 7L, null, null)), "m").path("usage");
        assertTrue(outputOnly.path("input_tokens").isMissingNode());
        assertEquals(7, outputOnly.path("output_tokens").asInt());
        assertTrue(outputOnly.path("total_tokens").isMissingNode());

        // 细分未知→省略对应 details，但已知细分仍要给出。
        JsonNode cachedOnly = encode(response(List.of(new TextPart("a")), StopReason.COMPLETED,
                new Usage(5L, 7L, null, 3L)), "m").path("usage");
        assertEquals(3, cachedOnly.path("input_tokens_details").path("cached_tokens").asInt());
        assertTrue(cachedOnly.path("output_tokens_details").isMissingNode());

        // 0 是已知值，必须原样输出，不得当作未知省略。
        JsonNode zeros = encode(response(List.of(new TextPart("a")), StopReason.COMPLETED,
                new Usage(0L, 0L, 0L, 0L)), "m").path("usage");
        assertEquals(0, zeros.path("input_tokens").asInt());
        assertEquals(0, zeros.path("total_tokens").asInt());
        assertEquals(0, zeros.path("output_tokens_details").path("reasoning_tokens").asInt());

        // usage 整体未知→省略 usage 键。
        assertTrue(encode(response(List.of(new TextPart("a")), StopReason.COMPLETED, null), "m")
                .path("usage").isMissingNode());
    }

    @Test
    void encodeResponseRejectsInexpressibleSemanticsInsteadOfRewriting() {
        // 非 assistant 角色是上游/语义合同缺陷，绝不静默改写。
        SemanticResponse userRole = new SemanticResponse("resp_1",
                List.of(new SemanticMessage(Role.USER, List.of(new TextPart("a")))), StopReason.COMPLETED, null);
        UpstreamException roleError = assertThrows(UpstreamException.class, () -> codec.encodeResponse(userRole, "m", ctx()));
        assertEquals("upstream_error", roleError.code());
        assertEquals(502, roleError.status());

        // UNKNOWN 终止不得映射为 completed。
        SemanticResponse unknownStop = response(List.of(new TextPart("a")), StopReason.UNKNOWN, null);
        assertThrows(UpstreamException.class, () -> codec.encodeResponse(unknownStop, "m", ctx()));
        // 缺失终止原因同样不可伪造成功。
        SemanticResponse noStop = response(List.of(new TextPart("a")), null, null);
        assertThrows(UpstreamException.class, () -> codec.encodeResponse(noStop, "m", ctx()));
        // 多候选不可表达（Responses 单条 output 序列）。
        SemanticResponse twoMessages = new SemanticResponse("resp_1", List.of(
                new SemanticMessage(Role.ASSISTANT, List.of(new TextPart("a"))),
                new SemanticMessage(Role.ASSISTANT, List.of(new TextPart("b")))), StopReason.COMPLETED, null);
        assertThrows(UpstreamException.class, () -> codec.encodeResponse(twoMessages, "m", ctx()));
    }

    @Test
    void encodeResponseOmitsModelWhenNullAndSynthesizesIdWhenAbsent() {
        JsonNode root = encode(new SemanticResponse(null,
                List.of(new SemanticMessage(Role.ASSISTANT, List.of(new TextPart("a")))),
                StopReason.COMPLETED, null), null);
        assertTrue(root.path("model").isMissingNode(), "model 未知时不得伪造");
        assertFalse(root.path("id").asString().isBlank());
        assertTrue(root.path("id").asString().startsWith("resp_"));
        // 无推理/无工具时只产 message 条目。
        assertEquals(1, root.path("output").size());
        assertEquals("message", root.path("output").get(0).path("type").asString());
        // 空 assistant 消息仍须给出合法 message 条目（content=[]），不得丢条目。
        JsonNode empty = encode(response(List.of(), StopReason.COMPLETED, null), "m");
        assertEquals(1, empty.path("output").size());
        assertEquals("[]", empty.path("output").get(0).path("content").toString());
    }

    @Test
    void encodeResponseCarriesLengthAndContentFilterAsIncomplete() {
        // 矩阵 §6.3 终止映射同样适用于非流式：截断/内容过滤不得被静默改写为 completed。
        JsonNode length = encode(response(List.of(new TextPart("a")), StopReason.LENGTH, null), "m");
        assertEquals("incomplete", length.path("status").asString());
        assertEquals("max_output_tokens", length.path("incomplete_details").path("reason").asString());
        JsonNode filtered = encode(response(List.of(new TextPart("a")), StopReason.CONTENT_FILTER, null), "m");
        assertEquals("incomplete", filtered.path("status").asString());
        assertEquals("content_filter", filtered.path("incomplete_details").path("reason").asString());
        // 正常完成不带 incomplete_details。
        assertTrue(encode(response(List.of(new TextPart("a")), StopReason.COMPLETED, null), "m")
                .path("incomplete_details").isMissingNode());
    }

    // ========== §10.2：错误形状 ==========

    @Test
    void errorJsonUsesResponsesShapeAndHttpStatus() {
        CapabilityException error = new CapabilityException(NOT_SUPPORTED, 400, "store", "网关不引入对话存储");
        JsonNode root = MAPPER.readTree(codec.errorJson(error));
        assertEquals(Set.of("error"), names(root));
        JsonNode body = root.path("error");
        assertEquals(Set.of("code", "message", "param", "type"), names(body));
        assertEquals(NOT_SUPPORTED, body.path("code").asString());
        assertEquals("网关不引入对话存储", body.path("message").asString());
        assertEquals("store", body.path("param").asString());
        assertEquals("invalid_request_error", body.path("type").asString());
        assertEquals(400, error.status());
        // 多违规取首条（与 Chat 同约定），不得拼接成不可解析的 message。
        CapabilityException multiple = new CapabilityException(400, List.of(
                new io.github.leohou0430.llmgw.conversion.Violation(INVALID, "first", "第一个违规"),
                new io.github.leohou0430.llmgw.conversion.Violation(INVALID, "second", "第二个违规")));
        JsonNode rendered = MAPPER.readTree(codec.errorJson(multiple)).path("error");
        assertEquals("first", rendered.path("param").asString());
        assertEquals("第一个违规", rendered.path("message").asString());
    }

    @Test
    void errorTypeFollowsHttpStatus() {
        Map<Integer, String> expected = Map.of(
                400, "invalid_request_error",
                404, "invalid_request_error",
                501, "invalid_request_error",
                429, "rate_limit_error",
                500, "server_error",
                502, "server_error",
                503, "server_error");
        expected.forEach((status, type) -> {
            CapabilityException error = new CapabilityException("code_" + status, status, "field", "reason");
            assertEquals(type, MAPPER.readTree(codec.errorJson(error)).path("error").path("type").asString());
        });
    }

    @Test
    void errorEventUsesResponsesErrorFrame() {
        CapabilityException error = new CapabilityException(INVALID, 400, "input", "bad input");
        String frame = codec.errorEvent(error);
        // 与 Chat 的 errorEvent 同归属：事件行含 event/data 两个 SSE 字段，但不含结尾换行（帧间隔归上层）。
        assertTrue(frame.startsWith("event: error\n"), "Responses 错误帧必须带 event 名: " + frame);
        assertFalse(frame.endsWith("\n"), "帧间隔归上层 transcoder: " + frame);
        JsonNode payload = MAPPER.readTree(frame.substring(frame.indexOf("data: ") + "data: ".length()));
        assertEquals(Set.of("type", "code", "message", "param", "sequence_number"), names(payload));
        assertEquals("error", payload.path("type").asString());
        assertEquals(INVALID, payload.path("code").asString());
        assertEquals("bad input", payload.path("message").asString());
        assertEquals("input", payload.path("param").asString());
        assertTrue(payload.path("sequence_number").isIntegralNumber());
        // 错误事件与错误 JSON 必须同源同码，不得各说一套。
        assertEquals(MAPPER.readTree(codec.errorJson(error)).path("error").path("code").asString(),
                payload.path("code").asString());
    }

    // ========== §6.3：事件序 / 单调 sequence_number / 恰一次终态 ==========

    @Test
    void streamTextOnlyEmitsFullItemLifecycleInOrder() {
        StreamEncoder encoder = encoder(null);
        List<String> lines = concat(
                drain(encoder, new TextDelta("Hel"), new TextDelta("lo"), new TerminalEvent(StopReason.COMPLETED)),
                encoder.onComplete());

        assertEquals(List.of("response.created", "response.in_progress",
                "response.output_item.added", "response.content_part.added",
                "response.output_text.delta", "response.output_text.delta",
                "response.output_text.done", "response.content_part.done", "response.output_item.done",
                "response.completed"), types(lines));
        assertMonotonicSequence(lines);
        assertTrue(encoder.terminalSeen());
        assertEquals(List.of(), encoder.onComplete(), "Responses 无 [DONE]，重复 onComplete 必须为空");

        List<JsonNode> nodes = payloads(lines);
        JsonNode created = nodes.get(0).path("response");
        assertEquals("in_progress", created.path("status").asString());
        assertEquals("[]", created.path("output").toString());
        assertEquals("response", created.path("object").asString());
        assertEquals("deepseek-flash", created.path("model").asString());
        assertEquals("in_progress", nodes.get(1).path("response").path("status").asString());
        String id = created.path("id").asString();
        assertFalse(id.isBlank());

        // 条目生命周期内的 item_id / output_index / content_index 必须自洽。
        String itemId = nodes.get(2).path("item").path("id").asString();
        assertEquals("message", nodes.get(2).path("item").path("type").asString());
        assertEquals("in_progress", nodes.get(2).path("item").path("status").asString());
        for (int i = 2; i <= 8; i++) {
            assertEquals(0, nodes.get(i).path("output_index").asInt());
        }
        for (int i = 3; i <= 7; i++) {
            assertEquals(itemId, nodes.get(i).path("item_id").asString());
            assertEquals(0, nodes.get(i).path("content_index").asInt());
        }
        assertEquals("output_text", nodes.get(3).path("part").path("type").asString());
        assertEquals("", nodes.get(3).path("part").path("text").asString());
        assertEquals("Hel", nodes.get(4).path("delta").asString());
        assertEquals("lo", nodes.get(5).path("delta").asString());
        assertEquals("Hello", nodes.get(6).path("text").asString());
        assertEquals("Hello", nodes.get(7).path("part").path("text").asString());
        assertEquals("completed", nodes.get(8).path("item").path("status").asString());

        JsonNode terminal = nodes.get(9).path("response");
        assertEquals(id, terminal.path("id").asString());
        assertEquals("completed", terminal.path("status").asString());
        assertEquals(1, terminal.path("output").size());
        assertEquals("Hello", terminal.path("output").get(0).path("content").get(0).path("text").asString());
        assertTrue(terminal.path("usage").isMissingNode(), "usage 未知→省略，不得填 0");
    }

    @Test
    void streamReasoningThenTextUsesReasoningTextNotSummary() {
        StreamEncoder encoder = encoder(null);
        List<String> lines = concat(drain(encoder, new ThinkingDelta("CoT-"), new ThinkingDelta("TEXT"),
                new TextDelta("ans"), new TerminalEvent(StopReason.COMPLETED)), encoder.onComplete());

        assertEquals(List.of("response.created", "response.in_progress",
                "response.output_item.added", "response.content_part.added",
                "response.reasoning_text.delta", "response.reasoning_text.delta",
                "response.reasoning_text.done", "response.content_part.done", "response.output_item.done",
                "response.output_item.added", "response.content_part.added",
                "response.output_text.delta", "response.output_text.done",
                "response.content_part.done", "response.output_item.done",
                "response.completed"), types(lines));
        assertMonotonicSequence(lines);
        // 网关不伪造摘要：绝不使用 reasoning_summary_text.*（矩阵 §6.3 注 / §7.4）。
        assertTrue(types(lines).stream().noneMatch(t -> t.contains("reasoning_summary")),
                "不得使用 response.reasoning_summary_text.*");

        List<JsonNode> nodes = payloads(lines);
        assertEquals("reasoning", nodes.get(2).path("item").path("type").asString());
        assertEquals("[]", nodes.get(2).path("item").path("summary").toString());
        assertEquals("reasoning_text", nodes.get(3).path("part").path("type").asString());
        assertEquals("CoT-", nodes.get(4).path("delta").asString());
        assertEquals("TEXT", nodes.get(5).path("delta").asString());
        assertEquals("CoT-TEXT", nodes.get(6).path("text").asString());
        assertEquals("CoT-TEXT", nodes.get(8).path("item").path("content").get(0).path("text").asString());
        // 矩阵 §7.3：流式终态 reasoning 条目铸造续轮信封（output_item.done 与终态 output[] 一致）。
        String streamToken = nodes.get(8).path("item").path("encrypted_content").asString();
        assertFalse(streamToken.isBlank(), "流式 output_item.done 必须携带 encrypted_content");
        ENVELOPE.verify(streamToken, "project-1", "CoT-TEXT");
        for (int i = 2; i <= 8; i++) {
            assertEquals(0, nodes.get(i).path("output_index").asInt());
        }
        for (int i = 9; i <= 14; i++) {
            assertEquals(1, nodes.get(i).path("output_index").asInt());
        }
        JsonNode output = nodes.get(15).path("response").path("output");
        assertEquals(List.of("reasoning", "message"),
                List.of(output.get(0).path("type").asString(), output.get(1).path("type").asString()));
        assertEquals("ans", output.get(1).path("content").get(0).path("text").asString());
        assertEquals(streamToken, output.get(0).path("encrypted_content").asString(),
                "终态 output[] 的 reasoning 信封须与 output_item.done 一致");
    }

    @Test
    void streamReasoningTerminalMintsEnvelopeAndRoundTripsThroughDecode() {
        StreamEncoder encoder = encoder(null);
        List<String> lines = concat(drain(encoder, new ThinkingDelta("stream "), new ThinkingDelta("reasoning"),
                new TextDelta("ans"), new TerminalEvent(StopReason.COMPLETED)), encoder.onComplete());
        List<JsonNode> nodes = payloads(lines);
        JsonNode reasoning = nodes.get(nodes.size() - 1).path("response").path("output").get(0);
        assertEquals("reasoning", reasoning.path("type").asString());
        assertEquals("[]", reasoning.path("summary").toString(), "网关不伪造摘要（矩阵 §7.4）");
        assertEquals("stream reasoning", reasoning.path("content").get(0).path("text").asString());
        String token = reasoning.path("encrypted_content").asString();
        assertFalse(token.isBlank(), "流式终态必须铸造续轮信封（矩阵 §7.3）");
        ENVELOPE.verify(token, "project-1", "stream reasoning");

        // 真实流式终态→decode 往返：信封须被同一项目 ctx 校验并还原 continuationToken。
        ObjectNode root = baseRequest();
        root.putArray("input").add(reasoning);
        SemanticRequest req = codec.decode(MAPPER.writeValueAsString(root), ctx());
        ThinkingPart thinking = req.messages().stream()
                .flatMap(m -> m.parts().stream())
                .filter(p -> p instanceof ThinkingPart)
                .map(p -> (ThinkingPart) p)
                .findFirst().orElseThrow();
        assertEquals("stream reasoning", thinking.reasoning());
        assertEquals(token, thinking.continuationToken(), "流式铸的信封须原样还原到 continuationToken");
    }

    @Test
    void streamReopenedReasoningMintsEnvelopePerItemBoundToItsOwnText() {
        StreamEncoder encoder = encoder(null);
        List<String> lines = concat(drain(encoder,
                new ThinkingDelta("X"),
                new TextDelta("mid"),
                new ThinkingDelta("Y"),
                new TerminalEvent(StopReason.COMPLETED)), encoder.onComplete());
        JsonNode output = payloads(lines).get(lines.size() - 1).path("response").path("output");
        // 终态按 output_index 升序：reasoning(X) idx0、message(mid) idx1、reasoning(Y) idx2。
        JsonNode first = output.get(0);
        JsonNode second = output.get(2);
        assertEquals("reasoning", first.path("type").asString());
        assertEquals("reasoning", second.path("type").asString());
        String t1 = first.path("encrypted_content").asString();
        String t2 = second.path("encrypted_content").asString();
        ENVELOPE.verify(t1, "project-1", "X");
        ENVELOPE.verify(t2, "project-1", "Y");
        assertNotEquals(t1, t2, "不同 reasoning 条目须铸不同信封");
        assertNotEquals(first.path("id").asString(), second.path("id").asString());
    }

    @Test
    void streamToolCallsEmitOneFunctionCallItemPerCall() {
        StreamEncoder encoder = encoder(null);
        List<String> lines = concat(drain(encoder,
                new ToolCallDelta(0, "call_1", "get_weather", "{\"city\":"),
                new ToolCallDelta(0, null, null, "\"Paris\"}"),
                new ToolCallDelta(1, "call_2", "ping", null),
                new TerminalEvent(StopReason.TOOL_CALL)), encoder.onComplete());

        assertEquals(List.of("response.created", "response.in_progress",
                "response.output_item.added", "response.function_call_arguments.delta",
                "response.function_call_arguments.delta", "response.function_call_arguments.done",
                "response.output_item.done",
                "response.output_item.added", "response.function_call_arguments.done",
                "response.output_item.done",
                "response.completed"), types(lines));
        assertMonotonicSequence(lines);

        List<JsonNode> nodes = payloads(lines);
        JsonNode first = nodes.get(2).path("item");
        assertEquals("function_call", first.path("type").asString());
        assertEquals("call_1", first.path("call_id").asString());
        assertEquals("get_weather", first.path("name").asString());
        assertEquals("{\"city\":", nodes.get(3).path("delta").asString());
        assertEquals("\"Paris\"}", nodes.get(4).path("delta").asString());
        assertEquals("{\"city\":\"Paris\"}", nodes.get(5).path("arguments").asString());
        assertEquals("call_1", nodes.get(5).path("call_id").asString());
        assertEquals("{\"city\":\"Paris\"}", nodes.get(6).path("item").path("arguments").asString());
        assertEquals(0, nodes.get(2).path("output_index").asInt());
        assertEquals(1, nodes.get(7).path("output_index").asInt());
        assertEquals("call_2", nodes.get(7).path("item").path("call_id").asString());
        assertEquals("", nodes.get(8).path("arguments").asString(), "无参数调用仍须闭合 arguments.done");

        JsonNode terminal = nodes.get(10).path("response");
        assertEquals("completed", terminal.path("status").asString(), "TOOL_CALL 属正常完成");
        JsonNode output = terminal.path("output");
        assertEquals(2, output.size(), "每个工具调用恰一个 function_call 条目");
        assertEquals(List.of("function_call", "function_call"),
                List.of(output.get(0).path("type").asString(), output.get(1).path("type").asString()));
        assertEquals("ping", output.get(1).path("name").asString());
        // 无文本时不得凭空插入空 message 条目。
        assertTrue(types(lines).stream().noneMatch(t -> t.equals("response.output_text.delta")));
    }

    @Test
    void streamMixedKeepsGlobalOutputIndexAndItemOrder() {
        StreamEncoder encoder = encoder(null);
        List<String> lines = concat(drain(encoder,
                new ThinkingDelta("think"), new TextDelta("say"),
                new ToolCallDelta(0, "call_1", "ping", "{}"),
                new ToolCallDelta(1, "call_2", "pong", "{}"),
                new UsageEvent(new Usage(5L, 7L, 4L, 2L)),
                new TerminalEvent(StopReason.TOOL_CALL)), encoder.onComplete());
        assertMonotonicSequence(lines);

        List<JsonNode> nodes = payloads(lines);
        // reasoning=0, message=1, call_1=2, call_2=3：output_index 全局连续，不按条目类型重置。
        List<Integer> addedIndexes = new java.util.ArrayList<>();
        for (JsonNode node : nodes) {
            if ("response.output_item.added".equals(node.path("type").asString())) {
                addedIndexes.add(node.path("output_index").asInt());
            }
        }
        assertEquals(List.of(0, 1, 2, 3), addedIndexes);
        JsonNode output = nodes.get(nodes.size() - 1).path("response").path("output");
        assertEquals(4, output.size());
        assertEquals(List.of("reasoning", "message", "function_call", "function_call"),
                List.of(output.get(0).path("type").asString(), output.get(1).path("type").asString(),
                        output.get(2).path("type").asString(), output.get(3).path("type").asString()));
        assertEquals("call_1", output.get(2).path("call_id").asString());
        assertEquals("call_2", output.get(3).path("call_id").asString());
        assertEquals(1, types(lines).stream().filter(t -> t.equals("response.completed")).count());
    }

    @Test
    void streamUsageIsBufferedAndOnlyAppearsInTerminal() {
        StreamEncoder encoder = encoder(true);
        List<String> beforeTerminal = drain(encoder, new TextDelta("a"),
                new UsageEvent(new Usage(5L, 7L, 4L, 2L)), new TextDelta("b"));
        // usage 只在终态：终态前任何事件都不得携带 usage，也不得发独立 usage 事件。
        for (JsonNode node : payloads(beforeTerminal)) {
            assertTrue(node.path("usage").isMissingNode(), "终态前不得出现 usage: " + node.path("type"));
            assertTrue(node.path("response").path("usage").isMissingNode());
        }
        assertEquals(List.of(), encoder.onEvent(new UsageEvent(new Usage(9L, 9L, 9L, 9L))),
                "UsageEvent 只缓冲，不单独成帧");

        List<String> terminal = encoder.onEvent(new TerminalEvent(StopReason.COMPLETED));
        // 终态批次 = 未闭合条目的收尾事件 + 恰一个 response.completed；usage 只挂在最后那个终态上。
        assertEquals(1, types(terminal).stream().filter(t -> t.equals("response.completed")).count());
        assertEquals("response.completed", types(terminal).get(terminal.size() - 1));
        for (JsonNode node : payloads(terminal.subList(0, terminal.size() - 1))) {
            assertTrue(node.path("usage").isMissingNode(), "条目收尾事件不得携带 usage");
            assertTrue(node.path("response").path("usage").isMissingNode());
        }
        JsonNode usage = payload(terminal.get(terminal.size() - 1)).path("response").path("usage");
        // 后到的 UsageEvent 覆盖先前的缓冲值（上游最终 usage 为准）。
        assertEquals(9, usage.path("input_tokens").asInt());
        assertEquals(9, usage.path("output_tokens").asInt());
        assertEquals(18, usage.path("total_tokens").asInt());
        assertEquals(9, usage.path("input_tokens_details").path("cached_tokens").asInt());
        assertEquals(9, usage.path("output_tokens_details").path("reasoning_tokens").asInt());
        assertMonotonicSequence(concat(beforeTerminal, terminal));
    }

    @Test
    void streamTerminalMappingCoversCompletedIncompleteAndFailed() {
        record Case(StopReason stop, String event, String status, String reason) {
        }
        List<Case> cases = List.of(
                new Case(StopReason.COMPLETED, "response.completed", "completed", null),
                new Case(StopReason.TOOL_CALL, "response.completed", "completed", null),
                new Case(StopReason.LENGTH, "response.incomplete", "incomplete", "max_output_tokens"),
                new Case(StopReason.CONTENT_FILTER, "response.incomplete", "incomplete", "content_filter"),
                new Case(StopReason.UNKNOWN, "response.failed", "failed", null));
        for (Case c : cases) {
            StreamEncoder encoder = encoder(null);
            List<String> lines = concat(drain(encoder, new TextDelta("a"), new TerminalEvent(c.stop())),
                    encoder.onComplete());
            assertEquals(c.event(), types(lines).get(lines.size() - 1), "终止映射: " + c.stop());
            assertEquals(1, types(lines).stream().filter(t -> t.startsWith("response.completed")
                    || t.equals("response.incomplete") || t.equals("response.failed")).count(),
                    "恰一次终态: " + c.stop());
            JsonNode response = payloads(lines).get(lines.size() - 1).path("response");
            assertEquals(c.status(), response.path("status").asString());
            if (c.reason() == null) {
                assertTrue(response.path("incomplete_details").isMissingNode());
            } else {
                assertEquals(c.reason(), response.path("incomplete_details").path("reason").asString());
            }
            if (c.stop() == StopReason.UNKNOWN) {
                assertFalse(response.path("error").path("code").asString().isBlank(),
                        "failed 必须带可辨识错误，不得伪造成功");
            }
            assertTrue(encoder.terminalSeen());
            // 终态后到达的事件一律忽略，不得追加第二个终态。
            assertEquals(List.of(), encoder.onEvent(new TextDelta("late")));
            assertEquals(List.of(), encoder.onEvent(new TerminalEvent(StopReason.COMPLETED)));
            assertEquals(List.of(), encoder.onError("upstream_error", "late"));
            assertEquals(List.of(), encoder.onComplete());
            assertMonotonicSequence(lines);
        }
    }

    @Test
    void streamErrorEmitsErrorEventAndNeverASuccessTerminal() {
        StreamEncoder encoder = encoder(null);
        List<String> lines = concat(drain(encoder, new TextDelta("partial")),
                encoder.onError("upstream_error", "upstream aborted"));
        assertEquals(List.of("response.created", "response.in_progress",
                "response.output_item.added", "response.content_part.added",
                "response.output_text.delta", "error"), types(lines));
        assertMonotonicSequence(lines);
        JsonNode error = payloads(lines).get(lines.size() - 1);
        assertEquals("upstream_error", error.path("code").asString());
        assertEquals("upstream aborted", error.path("message").asString());
        assertTrue(error.path("param").isNull(), "流式错误无字段路径→param=null");
        assertTrue(encoder.terminalSeen());
        // 错误后不得再发 completed/failed/incomplete，也不得发 [DONE] 伪造成功。
        assertEquals(List.of(), encoder.onComplete());
        assertEquals(List.of(), encoder.onEvent(new TerminalEvent(StopReason.COMPLETED)));
        assertEquals(List.of(), encoder.onError("upstream_error", "again"), "onError 必须幂等");
        assertEquals(1, types(lines).stream().filter(t -> t.equals("error")).count());
    }

    @Test
    void streamErrorFallsBackOnBlankCodeAndMessage() {
        for (String[] blank : List.of(new String[][]{{null, null}, {"", ""}, {"  ", "  "}, {null, "detail"},
                {"upstream_error", null}})) {
            StreamEncoder encoder = encoder(null);
            List<String> lines = encoder.onError(blank[0], blank[1]);
            assertEquals(1, lines.size(), "空 code/message 也必须落一帧合法错误，不得抛裸异常");
            JsonNode error = payload(lines.get(0));
            assertEquals("error", error.path("type").asString());
            assertFalse(error.path("code").asString().isBlank());
            assertFalse(error.path("message").asString().isBlank());
            assertEquals(blank[0] == null || blank[0].isBlank() ? "internal_error" : blank[0],
                    error.path("code").asString());
            assertTrue(encoder.terminalSeen());
            assertEquals(List.of(), encoder.onComplete());
        }
    }

    @Test
    void streamWithoutTerminalRefusesToFakeSuccess() {
        StreamEncoder encoder = encoder(null);
        drain(encoder, new TextDelta("partial"));
        assertFalse(encoder.terminalSeen());
        assertThrows(IllegalStateException.class, encoder::onComplete,
                "缺终态不得伪造成功，截断交上层判定");
        assertFalse(encoder.terminalSeen());
        // 全新实例同样不得凭空补终态。
        assertThrows(IllegalStateException.class, () -> encoder(null).onComplete());
        // 出错后 onComplete 不再抛（已是一次终态）。
        StreamEncoder failed = encoder(null);
        failed.onError("upstream_error", "boom");
        assertEquals(List.of(), failed.onComplete());
    }

    @Test
    void streamEncoderInstancesAreIsolatedOnSharedCodec() {
        StreamEncoder first = encoder(null);
        StreamEncoder second = encoder(null);
        assertNotSame(first, second);
        List<String> a = concat(drain(first, new TextDelta("a"), new TerminalEvent(StopReason.COMPLETED)),
                first.onComplete());
        List<String> b = concat(drain(second, new ThinkingDelta("t"), new TextDelta("b"),
                new TerminalEvent(StopReason.COMPLETED)), second.onComplete());
        assertMonotonicSequence(a);
        assertMonotonicSequence(b);
        assertNotEquals(payload(a.get(0)).path("response").path("id").asString(),
                payload(b.get(0)).path("response").path("id").asString(), "每请求独立响应 id");
        assertEquals(1, payload(a.get(a.size() - 1)).path("response").path("output").size());
        assertEquals(2, payload(b.get(b.size() - 1)).path("response").path("output").size());
        // 第一个实例的错误不得影响第二个实例的终态。
        first.onError("upstream_error", "late");
        assertEquals(List.of(), first.onError("upstream_error", "late"));
        assertTrue(second.terminalSeen());
    }

    @Test
    void streamModelIsOmittedWhenNullAndIncludeUsageIsCarriedFromRequest() {
        StreamEncoder noModel = codec.newStreamEncoder(null, false, ctx());
        List<String> lines = concat(drain(noModel, new TextDelta("a"),
                new UsageEvent(new Usage(1L, 2L, null, null)), new TerminalEvent(StopReason.COMPLETED)),
                noModel.onComplete());
        assertTrue(payloads(lines).get(0).path("response").path("model").isMissingNode());
        // Responses 的 usage 恒在终态（§6.3），include_usage 只是请求侧无损载体，不改变终态形状。
        for (Boolean preference : java.util.Arrays.asList(Boolean.TRUE, Boolean.FALSE, null)) {
            SemanticRequest req = decode(streamRequest(preference));
            assertEquals(preference, req.includeUsage());
            StreamEncoder enc = codec.newStreamEncoder(req.model(), Boolean.TRUE.equals(req.includeUsage()), ctx());
            List<String> streamed = concat(drain(enc, new TextDelta("a"),
                    new UsageEvent(new Usage(1L, 2L, null, null)), new TerminalEvent(StopReason.COMPLETED)),
                    enc.onComplete());
            JsonNode usage = payloads(streamed).get(streamed.size() - 1).path("response").path("usage");
            assertEquals(1, usage.path("input_tokens").asInt());
            assertEquals(3, usage.path("total_tokens").asInt());
            assertTrue(usage.path("output_tokens_details").isMissingNode());
        }
    }

    @Test
    void streamReasoningOnlyStillClosesItemAndTerminatesOnce() {
        StreamEncoder encoder = encoder(null);
        List<String> lines = concat(drain(encoder, new ThinkingDelta("only think"),
                new TerminalEvent(StopReason.LENGTH)), encoder.onComplete());
        assertEquals(List.of("response.created", "response.in_progress",
                "response.output_item.added", "response.content_part.added",
                "response.reasoning_text.delta", "response.reasoning_text.done",
                "response.content_part.done", "response.output_item.done",
                "response.incomplete"), types(lines));
        assertMonotonicSequence(lines);
        JsonNode response = payloads(lines).get(lines.size() - 1).path("response");
        assertEquals(1, response.path("output").size());
        assertEquals("reasoning", response.path("output").get(0).path("type").asString());
        assertEquals("max_output_tokens", response.path("incomplete_details").path("reason").asString());
    }

    @Test
    void streamEmptyTerminalStillEmitsPreambleAndOneTerminal() {
        StreamEncoder encoder = encoder(null);
        List<String> lines = concat(drain(encoder, new TerminalEvent(StopReason.CONTENT_FILTER)),
                encoder.onComplete());
        assertEquals(List.of("response.created", "response.in_progress", "response.incomplete"), types(lines));
        assertMonotonicSequence(lines);
        JsonNode response = payloads(lines).get(2).path("response");
        assertEquals("[]", response.path("output").toString());
        assertEquals("content_filter", response.path("incomplete_details").path("reason").asString());
        assertTrue(encoder.terminalSeen());
    }

    // ========== 评审修复：A 编码器健壮性 / B 不可表达部件 / C 可空字段 / 往返与畸形 JSON ==========

    /** A1：text→tool→text 重开 message 时清空缓冲，第二个条目只含自己的增量（不得变成 "AB"）。 */
    @Test
    void streamReopenedMessageBufferCarriesOnlyItsOwnDeltas() {
        StreamEncoder encoder = encoder(null);
        List<String> lines = concat(drain(encoder,
                new TextDelta("A"),
                new ToolCallDelta(0, "call_1", "ping", "{}"),
                new TextDelta("B"),
                new TerminalEvent(StopReason.COMPLETED)), encoder.onComplete());
        List<String> doneTexts = new java.util.ArrayList<>();
        for (JsonNode node : payloads(lines)) {
            if ("response.output_text.done".equals(node.path("type").asString())) {
                doneTexts.add(node.path("text").asString());
            }
        }
        assertEquals(List.of("A", "B"), doneTexts, "重开的 message 不得复用脏缓冲（A1）");
        assertMonotonicSequence(lines);
    }

    /** A1：thinking→text→thinking 重开 reasoning 时清空缓冲（不得变成 "XY"）。 */
    @Test
    void streamReopenedReasoningBufferCarriesOnlyItsOwnDeltas() {
        StreamEncoder encoder = encoder(null);
        List<String> lines = concat(drain(encoder,
                new ThinkingDelta("X"),
                new TextDelta("mid"),
                new ThinkingDelta("Y"),
                new TerminalEvent(StopReason.COMPLETED)), encoder.onComplete());
        List<String> doneTexts = new java.util.ArrayList<>();
        for (JsonNode node : payloads(lines)) {
            if ("response.reasoning_text.done".equals(node.path("type").asString())) {
                doneTexts.add(node.path("text").asString());
            }
        }
        assertEquals(List.of("X", "Y"), doneTexts, "重开的 reasoning 不得复用脏缓冲（A1）");
        assertMonotonicSequence(lines);
    }

    /** A2：终态 output[] 按 output_index 升序，而非条目闭合顺序（text→tool0→tool1→text）。 */
    @Test
    void streamTerminalOutputIsOrderedByOutputIndexNotCloseOrder() {
        StreamEncoder encoder = encoder(null);
        List<String> lines = concat(drain(encoder,
                new TextDelta("a"),
                new ToolCallDelta(0, "call_1", "ping", "{}"),
                new ToolCallDelta(1, "call_2", "pong", "{}"),
                new TextDelta("b"),
                new TerminalEvent(StopReason.TOOL_CALL)), encoder.onComplete());
        List<JsonNode> nodes = payloads(lines);
        List<Integer> addedIndexes = new java.util.ArrayList<>();
        for (JsonNode node : nodes) {
            if ("response.output_item.added".equals(node.path("type").asString())) {
                addedIndexes.add(node.path("output_index").asInt());
            }
        }
        assertEquals(List.of(0, 1, 2, 3), addedIndexes);
        JsonNode output = nodes.get(nodes.size() - 1).path("response").path("output");
        assertEquals(4, output.size());
        assertEquals(List.of("message", "function_call", "function_call", "message"),
                List.of(output.get(0).path("type").asString(), output.get(1).path("type").asString(),
                        output.get(2).path("type").asString(), output.get(3).path("type").asString()),
                "终态 output 必须按 output_index 升序（A2）");
        assertEquals("a", output.get(0).path("content").get(0).path("text").asString());
        assertEquals("call_1", output.get(1).path("call_id").asString());
        assertEquals("call_2", output.get(2).path("call_id").asString());
        assertEquals("b", output.get(3).path("content").get(0).path("text").asString());
        assertMonotonicSequence(lines);
    }

    /** A3：工具调用缺 id/name → 恰一次 error 终态，绝不 NPE 或伪造空名 completed 条目。 */
    @Test
    void streamToolCallMissingIdOrNameEmitsOneErrorTerminal() {
        for (ToolCallDelta bad : List.of(
                new ToolCallDelta(0, "call_1", null, "{}"),
                new ToolCallDelta(0, null, "ping", "{}"))) {
            StreamEncoder encoder = encoder(null);
            List<String> lines = concat(drain(encoder, bad, new TerminalEvent(StopReason.TOOL_CALL)),
                    encoder.onComplete());
            assertTrue(encoder.terminalSeen(), "畸形工具调用必须落终态（A3）");
            List<String> types = types(lines);
            assertEquals(1, types.stream().filter(t -> t.equals("error")).count());
            assertTrue(types.stream().noneMatch(t -> t.equals("response.completed")
                            || t.equals("response.incomplete") || t.equals("response.failed")),
                    "不得伪造成功终态");
            assertTrue(types.stream().noneMatch(t -> t.equals("response.output_item.done")),
                    "不得伪造 function_call 完成条目");
            JsonNode error = payloads(lines).get(lines.size() - 1);
            assertEquals("upstream_error", error.path("code").asString());
            assertFalse(error.path("message").asString().isBlank());
            assertTrue(error.path("message").asString().contains("0"), "错误消息须指明 index");
            assertMonotonicSequence(lines);
            assertEquals(List.of(), encoder.onEvent(new TerminalEvent(StopReason.COMPLETED)));
            assertEquals(List.of(), encoder.onError("upstream_error", "late"));
            assertEquals(List.of(), encoder.onComplete());
        }
    }

    /** A4：已闭合 index 的非连续增量 → 恰一次 error 终态，不静默截断成 completed。 */
    @Test
    void streamLateDeltaForClosedToolIndexEmitsOneErrorTerminal() {
        StreamEncoder encoder = encoder(null);
        List<String> lines = concat(drain(encoder,
                new ToolCallDelta(0, "call_1", "ping", "{\"a\":"),
                new ToolCallDelta(1, "call_2", "pong", "{}"),
                new ToolCallDelta(0, null, null, "1}"),
                new TerminalEvent(StopReason.TOOL_CALL)), encoder.onComplete());
        assertTrue(encoder.terminalSeen());
        List<String> types = types(lines);
        assertEquals(1, types.stream().filter(t -> t.equals("error")).count());
        assertTrue(types.stream().noneMatch(t -> t.equals("response.completed")),
                "非连续增量不得静默截断成 completed（A4）");
        JsonNode error = payloads(lines).get(lines.size() - 1);
        assertEquals("upstream_error", error.path("code").asString());
        assertTrue(error.path("message").asString().contains("non-contiguous"), error.path("message").asString());
        assertTrue(error.path("message").asString().contains("0"));
        assertMonotonicSequence(lines);
        assertEquals(List.of(), encoder.onEvent(new TerminalEvent(StopReason.COMPLETED)));
    }

    /** B：assistant 消息含 ImagePart/ToolResultPart 不可表达 → 抛 UpstreamException(502)，绝不静默丢弃。 */
    @Test
    void encodeResponseRejectsInexpressibleAssistantPartsInsteadOfDropping() {
        SemanticResponse withImage = response(List.of(new TextPart("a"),
                new ImagePart("image/png", "QUJD", null, null)), StopReason.COMPLETED, null);
        UpstreamException imageError = assertThrows(UpstreamException.class,
                () -> codec.encodeResponse(withImage, "m", ctx()));
        assertEquals("upstream_error", imageError.code());
        assertEquals(502, imageError.status());
        SemanticResponse withToolResult = response(List.of(new TextPart("a"),
                new ToolResultPart("call_1", "{}", false)), StopReason.COMPLETED, null);
        UpstreamException resultError = assertThrows(UpstreamException.class,
                () -> codec.encodeResponse(withToolResult, "m", ctx()));
        assertEquals(502, resultError.status());
    }

    /** C：钉住 SDK 允许 null 的字段按缺省处理，不当 400；错误类型仍拒绝。 */
    @Test
    void decodeNullableFieldsTreatNullAsAbsentNotInvalid() {
        SemanticRequest noInstructions = decode(withField("instructions", "null"));
        assertEquals(List.of(Role.USER), noInstructions.messages().stream().map(SemanticMessage::role).toList(),
                "instructions:null 不得前置 system 消息");
        assertNull(decode(withField("user", "null")).userId(), "user:null → userId 省略");
        assertFalse(decode(withField("stream", "null")).stream(), "stream:null → 默认 false");
        CapabilityContext context = ctx();
        SemanticRequest noSummary = codec.decode(withField("reasoning", "{\"summary\":null}"), context);
        assertTrue(context.audit().applied().isEmpty(), "reasoning.summary:null 不得要求 display_raw 授权");
        assertNull(noSummary.thinkingEffort());
        // 错误类型仍按 invalid_value 拒绝。
        assertViolation(failure(withField("instructions", "123")), INVALID, "instructions");
        assertViolation(failure(withField("user", "123")), INVALID, "user");
        assertViolation(failure(withField("stream", "\"yes\"")), INVALID, "stream");
    }

    /** 畸形 JSON：保留 cause、客户端原因简洁单行、不泄露 Jackson 源位置；null body 前置 400。 */
    @Test
    void decodeMalformedJsonPreservesCauseAndKeepsClientReasonConcise() {
        CapabilityException error = failure("{\"model\":\"m\",\"input\":[");
        assertViolation(error, INVALID, "request");
        String reason = error.violations().get(0).reason();
        assertEquals("invalid request JSON", reason);
        assertFalse(reason.contains("[Source:"), "不得把 Jackson 源位置 dump 泄露给客户端: " + reason);
        assertFalse(reason.contains("\n"), "客户端原因必须单行: " + reason);
        assertNotNull(error.getCause(), "解析异常必须保留 cause，不得吞栈");
        assertInstanceOf(JacksonException.class, error.getCause());
        assertViolation(failure(null), INVALID, "request");
    }

    /** 往返：encodeResponse → 独立解析 output[] → 语义等价（ThinkingPart/TextPart/ToolCallPart 不丢）。 */
    @Test
    void responseRoundTripPreservesSemantics() {
        SemanticResponse original = new SemanticResponse("resp_rt",
                List.of(new SemanticMessage(Role.ASSISTANT, List.of(
                        new ThinkingPart("reason", null),
                        new TextPart("answer"),
                        new ToolCallPart("call_9", "ping", "{}")))),
                StopReason.TOOL_CALL, new Usage(10L, 20L, 3L, 4L));
        JsonNode json = MAPPER.readTree(codec.encodeResponse(original, "client-model", ctx()));
        assertEquals(original, readResponse(json));
    }

    /** 测试侧独立解析 Responses output[]：证明入口 JSON 未丢语义（不依赖被测编码器内部结构）。 */
    private static SemanticResponse readResponse(JsonNode json) {
        List<ContentPart> parts = new java.util.ArrayList<>();
        boolean hasToolCall = false;
        for (JsonNode item : json.path("output")) {
            switch (item.path("type").asString()) {
                case "reasoning" -> parts.add(new ThinkingPart(joinContentText(item), null));
                case "message" -> parts.add(new TextPart(joinContentText(item)));
                case "function_call" -> {
                    hasToolCall = true;
                    parts.add(new ToolCallPart(item.path("call_id").asString(), item.path("name").asString(),
                            item.path("arguments").asString()));
                }
                default -> { }
            }
        }
        StopReason stop = switch (json.path("status").asString()) {
            case "completed" -> hasToolCall ? StopReason.TOOL_CALL : StopReason.COMPLETED;
            case "incomplete" -> "content_filter".equals(json.path("incomplete_details").path("reason").asString())
                    ? StopReason.CONTENT_FILTER : StopReason.LENGTH;
            default -> StopReason.UNKNOWN;
        };
        JsonNode usage = json.path("usage");
        Usage parsed = usage.isMissingNode() ? null : new Usage(
                longOrNull(usage.path("input_tokens")),
                longOrNull(usage.path("output_tokens")),
                longOrNull(usage.path("output_tokens_details").path("reasoning_tokens")),
                longOrNull(usage.path("input_tokens_details").path("cached_tokens")));
        return new SemanticResponse(json.path("id").asString(),
                List.of(new SemanticMessage(Role.ASSISTANT, parts)), stop, parsed);
    }

    private static String joinContentText(JsonNode item) {
        StringBuilder text = new StringBuilder();
        for (JsonNode part : item.path("content")) {
            text.append(part.path("text").asString());
        }
        return text.toString();
    }

    private static Long longOrNull(JsonNode node) {
        return node.isNumber() ? node.asLong() : null;
    }

    // ========== §6.3：流式编码器脚手架 ==========

    /** Responses 事件行＝event 名 + data JSON；事件名必须等于 JSON type，不含结尾换行（帧间隔归上层）。 */
    private static JsonNode payload(String line) {
        assertTrue(line.startsWith("event: "), "Responses 事件行必须带 event 名: " + line);
        int nl = line.indexOf('\n');
        assertTrue(nl > 0, "event 与 data 必须分行: " + line);
        String eventName = line.substring("event: ".length(), nl);
        assertTrue(line.startsWith("data: ", nl + 1), "第二行必须是 data 框架: " + line);
        assertFalse(line.endsWith("\n"), "帧间隔归上层 transcoder: " + line);
        JsonNode node = MAPPER.readTree(line.substring(nl + 1 + "data: ".length()));
        assertEquals(eventName, node.path("type").asString(), "事件名必须等于 JSON type");
        return node;
    }

    private static List<JsonNode> payloads(List<String> lines) {
        return lines.stream().map(ResponsesEntryCodecTest::payload).toList();
    }

    private static List<String> types(List<String> lines) {
        return payloads(lines).stream().map(node -> node.path("type").asString()).toList();
    }

    /** 单调 sequence_number 从 0 开始，逐事件 +1，不得跳号/回退。 */
    private static void assertMonotonicSequence(List<String> lines) {
        long expected = 0;
        for (JsonNode node : payloads(lines)) {
            assertEquals(expected, node.path("sequence_number").asLong(),
                    "sequence_number 必须从 0 单调递增: " + node.path("type").asString());
            expected++;
        }
    }

    private static List<String> concat(List<String>... chunks) {
        List<String> all = new java.util.ArrayList<>();
        for (List<String> chunk : chunks) {
            all.addAll(chunk);
        }
        return all;
    }

    private StreamEncoder encoder(Boolean includeUsage) {
        return codec.newStreamEncoder("deepseek-flash", Boolean.TRUE.equals(includeUsage), ctx());
    }

    private static List<String> drain(StreamEncoder encoder, SemanticEvent... events) {
        List<String> lines = new java.util.ArrayList<>();
        for (SemanticEvent event : events) {
            lines.addAll(encoder.onEvent(event));
        }
        return lines;
    }
}