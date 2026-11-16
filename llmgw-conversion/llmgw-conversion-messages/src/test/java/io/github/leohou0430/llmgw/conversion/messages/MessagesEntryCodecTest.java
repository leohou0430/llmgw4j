package io.github.leohou0430.llmgw.conversion.messages;

import io.github.leohou0430.llmgw.conversion.CapabilityContext;
import io.github.leohou0430.llmgw.conversion.CapabilityException;
import io.github.leohou0430.llmgw.conversion.ContentPart;
import io.github.leohou0430.llmgw.conversion.ContinuationEnvelope;
import io.github.leohou0430.llmgw.conversion.DegradationAudit;
import io.github.leohou0430.llmgw.conversion.DegradationGrant;
import io.github.leohou0430.llmgw.conversion.ImagePart;
import io.github.leohou0430.llmgw.conversion.Role;
import io.github.leohou0430.llmgw.conversion.SemanticEvent;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
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

/** 映射矩阵 §1.1/§5.3/§6.4/§8/§9/§10.2/§11 驱动的 Anthropic Messages 入口合同测试。 */
class MessagesEntryCodecTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String UNKNOWN = CapabilityException.UNKNOWN_FIELD;
    private static final String NOT_SUPPORTED = CapabilityException.NOT_SUPPORTED;
    private static final String NOT_AUTHORIZED = CapabilityException.DEGRADATION_NOT_AUTHORIZED;
    private static final String INVALID = CapabilityException.INVALID_VALUE;
    private static final String CONTINUATION_INVALID = CapabilityException.CONTINUATION_INVALID;
    private static final String BUDGET_TO_EFFORT = "thinking.budget_to_effort";
    private static final String DISPLAY_RAW = "thinking.display_raw";
    private static final String FORCE_TO_AUTO = "tool_choice.force_to_auto";
    private static final String SCHEMA_TO_OBJECT = "response_format.schema_to_object";
    /** 与 Chat/Responses 的图片容量守卫同值（矩阵 §4.4/§8）：单图 32 MiB、总量 64 MiB、≤600 张。 */
    private static final long MAX_IMAGE_BYTES = 32L * 1024 * 1024;
    private static final long MAX_TOTAL_IMAGE_BYTES = 64L * 1024 * 1024;
    /**
     * T1.9 独立真值：自有小图 llmgw-probe.png 的 sha256，取自
     * tools/acceptance/stage1/fixtures/images/manifest.json（固化字面量，不在测试内重算期望值）。
     */
    private static final String PROBE_SHA256 =
            "f24b0899ce1bb9147836beb6812c9ab7778a864f39c44b14d32548e4d91228d9";

    private final MessagesEntryCodec codec = new MessagesEntryCodec();

    // ========== 测试脚手架 ==========

    private static String fixture(String name) {
        try (InputStream in = MessagesEntryCodecTest.class.getResourceAsStream("/messages/" + name)) {
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

    private static Set<String> names(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.properties().forEach(entry -> fields.add(entry.getKey()));
        return fields;
    }

    private static final ContinuationEnvelope ENVELOPE =
            new ContinuationEnvelope("messages-entry-test-key".getBytes(StandardCharsets.UTF_8));

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

    /** 续轮请求：user "hi" + assistant 单 thinking 块；signature 为 null 时省略该键。 */
    private static String thinkingRequest(String reasoningText, String signature) {
        ObjectNode root = baseRequest();
        ObjectNode block = MAPPER.createObjectNode().put("type", "thinking").put("thinking", reasoningText);
        if (signature != null) {
            block.put("signature", signature);
        }
        ((ArrayNode) root.path("messages")).addObject().put("role", "assistant").putArray("content").add(block);
        return MAPPER.writeValueAsString(root);
    }

    /**
     * 篡改 MAC 首字符：base64url 末字符只有高位有效（32 字节 MAC → 43 字符，末 4 bit 被丢弃），
     * 改末位可能解码出同一 MAC 而让负控假通过；首字符 6 bit 全有效，改动必然改变 MAC 字节。
     */
    private static String tamperSignature(String token) {
        int separator = token.lastIndexOf('.');
        char first = token.charAt(separator + 1);
        return token.substring(0, separator + 1) + (first == 'A' ? 'B' : 'A') + token.substring(separator + 2);
    }

    /** 白盒读信封 payload（base64url 解码），断言项目绑定与 itemId；不参与 HMAC 计算。 */
    private static JsonNode envelopePayload(String token) {
        assertTrue(token.startsWith("llmgw1:"), "信封必须带网关命名空间前缀: " + token);
        String body = token.substring("llmgw1:".length());
        return MAPPER.readTree(Base64.getUrlDecoder().decode(body.substring(0, body.indexOf('.'))));
    }

    /** 把流式帧重建成客户端回传的 assistant thinking 块（明文＝各 thinking_delta 拼接，签名＝signature_delta）。 */
    private static ObjectNode rebuiltThinkingBlock(List<JsonNode> nodes, int index) {
        String text = String.join("", thinkingDeltas(nodes, index));
        List<String> signatures = nodes.stream()
                .filter(n -> "content_block_delta".equals(n.path("type").asString())
                        && n.path("index").asInt() == index
                        && "signature_delta".equals(n.path("delta").path("type").asString()))
                .map(n -> n.path("delta").path("signature").asString()).toList();
        assertEquals(1, signatures.size(), "每个 thinking 块闭合前恰一个 signature_delta");
        return MAPPER.createObjectNode().put("type", "thinking").put("thinking", text)
                .put("signature", signatures.get(0));
    }

    /** 用重建的 thinking 块构造续轮请求并 decode，返回还原的 ThinkingPart。 */
    private ThinkingPart decodeRebuiltThinking(ObjectNode block, CapabilityContext context) {
        ObjectNode root = baseRequest();
        ((ArrayNode) root.path("messages")).addObject().put("role", "assistant").putArray("content").add(block);
        SemanticRequest req = codec.decode(MAPPER.writeValueAsString(root), context);
        return req.messages().stream()
                .flatMap(m -> m.parts().stream())
                .filter(p -> p instanceof ThinkingPart)
                .map(p -> (ThinkingPart) p)
                .findFirst().orElseThrow();
    }

    private SemanticRequest decode(String json) {
        return codec.decode(json, ctx());
    }

    private CapabilityException failure(String json) {
        return assertThrows(CapabilityException.class, () -> codec.decode(json, ctx()));
    }

    private CapabilityException failure(String json, CapabilityContext context) {
        return assertThrows(CapabilityException.class, () -> codec.decode(json, context));
    }

    private static void assertViolation(CapabilityException error, String code, String fieldPath) {
        assertEquals(400, error.status(), error.getMessage());
        assertEquals(1, error.violations().size(), error.getMessage());
        assertEquals(code, error.violations().get(0).code(), error.getMessage());
        assertEquals(fieldPath, error.violations().get(0).fieldPath(), error.getMessage());
        assertFalse(error.violations().get(0).reason().isBlank());
    }

    private static ObjectNode baseRequest() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", "client-model");
        root.put("max_tokens", 1024);
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

    /** baseRequest 的语义基线：忽略类字段不得改变其中任何一项。 */
    private static SemanticRequest expectedBase() {
        return new SemanticRequest("client-model",
                List.of(new SemanticMessage(Role.USER, List.of(new TextPart("hi")))),
                null, null, null, 1024, false, null, List.of(), null, null, null, null, null);
    }

    /** 单条消息请求：content 为原始 JSON（字符串或块数组）。 */
    private static String messageRequest(String role, String contentJson) {
        ObjectNode root = baseRequest();
        ObjectNode message = root.putArray("messages").addObject().put("role", role);
        message.set("content", MAPPER.readTree(contentJson));
        return MAPPER.writeValueAsString(root);
    }

    private static String blocksRequest(String role, String... blockJson) {
        ArrayNode content = MAPPER.createArrayNode();
        for (String block : blockJson) {
            content.add(MAPPER.readTree(block));
        }
        return messageRequest(role, MAPPER.writeValueAsString(content));
    }

    private static String inlineImageSource(long bytes) {
        return "{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"" + base64OfBytes(bytes) + "\"}";
    }

    private static String base64OfBytes(long bytes) {
        return Base64.getEncoder().encodeToString(new byte[(int) bytes]);
    }

    private static String imageBlock(String sourceJson) {
        return "{\"type\":\"image\",\"source\":" + sourceJson + "}";
    }

    private static SemanticResponse response(List<ContentPart> parts, StopReason stop, Usage usage) {
        return new SemanticResponse("msg_test", List.of(new SemanticMessage(Role.ASSISTANT, parts)), stop, usage);
    }

    private static Long longOrNull(JsonNode node) {
        return node.isNumber() ? node.asLong() : null;
    }

    // ========== §5.3 E：system / messages / max_tokens ==========

    @Test
    void decodeSystemStringPrependsSystemMessage() {
        SemanticRequest req = decode(withField("system", "\"be nice\""));
        assertEquals(List.of(Role.SYSTEM, Role.USER), req.messages().stream().map(SemanticMessage::role).toList());
        assertEquals(List.of(new TextPart("be nice")), req.messages().get(0).parts());
        assertEquals(List.of(new TextPart("hi")), req.messages().get(1).parts());
    }

    @Test
    void decodeSystemTextBlockArrayPrependsSystemMessageKeepingSegmentOrder() {
        SemanticRequest req = decode(withField("system",
                "[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]"));
        assertEquals(Role.SYSTEM, req.messages().get(0).role());
        assertEquals(List.of(new TextPart("a"), new TextPart("b")), req.messages().get(0).parts());
    }

    @Test
    void decodeSystemRejectsNonTextBlocksAndUnknownKeys() {
        assertViolation(failure(withField("system", "[{\"type\":\"image\",\"source\":{\"type\":\"base64\","
                + "\"media_type\":\"image/png\",\"data\":\"QUJD\"}}]")), INVALID, "system[0]");
        assertViolation(failure(withField("system", "[{\"type\":\"text\",\"text\":\"a\",\"bogus\":1}]")),
                UNKNOWN, "system[0].bogus");
        assertViolation(failure(withField("system", "123")), INVALID, "system");
    }

    @Test
    void decodeMidConversationSystemMessageIsPreservedInOrder() {
        SemanticRequest req = decode(fixture("decode-multiturn.json"));
        assertEquals(List.of(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL, Role.SYSTEM, Role.USER),
                req.messages().stream().map(SemanticMessage::role).toList());
        assertEquals(List.of(new TextPart("stay brief")), req.messages().get(4).parts());
        assertEquals("be concise", ((TextPart) req.messages().get(0).parts().get(0)).text());
        assertEquals(List.of("END"), req.stop());
        assertEquals("user-1", req.userId());
        assertEquals(2048, req.maxOutputTokens());
        assertEquals("auto", req.toolChoice());
        assertEquals(List.of(new ToolSpec("get_weather", "weather lookup",
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")), req.tools());
    }

    @Test
    void decodeMultiturnToolUseAndToolResultPairByToolCallId() {
        SemanticRequest req = decode(fixture("decode-multiturn.json"));
        SemanticMessage assistant = req.messages().get(2);
        assertEquals(List.of(new TextPart("let me check"),
                new ToolCallPart("call_1", "get_weather", "{\"city\":\"SF\"}")), assistant.parts());
        SemanticMessage tool = req.messages().get(3);
        assertEquals(List.of(new ToolResultPart("call_1", "sunny", false)), tool.parts());
    }

    @Test
    void decodeRequiresMaxTokens() {
        ObjectNode root = baseRequest();
        root.remove("max_tokens");
        assertViolation(failure(MAPPER.writeValueAsString(root)), INVALID, "max_tokens");
        assertViolation(failure(withField("max_tokens", "null")), INVALID, "max_tokens");
    }

    @Test
    void decodeMaxTokensGuardsRange() {
        for (String bad : List.of("0", "-1", "393217", "\"10\"", "1.5")) {
            assertViolation(failure(withField("max_tokens", bad)), INVALID, "max_tokens");
        }
        assertEquals(1, decode(withField("max_tokens", "1")).maxOutputTokens());
        assertEquals(393216, decode(withField("max_tokens", "393216")).maxOutputTokens());
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
        assertViolation(failure(withField("model", "\"  \"")), INVALID, "model");
    }

    @Test
    void decodeMessageRolesAreUserAssistantSystemOnly() {
        assertEquals(Role.USER, decode(messageRequest("user", "\"hi\"")).messages().get(0).role());
        assertEquals(Role.ASSISTANT, decode(messageRequest("assistant", "\"hi\"")).messages().get(0).role());
        assertEquals(Role.SYSTEM, decode(messageRequest("system", "\"hi\"")).messages().get(0).role());
        // Messages 无 tool 角色：工具结果走 user 消息里的 tool_result 块。
        assertViolation(failure(messageRequest("tool", "\"hi\"")), INVALID, "messages[0].role");
        assertViolation(failure(messageRequest("developer", "\"hi\"")), INVALID, "messages[0].role");
    }

    @Test
    void decodeUserContentStringAndTextBlocksMapToTextParts() {
        assertEquals(List.of(new TextPart("hi")), decode(messageRequest("user", "\"hi\"")).messages().get(0).parts());
        assertEquals(List.of(new TextPart("a"), new TextPart("b")),
                decode(blocksRequest("user", "{\"type\":\"text\",\"text\":\"a\"}",
                        "{\"type\":\"text\",\"text\":\"b\"}")).messages().get(0).parts());
        assertViolation(failure(blocksRequest("user", "{\"type\":\"text\",\"text\":\"a\",\"bogus\":1}")),
                UNKNOWN, "messages[0].content[0].bogus");
        assertViolation(failure(messageRequest("user", "[]")), INVALID, "messages[0].content");
    }

    /**
     * assistant 轮缺 content / content:null / content:[]：与 Chat 入口同行为——保留空 assistant 轮，
     * 既不静默丢弃已给内容，也不伪造部件；user 轮无内容可发则前置拒绝（同 Chat/Responses/上游编码器）。
     */
    @Test
    void decodeAssistantWithoutContentKeepsEmptyTurnAndUserWithoutContentIsRejected() {
        ObjectNode root = baseRequest();
        root.putArray("messages").addObject().put("role", "assistant");
        assertEquals(List.of(new SemanticMessage(Role.ASSISTANT, List.of())),
                decode(MAPPER.writeValueAsString(root)).messages());
        assertEquals(List.of(new SemanticMessage(Role.ASSISTANT, List.of())),
                decode(messageRequest("assistant", "null")).messages());
        assertEquals(List.of(new SemanticMessage(Role.ASSISTANT, List.of())),
                decode(messageRequest("assistant", "[]")).messages());
        assertViolation(failure(messageRequest("user", "null")), INVALID, "messages[0].content");
        ObjectNode userNoContent = baseRequest();
        userNoContent.putArray("messages").addObject().put("role", "user");
        assertViolation(failure(MAPPER.writeValueAsString(userNoContent)), INVALID, "messages[0].content");
    }

    // ========== §5.3/§8：图片 ==========

    @Test
    void decodeInlineImageParsesMediaTypeAndBase64WithoutDetail() {
        SemanticRequest req = decode(fixture("decode-image.json"));
        List<ContentPart> parts = req.messages().get(0).parts();
        assertEquals(List.of(new TextPart("what is this?")), parts.subList(0, 1));
        ImagePart image = assertInstanceOf(ImagePart.class, parts.get(1));
        assertEquals("image/png", image.mediaType());
        assertEquals("aGVsbG8=", image.base64Data());
        assertNull(image.fileRef());
        // 钉住 SDK 的 Messages image 块不带 detail：不得伪造 Chat/Responses 的 detail 语义。
        assertNull(image.detail());
    }

    // T1.9 需求 a：真实自有小图内联解码字节保真（base64 逐字符 + 解码字节 sha256 == 固化真值）。
    @Test
    void decodeInlineImagePreservesRealPngBytes() {
        String json = fixture("decode-image-real.json");
        String inputBase64 = MAPPER.readTree(json).path("messages").get(0).path("content").get(0)
                .path("source").path("data").asString();
        List<ContentPart> parts = decode(json).messages().get(0).parts();
        ImagePart image = assertInstanceOf(ImagePart.class, parts.get(0));
        assertEquals("image/png", image.mediaType());
        // 解码产物必须逐字符等于输入 base64（不得重编码/截断/损坏）。
        assertEquals(inputBase64, image.base64Data());
        assertNull(image.fileRef());
        assertNull(image.detail());
        // 独立真值：解码后的字节 sha256 必须等于 manifest 固化值（证明就是那张自有小图）。
        assertEquals(PROBE_SHA256, sha256Hex(Base64.getDecoder().decode(image.base64Data())));
    }

    @Test
    void decodeImageAcceptsAllPinnedMediaTypes() {
        for (String mediaType : List.of("image/jpeg", "image/png", "image/gif", "image/webp")) {
            SemanticRequest req = decode(blocksRequest("user", imageBlock(
                    "{\"type\":\"base64\",\"media_type\":\"" + mediaType + "\",\"data\":\"QUJD\"}")));
            assertEquals(mediaType, assertInstanceOf(ImagePart.class, req.messages().get(0).parts().get(0)).mediaType());
        }
    }

    @Test
    void decodeImageRejectsIllegalMediaTypeAndBase64() {
        String dataPath = "messages[0].content[0].source.data";
        assertViolation(failure(blocksRequest("user", imageBlock(
                "{\"type\":\"base64\",\"media_type\":\"image/bmp\",\"data\":\"QUJD\"}"))),
                INVALID, "messages[0].content[0].source.media_type");
        assertViolation(failure(blocksRequest("user", imageBlock(
                "{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"!!!\"}"))), INVALID, dataPath);
        assertViolation(failure(blocksRequest("user", imageBlock(
                "{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"\"}"))), INVALID, dataPath);
        assertViolation(failure(blocksRequest("user", imageBlock(
                "{\"type\":\"base64\",\"media_type\":\"image/png\"}"))), INVALID, dataPath);
    }

    /** §5.3：阶段1仅内联；外部 URL 支持延后阶段2/3 → 入口即 not_supported（与 Chat/Responses 同判）。 */
    @Test
    void decodeImageUrlSourceIsNotSupportedAtEntryInStage1() {
        String urlPath = "messages[0].content[0].source.url";
        for (String url : List.of("https://example.com/a.png", "http://example.com/a.png", "ftp://x/a.png")) {
            assertViolation(failure(blocksRequest("user", imageBlock("{\"type\":\"url\",\"url\":\"" + url + "\"}"))),
                    NOT_SUPPORTED, urlPath);
        }
        // 类型/未知键守卫仍先于能力判定：非字符串 url → invalid_value，未知键 → unknown_field。
        assertViolation(failure(blocksRequest("user", imageBlock("{\"type\":\"url\",\"url\":123}"))), INVALID, urlPath);
        assertViolation(failure(blocksRequest("user",
                imageBlock("{\"type\":\"url\",\"url\":\"https://x/a.png\",\"bogus\":1}"))),
                UNKNOWN, "messages[0].content[0].source.bogus");
    }

    @Test
    void decodeImageFileSourceIsNotSupportedInStage1() {
        assertViolation(failure(blocksRequest("user", imageBlock("{\"type\":\"file\",\"file_id\":\"file-api-1\"}"))),
                NOT_SUPPORTED, "messages[0].content[0].source.file_id");
    }

    @Test
    void decodeImageOnlyAllowedInUserMessages() {
        String block = imageBlock("{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"QUJD\"}");
        for (String role : List.of("system", "assistant")) {
            assertViolation(failure(blocksRequest(role, block)), INVALID, "messages[0].content[0]");
        }
    }

    @Test
    void decodeImageCountAboveUpstreamLimitIsRejected() {
        ArrayNode content = MAPPER.createArrayNode();
        for (int i = 0; i < 601; i++) {
            content.add(MAPPER.readTree(imageBlock(
                    "{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"QUJD\"}")));
        }
        assertViolation(failure(messageRequest("user", MAPPER.writeValueAsString(content))), INVALID, "messages");
    }

    @Test
    void decodeImageAbovePerImageByteCapIsRejected() {
        String dataPath = "messages[0].content[0].source.data";
        // 上限含等号：恰好 32 MiB 必须放行。
        assertEquals(1, decode(blocksRequest("user", imageBlock(inlineImageSource(MAX_IMAGE_BYTES))))
                .messages().get(0).parts().size());
        CapabilityException over = failure(blocksRequest("user", imageBlock(inlineImageSource(MAX_IMAGE_BYTES + 1))));
        assertViolation(over, INVALID, dataPath);
        assertEquals("inline image exceeds 32 MiB", over.violations().get(0).reason());
    }

    @Test
    void decodeImageByteCapIsGuardedBeforeBase64Decoding() {
        // 超限且 base64 字符非法：若先解码会报 base64 非法，容量守卫必须前置（不为超大图片分配解码缓冲）。
        long oversized = MAX_IMAGE_BYTES + 1024;
        String garbage = "@".repeat((int) (oversized / 3 * 4));
        CapabilityException error = failure(blocksRequest("user", imageBlock(
                "{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"" + garbage + "\"}")));
        assertViolation(error, INVALID, "messages[0].content[0].source.data");
        assertEquals("inline image exceeds 32 MiB", error.violations().get(0).reason());
    }

    @Test
    void decodeTotalImageBytesAboveCapIsRejected() {
        String half = imageBlock(inlineImageSource(MAX_TOTAL_IMAGE_BYTES / 2));
        assertEquals(2, decode(blocksRequest("user", half, half)).messages().get(0).parts().size());
        CapabilityException error = failure(blocksRequest("user", half, half,
                imageBlock("{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"QQ==\"}")));
        assertViolation(error, INVALID, "messages");
        assertEquals("inline images exceed 64 MiB in total", error.violations().get(0).reason());
    }

    @Test
    void decodeImageUnknownKeysFailWithFullPath() {
        assertViolation(failure(blocksRequest("user",
                "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\","
                        + "\"data\":\"QUJD\"},\"detail\":\"high\"}")), UNKNOWN, "messages[0].content[0].detail");
        assertViolation(failure(blocksRequest("user", imageBlock(
                "{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"QUJD\",\"bogus\":1}"))),
                UNKNOWN, "messages[0].content[0].source.bogus");
    }

    // ========== §5.3/§7：thinking 块与私有块 ==========

    @Test
    void decodeThinkingBlockVerifiesEnvelopeAndRestoresContinuationToken() {
        ObjectNode root = (ObjectNode) MAPPER.readTree(fixture("decode-thinking.json"));
        String token = mint("th_fixture", "step one");
        ((ObjectNode) root.path("messages").get(1).path("content").get(0)).put("signature", token);
        SemanticRequest req = decode(MAPPER.writeValueAsString(root));
        SemanticMessage assistant = req.messages().get(1);
        // 矩阵 §7.3：明文 thinking 原样取出，校验通过的信封写入 continuationToken（不伪造供应商签名）。
        assertEquals(new ThinkingPart("step one", token), assistant.parts().get(0));
        assertEquals(new TextPart("answer"), assistant.parts().get(1));
        assertEquals(new ToolCallPart("call_2", "ping", "{}"), assistant.parts().get(2));
        assertEquals(Boolean.TRUE, req.thinkingEnabled());
        assertEquals("high", req.thinkingEffort());
        assertEquals(List.of(new ToolResultPart("call_2", "pong", false)), req.messages().get(2).parts());
    }

    @Test
    void decodeThinkingBlockTamperedSignatureIsContinuationInvalid() {
        String token = mint("th_1", "real reasoning");
        String tampered = tamperSignature(token);
        assertNotEquals(token, tampered);
        CapabilityException error = failure(thinkingRequest("real reasoning", tampered));
        assertEquals(400, error.status());
        assertEquals(CONTINUATION_INVALID, error.violations().get(0).code());
    }

    @Test
    void decodeThinkingBlockCrossProjectSignatureIsContinuationInvalid() {
        String token = mint("th_1", "secret reasoning");
        CapabilityException error = failure(thinkingRequest("secret reasoning", token), ctxForProject("project-2"));
        assertEquals(400, error.status());
        assertEquals(CONTINUATION_INVALID, error.violations().get(0).code());
    }

    @Test
    void decodeThinkingBlockReasoningDigestMismatchIsContinuationInvalid() {
        String token = mint("th_1", "original");
        CapabilityException error = failure(thinkingRequest("tampered plaintext", token));
        assertEquals(400, error.status());
        assertEquals(CONTINUATION_INVALID, error.violations().get(0).code());
    }

    @Test
    void decodeThinkingBlockMissingSignatureIsContinuationInvalid() {
        CapabilityException error = failure(thinkingRequest("plain reasoning", null));
        assertViolation(error, CONTINUATION_INVALID, "messages[1].content[0].signature");
    }

    @Test
    void decodeThinkingBlockNullSignatureIsContinuationInvalid() {
        ObjectNode root = baseRequest();
        ObjectNode block = MAPPER.createObjectNode().put("type", "thinking").put("thinking", "plain");
        block.putNull("signature");
        ((ArrayNode) root.path("messages")).addObject().put("role", "assistant").putArray("content").add(block);
        assertViolation(failure(MAPPER.writeValueAsString(root)),
                CONTINUATION_INVALID, "messages[1].content[0].signature");
    }

    /** T1.7 的空占位与供应商式签名都不带网关命名空间：一律拒绝，绝不把非信封当合法续轮。 */
    @Test
    void decodeThinkingBlockEmptyOrVendorStyleSignatureIsContinuationInvalid() {
        for (String signature : List.of("", "EqQBCg8IARgCKkA5rVENDORsignatureBASE64==")) {
            CapabilityException error = failure(thinkingRequest("plain reasoning", signature));
            assertEquals(400, error.status());
            assertEquals(CONTINUATION_INVALID, error.violations().get(0).code());
        }
    }

    @Test
    void decodeThinkingBlockRequiresPlaintextAndGuardsSignatureType() {
        assertViolation(failure(blocksRequest("assistant", "{\"type\":\"thinking\",\"signature\":\"s\"}")),
                INVALID, "messages[0].content[0].thinking");
        assertViolation(failure(blocksRequest("assistant",
                "{\"type\":\"thinking\",\"thinking\":\"t\",\"signature\":5}")),
                INVALID, "messages[0].content[0].signature");
        assertViolation(failure(blocksRequest("assistant",
                "{\"type\":\"thinking\",\"thinking\":\"t\",\"bogus\":1}")),
                UNKNOWN, "messages[0].content[0].bogus");
        // thinking 块只属 assistant 轮。
        assertViolation(failure(blocksRequest("user", "{\"type\":\"thinking\",\"thinking\":\"t\"}")),
                INVALID, "messages[0].content[0]");
    }

    @Test
    void decodeRedactedThinkingAndPrivateBlocksAreNotSupported() {
        List<String> privateBlocks = List.of("redacted_thinking", "server_tool_use", "web_search_tool_result",
                "code_execution_tool_result", "mcp_tool_use", "mcp_tool_result", "container_upload", "document",
                "search_result", "bash_code_execution_tool_result", "text_editor_code_execution_tool_result");
        for (String type : privateBlocks) {
            assertViolation(failure(blocksRequest("user", "{\"type\":\"" + type + "\"}")),
                    NOT_SUPPORTED, "messages[0].content[0]");
        }
    }

    // ========== §5.3：tool_use / tool_result ==========

    @Test
    void decodeToolUseSerializesInputObjectToArgumentsJsonString() {
        SemanticRequest req = decode(blocksRequest("assistant",
                "{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"ping\",\"input\":{\"a\":1,\"b\":[2,3]}}"));
        ToolCallPart call = assertInstanceOf(ToolCallPart.class, req.messages().get(0).parts().get(0));
        assertEquals("call_1", call.id());
        assertEquals("ping", call.name());
        // input 是 JSON 对象，argumentsJson 必须是其序列化字符串（不是对象）。
        assertEquals(MAPPER.readTree("{\"a\":1,\"b\":[2,3]}"), MAPPER.readTree(call.argumentsJson()));
        assertInstanceOf(String.class, call.argumentsJson());
    }

    @Test
    void decodeToolUseRequiresObjectInputAndGuardsIdName() {
        String path = "messages[0].content[0]";
        for (String input : List.of("\"str\"", "[1]", "5", "null")) {
            assertViolation(failure(blocksRequest("assistant",
                    "{\"type\":\"tool_use\",\"id\":\"c\",\"name\":\"ping\",\"input\":" + input + "}")),
                    INVALID, path + ".input");
        }
        assertViolation(failure(blocksRequest("assistant", "{\"type\":\"tool_use\",\"name\":\"ping\",\"input\":{}}")),
                INVALID, path + ".id");
        assertViolation(failure(blocksRequest("assistant", "{\"type\":\"tool_use\",\"id\":\"c\",\"input\":{}}")),
                INVALID, path + ".name");
        assertViolation(failure(blocksRequest("assistant",
                "{\"type\":\"tool_use\",\"id\":\"c\",\"name\":\"bad name!\",\"input\":{}}")), INVALID, path + ".name");
        assertViolation(failure(blocksRequest("assistant",
                "{\"type\":\"tool_use\",\"id\":\"c\",\"name\":\"ping\",\"input\":{},\"bogus\":1}")),
                UNKNOWN, path + ".bogus");
        // 缺 input 视为空对象（Anthropic 无参工具）。
        assertEquals("{}", assertInstanceOf(ToolCallPart.class, decode(blocksRequest("assistant",
                "{\"type\":\"tool_use\",\"id\":\"c\",\"name\":\"ping\"}")).messages().get(0).parts().get(0))
                .argumentsJson());
    }

    @Test
    void decodeToolResultMapsContentStringAndIsError() {
        SemanticRequest req = decode(blocksRequest("user",
                "{\"type\":\"tool_result\",\"tool_use_id\":\"call_1\",\"content\":\"boom\",\"is_error\":true}"));
        assertEquals(Role.TOOL, req.messages().get(0).role());
        assertEquals(List.of(new ToolResultPart("call_1", "boom", true)), req.messages().get(0).parts());
        // is_error 省略 → false。
        assertEquals(List.of(new ToolResultPart("call_1", "ok", false)),
                decode(blocksRequest("user", "{\"type\":\"tool_result\",\"tool_use_id\":\"call_1\",\"content\":\"ok\"}"))
                        .messages().get(0).parts());
    }

    @Test
    void decodeToolResultTextBlocksJoinToString() {
        SemanticRequest req = decode(blocksRequest("user",
                "{\"type\":\"tool_result\",\"tool_use_id\":\"call_1\",\"content\":"
                        + "[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]}"));
        assertEquals(List.of(new ToolResultPart("call_1", "ab", false)), req.messages().get(0).parts());
    }

    @Test
    void decodeToolResultImageIsNotSupportedPendingControlledVerification() {
        // 矩阵 §4.4/§13.2：tool_result 图片经 Chat 上游待 T1.14 受控验证，第一阶段前置拒绝而非静默去图。
        assertViolation(failure(blocksRequest("user",
                "{\"type\":\"tool_result\",\"tool_use_id\":\"call_1\",\"content\":[" + imageBlock(
                        "{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"QUJD\"}") + "]}")),
                NOT_SUPPORTED, "messages[0].content[0].content[0]");
    }

    @Test
    void decodeToolResultGuardsIdRoleAndUnknownKeys() {
        assertViolation(failure(blocksRequest("user", "{\"type\":\"tool_result\",\"content\":\"x\"}")),
                INVALID, "messages[0].content[0].tool_use_id");
        assertViolation(failure(blocksRequest("assistant",
                "{\"type\":\"tool_result\",\"tool_use_id\":\"c\",\"content\":\"x\"}")),
                INVALID, "messages[0].content[0]");
        assertViolation(failure(blocksRequest("user",
                "{\"type\":\"tool_result\",\"tool_use_id\":\"c\",\"content\":\"x\",\"bogus\":1}")),
                UNKNOWN, "messages[0].content[0].bogus");
        assertViolation(failure(blocksRequest("user",
                "{\"type\":\"tool_result\",\"tool_use_id\":\"c\",\"content\":\"x\",\"is_error\":\"yes\"}")),
                INVALID, "messages[0].content[0].is_error");
    }

    @Test
    void decodeToolResultAndTextPreserveRelativeOrderAsSeparateMessages() {
        SemanticRequest req = decode(blocksRequest("user",
                "{\"type\":\"text\",\"text\":\"before\"}",
                "{\"type\":\"tool_result\",\"tool_use_id\":\"c1\",\"content\":\"r1\"}",
                "{\"type\":\"tool_result\",\"tool_use_id\":\"c2\",\"content\":\"r2\"}",
                "{\"type\":\"text\",\"text\":\"after\"}"));
        assertEquals(List.of(Role.USER, Role.TOOL, Role.TOOL, Role.USER),
                req.messages().stream().map(SemanticMessage::role).toList());
        assertEquals(List.of(new TextPart("before")), req.messages().get(0).parts());
        assertEquals(List.of(new ToolResultPart("c1", "r1", false)), req.messages().get(1).parts());
        assertEquals(List.of(new ToolResultPart("c2", "r2", false)), req.messages().get(2).parts());
        assertEquals(List.of(new TextPart("after")), req.messages().get(3).parts());
    }

    // ========== §5.3/§11：thinking 模式与降级 ==========

    @Test
    void thinkingDisabledIsEquivalentAndSetsThinkingEnabledFalse() {
        CapabilityContext context = ctx();
        SemanticRequest req = codec.decode(withField("thinking", "{\"type\":\"disabled\"}"), context);
        assertEquals(Boolean.FALSE, req.thinkingEnabled());
        assertNull(req.thinkingEffort());
        assertTrue(context.audit().applied().isEmpty(), "E 类等价映射不得记降级");
    }

    @Test
    void thinkingAdaptiveIsEquivalentWithHighEffort() {
        CapabilityContext context = ctx();
        SemanticRequest req = codec.decode(withField("thinking", "{\"type\":\"adaptive\"}"), context);
        assertEquals(Boolean.TRUE, req.thinkingEnabled());
        assertEquals("high", req.thinkingEffort());
        assertTrue(context.audit().applied().isEmpty(), "adaptive 无需 budget 降级授权（矩阵 §11 注）");
    }

    @Test
    void thinkingEnabledBudgetRequiresBudgetToEffortGrant() {
        String json = withField("thinking", "{\"type\":\"enabled\",\"budget_tokens\":2048}");
        assertViolation(failure(json), NOT_AUTHORIZED, "thinking.budget_tokens");
        CapabilityContext context = ctx(BUDGET_TO_EFFORT);
        SemanticRequest req = codec.decode(json, context);
        assertEquals(Boolean.TRUE, req.thinkingEnabled());
        assertEquals("low", req.thinkingEffort());
        assertEquals(1, context.audit().applied().size());
        assertEquals("thinking.budget_tokens", context.audit().applied().get(0).fieldPath());
        assertEquals(BUDGET_TO_EFFORT, context.audit().applied().get(0).degradationId());
        assertFalse(context.audit().applied().get(0).reason().isBlank());
    }

    @Test
    void thinkingBudgetMapsToCoarseEffortTiers() {
        record Case(int budget, String effort) { }
        for (Case c : List.of(new Case(1024, "low"), new Case(8191, "low"), new Case(8192, "high"),
                new Case(32767, "high"), new Case(32768, "max"), new Case(200000, "max"))) {
            SemanticRequest req = codec.decode(withField("thinking",
                    "{\"type\":\"enabled\",\"budget_tokens\":" + c.budget() + "}"), ctx(BUDGET_TO_EFFORT));
            assertEquals(c.effort(), req.thinkingEffort(), "budget=" + c.budget());
        }
    }

    @Test
    void thinkingBudgetGuardsPositiveInteger() {
        for (String bad : List.of("0", "-5", "\"2048\"", "1.5")) {
            assertViolation(failure(withField("thinking", "{\"type\":\"enabled\",\"budget_tokens\":" + bad + "}"),
                    ctx(BUDGET_TO_EFFORT)), INVALID, "thinking.budget_tokens");
        }
        assertViolation(failure(withField("thinking", "{\"type\":\"enabled\"}"), ctx(BUDGET_TO_EFFORT)),
                INVALID, "thinking.budget_tokens");
    }

    @Test
    void thinkingBetweenToolsIsNotSupported() {
        assertViolation(failure(withField("thinking", "{\"type\":\"between_tools\"}")),
                NOT_SUPPORTED, "thinking.type");
    }

    @Test
    void thinkingDisplayRequiresDisplayRawGrant() {
        for (String display : List.of("summarized", "omitted")) {
            String json = withField("thinking", "{\"type\":\"adaptive\",\"display\":\"" + display + "\"}");
            assertViolation(failure(json), NOT_AUTHORIZED, "thinking.display");
            CapabilityContext context = ctx(DISPLAY_RAW);
            SemanticRequest req = codec.decode(json, context);
            assertEquals(Boolean.TRUE, req.thinkingEnabled());
            assertEquals("high", req.thinkingEffort());
            assertEquals(List.of(DISPLAY_RAW),
                    context.audit().applied().stream().map(a -> a.degradationId()).toList());
        }
        assertViolation(failure(withField("thinking", "{\"type\":\"adaptive\",\"display\":\"raw\"}"), ctx(DISPLAY_RAW)),
                INVALID, "thinking.display");
    }

    @Test
    void thinkingUnknownTypeAndKeysAreRejected() {
        assertViolation(failure(withField("thinking", "{\"type\":\"bogus\"}")), INVALID, "thinking.type");
        assertViolation(failure(withField("thinking", "{\"type\":\"adaptive\",\"bogus\":1}")),
                UNKNOWN, "thinking.bogus");
        assertViolation(failure(withField("thinking", "\"enabled\"")), INVALID, "thinking");
    }

    @Test
    void thinkingAbsentLeavesUpstreamDefaultAndNullIsAbsent() {
        assertNull(decode(withField("thinking", "null")).thinkingEnabled());
        assertNull(codec.decode(MAPPER.writeValueAsString(baseRequest()), ctx()).thinkingEnabled());
    }

    @Test
    void outputConfigEffortIsEquivalentForPinnedValues() {
        for (String effort : List.of("low", "medium", "high", "xhigh", "max")) {
            SemanticRequest req = decode(withField("output_config", "{\"effort\":\"" + effort + "\"}"));
            assertEquals(effort, req.thinkingEffort());
        }
        assertViolation(failure(withField("output_config", "{\"effort\":\"none\"}")),
                INVALID, "output_config.effort");
        assertViolation(failure(withField("output_config", "{\"bogus\":1}")), UNKNOWN, "output_config.bogus");
    }

    @Test
    void outputConfigJsonSchemaRequiresSchemaToObjectGrant() {
        String json = withField("output_config",
                "{\"format\":{\"type\":\"json_schema\",\"schema\":{\"type\":\"object\"}}}");
        assertViolation(failure(json), NOT_AUTHORIZED, "output_config.format");
        CapabilityContext context = ctx(SCHEMA_TO_OBJECT);
        SemanticRequest req = codec.decode(json, context);
        assertEquals("json_object", req.responseFormat());
        assertEquals(List.of(SCHEMA_TO_OBJECT),
                context.audit().applied().stream().map(a -> a.degradationId()).toList());
        assertViolation(failure(withField("output_config", "{\"format\":{\"type\":\"bogus\"}}"), ctx(SCHEMA_TO_OBJECT)),
                INVALID, "output_config.format.type");
    }

    /** 未知嵌套键先于降级授权判定：未授权＋未知键必须报 unknown_field，而不是 degradation_not_authorized。 */
    @Test
    void outputConfigFormatUnknownKeyFailsBeforeDegradationAuthorization() {
        String json = withField("output_config",
                "{\"format\":{\"type\":\"json_schema\",\"schema\":{\"type\":\"object\"},\"bogus\":1}}");
        assertViolation(failure(json), UNKNOWN, "output_config.format.bogus");
        assertViolation(failure(json, ctx(SCHEMA_TO_OBJECT)), UNKNOWN, "output_config.format.bogus");
    }

    @Test
    void explicitOutputConfigEffortWinsOverDerivedBudgetTier() {
        SemanticRequest req = codec.decode(withField("thinking",
                "{\"type\":\"enabled\",\"budget_tokens\":2048}"), ctx(BUDGET_TO_EFFORT));
        assertEquals("low", req.thinkingEffort());
        ObjectNode root = (ObjectNode) MAPPER.readTree(withField("thinking", "{\"type\":\"enabled\",\"budget_tokens\":2048}"));
        root.putObject("output_config").put("effort", "max");
        SemanticRequest both = codec.decode(MAPPER.writeValueAsString(root), ctx(BUDGET_TO_EFFORT));
        assertEquals("max", both.thinkingEffort(), "显式 effort 优先于预算推导档");
        assertEquals(Boolean.TRUE, both.thinkingEnabled());
    }

    // ========== §5.3：tools / tool_choice ==========

    @Test
    void decodeCustomToolsMapInputSchemaToParameters() {
        SemanticRequest req = decode(withField("tools",
                "[{\"type\":\"custom\",\"name\":\"ping\",\"description\":\"d\","
                        + "\"input_schema\":{\"type\":\"object\",\"properties\":{}}}]"));
        assertEquals(List.of(new ToolSpec("ping", "d", "{\"type\":\"object\",\"properties\":{}}")), req.tools());
        // type 省略即 custom（钉住 SDK 的自定义工具形态）。
        assertEquals(List.of(new ToolSpec("ping", null, "{\"type\":\"object\"}")),
                decode(withField("tools", "[{\"name\":\"ping\",\"input_schema\":{\"type\":\"object\"}}]")).tools());
    }

    @Test
    void decodeServerToolsAreNotSupported() {
        List<String> serverTypes = List.of("bash_20250124", "code_execution_20250522", "web_search_20250305",
                "web_fetch_20250910", "computer_20250124", "memory_20250818", "text_editor_20250728",
                "tool_search_20251028", "browser_20251028", "bash", "web_search");
        for (String type : serverTypes) {
            assertViolation(failure(withField("tools", "[{\"type\":\"" + type + "\",\"name\":\"x\"}]")),
                    NOT_SUPPORTED, "tools[0]");
        }
    }

    @Test
    void decodeToolsGuardSchemaNameAndDuplicates() {
        assertViolation(failure(withField("tools", "[{\"name\":\"ping\",\"input_schema\":[]}]")),
                INVALID, "tools[0].input_schema");
        assertViolation(failure(withField("tools", "[{\"name\":\"bad name!\",\"input_schema\":{}}]")),
                INVALID, "tools[0].name");
        assertViolation(failure(withField("tools",
                "[{\"name\":\"ping\",\"input_schema\":{}},{\"name\":\"ping\",\"input_schema\":{}}]")),
                INVALID, "tools[1].name");
        assertViolation(failure(withField("tools", "[{\"name\":\"ping\",\"input_schema\":{},\"bogus\":1}]")),
                UNKNOWN, "tools[0].bogus");
        assertViolation(failure(withField("tools", "[{\"name\":\"ping\"}]")), INVALID, "tools[0].input_schema");
    }

    @Test
    void toolChoiceAutoAndNoneAreEquivalent() {
        assertEquals("auto", decode(withField("tool_choice", "{\"type\":\"auto\"}")).toolChoice());
        assertEquals("none", decode(withField("tool_choice", "{\"type\":\"none\"}")).toolChoice());
        assertNull(decode(withField("tool_choice", "null")).toolChoice());
    }

    @Test
    void toolChoiceAnyAndToolAreEquivalentWhenNotThinking() {
        ObjectNode root = (ObjectNode) MAPPER.readTree(withField("thinking", "{\"type\":\"disabled\"}"));
        root.putObject("tool_choice").put("type", "any");
        assertEquals("required", codec.decode(MAPPER.writeValueAsString(root), ctx()).toolChoice());
        root.putObject("tool_choice").put("type", "tool").put("name", "ping");
        assertEquals("tool:ping", codec.decode(MAPPER.writeValueAsString(root), ctx()).toolChoice());
    }

    @Test
    void toolChoiceAnyAndToolInThinkingModeRequireForceToAutoGrant() {
        for (String choice : List.of("{\"type\":\"any\"}", "{\"type\":\"tool\",\"name\":\"ping\"}")) {
            String json = withField("tool_choice", choice);
            // 省略 thinking → 上游默认开启思考（矩阵 §4.2），故 any/具名落 R/D。
            assertViolation(failure(json), NOT_AUTHORIZED, "tool_choice");
            CapabilityContext context = ctx(FORCE_TO_AUTO);
            assertEquals("auto", codec.decode(json, context).toolChoice());
            assertEquals(List.of(FORCE_TO_AUTO),
                    context.audit().applied().stream().map(a -> a.degradationId()).toList());
        }
    }

    @Test
    void disableParallelToolUseIsIgnorable() {
        CapabilityContext context = ctx();
        SemanticRequest req = codec.decode(withField("tool_choice",
                "{\"type\":\"auto\",\"disable_parallel_tool_use\":true}"), context);
        assertEquals("auto", req.toolChoice());
        assertTrue(context.audit().applied().isEmpty());
        assertEquals(expectedBase().messages(), req.messages());
    }

    @Test
    void toolChoiceUnknownTypeAndKeysAreRejected() {
        assertViolation(failure(withField("tool_choice", "{\"type\":\"bogus\"}")), INVALID, "tool_choice.type");
        assertViolation(failure(withField("tool_choice", "{\"type\":\"auto\",\"bogus\":1}")),
                UNKNOWN, "tool_choice.bogus");
        assertViolation(failure(withField("tool_choice", "{\"type\":\"tool\"}")), INVALID, "tool_choice.name");
        assertViolation(failure(withField("tool_choice", "\"auto\"")), INVALID, "tool_choice");
    }

    // ========== §5.3：metadata / stream / stop_sequences / 全量分类 ==========

    @Test
    void metadataUserIdMapsToUserIdAndOtherKeysAreIgnorable() {
        CapabilityContext context = ctx();
        SemanticRequest req = codec.decode(withField("metadata",
                "{\"user_id\":\"user-1\",\"trace\":\"abc\"}"), context);
        assertEquals("user-1", req.userId());
        assertTrue(context.audit().applied().isEmpty());
        assertNull(decode(withField("metadata", "{\"trace\":\"abc\"}")).userId());
        assertNull(decode(withField("metadata", "null")).userId());
        assertViolation(failure(withField("metadata", "{\"user_id\":123}")), INVALID, "metadata.user_id");
        assertViolation(failure(withField("metadata",
                "{\"user_id\":\"" + "u".repeat(513) + "\"}")), INVALID, "metadata.user_id");
    }

    /**
     * §1.1：{@code stream_options} 不在钉住的 Messages 字段全集内；§6.4 的 Messages usage 恒在
     * message_delta，与 include_usage 无关 → 入口按未知字段拒绝，includeUsage 恒 null。
     */
    @Test
    void streamOptionsIsUnknownFieldBecauseMessagesUsageIsAlwaysInMessageDelta() {
        for (String options : List.of("{\"include_usage\":true}", "{\"include_usage\":false}", "{}", "null")) {
            ObjectNode root = baseRequest();
            root.put("stream", true);
            root.set("stream_options", MAPPER.readTree(options));
            assertViolation(failure(MAPPER.writeValueAsString(root)), UNKNOWN, "stream_options");
        }
        assertViolation(failure(withField("stream_options", "{\"include_usage\":true}")), UNKNOWN, "stream_options");
        assertNull(decode(withField("stream", "true")).includeUsage(), "Messages 不需要 includeUsage");
    }

    @Test
    void decodeStreamGuardsBooleanAndNullIsAbsent() {
        assertViolation(failure(withField("stream", "\"yes\"")), INVALID, "stream");
        assertFalse(decode(withField("stream", "null")).stream());
        assertTrue(decode(withField("stream", "true")).stream());
    }

    @Test
    void stopSequencesMapToStopWithCap() {
        assertEquals(List.of("a", "b"), decode(withField("stop_sequences", "[\"a\",\"b\"]")).stop());
        assertEquals(List.of(), decode(withField("stop_sequences", "null")).stop());
        ArrayNode many = MAPPER.createArrayNode();
        for (int i = 0; i < 17; i++) {
            many.add("s" + i);
        }
        assertViolation(failure(withField("stop_sequences", many)), INVALID, "stop_sequences");
        assertViolation(failure(withField("stop_sequences", "[1]")), INVALID, "stop_sequences[0]");
        assertViolation(failure(withField("stop_sequences", "\"a\"")), INVALID, "stop_sequences");
    }

    @Test
    void rejectFieldsFailWithNotSupportedAndHttp400() {
        fixtureJson("reject-fields.json").properties().forEach(entry ->
                assertViolation(failure(withField(entry.getKey(), entry.getValue())), NOT_SUPPORTED, entry.getKey()));
    }

    @Test
    void ignoreFieldsLeaveSemanticsUntouched() {
        fixtureJson("ignore-fields.json").properties().forEach(entry -> {
            CapabilityContext context = ctx();
            assertEquals(expectedBase(), codec.decode(withField(entry.getKey(), entry.getValue()), context),
                    "忽略类字段不得改变语义: " + entry.getKey());
            assertTrue(context.audit().applied().isEmpty(), "I 类不得记降级: " + entry.getKey());
        });
    }

    @Test
    void unknownFieldsFailWithUnknownFieldCode() {
        fixtureJson("unknown-fields.json").properties().forEach(entry ->
                assertViolation(failure(withField(entry.getKey(), entry.getValue())), UNKNOWN, entry.getKey()));
    }

    /** §1.1：2.8.0 未暴露 temperature/top_k/top_p → 未知字段码，不是 not_supported。 */
    @Test
    void temperatureTopKTopPAreUnknownFieldNotNotSupported() {
        for (String name : List.of("temperature", "top_k", "top_p")) {
            CapabilityException error = failure(withField(name, "0.7"));
            assertViolation(error, UNKNOWN, name);
            assertFalse(NOT_SUPPORTED.equals(error.violations().get(0).code()),
                    "未钉住字段必须落 unknown_field，不得落 not_supported: " + name);
        }
    }

    @Test
    void everyPinnedFieldIsClassifiedNotUnknown() {
        List<String> pinned = List.of("cache_control", "container", "diagnostics", "inference_geo", "max_tokens",
                "messages", "metadata", "model", "output_config", "service_tier", "speed", "stop_sequences",
                "stream", "system", "thinking", "tool_choice", "tools", "user_profile_id", "workspace_id");
        Set<String> ignorable = Set.of("metadata", "service_tier", "speed");
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

    @Test
    void unknownNestedFieldsFailWithTheirFullPath() {
        assertViolation(failure(withField("thinking", "{\"type\":\"adaptive\",\"probe\":1}")),
                UNKNOWN, "thinking.probe");
        assertViolation(failure(withField("output_config", "{\"probe\":1}")), UNKNOWN, "output_config.probe");
        assertViolation(failure(withField("tool_choice", "{\"type\":\"auto\",\"probe\":1}")),
                UNKNOWN, "tool_choice.probe");
        assertViolation(failure(withField("tools", "[{\"name\":\"p\",\"input_schema\":{},\"probe\":1}]")),
                UNKNOWN, "tools[0].probe");
        assertViolation(failure(withField("messages",
                "[{\"role\":\"user\",\"content\":\"hi\",\"probe\":1}]")), UNKNOWN, "messages[0].probe");
    }

    @Test
    void perRequestAuditIsolationOnSharedCodec() {
        String json = withField("thinking", "{\"type\":\"enabled\",\"budget_tokens\":2048}");
        CapabilityContext granted = ctx(BUDGET_TO_EFFORT);
        CapabilityContext denied = ctx();
        codec.decode(json, granted);
        assertEquals(1, granted.audit().applied().size());
        assertViolation(failure(json, denied), NOT_AUTHORIZED, "thinking.budget_tokens");
        assertTrue(denied.audit().applied().isEmpty(), "拒绝路径不得留痕，也不得被上一请求污染");
        assertEquals(1, granted.audit().applied().size(), "另一个请求不得污染已留痕的实例");
        // 共享单例上的第二次授权请求必须重新计数：留痕归每请求 audit，不在 codec 上累积。
        CapabilityContext second = ctx(BUDGET_TO_EFFORT);
        codec.decode(json, second);
        assertEquals(1, second.audit().applied().size(), "共享单例不得把上一请求的留痕带到下一请求");
    }

    @Test
    void decodeMalformedJsonPreservesCauseAndKeepsClientReasonConcise() {
        CapabilityException error = failure("{\"model\":\"m\",\"max_tokens\":1,\"messages\":[");
        assertViolation(error, INVALID, "request");
        String reason = error.violations().get(0).reason();
        assertEquals("invalid request JSON", reason);
        assertFalse(reason.contains("[Source:"), "不得把 Jackson 源位置 dump 泄露给客户端: " + reason);
        assertFalse(reason.contains("\n"), "客户端原因必须单行: " + reason);
        assertNotNull(error.getCause(), "解析异常必须保留 cause，不得吞栈");
        assertInstanceOf(JacksonException.class, error.getCause());
        assertViolation(failure(null), INVALID, "request");
        assertViolation(failure("[]"), INVALID, "request");
    }

    // ========== encodeResponse：块顺序 / stop_reason / usage 不对称 ==========

    @Test
    void encodeResponseBuildsThinkingTextAndToolUseBlocksInOrder() {
        SemanticResponse resp = new SemanticResponse("msg_fixed",
                List.of(new SemanticMessage(Role.ASSISTANT, List.of(
                        new ThinkingPart("reason", null),
                        new TextPart("answer"),
                        new ToolCallPart("call_9", "ping", "{\"a\":1}")))),
                StopReason.TOOL_CALL, new Usage(120L, 40L, 12L, 80L));
        JsonNode actual = MAPPER.readTree(codec.encodeResponse(resp, "client-model", ctx()));
        ObjectNode expected = (ObjectNode) fixtureJson("expected-message-tools.json");
        // 信封 itemId 每请求随机 → 无法写成固定字面量：先独立验签，再把已验令牌填进期望值做全量比对。
        String token = actual.path("content").get(0).path("signature").asString();
        ENVELOPE.verify(token, "project-1", "reason");
        ((ObjectNode) expected.path("content").get(0)).put("signature", token);
        assertEquals(expected, actual);
    }

    @Test
    void encodeResponseTextOnlyMatchesFixtureAndOmitsModelWhenNull() {
        SemanticResponse resp = new SemanticResponse("msg_text",
                List.of(new SemanticMessage(Role.ASSISTANT, List.of(new TextPart("hello"), new TextPart(" world")))),
                StopReason.COMPLETED, new Usage(100L, 7L, null, 0L));
        assertEquals(fixtureJson("expected-message-text.json"), MAPPER.readTree(codec.encodeResponse(resp, null, ctx())));
        JsonNode withModel = MAPPER.readTree(codec.encodeResponse(resp, "client-model", ctx()));
        assertEquals("client-model", withModel.path("model").asString());
        assertTrue(MAPPER.readTree(codec.encodeResponse(resp, null, ctx())).path("model").isMissingNode());
    }

    @Test
    void encodeResponseSynthesizesIdWhenAbsentAndAlwaysNullsStopSequence() {
        JsonNode json = MAPPER.readTree(codec.encodeResponse(
                response(List.of(new TextPart("a")), StopReason.COMPLETED, null), null, ctx()));
        assertFalse(json.path("id").asString().isBlank());
        assertTrue(json.path("id").asString().startsWith("msg_"));
        assertTrue(json.path("stop_sequence").isNull());
        assertEquals("message", json.path("type").asString());
        assertEquals("assistant", json.path("role").asString());
        assertTrue(json.path("usage").isMissingNode(), "usage 全未知 → 省略，不得填 0");
    }

    /** 语义响应无消息（空 messages）：content 为空数组，不抛异常也不伪造部件/usage。 */
    @Test
    void encodeResponseWithNoMessagesEmitsEmptyContentArray() {
        JsonNode json = MAPPER.readTree(codec.encodeResponse(
                new SemanticResponse("msg_empty", List.of(), StopReason.COMPLETED, null), "client-model", ctx()));
        assertEquals("msg_empty", json.path("id").asString());
        assertEquals("assistant", json.path("role").asString());
        assertTrue(json.path("content").isArray());
        assertEquals(0, json.path("content").size());
        assertEquals("end_turn", json.path("stop_reason").asString());
        assertTrue(json.path("usage").isMissingNode());
    }

    @Test
    void encodeResponseMapsEveryExpressibleStopReason() {
        record Case(StopReason stop, String wire) { }
        for (Case c : List.of(new Case(StopReason.COMPLETED, "end_turn"), new Case(StopReason.LENGTH, "max_tokens"),
                new Case(StopReason.TOOL_CALL, "tool_use"), new Case(StopReason.CONTENT_FILTER, "refusal"))) {
            JsonNode json = MAPPER.readTree(codec.encodeResponse(response(List.of(new TextPart("a")), c.stop(), null), null, ctx()));
            assertEquals(c.wire(), json.path("stop_reason").asString(), c.stop().name());
        }
    }

    @Test
    void encodeResponseUnknownOrMissingStopNeverFakesSuccess() {
        UpstreamException unknown = assertThrows(UpstreamException.class,
                () -> codec.encodeResponse(response(List.of(new TextPart("a")), StopReason.UNKNOWN, null), null, ctx()));
        assertEquals("upstream_error", unknown.code());
        assertEquals(502, unknown.status());
        UpstreamException missing = assertThrows(UpstreamException.class,
                () -> codec.encodeResponse(response(List.of(new TextPart("a")), null, null), null, ctx()));
        assertEquals(502, missing.status());
    }

    /** §9 关键不对称：input_tokens＝缓存 MISS（total−hit），不是 total；cache_creation 恒 0；无 total_tokens。 */
    @Test
    void encodeResponseUsageIsAsymmetricMissNotTotal() {
        JsonNode full = MAPPER.readTree(codec.encodeResponse(
                response(List.of(new TextPart("a")), StopReason.COMPLETED, new Usage(120L, 40L, 12L, 80L)), null, ctx()));
        assertEquals(fixtureJson("expected-usage-full.json"), full.path("usage"));
        assertEquals(40, full.path("usage").path("input_tokens").asLong(), "120 total − 80 hit ＝ 40 miss");
        assertTrue(full.path("usage").path("total_tokens").isMissingNode(), "Messages 无 total_tokens");
        assertEquals(fixtureJson("expected-usage-no-details.json"), MAPPER.readTree(codec.encodeResponse(
                response(List.of(new TextPart("a")), StopReason.COMPLETED, new Usage(120L, 40L, null, 80L)), null, ctx()))
                .path("usage"));
        assertEquals(fixtureJson("expected-usage-unknown-cache.json"), MAPPER.readTree(codec.encodeResponse(
                response(List.of(new TextPart("a")), StopReason.COMPLETED, new Usage(120L, 40L, 12L, null)), null, ctx()))
                .path("usage"));
    }

    @Test
    void encodeResponseUsageOmitsUnknownAndNeverFakesZero() {
        // reasoning 未知 → 整个 output_tokens_details 省略（thinking_tokens 是必填 int，不得填 0）。
        JsonNode noReasoning = MAPPER.readTree(codec.encodeResponse(
                response(List.of(new TextPart("a")), StopReason.COMPLETED, new Usage(10L, 5L, null, 0L)), null, ctx()));
        assertTrue(noReasoning.path("usage").path("output_tokens_details").isMissingNode());
        // output 未知 → 省略 output_tokens，但已知的输入侧仍给出。
        JsonNode noOutput = MAPPER.readTree(codec.encodeResponse(
                response(List.of(new TextPart("a")), StopReason.COMPLETED, new Usage(10L, null, null, 2L)), null, ctx()));
        assertTrue(noOutput.path("usage").path("output_tokens").isMissingNode());
        assertEquals(8, noOutput.path("usage").path("input_tokens").asLong());
        assertEquals(2, noOutput.path("usage").path("cache_read_input_tokens").asLong());
        // input 未知 → 省略 input_tokens，不得用 0 或 output 推算。
        JsonNode noInput = MAPPER.readTree(codec.encodeResponse(
                response(List.of(new TextPart("a")), StopReason.COMPLETED, new Usage(null, 5L, null, null)), null, ctx()));
        assertTrue(noInput.path("usage").path("input_tokens").isMissingNode());
        assertTrue(noInput.path("usage").path("cache_read_input_tokens").isMissingNode());
        assertEquals(5, noInput.path("usage").path("output_tokens").asLong());
        assertEquals(0, noInput.path("usage").path("cache_creation_input_tokens").asLong());
        // 零命中是已知事实：input_tokens == total。
        assertEquals(10, MAPPER.readTree(codec.encodeResponse(
                response(List.of(new TextPart("a")), StopReason.COMPLETED, new Usage(10L, 5L, null, 0L)), null, ctx()))
                .path("usage").path("input_tokens").asLong());
    }

    @Test
    void encodeResponseUsageRejectsCacheHitAboveTotal() {
        UpstreamException error = assertThrows(UpstreamException.class, () -> codec.encodeResponse(
                response(List.of(new TextPart("a")), StopReason.COMPLETED, new Usage(10L, 5L, null, 20L)), null, ctx()));
        assertEquals(502, error.status());
    }

    @Test
    void encodeResponseMintsContinuationEnvelopeOnThinkingBlock() {
        JsonNode json = MAPPER.readTree(codec.encodeResponse(
                response(List.of(new ThinkingPart("reason", "llmgw1:payload.hmac")), StopReason.COMPLETED, null), null, ctx()));
        JsonNode block = json.path("content").get(0);
        assertEquals("thinking", block.path("type").asString());
        assertEquals("reason", block.path("thinking").asString());
        String token = block.path("signature").asString();
        // 矩阵 §7.2/§7.3：signature 承载网关命名空间信封，绝不伪造供应商签名，也不回显入参 continuationToken。
        assertFalse(token.isBlank(), "encodeResponse 必须铸造续轮信封 signature");
        assertTrue(token.startsWith("llmgw1:"), "信封令牌格式 llmgw1:<payload>.<hmac>");
        assertNotEquals("llmgw1:payload.hmac", token, "不得把上游 continuationToken 当签名回显");
        ENVELOPE.verify(token, "project-1", "reason");
        assertEquals("project-1", envelopePayload(token).path("projectId").asString());
        assertFalse(envelopePayload(token).path("itemId").asString().isBlank(), "itemId 必须非空并绑定进信封");
        // 摘要绑定的是本块明文：换文本即校验失败（独立真值＝测试密钥 + 规格文本）。
        CapabilityException mismatch = assertThrows(CapabilityException.class,
                () -> ENVELOPE.verify(token, "project-1", "other text"));
        assertEquals(CONTINUATION_INVALID, mismatch.violations().get(0).code());
    }

    @Test
    void encodeResponseJoinedReasoningMintsEnvelopeOverFullText() {
        // 多个 ThinkingPart 合成一个 thinking 块：信封摘要必须覆盖拼接后的全文，而不是首段。
        JsonNode json = MAPPER.readTree(codec.encodeResponse(
                response(List.of(new ThinkingPart("first ", null), new ThinkingPart("second", null)),
                        StopReason.COMPLETED, null), null, ctx()));
        JsonNode block = json.path("content").get(0);
        assertEquals("first second", block.path("thinking").asString());
        ENVELOPE.verify(block.path("signature").asString(), "project-1", "first second");
    }

    @Test
    void encodeResponseThinkingRoundTripsThroughDecodeRestoringContinuationToken() {
        // 真实 encode→decode 往返：encodeResponse 铸的信封须能被同一项目 ctx 的 decode 校验并还原 continuationToken。
        JsonNode json = MAPPER.readTree(codec.encodeResponse(
                response(List.of(new ThinkingPart("round trip reasoning", null), new TextPart("answer")),
                        StopReason.COMPLETED, null), "client-model", ctx()));
        JsonNode block = json.path("content").get(0);
        String token = block.path("signature").asString();
        ThinkingPart thinking = decodeRebuiltThinking((ObjectNode) block, ctx());
        assertEquals("round trip reasoning", thinking.reasoning());
        assertEquals(token, thinking.continuationToken(), "encode 铸的信封须原样还原到 continuationToken");
    }

    @Test
    void encodeResponseToolUseInputIsParsedBackToObject() {
        JsonNode json = MAPPER.readTree(codec.encodeResponse(
                response(List.of(new ToolCallPart("c", "ping", "{\"a\":1}")), StopReason.TOOL_CALL, null), null, ctx()));
        JsonNode block = json.path("content").get(0);
        assertEquals("tool_use", block.path("type").asString());
        assertEquals("c", block.path("id").asString());
        assertEquals("ping", block.path("name").asString());
        assertTrue(block.path("input").isObject());
        assertEquals(1, block.path("input").path("a").asInt());
        // 空参数 → 空对象（不是 null / 不是字符串）。
        for (String args : new String[] {null, "", "   "}) {
            JsonNode empty = MAPPER.readTree(codec.encodeResponse(
                    response(List.of(new ToolCallPart("c", "ping", args)), StopReason.TOOL_CALL, null), null, ctx()));
            assertTrue(empty.path("content").get(0).path("input").isObject());
            assertEquals(0, empty.path("content").get(0).path("input").size());
        }
        // 非对象/非法 JSON 不可表达 → 502，绝不静默改写成空对象。
        for (String bad : List.of("[1]", "\"x\"", "{broken")) {
            UpstreamException error = assertThrows(UpstreamException.class, () -> codec.encodeResponse(
                    response(List.of(new ToolCallPart("c", "ping", bad)), StopReason.TOOL_CALL, null), null, ctx()));
            assertEquals(502, error.status());
        }
    }

    @Test
    void encodeResponseRejectsInexpressibleSemanticsInsteadOfRewriting() {
        SemanticResponse twoMessages = new SemanticResponse("m", List.of(
                new SemanticMessage(Role.ASSISTANT, List.of(new TextPart("a"))),
                new SemanticMessage(Role.ASSISTANT, List.of(new TextPart("b")))), StopReason.COMPLETED, null);
        assertEquals(502, assertThrows(UpstreamException.class,
                () -> codec.encodeResponse(twoMessages, "m", ctx())).status());
        SemanticResponse userRole = new SemanticResponse("m",
                List.of(new SemanticMessage(Role.USER, List.of(new TextPart("a")))), StopReason.COMPLETED, null);
        assertEquals(502, assertThrows(UpstreamException.class,
                () -> codec.encodeResponse(userRole, "m", ctx())).status());
        for (ContentPart part : List.of(new ImagePart("image/png", "QUJD", null, null),
                new ToolResultPart("c", "{}", false))) {
            UpstreamException error = assertThrows(UpstreamException.class, () -> codec.encodeResponse(
                    response(List.of(new TextPart("a"), part), StopReason.COMPLETED, null), null, ctx()));
            assertEquals("upstream_error", error.code());
            assertEquals(502, error.status());
        }
    }

    // ========== §10.2：错误形状 ==========

    @Test
    void errorJsonUsesMessagesShapeAndHttpStatus() {
        CapabilityException error = new CapabilityException(UNKNOWN, 400, "temperature", "未知请求字段: temperature");
        JsonNode json = MAPPER.readTree(codec.errorJson(error));
        assertEquals(Set.of("type", "error"), names(json));
        assertEquals("error", json.path("type").asString());
        assertEquals(Set.of("type", "message"), names(json.path("error")));
        assertEquals(UNKNOWN, json.path("error").path("type").asString());
        assertEquals("未知请求字段: temperature (field: temperature)", json.path("error").path("message").asString());
        assertEquals(400, error.status());
        CapabilityException upstream = new CapabilityException("upstream_error", 502, "messages", "上游异常终止");
        assertEquals("upstream_error",
                MAPPER.readTree(codec.errorJson(upstream)).path("error").path("type").asString());
        assertEquals(502, upstream.status());
    }

    @Test
    void errorEventUsesMessagesErrorFrame() {
        CapabilityException error = new CapabilityException(INVALID, 400, "max_tokens", "max_tokens is required");
        String frame = codec.errorEvent(error);
        assertTrue(frame.startsWith("event: error\n"), "Messages 错误帧必须带 event 名: " + frame);
        assertFalse(frame.endsWith("\n"), "帧间隔归上层 transcoder: " + frame);
        JsonNode payload = MAPPER.readTree(frame.substring(frame.indexOf("data: ") + "data: ".length()));
        assertEquals(Set.of("type", "error"), names(payload));
        assertEquals("error", payload.path("type").asString());
        assertEquals(INVALID, payload.path("error").path("type").asString());
        assertEquals("max_tokens is required (field: max_tokens)", payload.path("error").path("message").asString());
        // 错误事件与错误 JSON 必须同源同码。
        assertEquals(MAPPER.readTree(codec.errorJson(error)).path("error").path("type").asString(),
                payload.path("error").path("type").asString());
    }

    /** §10.1：每个错误必带 fieldPath＋reason；Messages 错误形状无 param 槽 → 折进 message 文本。 */
    @Test
    void errorBodyFoldsFieldPathIntoMessageText() {
        CapabilityException error = new CapabilityException(NOT_SUPPORTED, 400,
                "messages[0].content[0].source.url", "第一阶段仅支持内联图片");
        String frame = codec.errorEvent(error);
        List<String> bodies = List.of(codec.errorJson(error),
                frame.substring(frame.indexOf("data: ") + "data: ".length()));
        for (String body : bodies) {
            JsonNode json = MAPPER.readTree(body);
            assertEquals(Set.of("type", "message"), names(json.path("error")), "不得新增 Messages 未钉住的键");
            String message = json.path("error").path("message").asString();
            assertTrue(message.contains("第一阶段仅支持内联图片"), message);
            assertTrue(message.contains("messages[0].content[0].source.url"),
                    "fieldPath 必须出现在错误文本里: " + message);
        }
    }

    // ========== 往返：decode → encodeResponse → decode ==========

    /** 测试侧独立解析 Messages content[]：证明入口 JSON 未丢语义（不依赖被测编码器内部结构）。 */
    private static SemanticResponse readResponse(JsonNode json) {
        List<ContentPart> parts = new ArrayList<>();
        boolean hasToolCall = false;
        for (JsonNode block : json.path("content")) {
            switch (block.path("type").asString()) {
                case "thinking" -> parts.add(new ThinkingPart(block.path("thinking").asString(), null));
                case "text" -> parts.add(new TextPart(block.path("text").asString()));
                case "tool_use" -> {
                    hasToolCall = true;
                    parts.add(new ToolCallPart(block.path("id").asString(), block.path("name").asString(),
                            MAPPER.writeValueAsString(block.path("input"))));
                }
                default -> { }
            }
        }
        StopReason stop = switch (json.path("stop_reason").asString()) {
            case "end_turn" -> hasToolCall ? StopReason.TOOL_CALL : StopReason.COMPLETED;
            case "max_tokens" -> StopReason.LENGTH;
            case "tool_use" -> StopReason.TOOL_CALL;
            case "refusal" -> StopReason.CONTENT_FILTER;
            default -> StopReason.UNKNOWN;
        };
        JsonNode usage = json.path("usage");
        Usage parsed = usage.isMissingNode() ? null : new Usage(
                longOrNull(usage.path("input_tokens")),
                longOrNull(usage.path("output_tokens")),
                longOrNull(usage.path("output_tokens_details").path("thinking_tokens")),
                longOrNull(usage.path("cache_read_input_tokens")));
        return new SemanticResponse(json.path("id").asString(),
                List.of(new SemanticMessage(Role.ASSISTANT, parts)), stop, parsed);
    }

    @Test
    void responseRoundTripPreservesSemantics() {
        SemanticResponse original = new SemanticResponse("msg_rt",
                List.of(new SemanticMessage(Role.ASSISTANT, List.of(
                        new ThinkingPart("reason", null),
                        new TextPart("answer"),
                        new ToolCallPart("call_9", "ping", "{\"a\":1}")))),
                StopReason.TOOL_CALL, new Usage(120L, 40L, 12L, 80L));
        JsonNode json = MAPPER.readTree(codec.encodeResponse(original, "client-model", ctx()));
        SemanticResponse parsed = readResponse(json);
        assertEquals(original.id(), parsed.id());
        assertEquals(original.messages(), parsed.messages());
        assertEquals(original.stop(), parsed.stop());
        // usage 往返须按 Messages 语义还原：input_tokens 是 miss，加回 cache_read 才是语义 total。
        assertEquals(original.usage().outputTokens(), parsed.usage().outputTokens());
        assertEquals(original.usage().reasoningTokens(), parsed.usage().reasoningTokens());
        assertEquals(original.usage().cachedInputTokens(), parsed.usage().cachedInputTokens());
        assertEquals(original.usage().inputTokens(),
                parsed.usage().inputTokens() + parsed.usage().cachedInputTokens());
    }

    /** 续轮往返：客户端把响应 content[] 原样作为 assistant 历史回传 → decode 语义不变，信封令牌还原。 */
    @Test
    void continuationRoundTripPreservesAssistantTurn() {
        List<ContentPart> parts = List.of(new ThinkingPart("reason", null), new TextPart("answer"),
                new ToolCallPart("call_9", "ping", "{\"a\":1}"));
        SemanticResponse original = response(parts, StopReason.TOOL_CALL, new Usage(120L, 40L, 12L, 80L));
        JsonNode json = MAPPER.readTree(codec.encodeResponse(original, "client-model", ctx()));
        String token = json.path("content").get(0).path("signature").asString();

        ObjectNode root = baseRequest();
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "user").put("content", "hi");
        messages.addObject().put("role", "assistant").set("content", json.path("content"));
        SemanticRequest decoded = decode(MAPPER.writeValueAsString(root));
        // 明文/文本/tool_use 参数不得漂移；thinking 的 continuationToken 由 null 变为已校验的网关信封（矩阵 §7.3）。
        assertEquals(List.of(new ThinkingPart("reason", token), new TextPart("answer"),
                        new ToolCallPart("call_9", "ping", "{\"a\":1}")),
                decoded.messages().get(1).parts(), "thinking 明文/信封/文本/tool_use 参数往返不得漂移");
    }

    // ========== §6.4：事件序 / 块生命周期 / 恰一次终态 ==========

    /** Messages 事件行＝event 名 + data JSON；事件名必须等于 JSON type，不含结尾换行（帧间隔归上层）。 */
    private static JsonNode payload(String line) {
        assertTrue(line.startsWith("event: "), "Messages 事件行必须带 event 名: " + line);
        int nl = line.indexOf('\n');
        assertTrue(nl > 0, "event 与 data 必须分行: " + line);
        String eventName = line.substring("event: ".length(), nl);
        assertTrue(line.startsWith("data: ", nl + 1), "第二行必须是 data 框架: " + line);
        assertFalse(line.endsWith("\n"), "帧间隔归上层 transcoder: " + line);
        assertFalse(line.contains("[DONE]"), "Messages 无 data:[DONE] 帧: " + line);
        JsonNode node = MAPPER.readTree(line.substring(nl + 1 + "data: ".length()));
        assertEquals(eventName, node.path("type").asString(), "事件名必须等于 JSON type");
        return node;
    }

    private static List<JsonNode> payloads(List<String> lines) {
        return lines.stream().map(MessagesEntryCodecTest::payload).toList();
    }

    private static List<String> types(List<String> lines) {
        return payloads(lines).stream().map(node -> node.path("type").asString()).toList();
    }

    @SafeVarargs
    private static List<String> concat(List<String>... chunks) {
        List<String> all = new ArrayList<>();
        for (List<String> chunk : chunks) {
            all.addAll(chunk);
        }
        return all;
    }

    private StreamEncoder encoder() {
        return codec.newStreamEncoder("deepseek-flash", false, ctx());
    }

    private static List<String> drain(StreamEncoder encoder, SemanticEvent... events) {
        List<String> lines = new ArrayList<>();
        for (SemanticEvent event : events) {
            lines.addAll(encoder.onEvent(event));
        }
        return lines;
    }

    /** 块索引连续（0,1,2…）、每块 start→delta*→stop 闭合、delta 必落在未闭合块上。 */
    private static void assertBlockLifecycle(List<JsonNode> nodes) {
        int next = 0;
        Set<Integer> open = new HashSet<>();
        List<Integer> opened = new ArrayList<>();
        for (JsonNode node : nodes) {
            switch (node.path("type").asString()) {
                case "content_block_start" -> {
                    int index = node.path("index").asInt();
                    assertEquals(next, index, "块索引必须连续递增");
                    next++;
                    assertTrue(open.add(index), "同一 index 不得重复开启");
                    opened.add(index);
                }
                case "content_block_delta" -> assertTrue(open.contains(node.path("index").asInt()),
                        "delta 必须落在已开启且未闭合的块上");
                case "content_block_stop" -> assertTrue(open.remove(node.path("index").asInt()),
                        "stop 必须闭合一个已开启的块");
                default -> { }
            }
        }
        assertTrue(open.isEmpty(), "每个块都必须闭合");
        assertEquals(opened, java.util.stream.IntStream.range(0, next).boxed().toList());
    }

    private static void assertOneMessageStop(List<String> lines) {
        assertEquals(1, types(lines).stream().filter("message_stop"::equals).count(),
                "message_stop 必须恰一次: " + types(lines));
    }

    private static JsonNode nodeOfType(List<JsonNode> nodes, String type) {
        return nodes.stream().filter(n -> type.equals(n.path("type").asString())).findFirst().orElseThrow();
    }

    @Test
    void streamTextOnlyEmitsFullBlockLifecycleInOrder() {
        StreamEncoder encoder = encoder();
        List<String> lines = concat(drain(encoder, new TextDelta("Hel"), new TextDelta("lo"),
                new TerminalEvent(StopReason.COMPLETED)), encoder.onComplete());

        assertEquals(List.of("message_start", "content_block_start", "content_block_delta", "content_block_delta",
                "content_block_stop", "message_delta", "message_stop"), types(lines));
        List<JsonNode> nodes = payloads(lines);
        assertBlockLifecycle(nodes);
        assertOneMessageStop(lines);
        assertTrue(encoder.terminalSeen());
        assertEquals(List.of(), encoder.onComplete(), "Messages 无 [DONE]，重复 onComplete 必须为空");

        JsonNode message = nodes.get(0).path("message");
        assertEquals("assistant", message.path("role").asString());
        assertEquals("message", message.path("type").asString());
        assertEquals("deepseek-flash", message.path("model").asString());
        assertEquals("[]", message.path("content").toString());
        assertTrue(message.path("stop_reason").isNull());
        assertFalse(message.path("id").asString().isBlank());

        assertEquals("text", nodes.get(1).path("content_block").path("type").asString());
        assertEquals("", nodes.get(1).path("content_block").path("text").asString());
        assertEquals(0, nodes.get(1).path("index").asInt());
        assertEquals("text_delta", nodes.get(2).path("delta").path("type").asString());
        assertEquals("Hel", nodes.get(2).path("delta").path("text").asString());
        assertEquals("lo", nodes.get(3).path("delta").path("text").asString());
        assertEquals(0, nodes.get(4).path("index").asInt());
        assertEquals("end_turn", nodes.get(5).path("delta").path("stop_reason").asString());
        assertTrue(nodes.get(5).path("delta").path("stop_sequence").isNull());
    }

    @Test
    void streamThinkingThenTextMintsEnvelopeInSignatureDelta() {
        StreamEncoder encoder = encoder();
        List<String> lines = concat(drain(encoder, new ThinkingDelta("CoT-"), new ThinkingDelta("TEXT"),
                new TextDelta("ans"), new TerminalEvent(StopReason.COMPLETED)), encoder.onComplete());

        assertEquals(List.of("message_start",
                "content_block_start", "content_block_delta", "content_block_delta", "content_block_delta",
                "content_block_stop",
                "content_block_start", "content_block_delta", "content_block_stop",
                "message_delta", "message_stop"), types(lines));
        List<JsonNode> nodes = payloads(lines);
        assertBlockLifecycle(nodes);
        assertOneMessageStop(lines);

        JsonNode thinkingStart = nodes.get(1).path("content_block");
        assertEquals("thinking", thinkingStart.path("type").asString());
        assertEquals("", thinkingStart.path("thinking").asString());
        // SDK ThinkingBlock.signature 必填 str：开块时全文未知，只能给初始占位，由 signature_delta 填充。
        assertEquals("", thinkingStart.path("signature").asString());
        assertEquals(0, nodes.get(1).path("index").asInt());
        assertEquals("thinking_delta", nodes.get(2).path("delta").path("type").asString());
        assertEquals("CoT-", nodes.get(2).path("delta").path("thinking").asString());
        assertEquals("TEXT", nodes.get(3).path("delta").path("thinking").asString());
        // 矩阵 §6.4/§7.3：signature_delta 承载累计全文铸出的网关信封，绝不伪造供应商签名。
        assertEquals("signature_delta", nodes.get(4).path("delta").path("type").asString());
        String token = nodes.get(4).path("delta").path("signature").asString();
        assertTrue(token.startsWith("llmgw1:"), "signature_delta 必须是 llmgw1 信封: " + token);
        ENVELOPE.verify(token, "project-1", "CoT-TEXT");
        assertEquals("project-1", envelopePayload(token).path("projectId").asString());
        assertEquals(0, nodes.get(5).path("index").asInt());

        assertEquals(1, nodes.get(6).path("index").asInt());
        assertEquals("text", nodes.get(6).path("content_block").path("type").asString());
        assertEquals("text_delta", nodes.get(7).path("delta").path("type").asString());
        assertEquals("ans", nodes.get(7).path("delta").path("text").asString());
        assertEquals(1, nodes.get(8).path("index").asInt());
    }

    @Test
    void streamThinkingSignatureDeltaRoundTripsThroughDecode() {
        StreamEncoder encoder = encoder();
        List<String> lines = concat(drain(encoder, new ThinkingDelta("stream "), new ThinkingDelta("reasoning"),
                new TextDelta("ans"), new TerminalEvent(StopReason.COMPLETED)), encoder.onComplete());
        List<JsonNode> nodes = payloads(lines);
        // 客户端视角重建：明文＝thinking_delta 拼接，签名＝signature_delta；原样回传须校验通过。
        ObjectNode block = rebuiltThinkingBlock(nodes, 0);
        assertEquals("stream reasoning", block.path("thinking").asString());
        ThinkingPart thinking = decodeRebuiltThinking(block, ctx());
        assertEquals("stream reasoning", thinking.reasoning());
        assertEquals(block.path("signature").asString(), thinking.continuationToken(),
                "流式铸的信封须原样还原到 continuationToken");
    }

    @Test
    void streamThinkingClosedByToolCallMintsEnvelopeOverItsOwnText() {
        // 思考块被工具块闭合（非终态路径）：信封仍须绑定本块累计全文。
        StreamEncoder encoder = encoder();
        List<String> lines = concat(drain(encoder, new ThinkingDelta("think "), new ThinkingDelta("hard"),
                new ToolCallDelta(0, "call_1", "ping", "{}"),
                new TerminalEvent(StopReason.TOOL_CALL)), encoder.onComplete());
        List<JsonNode> nodes = payloads(lines);
        assertBlockLifecycle(nodes);
        ObjectNode block = rebuiltThinkingBlock(nodes, 0);
        assertEquals("think hard", block.path("thinking").asString());
        ENVELOPE.verify(block.path("signature").asString(), "project-1", "think hard");
        assertEquals("think hard", decodeRebuiltThinking(block, ctx()).reasoning());
    }

    @Test
    void streamEmptyThinkingDeltaStillMintsVerifiableEnvelope() {
        // 空增量不得让铸信封抛 IllegalArgumentException（itemId/文本均非 null）。
        StreamEncoder encoder = encoder();
        List<String> lines = concat(drain(encoder, new ThinkingDelta(""),
                new TerminalEvent(StopReason.COMPLETED)), encoder.onComplete());
        ObjectNode block = rebuiltThinkingBlock(payloads(lines), 0);
        assertEquals("", block.path("thinking").asString());
        ENVELOPE.verify(block.path("signature").asString(), "project-1", "");
        assertEquals("", decodeRebuiltThinking(block, ctx()).reasoning());
    }

    @Test
    void streamToolCallsEmitOneToolUseBlockPerCall() {
        StreamEncoder encoder = encoder();
        List<String> lines = concat(drain(encoder,
                new ToolCallDelta(0, "call_1", "ping", "{\"a\":"),
                new ToolCallDelta(0, null, null, "1}"),
                new ToolCallDelta(1, "call_2", "pong", "{}"),
                new TerminalEvent(StopReason.TOOL_CALL)), encoder.onComplete());

        assertEquals(List.of("message_start",
                "content_block_start", "content_block_delta", "content_block_delta", "content_block_stop",
                "content_block_start", "content_block_delta", "content_block_stop",
                "message_delta", "message_stop"), types(lines));
        List<JsonNode> nodes = payloads(lines);
        assertBlockLifecycle(nodes);
        assertOneMessageStop(lines);

        JsonNode first = nodes.get(1).path("content_block");
        assertEquals("tool_use", first.path("type").asString());
        assertEquals("call_1", first.path("id").asString());
        assertEquals("ping", first.path("name").asString());
        assertEquals("{}", first.path("input").toString());
        assertEquals("input_json_delta", nodes.get(2).path("delta").path("type").asString());
        assertEquals("{\"a\":", nodes.get(2).path("delta").path("partial_json").asString());
        assertEquals("1}", nodes.get(3).path("delta").path("partial_json").asString());
        assertEquals(0, nodes.get(4).path("index").asInt());
        assertEquals(1, nodes.get(5).path("index").asInt());
        assertEquals("call_2", nodes.get(5).path("content_block").path("id").asString());
        assertEquals("tool_use", nodeOfType(nodes, "message_delta").path("delta").path("stop_reason").asString());
    }

    @Test
    void streamMixedKeepsContiguousBlockIndicesAndThinkingTextToolOrder() {
        StreamEncoder encoder = encoder();
        List<String> lines = concat(drain(encoder,
                new ThinkingDelta("think"), new TextDelta("say"),
                new ToolCallDelta(0, "call_1", "ping", "{}"),
                new ToolCallDelta(1, "call_2", "pong", "{}"),
                new TerminalEvent(StopReason.TOOL_CALL)), encoder.onComplete());

        assertEquals(List.of("message_start",
                "content_block_start", "content_block_delta", "content_block_delta", "content_block_stop",
                "content_block_start", "content_block_delta", "content_block_stop",
                "content_block_start", "content_block_delta", "content_block_stop",
                "content_block_start", "content_block_delta", "content_block_stop",
                "message_delta", "message_stop"), types(lines));
        List<JsonNode> nodes = payloads(lines);
        assertBlockLifecycle(nodes);
        assertOneMessageStop(lines);
        List<String> blockTypes = nodes.stream()
                .filter(n -> "content_block_start".equals(n.path("type").asString()))
                .map(n -> n.path("content_block").path("type").asString()).toList();
        assertEquals(List.of("thinking", "text", "tool_use", "tool_use"), blockTypes);
        assertEquals(List.of(0, 1, 2, 3), nodes.stream()
                .filter(n -> "content_block_start".equals(n.path("type").asString()))
                .map(n -> n.path("index").asInt()).toList());
    }

    @Test
    void streamThinkingOnlyStillClosesBlockAndTerminatesOnce() {
        StreamEncoder encoder = encoder();
        List<String> lines = concat(drain(encoder, new ThinkingDelta("only think"),
                new TerminalEvent(StopReason.COMPLETED)), encoder.onComplete());
        assertEquals(List.of("message_start", "content_block_start", "content_block_delta", "content_block_delta",
                "content_block_stop", "message_delta", "message_stop"), types(lines));
        assertBlockLifecycle(payloads(lines));
        assertOneMessageStop(lines);
    }

    @Test
    void streamEmptyTerminalStillEmitsMessageStartAndOneMessageStop() {
        StreamEncoder encoder = encoder();
        List<String> lines = concat(drain(encoder, new TerminalEvent(StopReason.CONTENT_FILTER)),
                encoder.onComplete());
        assertEquals(List.of("message_start", "message_delta", "message_stop"), types(lines));
        assertEquals("refusal", nodeOfType(payloads(lines), "message_delta").path("delta").path("stop_reason").asString());
        assertOneMessageStop(lines);
        assertTrue(encoder.terminalSeen());
    }

    /**
     * §9/S10 + 钉住 SDK：message_start 必带 usage（input_tokens/output_tokens 均为必填 int），
     * message_delta 带真实输入侧＋输出侧；SDK accumulator 以累计总量**覆盖**而非相加
     * （anthropic/lib/streaming/_messages.py:547-548），故两侧同值不会双计。
     */
    @Test
    void streamUsageKnownBeforeFirstDeltaIsCarriedInStartAndFinalizedInDelta() {
        StreamEncoder encoder = encoder();
        List<String> lines = concat(drain(encoder, new UsageEvent(new Usage(120L, 40L, 12L, 80L)),
                new TextDelta("a"), new TerminalEvent(StopReason.COMPLETED)), encoder.onComplete());
        List<JsonNode> nodes = payloads(lines);
        JsonNode startUsage = nodes.get(0).path("message").path("usage");
        assertEquals(40, startUsage.path("input_tokens").asLong(), "输入侧＝miss（120−80）");
        assertEquals(80, startUsage.path("cache_read_input_tokens").asLong());
        assertEquals(0, startUsage.path("cache_creation_input_tokens").asLong());
        assertEquals(0, startUsage.path("output_tokens").asLong(), "SDK 必填；此刻尚无输出＝事实 0");
        assertTrue(startUsage.path("total_tokens").isMissingNode());

        JsonNode deltaUsage = nodeOfType(nodes, "message_delta").path("usage");
        assertEquals(40, deltaUsage.path("output_tokens").asLong());
        assertEquals(12, deltaUsage.path("output_tokens_details").path("thinking_tokens").asLong());
        assertEquals(40, deltaUsage.path("input_tokens").asLong(), "累计总量覆盖，绝不是 40+40");
        assertEquals(80, deltaUsage.path("cache_read_input_tokens").asLong());
        assertEquals(0, deltaUsage.path("cache_creation_input_tokens").asLong());
        assertTrue(deltaUsage.path("total_tokens").isMissingNode());
        assertOneMessageStop(lines);
    }

    /** DeepSeek 真实事件序：内容增量 → UsageEvent → TerminalEvent；已知输入侧绝不被丢弃。 */
    @Test
    void streamRealUpstreamOrderFinalizesUsageWithoutDroppingInputSide() {
        StreamEncoder encoder = encoder();
        List<String> lines = concat(drain(encoder, new TextDelta("a"),
                new UsageEvent(new Usage(120L, 40L, 12L, 80L)),
                new TerminalEvent(StopReason.COMPLETED)), encoder.onComplete());
        List<JsonNode> nodes = payloads(lines);
        JsonNode startUsage = nodes.get(0).path("message").path("usage");
        assertFalse(startUsage.isMissingNode(), "SDK Usage 必填：message_start 必须带 usage");
        assertEquals(0, startUsage.path("input_tokens").asLong(), "此刻输入未知 → SDK 必需初始占位，由 delta 覆盖");
        assertEquals(0, startUsage.path("output_tokens").asLong());
        assertTrue(startUsage.path("cache_read_input_tokens").isMissingNode(), "未知≠0");

        JsonNode deltaUsage = nodeOfType(nodes, "message_delta").path("usage");
        assertEquals(40, deltaUsage.path("input_tokens").asLong(), "miss＝120−80：真实输入侧不得丢");
        assertEquals(80, deltaUsage.path("cache_read_input_tokens").asLong());
        assertEquals(0, deltaUsage.path("cache_creation_input_tokens").asLong());
        assertEquals(40, deltaUsage.path("output_tokens").asLong());
        assertEquals(12, deltaUsage.path("output_tokens_details").path("thinking_tokens").asLong());
        assertTrue(deltaUsage.path("total_tokens").isMissingNode());
        assertOneMessageStop(lines);
    }

    @Test
    void streamUsageUnknownStillEmitsSdkRequiredUsageAndOmitsUnknownInputSide() {
        StreamEncoder encoder = encoder();
        List<String> lines = concat(drain(encoder, new TextDelta("a"),
                new TerminalEvent(StopReason.COMPLETED)), encoder.onComplete());
        List<JsonNode> nodes = payloads(lines);
        JsonNode startUsage = nodes.get(0).path("message").path("usage");
        assertFalse(startUsage.isMissingNode(), "SDK Usage.input_tokens/output_tokens 必填，不得整块省略");
        assertEquals(0, startUsage.path("input_tokens").asLong());
        assertEquals(0, startUsage.path("output_tokens").asLong());
        assertTrue(startUsage.path("cache_read_input_tokens").isMissingNode());
        JsonNode deltaUsage = nodeOfType(nodes, "message_delta").path("usage");
        assertFalse(deltaUsage.isMissingNode(), "SDK MessageDeltaUsage.output_tokens 必填");
        assertTrue(deltaUsage.path("input_tokens").isMissingNode(), "输入侧未知 → 省略，不得填 0");
        assertTrue(deltaUsage.path("cache_read_input_tokens").isMissingNode());
        assertTrue(deltaUsage.path("output_tokens_details").isMissingNode());

        // 已知输入侧＋未知 reasoning：输入侧照实给，details 整块省略（thinking_tokens 是必填 int）。
        StreamEncoder partial = encoder();
        List<String> partialLines = concat(drain(partial, new TextDelta("a"),
                new UsageEvent(new Usage(10L, 5L, null, 0L)),
                new TerminalEvent(StopReason.COMPLETED)), partial.onComplete());
        JsonNode partialDelta = nodeOfType(payloads(partialLines), "message_delta").path("usage");
        assertEquals(5, partialDelta.path("output_tokens").asLong());
        assertEquals(10, partialDelta.path("input_tokens").asLong(), "零命中是已知事实：miss＝total");
        assertEquals(0, partialDelta.path("cache_read_input_tokens").asLong());
        assertTrue(partialDelta.path("output_tokens_details").isMissingNode(),
                "thinking_tokens 未知 → 省略整个 details，不得填 0");

        // hit 未知 → miss 不可计算：省略 input_tokens，绝不把未知命中当 0 伪造 miss。
        StreamEncoder unknownHit = encoder();
        List<String> hitLines = concat(drain(unknownHit, new TextDelta("a"),
                new UsageEvent(new Usage(120L, 5L, null, null)),
                new TerminalEvent(StopReason.COMPLETED)), unknownHit.onComplete());
        JsonNode hitDelta = nodeOfType(payloads(hitLines), "message_delta").path("usage");
        assertEquals(5, hitDelta.path("output_tokens").asLong());
        assertTrue(hitDelta.path("input_tokens").isMissingNode());
        assertTrue(hitDelta.path("cache_read_input_tokens").isMissingNode());
        assertEquals(0, payloads(hitLines).get(0).path("message").path("usage").path("input_tokens").asLong(),
                "miss 不可计算 → start 用 SDK 必需占位 0，最终由 delta 决定（此处仍未知故省略）");
    }

    @Test
    void streamUsageEventAloneNeverEmitsAFrame() {
        StreamEncoder encoder = encoder();
        assertEquals(List.of(), encoder.onEvent(new UsageEvent(new Usage(1L, 2L, 3L, 4L))),
                "usage 只缓冲到 message_start/message_delta，绝不单独成帧");
        assertFalse(encoder.terminalSeen());
    }

    @Test
    void streamTerminalMappingCoversEndTurnMaxTokensToolUseRefusal() {
        record Case(StopReason stop, String wire) { }
        for (Case c : List.of(new Case(StopReason.COMPLETED, "end_turn"), new Case(StopReason.LENGTH, "max_tokens"),
                new Case(StopReason.TOOL_CALL, "tool_use"), new Case(StopReason.CONTENT_FILTER, "refusal"))) {
            StreamEncoder encoder = encoder();
            List<String> lines = concat(drain(encoder, new TextDelta("a"), new TerminalEvent(c.stop())),
                    encoder.onComplete());
            assertEquals(c.wire(), nodeOfType(payloads(lines), "message_delta").path("delta").path("stop_reason")
                    .asString(), c.stop().name());
            assertOneMessageStop(lines);
            assertTrue(encoder.terminalSeen());
        }
    }

    @Test
    void streamUnknownStopEmitsErrorAndNoMessageStop() {
        StreamEncoder encoder = encoder();
        List<String> lines = concat(drain(encoder, new TextDelta("partial"),
                new TerminalEvent(StopReason.UNKNOWN)), encoder.onComplete());
        List<String> types = types(lines);
        assertTrue(encoder.terminalSeen());
        assertEquals(List.of("message_start", "content_block_start", "content_block_delta", "content_block_stop",
                "error"), types);
        assertTrue(types.stream().noneMatch("message_stop"::equals), "异常终止不得伪造 message_stop 成功");
        assertTrue(types.stream().noneMatch("message_delta"::equals));
        JsonNode error = payloads(lines).get(lines.size() - 1).path("error");
        assertEquals("upstream_error", error.path("type").asString());
        assertFalse(error.path("message").asString().isBlank());
        assertEquals(List.of(), encoder.onComplete());
    }

    @Test
    void streamErrorEmitsErrorEventAndNeverASuccessTerminal() {
        StreamEncoder encoder = encoder();
        List<String> lines = concat(drain(encoder, new TextDelta("partial")),
                encoder.onError("upstream_error", "上游截断"), encoder.onComplete());
        assertEquals(List.of("message_start", "content_block_start", "content_block_delta", "error"), types(lines));
        assertTrue(encoder.terminalSeen());
        JsonNode error = payloads(lines).get(lines.size() - 1);
        assertEquals(Set.of("type", "error"), names(error));
        assertEquals("upstream_error", error.path("error").path("type").asString());
        assertEquals("上游截断", error.path("error").path("message").asString());
        // 终态后幂等：不得再追加任何帧。
        assertEquals(List.of(), encoder.onEvent(new TextDelta("late")));
        assertEquals(List.of(), encoder.onEvent(new TerminalEvent(StopReason.COMPLETED)));
        assertEquals(List.of(), encoder.onError("upstream_error", "late"));
        assertEquals(List.of(), encoder.onComplete());
    }

    @Test
    void streamErrorFallsBackOnBlankCodeAndMessage() {
        for (String[] blank : List.of(new String[] {null, null}, new String[] {"", ""}, new String[] {"  ", "  "})) {
            StreamEncoder encoder = encoder();
            List<String> lines = encoder.onError(blank[0], blank[1]);
            assertEquals(1, lines.size(), "空 code/message 也必须落恰一帧错误");
            JsonNode error = payloads(lines).get(0);
            assertEquals("error", error.path("type").asString());
            assertEquals("internal_error", error.path("error").path("type").asString());
            assertFalse(error.path("error").path("message").asString().isBlank());
            assertTrue(encoder.terminalSeen());
            assertEquals(List.of(), encoder.onComplete());
        }
    }

    @Test
    void streamWithoutTerminalRefusesToFakeSuccess() {
        StreamEncoder encoder = encoder();
        drain(encoder, new TextDelta("partial"));
        assertFalse(encoder.terminalSeen());
        IllegalStateException error = assertThrows(IllegalStateException.class, encoder::onComplete);
        assertTrue(error.getMessage().contains("terminal"), error.getMessage());
        assertFalse(encoder.terminalSeen(), "缺终态不得被 onComplete 伪造成成功");
    }

    @Test
    void streamIncompleteToolCallEmitsOneErrorTerminalWithoutFabricatingBlock() {
        for (ToolCallDelta bad : List.of(new ToolCallDelta(0, null, "ping", "{}"),
                new ToolCallDelta(0, "call_1", null, "{}"),
                new ToolCallDelta(0, "  ", "ping", "{}"))) {
            StreamEncoder encoder = encoder();
            List<String> lines = concat(drain(encoder, bad, new TerminalEvent(StopReason.TOOL_CALL)),
                    encoder.onComplete());
            List<String> types = types(lines);
            assertTrue(encoder.terminalSeen());
            assertEquals(1, types.stream().filter("error"::equals).count(), types.toString());
            assertTrue(types.stream().noneMatch("message_stop"::equals), "不完整工具调用不得伪造成功终态");
            assertTrue(types.stream().noneMatch("content_block_start"::equals),
                    "不得伪造空 id/name 的 tool_use 块: " + types);
            JsonNode error = payloads(lines).get(lines.size() - 1).path("error");
            assertEquals("upstream_error", error.path("type").asString());
            assertTrue(error.path("message").asString().contains("0"), "错误消息须指明 index");
            assertEquals(List.of(), encoder.onEvent(new TerminalEvent(StopReason.COMPLETED)));
        }
    }

    @Test
    void streamLateDeltaForClosedToolIndexEmitsOneErrorTerminal() {
        StreamEncoder encoder = encoder();
        List<String> lines = concat(drain(encoder,
                new ToolCallDelta(0, "call_1", "ping", "{\"a\":"),
                new ToolCallDelta(1, "call_2", "pong", "{}"),
                new ToolCallDelta(0, null, null, "1}"),
                new TerminalEvent(StopReason.TOOL_CALL)), encoder.onComplete());
        List<String> types = types(lines);
        assertTrue(encoder.terminalSeen());
        assertEquals(1, types.stream().filter("error"::equals).count());
        assertTrue(types.stream().noneMatch("message_stop"::equals), "非连续增量不得静默截断成成功");
        JsonNode error = payloads(lines).get(lines.size() - 1).path("error");
        assertEquals("upstream_error", error.path("type").asString());
        assertTrue(error.path("message").asString().contains("non-contiguous"), error.path("message").asString());
        assertEquals(List.of(), encoder.onEvent(new TerminalEvent(StopReason.COMPLETED)));
    }

    /** 重开块只承载自己的增量：每块独立 index + 即时增量，且信封只绑定本块累计全文（缓冲重开清空）。 */
    @Test
    void streamReopenedThinkingBlockCarriesOnlyItsOwnDeltas() {
        StreamEncoder encoder = encoder();
        List<String> lines = concat(drain(encoder,
                new ThinkingDelta("first"), new TextDelta("mid"), new ThinkingDelta("second"),
                new TerminalEvent(StopReason.COMPLETED)), encoder.onComplete());
        List<JsonNode> nodes = payloads(lines);
        assertBlockLifecycle(nodes);
        List<String> firstBlock = thinkingDeltas(nodes, 0);
        List<String> thirdBlock = thinkingDeltas(nodes, 2);
        assertEquals(List.of("first"), firstBlock, "index 0 只含自己的 thinking_delta");
        assertEquals(List.of("second"), thirdBlock, "重开的 thinking 块不得拼接上一轮脏文本");
        // 矩阵 §7.3：每块闭合前恰一个 signature_delta，各铸各的信封（摘要只覆盖本块全文）。
        assertEquals(2, nodes.stream().filter(n -> "signature_delta".equals(n.path("delta").path("type").asString()))
                .count());
        ObjectNode first = rebuiltThinkingBlock(nodes, 0);
        ObjectNode second = rebuiltThinkingBlock(nodes, 2);
        String t1 = first.path("signature").asString();
        String t2 = second.path("signature").asString();
        ENVELOPE.verify(t1, "project-1", "first");
        ENVELOPE.verify(t2, "project-1", "second");
        assertNotEquals(t1, t2, "不同 thinking 块须铸不同信封");
        assertNotEquals(envelopePayload(t1).path("itemId").asString(), envelopePayload(t2).path("itemId").asString());
        // 负控：若重开时未清缓冲，t1 会绑定 "firstsecond" → 这里必须校验失败。
        CapabilityException dirty = assertThrows(CapabilityException.class,
                () -> ENVELOPE.verify(t1, "project-1", "firstsecond"));
        assertEquals(CONTINUATION_INVALID, dirty.violations().get(0).code());
        // 两块各自原样回传均须还原明文 + 令牌。
        assertEquals(new ThinkingPart("first", t1), decodeRebuiltThinking(first, ctx()));
        assertEquals(new ThinkingPart("second", t2), decodeRebuiltThinking(second, ctx()));
        assertOneMessageStop(lines);
    }

    private static List<String> thinkingDeltas(List<JsonNode> nodes, int index) {
        return nodes.stream()
                .filter(n -> "content_block_delta".equals(n.path("type").asString())
                        && n.path("index").asInt() == index
                        && "thinking_delta".equals(n.path("delta").path("type").asString()))
                .map(n -> n.path("delta").path("thinking").asString()).toList();
    }

    private static List<String> textDeltas(List<JsonNode> nodes, int index) {
        return nodes.stream()
                .filter(n -> "content_block_delta".equals(n.path("type").asString())
                        && n.path("index").asInt() == index
                        && "text_delta".equals(n.path("delta").path("type").asString()))
                .map(n -> n.path("delta").path("text").asString()).toList();
    }

    @Test
    void streamReopenedTextBlockCarriesOnlyItsOwnDeltas() {
        StreamEncoder encoder = encoder();
        List<String> lines = concat(drain(encoder,
                new TextDelta("first"), new ThinkingDelta("mid"), new TextDelta("second"),
                new TerminalEvent(StopReason.COMPLETED)), encoder.onComplete());
        List<JsonNode> nodes = payloads(lines);
        assertBlockLifecycle(nodes);
        assertEquals(List.of("first"), textDeltas(nodes, 0));
        assertEquals(List.of("second"), textDeltas(nodes, 2));
        assertOneMessageStop(lines);
    }

    @Test
    void streamEncoderInstancesAreIsolatedOnSharedCodec() {
        StreamEncoder first = encoder();
        StreamEncoder second = encoder();
        assertNotSame(first, second);
        List<String> a = concat(drain(first, new TextDelta("a"), new TerminalEvent(StopReason.COMPLETED)),
                first.onComplete());
        List<String> b = concat(drain(second, new ThinkingDelta("t"), new TextDelta("b"),
                new TerminalEvent(StopReason.COMPLETED)), second.onComplete());
        assertEquals(6, a.size(), types(a).toString());
        assertEquals(10, b.size(), types(b).toString());
        assertOneMessageStop(a);
        assertOneMessageStop(b);
        assertFalse(payloads(a).get(0).path("message").path("id").asString()
                .equals(payloads(b).get(0).path("message").path("id").asString()), "每请求独立 message id");
        assertTrue(first.terminalSeen());
        assertTrue(second.terminalSeen());
    }

    @Test
    void streamModelIsOmittedWhenNull() {
        StreamEncoder encoder = codec.newStreamEncoder(null, true, ctx());
        List<String> lines = concat(drain(encoder, new TextDelta("a"),
                new TerminalEvent(StopReason.COMPLETED)), encoder.onComplete());
        assertTrue(payloads(lines).get(0).path("message").path("model").isMissingNode());
        assertOneMessageStop(lines);
    }

    @Test
    void streamEventsAfterTerminalAreIgnored() {
        StreamEncoder encoder = encoder();
        drain(encoder, new TextDelta("a"), new TerminalEvent(StopReason.COMPLETED));
        assertEquals(List.of(), encoder.onEvent(new TextDelta("late")));
        assertEquals(List.of(), encoder.onEvent(new ThinkingDelta("late")));
        assertEquals(List.of(), encoder.onEvent(new ToolCallDelta(0, "c", "ping", "{}")));
        assertEquals(List.of(), encoder.onEvent(new UsageEvent(new Usage(1L, 2L, 3L, 4L))));
        assertEquals(List.of(), encoder.onEvent(new TerminalEvent(StopReason.LENGTH)));
    }
}