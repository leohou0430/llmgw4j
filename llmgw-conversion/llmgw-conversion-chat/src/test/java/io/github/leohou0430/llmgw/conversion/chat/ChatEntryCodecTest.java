package io.github.leohou0430.llmgw.conversion.chat;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 映射矩阵 §5.1/§6.2/§9/§10.2/§11 驱动的 Chat 入口合同测试。 */
class ChatEntryCodecTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String UNKNOWN = CapabilityException.UNKNOWN_FIELD;
    private static final String NOT_SUPPORTED = CapabilityException.NOT_SUPPORTED;
    private static final String NOT_AUTHORIZED = CapabilityException.DEGRADATION_NOT_AUTHORIZED;
    private static final String INVALID = CapabilityException.INVALID_VALUE;
    private static final String LEGACY = "tool_call.legacy_function";
    private static final String FORCE_AUTO = "tool_choice.force_to_auto";
    private static final String SCHEMA_TO_OBJECT = "response_format.schema_to_object";
    /** 与 ChatEntryCodec 的图片容量守卫同值（矩阵 §4.4/§8）：单图 32 MiB、总量 64 MiB。 */
    private static final long MAX_IMAGE_BYTES = 32L * 1024 * 1024;
    private static final long MAX_TOTAL_IMAGE_BYTES = 64L * 1024 * 1024;
    /**
     * T1.9 独立真值：自有小图 llmgw-probe.png 的 sha256，取自
     * tools/acceptance/stage1/fixtures/images/manifest.json（固化字面量，不在测试内重算期望值）。
     */
    private static final String PROBE_SHA256 =
            "f24b0899ce1bb9147836beb6812c9ab7778a864f39c44b14d32548e4d91228d9";

    private final ChatEntryCodec codec = new ChatEntryCodec();

    private static String fixture(String name) {
        try (InputStream in = ChatEntryCodecTest.class.getResourceAsStream("/chat/" + name)) {
            assertNotNull(in, "missing fixture: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read fixture " + name, e);
        }
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
            new ContinuationEnvelope("chat-entry-test-key".getBytes(StandardCharsets.UTF_8));

    private static CapabilityContext ctx(String... allowed) {
        return new CapabilityContext("tenant-1", "project-1",
                new DegradationAudit(new DegradationGrant(Set.of(allowed))), ENVELOPE);
    }

    private SemanticRequest decode(String json) {
        return codec.decode(json, ctx());
    }

    private static ObjectNode baseRequest() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", "client-model");
        root.putArray("messages").addObject().put("role", "user").put("content", "hi");
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

    private static String imageRequest(String role, String url, String detail) {
        ObjectNode root = baseRequest();
        ObjectNode message = (ObjectNode) root.path("messages").get(0);
        message.put("role", role);
        ObjectNode imageUrl = message.putArray("content").addObject()
                .put("type", "image_url").putObject("image_url");
        imageUrl.put("url", url);
        if (detail != null) {
            imageUrl.put("detail", detail);
        }
        return MAPPER.writeValueAsString(root);
    }

    /** 多图请求：按顺序内联 data URL，用于容量守卫（张数/单图/总量）测试。 */
    private static String imagesRequest(String... urls) {
        ObjectNode root = baseRequest();
        ArrayNode content = ((ObjectNode) root.path("messages").get(0)).putArray("content");
        for (String url : urls) {
            content.addObject().put("type", "image_url").putObject("image_url").put("url", url);
        }
        return MAPPER.writeValueAsString(root);
    }

    private static String base64OfBytes(long bytes) {
        return Base64.getEncoder().encodeToString(new byte[(int) bytes]);
    }

    private static String inlineImage(long bytes) {
        return "data:image/png;base64," + base64OfBytes(bytes);
    }

    /** 流式请求：includeUsage=null 表示不带 stream_options。 */
    private static String streamRequest(Boolean includeUsage) {
        ObjectNode root = baseRequest();
        root.put("stream", true);
        if (includeUsage != null) {
            root.putObject("stream_options").put("include_usage", includeUsage.booleanValue());
        }
        return MAPPER.writeValueAsString(root);
    }

    private static String toolChoiceRequest(String choiceJson, boolean thinking) {
        ObjectNode root = baseRequest();
        root.put("reasoning_effort", thinking ? "high" : "none");
        root.putArray("tools").addObject().put("type", "function").putObject("function").put("name", "get_weather");
        root.set("tool_choice", MAPPER.readTree(choiceJson));
        return MAPPER.writeValueAsString(root);
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

    private CapabilityException failure(String json, CapabilityContext ctx) {
        return assertThrows(CapabilityException.class, () -> codec.decode(json, ctx));
    }

    private static Set<String> names(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.properties().forEach(entry -> fields.add(entry.getKey()));
        return fields;
    }

    private static JsonNode payload(String line) {
        assertTrue(line.startsWith("data: "), "SSE 行必须含 data: 框架: " + line);
        assertFalse(line.contains("\n"), "SSE 行不含换行，帧间隔归上层: " + line);
        return MAPPER.readTree(line.substring("data: ".length()));
    }

    private static JsonNode firstDelta(List<String> lines) {
        assertEquals(1, lines.size());
        JsonNode chunk = payload(lines.get(0));
        assertEquals("chat.completion.chunk", chunk.path("object").asString());
        assertFalse(chunk.path("id").asString().isBlank());
        assertTrue(chunk.path("created").asLong() > 0);
        assertEquals(1, chunk.path("choices").size());
        JsonNode choice = chunk.path("choices").get(0);
        assertEquals(0, choice.path("index").asInt());
        return choice.path("delta");
    }

    /** 统计整条流里出现 delta.role 的帧数（role 必须恰好一次，矩阵 §6.2）。 */
    private static int roleFrames(List<String> lines) {
        int count = 0;
        for (String line : lines) {
            if ("data: [DONE]".equals(line)) {
                continue;
            }
            for (JsonNode choice : payload(line).path("choices")) {
                if (choice.path("delta").has("role")) {
                    assertEquals("assistant", choice.path("delta").path("role").asString());
                    count++;
                }
            }
        }
        return count;
    }

    // ========== §5.1 E：多轮/角色/消息内容 ==========

    @Test
    void decodeMultiTurnKeepsOrderAndMapsEveryContentForm() {
        SemanticRequest req = decode(fixture("decode-multiturn.json"));

        assertEquals("client-model", req.model());
        assertEquals(List.of(Role.SYSTEM, Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL, Role.USER),
                req.messages().stream().map(SemanticMessage::role).toList());
        assertEquals(List.of(new TextPart("be helpful")), req.messages().get(0).parts());
        assertEquals(List.of(new TextPart("prefer metric units")), req.messages().get(1).parts());
        assertEquals(List.of(new TextPart("hello")), req.messages().get(2).parts());
        assertEquals(List.of(
                        new ThinkingPart("thinking hard", null),
                        new TextPart("hi there"),
                        new ToolCallPart("call_1", "get_weather", "{\"city\":\"Paris\"}")),
                req.messages().get(3).parts());
        assertEquals(List.of(new ToolResultPart("call_1", "{\"temp\":21}", false)), req.messages().get(4).parts());
        assertEquals(List.of(new TextPart("segment one"), new TextPart("segment two")), req.messages().get(5).parts());
        assertEquals(256, req.maxOutputTokens().intValue());
        assertEquals(List.of("END"), req.stop());
        assertEquals(Boolean.FALSE, req.thinkingEnabled());
        assertEquals("none", req.thinkingEffort());
        assertEquals(0.7, req.temperature().doubleValue());
        assertNull(req.topP(), "非思考模式 top_p 属 I，不得映射");
        assertEquals("user-1", req.userId());
        assertFalse(req.stream());
        assertTrue(req.tools().isEmpty());
        assertNull(req.toolChoice());
        assertNull(req.responseFormat());
    }

    @Test
    void decodeSystemContentPartsAndAssistantNullContent() {
        String json = """
                {"model":"m","messages":[
                  {"role":"system","content":[{"type":"text","text":"rule one"},{"type":"text","text":"rule two"}]},
                  {"role":"assistant","content":null},
                  {"role":"user","content":"next"}]}
                """;
        SemanticRequest req = decode(json);
        assertEquals(List.of(new TextPart("rule one"), new TextPart("rule two")), req.messages().get(0).parts());
        assertTrue(req.messages().get(1).parts().isEmpty());
        assertEquals(Role.ASSISTANT, req.messages().get(1).role());
    }

    @Test
    void decodeToolMessageAcceptsTextParts() {
        String json = """
                {"model":"m","messages":[
                  {"role":"tool","tool_call_id":"call_1","content":[{"type":"text","text":"part one"},{"type":"text","text":"part two"}]}]}
                """;
        assertEquals(List.of(new ToolResultPart("call_1", "part onepart two", false)), decode(json).messages().get(0).parts());
    }

    // ========== §5.1 E + §4.4：图片 ==========

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
        String inputUrl = MAPPER.readTree(json).path("messages").get(0).path("content").get(0)
                .path("image_url").path("url").asString();
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
        String path = "messages[0].content[0].image_url.url";
        assertViolation(failure(imageRequest("user", "data:image/bmp;base64,QUJD", null)), INVALID, path);
        assertViolation(failure(imageRequest("user", "data:image/png;base64,@@@", null)), INVALID, path);
        assertViolation(failure(imageRequest("user", "data:image/png;base64,", null)), INVALID, path);
        assertViolation(failure(imageRequest("user", "data:image/png,QUJD", null)), INVALID, path);
        assertViolation(failure(imageRequest("user", "QUJD", null)), INVALID, path);
        assertViolation(failure(imageRequest("user", "data:image/png;base64,QUJD", "ultra")),
                INVALID, "messages[0].content[0].image_url.detail");
        for (String detail : List.of("low", "high", "auto", "original")) {
            assertEquals(detail, ((ImagePart) decode(imageRequest("user", "data:image/png;base64,QUJD", detail))
                    .messages().get(0).parts().get(0)).detail());
        }
    }

    @Test
    void decodeImageRejectsExternalUrlBecauseStage1IsInlineOnly() {
        assertViolation(failure(imageRequest("user", "https://example.com/a.png", null)),
                NOT_SUPPORTED, "messages[0].content[0].image_url.url");
    }

    @Test
    void decodeImageOnlyAllowedInUserMessages() {
        for (String role : List.of("system", "developer", "assistant", "tool")) {
            String json = imageRequest(role, "data:image/png;base64,QUJD", null);
            if (role.equals("tool")) {
                ObjectNode root = (ObjectNode) MAPPER.readTree(json);
                ((ObjectNode) root.path("messages").get(0)).put("tool_call_id", "call_1");
                json = MAPPER.writeValueAsString(root);
            }
            assertViolation(failure(json), INVALID, "messages[0].content[0]");
        }
    }

    @Test
    void decodeImageCountAboveUpstreamLimitIsRejected() {
        ObjectNode root = baseRequest();
        ArrayNode content = ((ObjectNode) root.path("messages").get(0)).putArray("content");
        for (int i = 0; i < 601; i++) {
            content.addObject().put("type", "image_url").putObject("image_url")
                    .put("url", "data:image/png;base64,QUJD");
        }
        assertViolation(failure(MAPPER.writeValueAsString(root)), INVALID, "messages");
    }

    @Test
    void decodeImageAbovePerImageByteCapIsRejected() {
        String urlPath = "messages[0].content[0].image_url.url";
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
        assertViolation(error, INVALID, "messages[0].content[0].image_url.url");
        assertEquals("inline image exceeds 32 MiB", error.violations().get(0).reason());
    }

    @Test
    void decodeTotalImageBytesAboveCapIsRejected() {
        long half = MAX_TOTAL_IMAGE_BYTES / 2;
        String atCap = inlineImage(half);
        // 两张各自合规的图片合计恰好 64 MiB：放行。
        assertEquals(2, decode(imagesRequest(atCap, atCap)).messages().get(0).parts().size());
        // 再加 1 字节 → 总量超限，字段路径归 messages。
        CapabilityException error = failure(imagesRequest(atCap, atCap, "data:image/png;base64,QQ=="));
        assertViolation(error, INVALID, "messages");
        assertEquals("inline images exceed 64 MiB in total", error.violations().get(0).reason());
    }

    @Test
    void decodeUnknownImagePartTypeIsNotSupported() {
        String json = """
                {"model":"m","messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"QUJD","format":"wav"}}]}]}
                """;
        assertViolation(failure(json), NOT_SUPPORTED, "messages[0].content[0]");
        String file = """
                {"model":"m","messages":[{"role":"user","content":[{"type":"file","file":{"file_id":"file-1"}}]}]}
                """;
        assertViolation(failure(file), NOT_SUPPORTED, "messages[0].content[0]");
    }

    // ========== §5.1 E：工具定义与 tool_choice ==========

    @Test
    void decodeToolsMapFunctionSpecs() {
        SemanticRequest req = decode(fixture("decode-tools.json"));
        assertEquals(2, req.tools().size());
        assertEquals("get_weather", req.tools().get(0).name());
        assertEquals("Get weather", req.tools().get(0).description());
        assertEquals(MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}"),
                MAPPER.readTree(req.tools().get(0).parametersJsonSchema()));
        assertEquals("ping", req.tools().get(1).name());
        assertNull(req.tools().get(1).description());
        assertNull(req.tools().get(1).parametersJsonSchema());
        assertEquals("auto", req.toolChoice());
    }

    @Test
    void decodeToolChoiceNoneAutoRequiredNamedAreEquivalentInNonThinkingMode() {
        assertEquals("none", decode(toolChoiceRequest("\"none\"", false)).toolChoice());
        assertEquals("auto", decode(toolChoiceRequest("\"auto\"", false)).toolChoice());
        assertEquals("required", decode(toolChoiceRequest("\"required\"", false)).toolChoice());
        assertEquals("tool:get_weather",
                decode(toolChoiceRequest("{\"type\":\"function\",\"function\":{\"name\":\"get_weather\"}}", false)).toolChoice());
    }

    @Test
    void decodeToolChoiceRequiredOrNamedInThinkingModeNeedsAuthorization() {
        for (String choice : List.of("\"required\"", "{\"type\":\"function\",\"function\":{\"name\":\"get_weather\"}}")) {
            assertViolation(failure(toolChoiceRequest(choice, true)), NOT_AUTHORIZED, "tool_choice");
            CapabilityContext granted = ctx(FORCE_AUTO);
            assertEquals("auto", codec.decode(toolChoiceRequest(choice, true), granted).toolChoice());
            assertEquals(1, granted.audit().applied().size());
            assertEquals("tool_choice", granted.audit().applied().get(0).fieldPath());
            assertEquals(FORCE_AUTO, granted.audit().applied().get(0).degradationId());
            assertFalse(granted.audit().applied().get(0).reason().isBlank());
        }
        assertEquals("none", decode(toolChoiceRequest("\"none\"", true)).toolChoice());
        assertEquals("auto", decode(toolChoiceRequest("\"auto\"", true)).toolChoice());
    }

    @Test
    void decodeToolChoiceAllowedToolsAndBadValuesAreRejected() {
        assertViolation(failure(toolChoiceRequest("{\"type\":\"allowed_tools\",\"tools\":[]}", false)),
                NOT_SUPPORTED, "tool_choice");
        assertViolation(failure(toolChoiceRequest("\"sometimes\"", false)), INVALID, "tool_choice");
        assertViolation(failure(toolChoiceRequest("{\"type\":\"function\",\"function\":{\"name\":\" \"}}", false)),
                INVALID, "tool_choice.function.name");
        assertViolation(failure(toolChoiceRequest("{\"type\":\"function\"}", false)), INVALID, "tool_choice.function");
    }

    @Test
    void decodeToolSpecGuardsNameSchemaAndStrict() {
        String badName = """
                {"model":"m","messages":[{"role":"user","content":"hi"}],
                 "tools":[{"type":"function","function":{"name":"get weather"}}]}
                """;
        assertViolation(failure(badName), INVALID, "tools[0].function.name");
        String longName = "a".repeat(129);
        assertViolation(failure(withField("tools", "[{\"type\":\"function\",\"function\":{\"name\":\"" + longName + "\"}}]")),
                INVALID, "tools[0].function.name");
        String duplicate = """
                {"model":"m","messages":[{"role":"user","content":"hi"}],
                 "tools":[{"type":"function","function":{"name":"ping"}},{"type":"function","function":{"name":"ping"}}]}
                """;
        assertViolation(failure(duplicate), INVALID, "tools[1].function.name");
        String badSchema = """
                {"model":"m","messages":[{"role":"user","content":"hi"}],
                 "tools":[{"type":"function","function":{"name":"ping","parameters":[]}}]}
                """;
        assertViolation(failure(badSchema), INVALID, "tools[0].function.parameters");
        String strict = """
                {"model":"m","messages":[{"role":"user","content":"hi"}],
                 "tools":[{"type":"function","function":{"name":"ping","strict":true}}]}
                """;
        assertViolation(failure(strict), NOT_SUPPORTED, "tools[0].function.strict");
        String notStrict = """
                {"model":"m","messages":[{"role":"user","content":"hi"}],
                 "tools":[{"type":"function","function":{"name":"ping","strict":false}}]}
                """;
        assertEquals("ping", decode(notStrict).tools().get(0).name());
        String custom = """
                {"model":"m","messages":[{"role":"user","content":"hi"}],"tools":[{"type":"custom","custom":{"name":"x"}}]}
                """;
        assertViolation(failure(custom), NOT_SUPPORTED, "tools[0]");
    }

    @Test
    void decodeAssistantToolCallGuardsIdTypeAndArguments() {
        String missingId = """
                {"model":"m","messages":[{"role":"assistant","tool_calls":[{"type":"function","function":{"name":"ping","arguments":"{}"}}]}]}
                """;
        assertViolation(failure(missingId), INVALID, "messages[0].tool_calls[0].id");
        String customType = """
                {"model":"m","messages":[{"role":"assistant","tool_calls":[{"id":"c","type":"custom","function":{"name":"ping"}}]}]}
                """;
        assertViolation(failure(customType), NOT_SUPPORTED, "messages[0].tool_calls[0].type");
        String objectArguments = """
                {"model":"m","messages":[{"role":"assistant","tool_calls":[{"id":"c","type":"function","function":{"name":"ping","arguments":{}}}]}]}
                """;
        assertViolation(failure(objectArguments), INVALID, "messages[0].tool_calls[0].function.arguments");
        String missingToolCallId = """
                {"model":"m","messages":[{"role":"tool","content":"{}"}]}
                """;
        assertViolation(failure(missingToolCallId), INVALID, "messages[0].tool_call_id");
    }

    // ========== §5.1 E/I：思考开关与采样 ==========

    @Test
    void decodeThinkingEffortDrivesModeAndSamplingClassification() {
        SemanticRequest thinking = decode(fixture("decode-thinking.json"));
        assertEquals(Boolean.TRUE, thinking.thinkingEnabled());
        assertEquals("high", thinking.thinkingEffort());
        assertNull(thinking.temperature(), "思考模式 temperature 属 I");
        assertEquals(0.97, thinking.topP().doubleValue());
        assertTrue(thinking.stream());

        ObjectNode nonThinking = baseRequest();
        nonThinking.put("reasoning_effort", "none");
        nonThinking.put("temperature", 0.2);
        nonThinking.put("top_p", 0.5);
        SemanticRequest req = decode(MAPPER.writeValueAsString(nonThinking));
        assertEquals(Boolean.FALSE, req.thinkingEnabled());
        assertEquals(0.2, req.temperature().doubleValue());
        assertNull(req.topP(), "非思考模式 top_p 属 I");

        ObjectNode defaulted = baseRequest();
        defaulted.put("temperature", 0.2);
        defaulted.put("top_p", 0.9);
        SemanticRequest implicit = decode(MAPPER.writeValueAsString(defaulted));
        assertNull(implicit.thinkingEnabled(), "省略 effort 时不伪造开关，交上游默认");
        assertNull(implicit.thinkingEffort());
        assertNull(implicit.temperature(), "上游默认思考模式 → temperature 属 I");
        assertEquals(0.9, implicit.topP().doubleValue());
    }

    @Test
    void decodeReasoningEffortAcceptsMatrixValuesOnly() {
        for (String effort : List.of("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")) {
            assertEquals(effort, decode(withField("reasoning_effort", "\"" + effort + "\"")).thinkingEffort());
        }
        assertViolation(failure(withField("reasoning_effort", "\"extreme\"")), INVALID, "reasoning_effort");
        assertViolation(failure(withField("reasoning_effort", "3")), INVALID, "reasoning_effort");
    }

    // ========== §5.1 D：legacy function / json_schema ==========

    @Test
    void decodeLegacyFunctionFieldsRequireExplicitAuthorization() {
        assertViolation(failure(fixture("decode-legacy.json")), NOT_AUTHORIZED, "messages[1].function_call");
        assertTrue(ctx().audit().applied().isEmpty());
    }

    @Test
    void decodeAuthorizedLegacyFunctionDegradesToToolCallsWithAudit() {
        CapabilityContext granted = ctx(LEGACY);
        SemanticRequest req = codec.decode(fixture("decode-legacy.json"), granted);

        assertEquals(1, req.tools().size());
        assertEquals("get_weather", req.tools().get(0).name());
        assertEquals("Get weather", req.tools().get(0).description());
        assertEquals(MAPPER.readTree("{\"type\":\"object\"}"), MAPPER.readTree(req.tools().get(0).parametersJsonSchema()));
        assertEquals("tool:get_weather", req.toolChoice());
        List<ContentPart> parts = req.messages().get(1).parts();
        assertEquals(1, parts.size());
        ToolCallPart call = assertInstanceOf(ToolCallPart.class, parts.get(0));
        assertEquals("get_weather", call.name());
        assertEquals("{\"city\":\"Paris\"}", call.argumentsJson());
        assertEquals("llmgw-legacy-1", call.id(), "legacy 形态无 id，降级时按消息下标合成稳定标识");
        assertEquals(List.of("messages[1].function_call", "functions", "function_call"),
                granted.audit().applied().stream().map(AppliedDegradation::fieldPath).toList());
        assertTrue(granted.audit().applied().stream().allMatch(a -> a.degradationId().equals(LEGACY)));
        assertTrue(granted.audit().applied().stream().allMatch(a -> !a.reason().isBlank()));
    }

    @Test
    void decodeLegacyFunctionCallStringFormsAndThinkingInteraction() {
        ObjectNode auto = (ObjectNode) MAPPER.readTree(fixture("decode-legacy.json"));
        auto.put("function_call", "auto");
        assertEquals("auto", codec.decode(MAPPER.writeValueAsString(auto), ctx(LEGACY)).toolChoice());
        auto.put("function_call", "none");
        assertEquals("none", codec.decode(MAPPER.writeValueAsString(auto), ctx(LEGACY)).toolChoice());

        ObjectNode thinking = (ObjectNode) MAPPER.readTree(fixture("decode-legacy.json"));
        thinking.put("reasoning_effort", "high");
        assertViolation(failure(MAPPER.writeValueAsString(thinking), ctx(LEGACY)), NOT_AUTHORIZED, "function_call");
        assertEquals("auto", codec.decode(MAPPER.writeValueAsString(thinking), ctx(LEGACY, FORCE_AUTO)).toolChoice());
    }

    @Test
    void decodeLegacyFunctionRoleIsRejectedEvenWhenAuthorized() {
        String json = """
                {"model":"m","messages":[{"role":"function","name":"get_weather","content":"{}"}]}
                """;
        assertViolation(failure(json, ctx(LEGACY)), NOT_SUPPORTED, "messages[0].role");
    }

    @Test
    void decodeLegacyAndModernToolFieldsConflict() {
        ObjectNode root = (ObjectNode) MAPPER.readTree(fixture("decode-legacy.json"));
        root.putArray("tools").addObject().put("type", "function").putObject("function").put("name", "ping");
        assertViolation(failure(MAPPER.writeValueAsString(root), ctx(LEGACY)), INVALID, "tools");
        ObjectNode choiceConflict = (ObjectNode) MAPPER.readTree(fixture("decode-legacy.json"));
        choiceConflict.put("tool_choice", "auto");
        assertViolation(failure(MAPPER.writeValueAsString(choiceConflict), ctx(LEGACY)), INVALID, "tool_choice");
    }

    @Test
    void decodeResponseFormatTextAndJsonObjectAreEquivalent() {
        assertEquals("text", decode(withField("response_format", "{\"type\":\"text\"}")).responseFormat());
        assertEquals("json_object", decode(withField("response_format", "{\"type\":\"json_object\"}")).responseFormat());
        assertViolation(failure(withField("response_format", "{\"type\":\"bogus\"}")), INVALID, "response_format.type");
        assertViolation(failure(withField("response_format", "{}")), INVALID, "response_format.type");
    }

    @Test
    void decodeJsonSchemaResponseFormatNeedsAuthorization() {
        assertViolation(failure(fixture("decode-schema-format.json")), NOT_AUTHORIZED, "response_format");
        CapabilityContext granted = ctx(SCHEMA_TO_OBJECT);
        assertEquals("json_object", codec.decode(fixture("decode-schema-format.json"), granted).responseFormat());
        assertEquals(List.of("response_format"),
                granted.audit().applied().stream().map(AppliedDegradation::fieldPath).toList());
        assertEquals(SCHEMA_TO_OBJECT, granted.audit().applied().get(0).degradationId());
    }

    // ========== 入口能力守卫 ==========

    @Test
    void decodeMaxTokensRangeAndAliasAgreement() {
        assertEquals(256, decode(withField("max_tokens", "256")).maxOutputTokens().intValue());
        assertEquals(128, decode(withField("max_completion_tokens", "128")).maxOutputTokens().intValue());
        assertEquals(393216, decode(withField("max_tokens", "393216")).maxOutputTokens().intValue());
        assertEquals(1, decode(withField("max_tokens", "1")).maxOutputTokens().intValue());
        ObjectNode both = baseRequest();
        both.put("max_tokens", 256);
        both.put("max_completion_tokens", 256);
        assertEquals(256, decode(MAPPER.writeValueAsString(both)).maxOutputTokens().intValue());
        ObjectNode conflict = baseRequest();
        conflict.put("max_tokens", 256);
        conflict.put("max_completion_tokens", 128);
        assertViolation(failure(MAPPER.writeValueAsString(conflict)), INVALID, "max_tokens");
        for (String bad : List.of("0", "-1", "393217", "\"256\"", "1.5")) {
            assertViolation(failure(withField("max_tokens", bad)), INVALID, "max_tokens");
        }
        assertNull(decode(fixture("decode-image.json")).maxOutputTokens());
    }

    @Test
    void decodeStopAcceptsStringOrArrayUpToSixteen() {
        assertEquals(List.of("END"), decode(withField("stop", "\"END\"")).stop());
        assertEquals(List.of("a", "b"), decode(withField("stop", "[\"a\",\"b\"]")).stop());
        assertTrue(decode(withField("stop", "[]")).stop().isEmpty());
        assertTrue(decode(withField("stop", "null")).stop().isEmpty());
        ArrayNode many = MAPPER.createArrayNode();
        for (int i = 0; i < 16; i++) {
            many.add("s" + i);
        }
        assertEquals(16, decode(withField("stop", many)).stop().size());
        many.add("s16");
        assertViolation(failure(withField("stop", many)), INVALID, "stop");
        assertViolation(failure(withField("stop", "[1]")), INVALID, "stop[0]");
    }

    @Test
    void decodeUserIdNormalizesUserAndSafetyIdentifierWithByteLimit() {
        assertEquals("u-1", decode(withField("user", "\"u-1\"")).userId());
        assertEquals("s-1", decode(withField("safety_identifier", "\"s-1\"")).userId());
        ObjectNode both = baseRequest();
        both.put("user", "same");
        both.put("safety_identifier", "same");
        assertEquals("same", decode(MAPPER.writeValueAsString(both)).userId());
        ObjectNode conflict = baseRequest();
        conflict.put("user", "a");
        conflict.put("safety_identifier", "b");
        assertViolation(failure(MAPPER.writeValueAsString(conflict)), INVALID, "safety_identifier");
        assertEquals("x".repeat(512), decode(withField("user", "\"" + "x".repeat(512) + "\"")).userId());
        assertViolation(failure(withField("user", "\"" + "x".repeat(513) + "\"")), INVALID, "user");
        assertViolation(failure(withField("user", "\"" + "中".repeat(200) + "\"")), INVALID, "user");
        assertViolation(failure(withField("user", "7")), INVALID, "user");
    }

    @Test
    void decodeStreamOptionsRequireStreamAndKnownKeys() {
        ObjectNode stream = baseRequest();
        stream.put("stream", true);
        stream.putObject("stream_options").put("include_usage", true);
        assertTrue(decode(MAPPER.writeValueAsString(stream)).stream());
        ObjectNode noStream = baseRequest();
        noStream.putObject("stream_options").put("include_usage", true);
        assertViolation(failure(MAPPER.writeValueAsString(noStream)), INVALID, "stream_options");
        ObjectNode unknownKey = baseRequest();
        unknownKey.put("stream", true);
        unknownKey.putObject("stream_options").put("bogus", true);
        assertViolation(failure(MAPPER.writeValueAsString(unknownKey)), UNKNOWN, "stream_options.bogus");
        assertViolation(failure(withField("stream", "\"yes\"")), INVALID, "stream");
        assertFalse(decode(fixture("decode-image.json")).stream());
    }

    @Test
    void decodeStreamOptionsCarriesIncludeUsageLosslessly() {
        assertEquals(Boolean.FALSE, decode(streamRequest(false)).includeUsage(), "false 必须原样承载，不得丢成 true");
        assertEquals(Boolean.TRUE, decode(streamRequest(true)).includeUsage());
        assertNull(decode(streamRequest(null)).includeUsage(), "缺 stream_options＝未指定");
        assertNull(decode(MAPPER.writeValueAsString(baseRequest())).includeUsage());
        ObjectNode emptyOptions = baseRequest();
        emptyOptions.put("stream", true);
        emptyOptions.putObject("stream_options");
        assertNull(decode(MAPPER.writeValueAsString(emptyOptions)).includeUsage(), "缺 include_usage 键＝未指定");
        // 承载真实值后，stream=true 守卫仍必须前置 400。
        ObjectNode noStream = baseRequest();
        noStream.putObject("stream_options").put("include_usage", false);
        assertViolation(failure(MAPPER.writeValueAsString(noStream)), INVALID, "stream_options");
    }

    @Test
    void streamUsageChunkFollowsTheDecodedIncludeUsagePreference() {
        SemanticRequest declined = decode(streamRequest(false));
        assertEquals(Boolean.FALSE, declined.includeUsage());
        List<String> declinedLines = streamOf(declined);
        assertEquals(3, declinedLines.size(), "内容帧 + 终态帧 + [DONE]，无 usage-only chunk");
        assertEquals("hi", payload(declinedLines.get(0)).path("choices").get(0).path("delta").path("content").asString());
        assertEquals("stop", payload(declinedLines.get(1)).path("choices").get(0).path("finish_reason").asString());
        assertEquals("data: [DONE]", declinedLines.get(2));
        for (String line : declinedLines) {
            if (!"data: [DONE]".equals(line)) {
                assertFalse(payload(line).has("usage"), "include_usage=false 不得回 usage: " + line);
            }
        }

        SemanticRequest requested = decode(streamRequest(true));
        assertEquals(Boolean.TRUE, requested.includeUsage());
        List<String> requestedLines = streamOf(requested);
        assertEquals(4, requestedLines.size(), "include_usage=true 必须补独立 usage-only chunk");
        assertEquals(0, payload(requestedLines.get(2)).path("choices").size());
        assertEquals(fixtureJson("expected-usage-full.json"), payload(requestedLines.get(2)).path("usage"));
        assertEquals("data: [DONE]", requestedLines.get(3));
        assertEquals(1, roleFrames(requestedLines));

        SemanticRequest unspecified = decode(streamRequest(null));
        assertNull(unspecified.includeUsage());
        assertEquals(3, streamOf(unspecified).size(), "未指定＝不请求 usage");
    }

    /** 用解码结果驱动编码器（而非手搓布尔），确保 decode→语义合同→encode 全链路不丢 usage 偏好。 */
    private List<String> streamOf(SemanticRequest request) {
        StreamEncoder encoder = codec.newStreamEncoder(request.model(), Boolean.TRUE.equals(request.includeUsage()), ctx());
        List<String> lines = new ArrayList<>(encoder.onEvent(new TextDelta("hi")));
        assertTrue(encoder.onEvent(new UsageEvent(new Usage(120L, 40L, 12L, 80L))).isEmpty());
        lines.addAll(encoder.onEvent(new TerminalEvent(StopReason.COMPLETED)));
        lines.addAll(encoder.onComplete());
        return lines;
    }

    @Test
    void decodeChoiceCountOnlyOneIsExpressible() {
        assertEquals("client-model", decode(withField("n", "1")).model());
        assertViolation(failure(withField("n", "2")), NOT_SUPPORTED, "n");
        assertViolation(failure(withField("n", "0")), INVALID, "n");
        assertViolation(failure(withField("n", "\"1\"")), INVALID, "n");
    }

    @Test
    void decodeRequiresModelAndMessages() {
        ObjectNode noModel = baseRequest();
        noModel.remove("model");
        assertViolation(failure(MAPPER.writeValueAsString(noModel)), INVALID, "model");
        ObjectNode noMessages = baseRequest();
        noMessages.remove("messages");
        assertViolation(failure(MAPPER.writeValueAsString(noMessages)), INVALID, "messages");
        assertViolation(failure(withField("messages", "[]")), INVALID, "messages");
        assertViolation(failure(withField("messages", "{}")), INVALID, "messages");
        assertViolation(failure(withField("model", "7")), INVALID, "model");
    }

    @Test
    void decodeMalformedBodyIsInvalidValue() {
        for (String body : List.of("not json", "[]", "", "{")) {
            assertViolation(failure(body), INVALID, "request");
        }
        assertViolation(failure(null), INVALID, "request");
    }

    @Test
    void decodeMalformedJsonPreservesCauseAndKeepsClientReasonConcise() {
        CapabilityException error = failure("{\"model\":\"m\",\"messages\":[");
        assertViolation(error, INVALID, "request");
        String reason = error.violations().get(0).reason();
        assertEquals("invalid request JSON", reason);
        assertFalse(reason.contains("[Source:"), "不得把 Jackson 源位置 dump 泄露给客户端: " + reason);
        assertFalse(reason.contains("\n"), "客户端原因必须单行: " + reason);
        assertNotNull(error.getCause(), "解析异常必须保留 cause，不得吞栈");
        assertInstanceOf(JacksonException.class, error.getCause());
    }

    @Test
    void decodeAssistantRefusalAndAudioAreNotSupported() {
        assertViolation(failure(withField("messages",
                        "[{\"role\":\"assistant\",\"refusal\":\"no\"}]")),
                NOT_SUPPORTED, "messages[0].refusal");
        assertViolation(failure(withField("messages",
                        "[{\"role\":\"assistant\",\"audio\":{\"id\":\"audio_1\"}}]")),
                NOT_SUPPORTED, "messages[0].audio");
    }

    @Test
    void decodeUnknownRoleIsInvalidValue() {
        assertViolation(failure(withField("messages", "[{\"role\":\"bogus\",\"content\":\"hi\"}]")),
                INVALID, "messages[0].role");
        assertViolation(failure(withField("messages", "[{\"content\":\"hi\"}]")), INVALID, "messages[0].role");
    }

    // ========== §5.1 I / R / 未知字段全量扫 ==========

    @Test
    void ignorableFieldsAreDroppedWithoutSemanticTrace() {
        JsonNode samples = fixtureJson("ignore-fields.json");
        samples.properties().forEach(entry -> {
            SemanticRequest req = decode(withField(entry.getKey(), entry.getValue()));
            assertEquals("client-model", req.model(), entry.getKey());
            assertNull(req.temperature(), entry.getKey());
            assertNull(req.topP(), entry.getKey());
            assertTrue(req.tools().isEmpty(), entry.getKey());
        });
        ObjectNode all = baseRequest();
        samples.properties().forEach(entry -> all.set(entry.getKey(), entry.getValue()));
        CapabilityContext context = ctx();
        SemanticRequest req = codec.decode(MAPPER.writeValueAsString(all), context);
        assertNull(req.temperature());
        assertTrue(context.audit().applied().isEmpty(), "I 类不得留降级痕迹");
    }

    @Test
    void rejectFieldsFailWithNotSupportedAndFieldPath() {
        fixtureJson("reject-fields.json").properties().forEach(entry -> {
            CapabilityException error = failure(withField(entry.getKey(), entry.getValue()));
            assertViolation(error, NOT_SUPPORTED, entry.getKey());
        });
    }

    @Test
    void unknownFieldsFailWithUnknownFieldCode() {
        fixtureJson("unknown-fields.json").properties().forEach(entry ->
                assertViolation(failure(withField(entry.getKey(), entry.getValue())), UNKNOWN, entry.getKey()));
    }

    @Test
    void unknownNestedFieldsFailWithTheirFullPath() {
        assertViolation(failure(withField("messages", "[{\"role\":\"user\",\"content\":\"hi\",\"bogus\":1}]")),
                UNKNOWN, "messages[0].bogus");
        assertViolation(failure(withField("tools", "[{\"type\":\"function\",\"function\":{\"name\":\"ping\",\"bogus\":1}}]")),
                UNKNOWN, "tools[0].function.bogus");
        assertViolation(failure(withField("response_format", "{\"type\":\"text\",\"bogus\":1}")),
                UNKNOWN, "response_format.bogus");
        // 矩阵未分类的消息级 name（参与者名）不得猜测映射，按未知字段拒绝。
        assertViolation(failure(withField("messages", "[{\"role\":\"user\",\"content\":\"hi\",\"name\":\"alice\"}]")),
                UNKNOWN, "messages[0].name");
        String image = imageRequest("user", "data:image/png;base64,QUJD", null);
        ObjectNode root = (ObjectNode) MAPPER.readTree(image);
        ((ObjectNode) root.path("messages").get(0).path("content").get(0).path("image_url")).put("bogus", 1);
        assertViolation(failure(MAPPER.writeValueAsString(root)), UNKNOWN, "messages[0].content[0].image_url.bogus");
    }

    @Test
    void perRequestAuditIsolationOnSharedCodec() {
        CapabilityContext granted = ctx(LEGACY);
        CapabilityContext denied = ctx();
        codec.decode(fixture("decode-legacy.json"), granted);
        assertEquals(3, granted.audit().applied().size());
        assertTrue(denied.audit().applied().isEmpty());
        assertViolation(failure(fixture("decode-legacy.json"), denied), NOT_AUTHORIZED, "messages[1].function_call");
        assertTrue(denied.audit().applied().isEmpty());
        assertEquals(3, granted.audit().applied().size(), "另一个请求不得污染已留痕的实例");
    }

    // ========== encodeResponse：choices/finish_reason/usage ==========

    @Test
    void encodeTextResponseRestoresChatCompletionShape() {
        SemanticResponse resp = new SemanticResponse("chatcmpl-1",
                List.of(new SemanticMessage(Role.ASSISTANT,
                        List.of(new ThinkingPart("because", null), new TextPart("hello world")))),
                StopReason.COMPLETED, new Usage(120L, 40L, 12L, 80L));
        JsonNode json = MAPPER.readTree(codec.encodeResponse(resp, "client-model", ctx()));

        assertEquals(Set.of("id", "object", "created", "model", "choices", "usage"), names(json));
        assertEquals("chat.completion", json.path("object").asString());
        assertEquals("chatcmpl-1", json.path("id").asString());
        assertTrue(json.path("created").asLong() > 0);
        assertEquals("client-model", json.path("model").asString());
        assertEquals(1, json.path("choices").size());
        JsonNode choice = json.path("choices").get(0);
        assertEquals(0, choice.path("index").asInt());
        assertEquals("stop", choice.path("finish_reason").asString());
        assertEquals(fixtureJson("expected-message-text.json"), choice.path("message"));
        assertEquals(fixtureJson("expected-usage-full.json"), json.path("usage"));
    }

    @Test
    void encodeToolCallResponseOmitsUnknownModelAndUsage() {
        SemanticResponse resp = new SemanticResponse("chatcmpl-2",
                List.of(new SemanticMessage(Role.ASSISTANT,
                        List.of(new ToolCallPart("call_1", "get_weather", "{\"city\":\"Paris\"}")))),
                StopReason.TOOL_CALL, null);
        JsonNode json = MAPPER.readTree(codec.encodeResponse(resp, null, ctx()));

        assertFalse(json.has("model"), "入口 model 未知时省略，不伪造上游模型");
        assertFalse(json.has("usage"), "usage 未知时省略，未知≠0");
        assertEquals("tool_calls", json.path("choices").get(0).path("finish_reason").asString());
        assertEquals(fixtureJson("expected-message-tools.json"), json.path("choices").get(0).path("message"));
    }

    @Test
    void encodeFinishReasonCoversEveryExpressibleStopReason() {
        Map<StopReason, String> expected = Map.of(
                StopReason.COMPLETED, "stop",
                StopReason.LENGTH, "length",
                StopReason.TOOL_CALL, "tool_calls",
                StopReason.CONTENT_FILTER, "content_filter");
        expected.forEach((stop, wire) -> {
            SemanticResponse resp = new SemanticResponse("id",
                    List.of(new SemanticMessage(Role.ASSISTANT, List.of(new TextPart("x")))), stop, null);
            assertEquals(wire, MAPPER.readTree(codec.encodeResponse(resp, null, ctx())).path("choices").get(0)
                    .path("finish_reason").asString());
        });
        SemanticResponse unknown = new SemanticResponse("id",
                List.of(new SemanticMessage(Role.ASSISTANT, List.of(new TextPart("x")))), StopReason.UNKNOWN, null);
        UpstreamException error = assertThrows(UpstreamException.class, () -> codec.encodeResponse(unknown, null, ctx()));
        assertEquals("upstream_error", error.code());
        assertEquals(502, error.status());
    }

    @Test
    void encodeSingleChoiceOnlyRejectsExtraMessages() {
        SemanticResponse resp = new SemanticResponse("id", List.of(
                new SemanticMessage(Role.ASSISTANT, List.of(new TextPart("first"))),
                new SemanticMessage(Role.ASSISTANT, List.of(new TextPart("second")))),
                StopReason.COMPLETED, null);
        UpstreamException error = assertThrows(UpstreamException.class, () -> codec.encodeResponse(resp, null, ctx()));
        assertEquals("upstream_error", error.code());
        assertEquals(502, error.status());
    }

    @Test
    void encodeResponseRejectsNonAssistantRoleInsteadOfRewritingIt() {
        for (Role role : List.of(Role.USER, Role.SYSTEM, Role.TOOL)) {
            SemanticResponse resp = new SemanticResponse("id",
                    List.of(new SemanticMessage(role, List.of(new TextPart("not an assistant turn")))),
                    StopReason.COMPLETED, null);
            UpstreamException error = assertThrows(UpstreamException.class, () -> codec.encodeResponse(resp, "m", ctx()),
                    "role=" + role);
            assertEquals("upstream_error", error.code(), "role=" + role);
            assertEquals(502, error.status(), "role=" + role);
            assertEquals("choices[0].message.role", error.fieldPath(), "role=" + role);
            assertTrue(error.getMessage().contains(role.name()), error.getMessage());
        }
    }

    @Test
    void encodeUsageOmitsUnknownFieldsAndNeverInventsZero() {
        assertEquals(fixtureJson("expected-usage-partial.json"), usageOf(new Usage(null, 40L, null, null)));
        assertEquals(MAPPER.readTree("{\"prompt_tokens\":100}"), usageOf(new Usage(100L, null, null, null)),
                "输出未知时 total_tokens 必须缺失，不得填 0");
        assertEquals(fixtureJson("expected-usage-no-details.json"), usageOf(new Usage(100L, 20L, null, null)));
        assertEquals(MAPPER.readTree("{\"prompt_tokens_details\":{\"cached_tokens\":80}}"),
                usageOf(new Usage(null, null, null, 80L)));
        assertEquals(MAPPER.readTree("{\"completion_tokens_details\":{\"reasoning_tokens\":12}}"),
                usageOf(new Usage(null, null, 12L, null)));
        assertEquals(MAPPER.readTree("{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}"),
                usageOf(new Usage(0L, 0L, null, null)), "实测 0 与未知不同，必须保留");
        SemanticResponse allUnknown = new SemanticResponse("id",
                List.of(new SemanticMessage(Role.ASSISTANT, List.of(new TextPart("x")))),
                StopReason.COMPLETED, new Usage(null, null, null, null));
        assertFalse(MAPPER.readTree(codec.encodeResponse(allUnknown, null, ctx())).has("usage"));
    }

    private JsonNode usageOf(Usage usage) {
        SemanticResponse resp = new SemanticResponse("id",
                List.of(new SemanticMessage(Role.ASSISTANT, List.of(new TextPart("x")))), StopReason.COMPLETED, usage);
        return MAPPER.readTree(codec.encodeResponse(resp, null, ctx())).path("usage");
    }

    @Test
    void responseRoundTripPreservesSemantics() {
        SemanticResponse original = new SemanticResponse("chatcmpl-rt",
                List.of(new SemanticMessage(Role.ASSISTANT, List.of(
                        new ThinkingPart("reason", null),
                        new TextPart("answer"),
                        new ToolCallPart("call_9", "ping", "{}")))),
                StopReason.TOOL_CALL, new Usage(10L, 20L, 3L, 4L));
        JsonNode json = MAPPER.readTree(codec.encodeResponse(original, "client-model", ctx()));
        assertEquals(original, readResponse(json));
    }

    /** 测试侧独立解析：证明入口 JSON 未丢语义（不依赖被测编码器的内部结构）。 */
    private static SemanticResponse readResponse(JsonNode json) {
        JsonNode choice = json.path("choices").get(0);
        JsonNode message = choice.path("message");
        List<ContentPart> parts = new ArrayList<>();
        if (message.hasNonNull("reasoning_content")) {
            parts.add(new ThinkingPart(message.path("reasoning_content").asString(), null));
        }
        if (message.hasNonNull("content")) {
            parts.add(new TextPart(message.path("content").asString()));
        }
        for (JsonNode call : message.path("tool_calls")) {
            parts.add(new ToolCallPart(call.path("id").asString(), call.path("function").path("name").asString(),
                    call.path("function").path("arguments").asString()));
        }
        StopReason stop = switch (choice.path("finish_reason").asString()) {
            case "stop" -> StopReason.COMPLETED;
            case "length" -> StopReason.LENGTH;
            case "tool_calls" -> StopReason.TOOL_CALL;
            case "content_filter" -> StopReason.CONTENT_FILTER;
            default -> StopReason.UNKNOWN;
        };
        JsonNode usage = json.path("usage");
        Usage parsed = usage.isMissingNode() ? null : new Usage(
                longOrNull(usage.path("prompt_tokens")),
                longOrNull(usage.path("completion_tokens")),
                longOrNull(usage.path("completion_tokens_details").path("reasoning_tokens")),
                longOrNull(usage.path("prompt_tokens_details").path("cached_tokens")));
        return new SemanticResponse(json.path("id").asString(),
                List.of(new SemanticMessage(Role.ASSISTANT, parts)), stop, parsed);
    }

    private static Long longOrNull(JsonNode node) {
        return node.isNumber() ? node.asLong() : null;
    }

    // ========== §6.2 SSE ==========

    @Test
    void streamEmitsRoleOnceThenDeltasFinishUsageAndDoneOnce() {
        StreamEncoder encoder = codec.newStreamEncoder("client-model", true, ctx());
        assertFalse(encoder.terminalSeen());

        List<String> firstLines = encoder.onEvent(new TextDelta("Hel"));
        JsonNode firstChunk = payload(firstLines.get(0));
        assertEquals("client-model", firstChunk.path("model").asString());
        assertTrue(firstChunk.path("choices").get(0).path("finish_reason").isNull());
        JsonNode first = firstDelta(firstLines);
        assertEquals("assistant", first.path("role").asString());
        assertEquals("Hel", first.path("content").asString());

        JsonNode second = firstDelta(encoder.onEvent(new TextDelta("lo")));
        assertEquals("lo", second.path("content").asString());
        assertFalse(second.has("role"), "role 只在首帧出现一次");

        assertTrue(encoder.onEvent(new UsageEvent(new Usage(120L, 40L, 12L, 80L))).isEmpty(),
                "usage 必须缓冲到终态帧之后");
        assertFalse(encoder.terminalSeen());

        List<String> terminal = encoder.onEvent(new TerminalEvent(StopReason.COMPLETED));
        assertEquals(2, terminal.size());
        JsonNode finish = payload(terminal.get(0));
        assertEquals("stop", finish.path("choices").get(0).path("finish_reason").asString());
        assertEquals(0, finish.path("choices").get(0).path("delta").size());
        assertFalse(finish.has("usage"), "终态帧不携带 usage");
        JsonNode usageChunk = payload(terminal.get(1));
        assertEquals(0, usageChunk.path("choices").size());
        assertEquals(fixtureJson("expected-usage-full.json"), usageChunk.path("usage"));
        assertTrue(encoder.terminalSeen());

        assertTrue(encoder.onEvent(new TextDelta("late")).isEmpty(), "终态后的事件被忽略");
        assertEquals(List.of("data: [DONE]"), encoder.onComplete());
        assertTrue(encoder.onComplete().isEmpty(), "[DONE] 只发一次");
    }

    @Test
    void streamMapsThinkingAndToolCallDeltasByIndex() {
        StreamEncoder encoder = codec.newStreamEncoder(null, false, ctx());
        JsonNode thinking = firstDelta(encoder.onEvent(new ThinkingDelta("hmm")));
        assertEquals("assistant", thinking.path("role").asString());
        assertEquals("hmm", thinking.path("reasoning_content").asString());
        assertFalse(thinking.has("content"), "思考增量不混入文本");

        JsonNode call = firstDelta(encoder.onEvent(new ToolCallDelta(0, "call_1", "get_weather", "{\"city\":")))
                .path("tool_calls").get(0);
        assertEquals(0, call.path("index").asInt());
        assertEquals("call_1", call.path("id").asString());
        assertEquals("function", call.path("type").asString());
        assertEquals("get_weather", call.path("function").path("name").asString());
        assertEquals("{\"city\":", call.path("function").path("arguments").asString());

        JsonNode continued = firstDelta(encoder.onEvent(new ToolCallDelta(0, null, null, "\"Paris\"}")))
                .path("tool_calls").get(0);
        assertEquals(0, continued.path("index").asInt());
        assertFalse(continued.has("id"));
        assertFalse(continued.has("type"));
        assertFalse(continued.path("function").has("name"));
        assertEquals("\"Paris\"}", continued.path("function").path("arguments").asString());

        JsonNode secondCall = firstDelta(encoder.onEvent(new ToolCallDelta(1, "call_2", "ping", null)))
                .path("tool_calls").get(0);
        assertEquals(1, secondCall.path("index").asInt());
        assertEquals("call_2", secondCall.path("id").asString());
        assertFalse(secondCall.path("function").has("arguments"));

        List<String> finish = encoder.onEvent(new TerminalEvent(StopReason.TOOL_CALL));
        assertEquals(1, finish.size(), "include_usage=false 不发 usage-only chunk");
        assertEquals("tool_calls", payload(finish.get(0)).path("choices").get(0).path("finish_reason").asString());
        assertEquals(List.of("data: [DONE]"), encoder.onComplete());
    }

    @Test
    void streamOmitsUsageChunkWhenUsageUnknownOrNotRequested() {
        StreamEncoder withoutUsage = codec.newStreamEncoder("m", true, ctx());
        withoutUsage.onEvent(new TextDelta("x"));
        assertEquals(1, withoutUsage.onEvent(new TerminalEvent(StopReason.COMPLETED)).size());

        StreamEncoder allNull = codec.newStreamEncoder("m", true, ctx());
        allNull.onEvent(new UsageEvent(new Usage(null, null, null, null)));
        assertEquals(1, allNull.onEvent(new TerminalEvent(StopReason.COMPLETED)).size(), "未知 usage 不得发 0");

        StreamEncoder partial = codec.newStreamEncoder("m", true, ctx());
        partial.onEvent(new UsageEvent(new Usage(null, 40L, null, null)));
        List<String> terminal = partial.onEvent(new TerminalEvent(StopReason.LENGTH));
        assertEquals(2, terminal.size());
        assertEquals("length", payload(terminal.get(0)).path("choices").get(0).path("finish_reason").asString());
        assertEquals(fixtureJson("expected-usage-partial.json"), payload(terminal.get(1)).path("usage"));
    }

    @Test
    void streamTerminalOnlyStillAnnouncesAssistantRoleExactlyOnce() {
        StreamEncoder encoder = codec.newStreamEncoder("client-model", true, ctx());
        List<String> lines = new ArrayList<>(encoder.onEvent(new TerminalEvent(StopReason.CONTENT_FILTER)));
        assertEquals(1, lines.size(), "usage 未知时终态只有一帧");
        JsonNode choice = payload(lines.get(0)).path("choices").get(0);
        assertEquals("content_filter", choice.path("finish_reason").asString());
        assertEquals("assistant", choice.path("delta").path("role").asString(), "只有终态帧也必须声明 role");
        assertEquals(1, choice.path("delta").size(), "终态帧只补 role，不伪造内容");
        assertTrue(encoder.terminalSeen());
        lines.addAll(encoder.onComplete());
        assertEquals("data: [DONE]", lines.get(lines.size() - 1));
        assertEquals(1, roleFrames(lines), "role 整条流恰好一次");
    }

    @Test
    void streamTerminalOnlyWithUsageKeepsRoleOnceAndUsageChunkLast() {
        StreamEncoder encoder = codec.newStreamEncoder("client-model", true, ctx());
        assertTrue(encoder.onEvent(new UsageEvent(new Usage(120L, 40L, 12L, 80L))).isEmpty());
        List<String> lines = new ArrayList<>(encoder.onEvent(new TerminalEvent(StopReason.COMPLETED)));
        assertEquals(2, lines.size());
        JsonNode terminal = payload(lines.get(0)).path("choices").get(0);
        assertEquals("stop", terminal.path("finish_reason").asString());
        assertEquals("assistant", terminal.path("delta").path("role").asString());
        assertEquals(0, payload(lines.get(1)).path("choices").size());
        assertEquals(fixtureJson("expected-usage-full.json"), payload(lines.get(1)).path("usage"));
        lines.addAll(encoder.onComplete());
        assertEquals(1, roleFrames(lines), "usage-only chunk 不得重复 role");
    }

    @Test
    void streamErrorAfterHeadersTerminatesWithoutDone() {
        StreamEncoder encoder = codec.newStreamEncoder("m", true, ctx());
        encoder.onEvent(new TextDelta("partial"));
        List<String> error = encoder.onError("upstream_error", "upstream terminated abnormally: finish_reason=aborted");
        assertEquals(1, error.size());
        JsonNode body = payload(error.get(0)).path("error");
        assertEquals(Set.of("message", "type", "code", "param"), names(body));
        assertEquals("upstream_error", body.path("code").asString());
        assertEquals("server_error", body.path("type").asString());
        assertEquals("upstream terminated abnormally: finish_reason=aborted", body.path("message").asString());
        assertTrue(body.path("param").isNull());
        assertTrue(encoder.terminalSeen());
        assertTrue(encoder.onComplete().isEmpty(), "错误终止后不得发 [DONE] 伪造成功");
        assertTrue(encoder.onError("upstream_error", "again").isEmpty(), "错误帧只发一次");
        assertTrue(encoder.onEvent(new TerminalEvent(StopReason.COMPLETED)).isEmpty());
    }

    @Test
    void streamErrorWithBlankCodeAndMessageStillEmitsWellFormedErrorFrame() {
        StreamEncoder encoder = codec.newStreamEncoder("m", true, ctx());
        encoder.onEvent(new TextDelta("partial"));
        List<String> error = encoder.onError("", "");
        assertEquals(1, error.size(), "空 code/message 不得逃逸成 500，必须落错误帧");
        JsonNode body = payload(error.get(0)).path("error");
        assertEquals(Set.of("message", "type", "code", "param"), names(body));
        assertEquals("internal_error", body.path("code").asString());
        assertEquals("server_error", body.path("type").asString());
        assertEquals("stream failed without an error detail", body.path("message").asString());
        assertTrue(body.path("param").isNull());
        assertTrue(encoder.terminalSeen());
        assertTrue(encoder.onComplete().isEmpty(), "错误终止后不得发 [DONE] 伪造成功");
        assertTrue(encoder.onError("upstream_error", "again").isEmpty(), "错误帧只发一次");
        assertTrue(encoder.onEvent(new TerminalEvent(StopReason.COMPLETED)).isEmpty());
    }

    @Test
    void streamErrorFillsOnlyTheBlankHalfAndKeepsTheKnownHalf() {
        StreamEncoder nulls = codec.newStreamEncoder(null, false, ctx());
        JsonNode bothNull = payload(nulls.onError(null, null).get(0)).path("error");
        assertEquals("internal_error", bothNull.path("code").asString());
        assertEquals("stream failed without an error detail", bothNull.path("message").asString());

        StreamEncoder blankMessage = codec.newStreamEncoder(null, false, ctx());
        JsonNode knownCode = payload(blankMessage.onError("upstream_rate_limited", "   ").get(0)).path("error");
        assertEquals("upstream_rate_limited", knownCode.path("code").asString(), "已知 code 不得被兜底覆盖");
        assertEquals("rate_limit_error", knownCode.path("type").asString());
        assertEquals("stream failed without an error detail", knownCode.path("message").asString());

        StreamEncoder blankCode = codec.newStreamEncoder(null, false, ctx());
        JsonNode knownMessage = payload(blankCode.onError("  ", "upstream went away").get(0)).path("error");
        assertEquals("internal_error", knownMessage.path("code").asString());
        assertEquals("upstream went away", knownMessage.path("message").asString(), "已知 message 不得被兜底覆盖");
    }

    @Test
    void streamCompleteWithoutTerminalNeverFabricatesSuccess() {
        StreamEncoder encoder = codec.newStreamEncoder(null, false, ctx());
        encoder.onEvent(new TextDelta("x"));
        assertThrows(IllegalStateException.class, encoder::onComplete);
        assertFalse(encoder.terminalSeen());
        assertTrue(encoder.onEvent(new TextDelta("y")).size() > 0, "未终止前仍可继续");
    }

    @Test
    void streamUnknownStopReasonBecomesInBandError() {
        StreamEncoder encoder = codec.newStreamEncoder("m", true, ctx());
        List<String> lines = encoder.onEvent(new TerminalEvent(StopReason.UNKNOWN));
        assertEquals(1, lines.size());
        assertEquals("upstream_error", payload(lines.get(0)).path("error").path("code").asString());
        assertTrue(encoder.terminalSeen());
        assertTrue(encoder.onComplete().isEmpty());
    }

    @Test
    void streamEncoderInstancesAreIsolated() {
        StreamEncoder first = codec.newStreamEncoder("m", false, ctx());
        StreamEncoder second = codec.newStreamEncoder("m", false, ctx());
        assertEquals("assistant", firstDelta(first.onEvent(new TextDelta("a"))).path("role").asString());
        assertEquals("assistant", firstDelta(second.onEvent(new TextDelta("b"))).path("role").asString());
        first.onEvent(new TerminalEvent(StopReason.COMPLETED));
        assertTrue(first.terminalSeen());
        assertFalse(second.terminalSeen());
        assertEquals(List.of("data: [DONE]"), first.onComplete());
        assertFalse(second.onEvent(new TextDelta("c")).isEmpty());
        second.onEvent(new TerminalEvent(StopReason.COMPLETED));
        assertEquals(List.of("data: [DONE]"), second.onComplete());
    }

    // ========== §10.2 错误形状 ==========

    @Test
    void errorJsonAndEventUseChatErrorShape() {
        CapabilityException error = new CapabilityException(UNKNOWN, 400, "bogus", "未知请求字段: bogus");
        JsonNode body = MAPPER.readTree(codec.errorJson(error));
        assertEquals(Set.of("error"), names(body));
        JsonNode rendered = body.path("error");
        assertEquals(Set.of("message", "type", "code", "param"), names(rendered));
        assertEquals("未知请求字段: bogus", rendered.path("message").asString());
        assertEquals("invalid_request_error", rendered.path("type").asString());
        assertEquals(UNKNOWN, rendered.path("code").asString());
        assertEquals("bogus", rendered.path("param").asString());
        assertEquals("data: " + codec.errorJson(error), codec.errorEvent(error));
        assertFalse(codec.errorEvent(error).contains("\n"));
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
        CapabilityException multiple = new CapabilityException(400, List.of(
                new io.github.leohou0430.llmgw.conversion.Violation(INVALID, "first", "第一个违规"),
                new io.github.leohou0430.llmgw.conversion.Violation(INVALID, "second", "第二个违规")));
        JsonNode rendered = MAPPER.readTree(codec.errorJson(multiple)).path("error");
        assertEquals("first", rendered.path("param").asString());
        assertEquals("第一个违规", rendered.path("message").asString());
    }

    @Test
    void errorCodeMappingCoversUpstreamAndGatewayCodes() {
        assertEquals("server_error", errorTypeOf("upstream_error"));
        assertEquals("rate_limit_error", errorTypeOf("upstream_rate_limited"));
        assertEquals("server_error", errorTypeOf("upstream_overloaded"));
        assertEquals("server_error", errorTypeOf("internal_error"));
        assertEquals("invalid_request_error", errorTypeOf("capability_not_supported"));
        assertEquals("invalid_request_error", errorTypeOf("resource_not_found"));
    }

    private String errorTypeOf(String code) {
        StreamEncoder encoder = codec.newStreamEncoder(null, false, ctx());
        List<String> lines = encoder.onError(code, "reason");
        return payload(lines.get(0)).path("error").path("type").asString();
    }
}