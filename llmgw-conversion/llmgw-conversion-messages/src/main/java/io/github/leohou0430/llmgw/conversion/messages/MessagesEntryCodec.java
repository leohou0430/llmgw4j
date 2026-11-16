package io.github.leohou0430.llmgw.conversion.messages;

import io.github.leohou0430.llmgw.conversion.CapabilityContext;
import io.github.leohou0430.llmgw.conversion.CapabilityException;
import io.github.leohou0430.llmgw.conversion.CapabilityPolicy;
import io.github.leohou0430.llmgw.conversion.Classification;
import io.github.leohou0430.llmgw.conversion.ContentPart;
import io.github.leohou0430.llmgw.conversion.ContinuationEnvelope;
import io.github.leohou0430.llmgw.conversion.DegradationAudit;
import io.github.leohou0430.llmgw.conversion.EntryCodec;
import io.github.leohou0430.llmgw.conversion.FieldRule;
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
import io.github.leohou0430.llmgw.conversion.Violation;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Anthropic Messages 入口编解码（映射矩阵 §1.1 合同边界 / §5.3 全量分类 / §6.4 SSE / §8 图片 /
 * §9 usage 不对称 / §10.2 错误形状 / §11 降级）。
 * 无状态可共享：每请求状态归 {@link CapabilityContext#audit()} 与 {@link StreamEncoder} 实例。
 */
public final class MessagesEntryCodec implements EntryCodec {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 矩阵 §5.3/§4.4 的入口守卫：越界前置 400，不把非法值丢给上游。 */
    private static final int MAX_OUTPUT_TOKENS = 393216;
    private static final int MAX_STOP = 16;
    private static final int MAX_USER_BYTES = 512;
    private static final int MAX_IMAGES = 600;
    private static final long MAX_IMAGE_BYTES = 32L * 1024 * 1024;
    private static final long MAX_TOTAL_IMAGE_BYTES = 64L * 1024 * 1024;
    private static final Set<String> IMAGE_MEDIA_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    /** 矩阵 §1.1：Messages 的 output_config.effort 钉住取值（无 none/minimal/ultra）。 */
    private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");
    private static final Set<String> THINKING_DISPLAYS = Set.of("summarized", "omitted");
    private static final Pattern TOOL_NAME = Pattern.compile("[a-zA-Z0-9_-]{1,128}");
    /** 矩阵 §5.3：服务端工具一律 R；钉住 SDK 的类型名带 _YYYYMMDD 版本后缀。 */
    private static final Set<String> SERVER_TOOLS = Set.of("bash", "code_execution", "web_search", "web_fetch",
            "computer", "memory", "text_editor", "tool_search", "browser");
    private static final Pattern TOOL_VERSION_SUFFIX = Pattern.compile("_\\d{8}$");

    private static final String BUDGET_TO_EFFORT = "thinking.budget_to_effort";
    private static final String DISPLAY_RAW = "thinking.display_raw";
    private static final String FORCE_TO_AUTO = "tool_choice.force_to_auto";
    private static final String SCHEMA_TO_OBJECT = "response_format.schema_to_object";
    private static final String BUDGET_REASON = "精确 token 预算降级为粗 effort 档，不保证预算上限";
    private static final String DISPLAY_RAW_REASON = "网关固定回传原文推理，无法按 Anthropic 语义摘要或隐藏思考";
    private static final String FORCE_AUTO_REASON = "思考模式不强制工具调用，any/具名退化为 auto";
    private static final String SCHEMA_REASON = "不强制 JSON Schema，仅保证 json_object";

    /**
     * 矩阵 §11 只钉住粗档名（low/high/max），未钉住阈值；以下为网关定义的确定性分档：
     * {@code budget < 8192 → low}，{@code budget < 32768 → high}，否则 {@code max}。
     */
    private static final int BUDGET_LOW_EXCLUSIVE = 8192;
    private static final int BUDGET_HIGH_EXCLUSIVE = 32768;

    /** 错误帧兜底（矩阵 §10.1 internal_error→500）：上游给了空 code/message 也必须落合法错误帧。 */
    private static final String FALLBACK_ERROR_CODE = "internal_error";
    private static final String FALLBACK_ERROR_MESSAGE = "stream failed without an error detail";

    /** 图片容量守卫的客户端原因；由实际上限推导，避免文案与常量漂移（矩阵 §4.4/§8）。 */
    private static final String IMAGE_TOO_LARGE = "inline image exceeds " + MAX_IMAGE_BYTES / (1024 * 1024) + " MiB";
    private static final String IMAGES_TOO_LARGE =
            "inline images exceed " + MAX_TOTAL_IMAGE_BYTES / (1024 * 1024) + " MiB in total";

    @Override
    public ProtocolKind kind() {
        return ProtocolKind.MESSAGES;
    }

    @Override
    public SemanticRequest decode(String rawJson, CapabilityContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        return new RequestDecoder(ctx).decode(parseRequest(rawJson));
    }

    @Override
    public String encodeResponse(SemanticResponse resp, String model, CapabilityContext ctx) {
        Objects.requireNonNull(resp, "resp");
        Objects.requireNonNull(ctx, "ctx");
        // Messages 入口只表达单条 assistant message：多候选不可表达，绝不静默丢弃其余消息。
        if (resp.messages().size() > 1) {
            throw new UpstreamException("messages entry expresses a single assistant turn, got "
                    + resp.messages().size() + " messages", "messages", null);
        }
        if (!resp.messages().isEmpty() && resp.messages().get(0).role() != Role.ASSISTANT) {
            throw new UpstreamException("messages entry expresses an assistant turn, got role "
                    + resp.messages().get(0).role(), "role", null);
        }
        List<ContentPart> parts = resp.messages().isEmpty() ? List.of() : resp.messages().get(0).parts();
        // 镜像 Chat/Responses：assistant 部件只可表达 Text/Thinking/ToolCall，其余绝不静默丢弃。
        for (ContentPart part : parts) {
            if (!(part instanceof TextPart) && !(part instanceof ThinkingPart) && !(part instanceof ToolCallPart)) {
                throw new UpstreamException("messages entry cannot express assistant part: "
                        + part.getClass().getSimpleName(), "content", null);
            }
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", messageId(resp.id()));
        root.put("type", "message");
        root.put("role", "assistant");
        if (model != null) {
            root.put("model", model);
        }
        // 矩阵 §6.4 块顺序：thinking → text → tool_use。
        ArrayNode content = root.putArray("content");
        String reasoning = joinReasoning(parts);
        if (reasoning != null) {
            // 矩阵 §7.3：为 thinking 块铸造续轮信封，绑定本块 itemId + ctx 项目 + 推理原文摘要。
            content.add(thinkingBlock(reasoning, "th_" + UUID.randomUUID(), ctx));
        }
        String text = joinText(parts);
        if (text != null) {
            content.addObject().put("type", "text").put("text", text);
        }
        for (ContentPart part : parts) {
            if (part instanceof ToolCallPart call) {
                content.add(toolUseBlock(call));
            }
        }
        root.put("stop_reason", stopReason(resp.stop()));
        root.putNull("stop_sequence");
        JsonNode usage = usageJson(resp.usage());
        if (usage != null) {
            root.set("usage", usage);
        }
        return MAPPER.writeValueAsString(root);
    }

    @Override
    public StreamEncoder newStreamEncoder(String model, boolean includeUsage, CapabilityContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        return new Encoder(model, includeUsage, ctx);
    }

    @Override
    public String errorJson(CapabilityException e) {
        Objects.requireNonNull(e, "error");
        Violation first = e.violations().get(0);
        return MAPPER.writeValueAsString(errorBody(first.code(), first.reason(), first.fieldPath()));
    }

    @Override
    public String errorEvent(CapabilityException e) {
        Objects.requireNonNull(e, "error");
        Violation first = e.violations().get(0);
        return event("error", errorBody(first.code(), first.reason(), first.fieldPath()));
    }

    // ========== 共享渲染 ==========

    private static String messageId(String id) {
        return id == null || id.isBlank() ? "msg_" + UUID.randomUUID() : id;
    }

    /** 异常/未知终止绝不映射为成功（矩阵 §4.7/§6.4）。 */
    private static String stopReason(StopReason stop) {
        if (stop == null) {
            throw new UpstreamException("upstream response has no stop reason (incomplete response)",
                    "stop_reason", null);
        }
        return switch (stop) {
            case COMPLETED -> "end_turn";
            case LENGTH -> "max_tokens";
            case TOOL_CALL -> "tool_use";
            case CONTENT_FILTER -> "refusal";
            case UNKNOWN -> throw new UpstreamException("upstream stop reason is not expressible: UNKNOWN",
                    "stop_reason", null);
        };
    }

    private static String joinReasoning(List<ContentPart> parts) {
        StringBuilder reasoning = null;
        for (ContentPart part : parts) {
            if (part instanceof ThinkingPart thinking) {
                reasoning = reasoning == null ? new StringBuilder() : reasoning;
                reasoning.append(thinking.reasoning());
            }
        }
        return reasoning == null ? null : reasoning.toString();
    }

    private static String joinText(List<ContentPart> parts) {
        StringBuilder text = null;
        for (ContentPart part : parts) {
            if (part instanceof TextPart textPart) {
                text = text == null ? new StringBuilder() : text;
                text.append(textPart.text());
            }
        }
        return text == null ? null : text.toString();
    }

    /**
     * 矩阵 §7.2/§7.4：{@code thinking} 字段承载原文推理（非 Anthropic 式 summarized 显示）。
     * <p>{@code signature} 承载网关命名空间续轮信封（{@code llmgw1:<payload>.<hmac>}），由每请求
     * {@link CapabilityContext#envelope()} 铸造，绑定 ctx 的 projectId + 本块 itemId + 推理原文摘要；
     * HMAC 密钥归 ExtensionContext，codec 无状态、不持密钥，绝不伪造供应商签名。
     */
    private static ObjectNode thinkingBlock(String reasoning, String itemId, CapabilityContext ctx) {
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "thinking");
        block.put("thinking", reasoning);
        block.put("signature", ctx.envelope().mint(ctx.projectId(), itemId, reasoning));
        return block;
    }

    /** 矩阵 §5.3：tool_use.input 是 JSON 对象，语义合同的 argumentsJson 是字符串 → 此处解析回对象。 */
    private static ObjectNode toolUseBlock(ToolCallPart call) {
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "tool_use");
        block.put("id", call.id());
        block.put("name", call.name());
        String arguments = call.argumentsJson();
        JsonNode input;
        if (arguments == null || arguments.isBlank()) {
            input = MAPPER.createObjectNode();
        } else {
            try {
                input = MAPPER.readTree(arguments);
            } catch (RuntimeException e) {
                throw new UpstreamException("tool_use arguments are not valid JSON for tool " + call.name(),
                        "content.input", e);
            }
            if (input == null || !input.isObject()) {
                throw new UpstreamException("tool_use input must be a JSON object for tool " + call.name(),
                        "content.input", null);
            }
        }
        block.set("input", input);
        return block;
    }

    /**
     * 矩阵 §9 的 Messages 不对称：{@code input_tokens}＝缓存 MISS（语义 total − hit），
     * {@code cache_read_input_tokens}＝hit，{@code cache_creation_input_tokens}＝0（DeepSeek 无缓存写入），
     * 无 {@code total_tokens}。null＝未知≠0：
     * <ul>
     *   <li>total 未知 → 省略 input_tokens；</li>
     *   <li>hit 未知 → miss 不可计算，同样省略 input_tokens（绝不把未知命中当 0 伪造 miss）；</li>
     *   <li>reasoning 未知 → 省略整个 output_tokens_details（thinking_tokens 是必填 int，不得填 0）。</li>
     * </ul>
     */
    private static JsonNode usageJson(Usage usage) {
        if (usage == null || isFullyUnknown(usage)) {
            return null;
        }
        ObjectNode node = inputSideUsage(usage);
        if (usage.outputTokens() != null) {
            node.put("output_tokens", usage.outputTokens().longValue());
        }
        putThinkingTokens(node, usage);
        return node;
    }

    private static boolean isFullyUnknown(Usage usage) {
        return usage.inputTokens() == null && usage.outputTokens() == null
                && usage.reasoningTokens() == null && usage.cachedInputTokens() == null;
    }

    /** 输入侧 usage 渲染：miss／hit／cache_creation（矩阵 §9）；未知量一律省略（null≠0）。 */
    private static ObjectNode inputSideUsage(Usage usage) {
        ObjectNode node = MAPPER.createObjectNode();
        Long miss = missTokens(usage);
        if (miss != null) {
            node.put("input_tokens", miss.longValue());
        }
        if (usage.cachedInputTokens() != null) {
            node.put("cache_read_input_tokens", usage.cachedInputTokens().longValue());
        }
        // DeepSeek 无缓存写入（矩阵 §9/S10）：这是已知常量 0，不是对未知量的推算。
        node.put("cache_creation_input_tokens", 0);
        return node;
    }

    /**
     * message_start 的初始 usage（矩阵 §6.4/§9 ＋ 钉住 SDK 的必填约束）。
     * <p>SDK 的 {@code Usage.input_tokens} 与 {@code Usage.output_tokens} 都是必填 int，缺任一个都会让
     * 官方客户端在 message_start 上抛 ValidationError。DeepSeek 的真实事件序是
     * 「内容增量 → UsageEvent → TerminalEvent」，故此刻输入侧通常还不知道：用 {@code input_tokens:0}
     * 作 SDK 必需的**初始占位**；{@code output_tokens:0} 是事实（此刻尚无输出）。已知输入侧则直接给真实值。
     * <p>accumulator 在 message_delta 上以累计总量**覆盖**这些字段而非相加
     * （{@code anthropic/lib/streaming/_messages.py:547-548}），占位由真实值终结：
     * 最终累计 usage 恒为真实值，绝不是伪造的最终 0。
     */
    private static ObjectNode startUsage(Usage usage) {
        ObjectNode node = usage == null ? MAPPER.createObjectNode() : inputSideUsage(usage);
        if (!node.has("input_tokens")) {
            node.put("input_tokens", 0);
        }
        node.put("output_tokens", 0);
        return node;
    }

    /**
     * message_delta 的最终 usage（矩阵 §6.4/§9）：{@code output_tokens} 是 SDK
     * {@code MessageDeltaUsage} 的必填 int，且必须带上**已知的输入侧真实值**——真实序里 UsageEvent
     * 晚于首个增量，只发输出侧会把已知 input_tokens/cache_read/cache_creation 丢掉。
     * <p>accumulator 覆盖而非相加（{@code _messages.py:547-548}），故与 message_start 同值不会双计。
     * 未知输入侧一律省略（null≠0）；output_tokens 未知时只能落 SDK 必需的 0 占位
     * （DeepSeek 正常完成必报 usage，此分支仅覆盖上游漏报，不代表真实计费为 0）。
     */
    private static ObjectNode deltaUsage(Usage usage) {
        ObjectNode node = usage == null ? MAPPER.createObjectNode() : inputSideUsage(usage);
        node.put("output_tokens", usage == null || usage.outputTokens() == null
                ? 0L : usage.outputTokens().longValue());
        if (usage != null) {
            putThinkingTokens(node, usage);
        }
        return node;
    }

    private static void putThinkingTokens(ObjectNode node, Usage usage) {
        if (usage.reasoningTokens() != null) {
            node.putObject("output_tokens_details").put("thinking_tokens", usage.reasoningTokens().longValue());
        }
    }

    /** miss＝total−hit，仅在两者都已知时可计算；hit&gt;total 是上游合同缺陷，绝不静默钳制。 */
    private static Long missTokens(Usage usage) {
        Long total = usage.inputTokens();
        Long hit = usage.cachedInputTokens();
        if (total == null || hit == null) {
            return null;
        }
        long miss = total - hit;
        if (miss < 0) {
            throw new UpstreamException("cached input tokens (" + hit + ") exceed total input tokens (" + total + ")",
                    "usage.input_tokens", null);
        }
        return miss;
    }

    /**
     * 矩阵 §10.2：Messages 错误体＝{@code {"type":"error","error":{"type","message"}}}，type 承载稳定码。
     * <p>矩阵 §10.1 要求每个错误带 fieldPath＋reason，而 Messages 形状没有 param 槽：
     * 故把 fieldPath 折进 message 文本（{@code "<reason> (field: <path>)"}），不新增未钉住的键。
     * 流内上游错误无字段路径（fieldPath=null）→ 只给 reason。
     */
    private static ObjectNode errorBody(String code, String message, String fieldPath) {
        ObjectNode error = MAPPER.createObjectNode();
        error.put("type", code);
        error.put("message", fieldPath == null || fieldPath.isBlank()
                ? message : message + " (field: " + fieldPath + ")");
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "error");
        root.set("error", error);
        return root;
    }

    /**
     * 只产事件行（{@code event: <type>} + {@code data: <json>}），不含结尾换行；
     * 帧间隔归上层 transcoder（与 {@link StreamEncoder} 的行框架归属一致）。Messages 无 {@code data:[DONE]}。
     */
    private static String event(String type, JsonNode payload) {
        return "event: " + type + "\ndata: " + MAPPER.writeValueAsString(payload);
    }

    private static JsonNode parseRequest(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw invalid("request", "request body must be a JSON object");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(rawJson);
        } catch (RuntimeException e) {
            // 保留底层解析异常栈；面向客户端只给简洁原因，绝不回显 Jackson 的 [Source: …] 位置 dump。
            throw invalid("request", "invalid request JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw invalid("request", "request body must be a JSON object");
        }
        return root;
    }

    private static CapabilityException invalid(String fieldPath, String reason) {
        return new CapabilityException(CapabilityException.INVALID_VALUE, 400, fieldPath, reason);
    }

    private static CapabilityException invalid(String fieldPath, String reason, Throwable cause) {
        return new CapabilityException(CapabilityException.INVALID_VALUE, 400, fieldPath, reason, cause);
    }

    private static String requiredText(JsonNode node, String fieldPath) {
        if (!node.isTextual()) {
            throw invalid(fieldPath, fieldPath + " must be a JSON string");
        }
        return node.asString();
    }

    /**
     * base64 解码后字节数的下界：每 4 字符至多 3 字节，再扣掉至多 2 个 {@code '='} padding。
     * 仅用于解码前的容量守卫（下界已超限即拒绝，不必分配解码缓冲）；
     * 字符合法性与精确长度仍由 {@link Base64#getDecoder()} 判定。
     */
    private static long base64LowerBoundBytes(String data) {
        return Math.max(0L, data.length() / 4L * 3L - 2L);
    }

    // ========== 每请求解码状态 ==========

    private static final class RequestDecoder {

        private final DegradationAudit audit;
        private final String projectId;
        private final ContinuationEnvelope envelope;

        private String model;
        private List<ContentPart> system;
        private List<SemanticMessage> messages;
        private Integer maxTokens;
        private List<String> stop = List.of();
        private boolean stream;
        private List<ToolSpec> tools;
        private String toolChoice;
        private Boolean thinkingEnabled;
        /** output_config.effort 的显式档（优先于 thinking 推导档）。 */
        private String effort;
        /** thinking.adaptive / budget 降级推导出的档。 */
        private String derivedEffort;
        private String responseFormat;
        private String userId;
        private int imageCount;
        private long imageBytes;

        private RequestDecoder(CapabilityContext ctx) {
            this.audit = ctx.audit();
            this.projectId = ctx.projectId();
            this.envelope = ctx.envelope();
        }

        private SemanticRequest decode(JsonNode root) {
            // 分类用的思考模式：只有显式 thinking.disabled 才关思考；省略时上游默认开启（矩阵 §4.2）。
            boolean thinkingMode = isThinkingMode(root);
            for (Map.Entry<String, JsonNode> entry : root.properties()) {
                field(entry.getKey(), entry.getValue(), thinkingMode);
            }
            return assemble();
        }

        private static boolean isThinkingMode(JsonNode root) {
            JsonNode type = root.path("thinking").path("type");
            return !type.isTextual() || !"disabled".equals(type.asString());
        }

        private void field(String name, JsonNode value, boolean thinkingMode) {
            switch (name) {
                case "model" -> {
                    accept("model");
                    model = requiredText(value, "model");
                    if (model.isBlank()) {
                        throw invalid("model", "model must not be blank");
                    }
                }
                case "system" -> {
                    if (!value.isNull()) {
                        accept("system");
                        system = decodeSystem(value);
                    }
                }
                case "messages" -> {
                    accept("messages");
                    messages = decodeMessages(value);
                }
                case "max_tokens" -> maxTokens = outputTokens(value);
                case "stop_sequences" -> stop = decodeStop(value);
                case "stream" -> {
                    if (!value.isNull()) {
                        accept("stream");
                        if (!value.isBoolean()) {
                            throw invalid("stream", "stream must be a boolean");
                        }
                        stream = value.booleanValue();
                    }
                }
                // 矩阵 §1.1：stream_options 不在钉住的 Messages 字段全集内；§6.4 的 usage 恒在 message_delta，
                // 与 include_usage 无关 → 落 unknown_field（不是 Chat/Responses 的 E）。
                case "thinking" -> {
                    if (!value.isNull()) {
                        decodeThinking(value);
                    }
                }
                case "output_config" -> {
                    if (!value.isNull()) {
                        decodeOutputConfig(value);
                    }
                }
                case "tools" -> {
                    if (!value.isNull()) {
                        accept("tools");
                        tools = decodeTools(value);
                    }
                }
                case "tool_choice" -> {
                    if (!value.isNull()) {
                        toolChoice = decodeToolChoice(value, thinkingMode);
                    }
                }
                case "metadata" -> {
                    if (!value.isNull()) {
                        decodeMetadata(value);
                    }
                }
                case "service_tier", "speed" -> ignore(name);
                case "inference_geo" -> throw reject(name, "推理地域约束不可表达");
                case "container" -> throw reject(name, "容器执行环境不可表达");
                case "cache_control" -> throw reject(name, "上游自动上下文缓存，无显式缓存控制");
                case "diagnostics" -> throw reject(name, "诊断输出不可表达");
                case "user_profile_id" -> throw reject(name, "用户 profile 不可表达");
                case "workspace_id" -> throw reject(name, "workspace 归属不可表达");
                // 矩阵 §1.1：2.8.0 未暴露 temperature/top_k/top_p，raw 出现按未知字段拒绝（不是 not_supported）。
                default -> throw unknown(name);
            }
        }

        private SemanticRequest assemble() {
            if (model == null) {
                throw invalid("model", "model is required");
            }
            if (messages == null) {
                throw invalid("messages", "messages is required");
            }
            if (maxTokens == null) {
                throw invalid("max_tokens", "max_tokens is required");
            }
            List<SemanticMessage> all = new ArrayList<>();
            if (system != null && !system.isEmpty()) {
                all.add(new SemanticMessage(Role.SYSTEM, system));
            }
            all.addAll(messages);
            // includeUsage 恒 null：Messages 的 usage 恒在 message_start/message_delta（矩阵 §6.4），无开关语义。
            return new SemanticRequest(model, all, tools, toolChoice, thinkingEnabled, maxTokens, stream,
                    effort != null ? effort : derivedEffort, stop, null, null, responseFormat, userId, null);
        }

        // ---------- 分类判定 ----------

        private void accept(String fieldPath) {
            CapabilityPolicy.evaluate(new FieldRule(fieldPath, Classification.EQUIVALENT, null, null), audit);
        }

        private void ignore(String fieldPath) {
            CapabilityPolicy.evaluate(new FieldRule(fieldPath, Classification.IGNORABLE, null, null), audit);
        }

        private void degrade(String fieldPath, String degradationId, String reason) {
            CapabilityPolicy.evaluate(new FieldRule(fieldPath, Classification.DEGRADABLE, degradationId, reason), audit);
        }

        /** REJECT 判定必抛；返回类型只为让调用点写在表达式位置。 */
        private CapabilityException reject(String fieldPath, String reason) {
            CapabilityPolicy.evaluate(new FieldRule(fieldPath, Classification.REJECT, null, reason), audit);
            throw new AssertionError("REJECT rule must fail: " + fieldPath);
        }

        private CapabilityException unknown(String fieldPath) {
            CapabilityPolicy.rejectUnknown(fieldPath);
            throw new AssertionError("unknown field rule must fail: " + fieldPath);
        }

        private void requireKeys(JsonNode value, String path, Set<String> allowed) {
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                if (!allowed.contains(entry.getKey())) {
                    throw unknown(path + "." + entry.getKey());
                }
            }
        }

        /** 块级 cache_control 与顶层同名能力同属 R（矩阵 §5.3）：不可表达，不是未知字段。 */
        private void cacheControl(JsonNode block, String path) {
            JsonNode node = block.path("cache_control");
            if (!node.isMissingNode() && !node.isNull()) {
                throw reject(path + ".cache_control", "上游自动上下文缓存，无显式缓存控制");
            }
        }

        // ---------- 标量守卫 ----------

        private Integer outputTokens(JsonNode value) {
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                throw invalid("max_tokens", "max_tokens must be an integer");
            }
            int tokens = value.intValue();
            if (tokens < 1 || tokens > MAX_OUTPUT_TOKENS) {
                throw invalid("max_tokens", "max_tokens must be within 1.." + MAX_OUTPUT_TOKENS);
            }
            accept("max_tokens");
            return tokens;
        }

        private List<String> decodeStop(JsonNode value) {
            accept("stop_sequences");
            if (value.isNull()) {
                return List.of();
            }
            if (!value.isArray()) {
                throw invalid("stop_sequences", "stop_sequences must be an array of strings");
            }
            if (value.size() > MAX_STOP) {
                throw invalid("stop_sequences", "stop_sequences accepts at most " + MAX_STOP + " sequences");
            }
            List<String> sequences = new ArrayList<>();
            for (int i = 0; i < value.size(); i++) {
                sequences.add(requiredText(value.get(i), "stop_sequences[" + i + "]"));
            }
            return List.copyOf(sequences);
        }

        private void decodeMetadata(JsonNode value) {
            if (!value.isObject()) {
                throw invalid("metadata", "metadata must be a JSON object");
            }
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                String path = "metadata." + entry.getKey();
                if (!"user_id".equals(entry.getKey())) {
                    ignore(path);
                    continue;
                }
                if (entry.getValue().isNull()) {
                    continue;
                }
                String id = requiredText(entry.getValue(), path);
                if (id.getBytes(StandardCharsets.UTF_8).length > MAX_USER_BYTES) {
                    throw invalid(path, path + " must not exceed " + MAX_USER_BYTES + " UTF-8 bytes");
                }
                accept(path);
                userId = id;
            }
        }

        // ---------- thinking / output_config ----------

        private void decodeThinking(JsonNode value) {
            if (!value.isObject()) {
                throw invalid("thinking", "thinking must be a JSON object");
            }
            JsonNode typeNode = value.path("type");
            if (!typeNode.isTextual()) {
                throw invalid("thinking.type", "thinking.type must be a string");
            }
            String type = typeNode.asString();
            Set<String> allowed = switch (type) {
                case "disabled", "adaptive" -> Set.of("type", "display");
                case "enabled" -> Set.of("type", "budget_tokens", "display");
                case "between_tools" -> Set.of("type");
                default -> throw invalid("thinking.type", "unsupported thinking type: " + type);
            };
            requireKeys(value, "thinking", allowed);
            switch (type) {
                case "disabled" -> {
                    accept("thinking");
                    thinkingEnabled = false;
                }
                case "adaptive" -> {
                    // 矩阵 §5.3 E：开思考等价，effort 固定 high；"模型自决是否思考"的细微差别属注记。
                    accept("thinking");
                    thinkingEnabled = true;
                    derivedEffort = "high";
                }
                case "enabled" -> {
                    accept("thinking");
                    thinkingEnabled = true;
                    JsonNode budget = value.path("budget_tokens");
                    if (!budget.isIntegralNumber() || !budget.canConvertToInt() || budget.intValue() < 1) {
                        throw invalid("thinking.budget_tokens", "thinking.budget_tokens must be a positive integer");
                    }
                    degrade("thinking.budget_tokens", BUDGET_TO_EFFORT, BUDGET_REASON);
                    derivedEffort = effortTier(budget.intValue());
                }
                case "between_tools" -> throw reject("thinking.type", "工具间思考形态不可表达");
                default -> throw new AssertionError("unhandled thinking type: " + type);
            }
            JsonNode display = value.path("display");
            if (!display.isMissingNode() && !display.isNull()) {
                String mode = requiredText(display, "thinking.display");
                if (!THINKING_DISPLAYS.contains(mode)) {
                    throw invalid("thinking.display", "unsupported thinking.display: " + mode);
                }
                degrade("thinking.display", DISPLAY_RAW, DISPLAY_RAW_REASON);
            }
        }

        private String effortTier(int budgetTokens) {
            if (budgetTokens < BUDGET_LOW_EXCLUSIVE) {
                return "low";
            }
            return budgetTokens < BUDGET_HIGH_EXCLUSIVE ? "high" : "max";
        }

        private void decodeOutputConfig(JsonNode value) {
            if (!value.isObject()) {
                throw invalid("output_config", "output_config must be a JSON object");
            }
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                String path = "output_config." + entry.getKey();
                switch (entry.getKey()) {
                    case "effort" -> {
                        if (!entry.getValue().isNull()) {
                            String value0 = requiredText(entry.getValue(), path);
                            if (!EFFORTS.contains(value0)) {
                                throw invalid(path, "unsupported output_config.effort: " + value0);
                            }
                            accept(path);
                            effort = value0;
                        }
                    }
                    case "format" -> {
                        if (!entry.getValue().isNull()) {
                            responseFormat = decodeOutputFormat(entry.getValue());
                        }
                    }
                    default -> throw unknown(path);
                }
            }
        }

        private String decodeOutputFormat(JsonNode value) {
            String path = "output_config.format";
            if (!value.isObject()) {
                throw invalid(path, path + " must be a JSON object");
            }
            JsonNode typeNode = value.path("type");
            if (!typeNode.isTextual()) {
                throw invalid(path + ".type", path + ".type must be a string");
            }
            String type = typeNode.asString();
            if (!"json_schema".equals(type)) {
                throw invalid(path + ".type", "unsupported output_config.format type: " + type);
            }
            // 未知嵌套键先判（unknown_field），再做降级授权判定：未授权＋未知键必须报未知键。
            requireKeys(value, path, Set.of("type", "schema", "name"));
            // 上游仅 json_object：未授权→R，授权 response_format.schema_to_object→D（不强制 schema）。
            // 已授权降级整体丢弃 schema 载体，不再逐字段判定其内部结构。
            degrade(path, SCHEMA_TO_OBJECT, SCHEMA_REASON);
            return "json_object";
        }

        // ---------- system / messages ----------

        private List<ContentPart> decodeSystem(JsonNode value) {
            if (value.isTextual()) {
                return List.of(new TextPart(value.asString()));
            }
            if (!value.isArray()) {
                throw invalid("system", "system must be a string or an array of text blocks");
            }
            List<ContentPart> parts = new ArrayList<>();
            for (int i = 0; i < value.size(); i++) {
                String path = "system[" + i + "]";
                JsonNode block = value.get(i);
                if (!block.isObject()) {
                    throw invalid(path, "system block must be a JSON object");
                }
                JsonNode typeNode = block.path("type");
                // 矩阵 §8：图片仅允许在 user 消息，system 含图上游 400 → 前置拒绝。
                if (!typeNode.isTextual() || !"text".equals(typeNode.asString())) {
                    throw invalid(path, "system only carries text blocks");
                }
                requireKeys(block, path, Set.of("type", "text", "cache_control"));
                cacheControl(block, path);
                parts.add(new TextPart(requiredText(block.path("text"), path + ".text")));
            }
            return List.copyOf(parts);
        }

        private List<SemanticMessage> decodeMessages(JsonNode value) {
            if (!value.isArray() || value.isEmpty()) {
                throw invalid("messages", "messages must be a non-empty array");
            }
            List<SemanticMessage> decoded = new ArrayList<>();
            for (int i = 0; i < value.size(); i++) {
                decoded.addAll(decodeMessage(value.get(i), i));
            }
            return List.copyOf(decoded);
        }

        /**
         * Messages 的 tool_result 块住在 user 消息里，而上游 Chat 需要独立的 {@code messages[tool]}。
         * 故按块顺序拆分：每个 tool_result 成一条 TOOL 消息，其余块保持相对顺序归入 USER 消息。
         */
        private List<SemanticMessage> decodeMessage(JsonNode value, int index) {
            String path = "messages[" + index + "]";
            if (!value.isObject()) {
                throw invalid(path, "message must be a JSON object");
            }
            JsonNode roleNode = value.path("role");
            if (!roleNode.isTextual()) {
                throw invalid(path + ".role", "message role must be a string");
            }
            Role role = role(roleNode.asString(), path + ".role");
            accept(path + ".role");

            List<ContentPart> parts = List.of();
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                String key = entry.getKey();
                String fieldPath = path + "." + key;
                switch (key) {
                    case "role" -> {
                    }
                    case "content" -> {
                        accept(fieldPath);
                        parts = decodeContent(entry.getValue(), role, path);
                    }
                    case "cache_control" -> cacheControl(value, path);
                    default -> throw unknown(fieldPath);
                }
            }
            if (role != Role.USER) {
                return List.of(new SemanticMessage(role, parts));
            }
            List<SemanticMessage> split = new ArrayList<>();
            List<ContentPart> pending = new ArrayList<>();
            for (ContentPart part : parts) {
                if (part instanceof ToolResultPart) {
                    if (!pending.isEmpty()) {
                        split.add(new SemanticMessage(Role.USER, List.copyOf(pending)));
                        pending.clear();
                    }
                    split.add(new SemanticMessage(Role.TOOL, List.of(part)));
                } else {
                    pending.add(part);
                }
            }
            if (!pending.isEmpty()) {
                split.add(new SemanticMessage(Role.USER, List.copyOf(pending)));
            }
            if (split.isEmpty()) {
                throw invalid(path + ".content", "user message must carry at least one content part");
            }
            return List.copyOf(split);
        }

        private Role role(String value, String fieldPath) {
            return switch (value) {
                case "system" -> Role.SYSTEM;
                case "user" -> Role.USER;
                case "assistant" -> Role.ASSISTANT;
                // Messages 无 tool/developer 角色：工具结果走 user 消息的 tool_result 块。
                default -> throw invalid(fieldPath, "unsupported message role: " + value);
            };
        }

        private List<ContentPart> decodeContent(JsonNode value, Role role, String messagePath) {
            String contentPath = messagePath + ".content";
            if (value.isNull()) {
                return List.of();
            }
            if (value.isTextual()) {
                return List.of(new TextPart(value.asString()));
            }
            if (!value.isArray()) {
                throw invalid(contentPath, "content must be a string or an array of content blocks");
            }
            List<ContentPart> parts = new ArrayList<>();
            for (int j = 0; j < value.size(); j++) {
                String partPath = contentPath + "[" + j + "]";
                JsonNode part = value.get(j);
                if (!part.isObject()) {
                    throw invalid(partPath, "content block must be a JSON object");
                }
                JsonNode typeNode = part.path("type");
                if (!typeNode.isTextual()) {
                    throw invalid(partPath + ".type", "content block type must be a string");
                }
                String type = typeNode.asString();
                switch (type) {
                    case "text" -> {
                        requireKeys(part, partPath, Set.of("type", "text", "cache_control"));
                        cacheControl(part, partPath);
                        parts.add(new TextPart(requiredText(part.path("text"), partPath + ".text")));
                    }
                    case "image" -> {
                        // 矩阵 §4.4/§8：图片仅允许在 user 消息，其余角色上游 400 → 前置拒绝。
                        if (role != Role.USER) {
                            throw invalid(partPath, "images are only allowed in user messages");
                        }
                        parts.add(decodeImage(part, partPath));
                    }
                    case "thinking" -> {
                        if (role != Role.ASSISTANT) {
                            throw invalid(partPath, "thinking blocks are only allowed in assistant messages");
                        }
                        parts.add(decodeThinkingBlock(part, partPath));
                    }
                    case "tool_use" -> {
                        if (role != Role.ASSISTANT) {
                            throw invalid(partPath, "tool_use blocks are only allowed in assistant messages");
                        }
                        parts.add(decodeToolUse(part, partPath));
                    }
                    case "tool_result" -> {
                        if (role != Role.USER) {
                            throw invalid(partPath, "tool_result blocks are only allowed in user messages");
                        }
                        parts.add(decodeToolResult(part, partPath));
                    }
                    case "redacted_thinking" -> throw reject(partPath, "私有加密思考块不可表达");
                    // server_tool_use/web_search_tool_result/code_execution_*/mcp_*/container_upload/
                    // document/search_result/*_tool_result 等私有/服务端块一律 R（矩阵 §5.3）。
                    default -> throw reject(partPath, "私有/服务端内容块不可表达: " + type);
                }
            }
            return List.copyOf(parts);
        }

        /**
         * 矩阵 §5.3/§8：{@code source:base64} → 内联 ImagePart；{@code source:url} 与 {@code source:file}
         * 一律入口 R（阶段 1 仅内联，外部 URL/file 引用延后阶段 2/3），与 Chat/Responses 同判。
         * 容量/媒体类型/base64 守卫与 Chat/Responses 同值同序：先估算再解码，超限不为超大图片分配缓冲。
         * <p>钉住 SDK 的 Messages image 块不带 {@code detail}，故 ImagePart.detail 恒 null（不伪造 Chat 语义）。
         * <p>{@code ImagePart.fileRef} 保留给 Files-API 引用（当前恒为 null）：绝不把外部 URL 塞进去，
         * 把拒绝推迟到上游 codec 会给出误导性原因。
         */
        private ImagePart decodeImage(JsonNode part, String partPath) {
            requireKeys(part, partPath, Set.of("type", "source"));
            JsonNode source = part.path("source");
            String sourcePath = partPath + ".source";
            if (!source.isObject()) {
                throw invalid(sourcePath, "image source must be a JSON object");
            }
            JsonNode typeNode = source.path("type");
            if (!typeNode.isTextual()) {
                throw invalid(sourcePath + ".type", "image source type must be a string");
            }
            String sourceType = typeNode.asString();
            return switch (sourceType) {
                case "base64" -> inlineImage(source, sourcePath);
                case "url" -> imageUrl(source, sourcePath);
                case "file" -> {
                    requireKeys(source, sourcePath, Set.of("type", "file_id"));
                    throw reject(sourcePath + ".file_id", "第一阶段仅支持内联图片，file 引用属阶段 2/3");
                }
                default -> throw invalid(sourcePath + ".type", "unsupported image source type: " + sourceType);
            };
        }

        private ImagePart inlineImage(JsonNode source, String sourcePath) {
            requireKeys(source, sourcePath, Set.of("type", "media_type", "data"));
            String mediaPath = sourcePath + ".media_type";
            String dataPath = sourcePath + ".data";
            String mediaType = requiredText(source.path("media_type"), mediaPath);
            if (!IMAGE_MEDIA_TYPES.contains(mediaType)) {
                throw invalid(mediaPath, "unsupported image media_type: " + mediaType
                        + " (allowed: image/jpeg, image/png, image/gif, image/webp)");
            }
            String data = requiredText(source.path("data"), dataPath);
            if (data.isEmpty()) {
                throw invalid(dataPath, "image base64 data must not be empty");
            }
            countImage();
            // 容量守卫前置：先按 base64 字符数估算，超限直接拒绝，不为超大图片分配整块解码缓冲。
            long estimated = base64LowerBoundBytes(data);
            if (estimated > MAX_IMAGE_BYTES) {
                throw invalid(dataPath, IMAGE_TOO_LARGE);
            }
            if (imageBytes + estimated > MAX_TOTAL_IMAGE_BYTES) {
                throw invalid("messages", IMAGES_TOO_LARGE);
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(data);
            } catch (IllegalArgumentException e) {
                throw invalid(dataPath, "invalid base64 image data: " + e.getMessage());
            }
            // 估算只是下界：临界载荷仍以解码后的真实字节数为准（上限含等号）。
            if (decoded.length > MAX_IMAGE_BYTES) {
                throw invalid(dataPath, IMAGE_TOO_LARGE);
            }
            imageBytes += decoded.length;
            if (imageBytes > MAX_TOTAL_IMAGE_BYTES) {
                throw invalid("messages", IMAGES_TOO_LARGE);
            }
            return new ImagePart(mediaType, data, null, null);
        }

        private ImagePart imageUrl(JsonNode source, String sourcePath) {
            requireKeys(source, sourcePath, Set.of("type", "url"));
            String urlPath = sourcePath + ".url";
            // 类型守卫先行（非字符串→invalid_value），随后与 Chat/Responses 入口同判：阶段1仅内联。
            requiredText(source.path("url"), urlPath);
            throw reject(urlPath, "第一阶段仅支持内联图片，外部 URL 属阶段 2/3");
        }

        private void countImage() {
            imageCount++;
            if (imageCount > MAX_IMAGES) {
                throw invalid("messages", "at most " + MAX_IMAGES + " images per request");
            }
        }

        /**
         * 矩阵 §5.3/§7：取 thinking 明文 → 上游 reasoning_content；{@code signature} 承载网关命名空间续轮信封
         * （{@code llmgw1:<payload>.<hmac>}），用 {@link ContinuationEnvelope} 验 HMAC + 项目绑定 + 推理文本完整性。
         * 缺失/篡改/跨项目/摘要不一致 → capability_continuation_invalid（400）；校验通过后把令牌写入
         * {@link ThinkingPart#continuationToken()}。不伪造供应商签名，也不接受无命名空间的供应商式签名。
         */
        private ThinkingPart decodeThinkingBlock(JsonNode part, String partPath) {
            accept(partPath);
            requireKeys(part, partPath, Set.of("type", "thinking", "signature", "cache_control"));
            cacheControl(part, partPath);
            String reasoning = requiredText(part.path("thinking"), partPath + ".thinking");
            JsonNode signature = part.path("signature");
            // 矩阵 §7.3：signature 必需——缺失信封无法续轮，落稳定 400 而不是静默当无思考历史。
            if (signature.isMissingNode() || signature.isNull()) {
                throw new CapabilityException(CapabilityException.CONTINUATION_INVALID, 400, partPath + ".signature",
                        "thinking 块缺少续轮信封 signature，无法续轮");
            }
            String token = requiredText(signature, partPath + ".signature");
            envelope.verify(token, projectId, reasoning);
            return new ThinkingPart(reasoning, token);
        }

        /** 矩阵 §5.3 E：input(JSON 对象) → argumentsJson(字符串)。 */
        private ToolCallPart decodeToolUse(JsonNode part, String partPath) {
            accept(partPath);
            requireKeys(part, partPath, Set.of("type", "id", "name", "input", "cache_control"));
            cacheControl(part, partPath);
            String id = requiredText(part.path("id"), partPath + ".id");
            if (id.isBlank()) {
                throw invalid(partPath + ".id", "tool_use id must not be blank");
            }
            String name = requiredText(part.path("name"), partPath + ".name");
            if (!TOOL_NAME.matcher(name).matches()) {
                throw invalid(partPath + ".name", "tool name must match [a-zA-Z0-9_-]{1,128}");
            }
            JsonNode input = part.path("input");
            String arguments;
            if (input.isMissingNode()) {
                arguments = "{}";
            } else if (!input.isObject()) {
                throw invalid(partPath + ".input", "tool_use input must be a JSON object");
            } else {
                arguments = MAPPER.writeValueAsString(input);
            }
            return new ToolCallPart(id, name, arguments);
        }

        /**
         * 矩阵 §5.3 E：content(字符串或 text 块) → 字符串；is_error → isError（上游无对应字段，语义层承载）。
         * <p><b>T1.14 接缝</b>：tool_result 图片经 Chat 上游是否接受属待受控验证（矩阵 §4.4/§13.2，
         * S3 与 S1 schema 表述不一致），第一阶段前置拒绝而非静默去图；受控验证通过后在此放开。
         */
        private ToolResultPart decodeToolResult(JsonNode part, String partPath) {
            accept(partPath);
            requireKeys(part, partPath, Set.of("type", "tool_use_id", "content", "is_error", "cache_control"));
            cacheControl(part, partPath);
            String toolCallId = requiredText(part.path("tool_use_id"), partPath + ".tool_use_id");
            if (toolCallId.isBlank()) {
                throw invalid(partPath + ".tool_use_id", "tool_result tool_use_id must not be blank");
            }
            String content = toolResultContent(part.path("content"), partPath + ".content");
            boolean isError = false;
            JsonNode errorNode = part.path("is_error");
            if (!errorNode.isMissingNode() && !errorNode.isNull()) {
                if (!errorNode.isBoolean()) {
                    throw invalid(partPath + ".is_error", "is_error must be a boolean");
                }
                isError = errorNode.booleanValue();
            }
            return new ToolResultPart(toolCallId, content, isError);
        }

        private String toolResultContent(JsonNode value, String path) {
            if (value.isMissingNode() || value.isNull()) {
                return "";
            }
            if (value.isTextual()) {
                return value.asString();
            }
            if (!value.isArray()) {
                throw invalid(path, "tool_result content must be a string or an array of text blocks");
            }
            StringBuilder text = new StringBuilder();
            for (int j = 0; j < value.size(); j++) {
                String partPath = path + "[" + j + "]";
                JsonNode block = value.get(j);
                if (!block.isObject()) {
                    throw invalid(partPath, "tool_result content block must be a JSON object");
                }
                JsonNode typeNode = block.path("type");
                if (!typeNode.isTextual() || !"text".equals(typeNode.asString())) {
                    throw reject(partPath, "tool_result 图片/非文本块经 Chat 上游待 T1.14 受控验证，第一阶段拒绝");
                }
                requireKeys(block, partPath, Set.of("type", "text", "cache_control"));
                cacheControl(block, partPath);
                text.append(requiredText(block.path("text"), partPath + ".text"));
            }
            return text.toString();
        }

        // ---------- tools / tool_choice ----------

        private List<ToolSpec> decodeTools(JsonNode value) {
            if (!value.isArray()) {
                throw invalid("tools", "tools must be an array");
            }
            List<ToolSpec> specs = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (int i = 0; i < value.size(); i++) {
                String path = "tools[" + i + "]";
                JsonNode tool = value.get(i);
                if (!tool.isObject()) {
                    throw invalid(path, "tool must be a JSON object");
                }
                JsonNode typeNode = tool.path("type");
                // 钉住 SDK 的自定义工具可省略 type；其余（服务端/内建）一律 R。
                String type = typeNode.isMissingNode() || typeNode.isNull()
                        ? "custom" : requiredText(typeNode, path + ".type");
                if (!"custom".equals(type)) {
                    String base = TOOL_VERSION_SUFFIX.matcher(type).replaceAll("");
                    throw reject(path, SERVER_TOOLS.contains(base)
                            ? "服务端工具不可表达: " + type
                            : "仅支持 custom 工具，不支持的工具类型: " + type);
                }
                specs.add(customSpec(tool, path, names));
            }
            return List.copyOf(specs);
        }

        private ToolSpec customSpec(JsonNode value, String path, Set<String> names) {
            String name = null;
            String description = null;
            String schema = null;
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                JsonNode node = entry.getValue();
                String fieldPath = path + "." + entry.getKey();
                switch (entry.getKey()) {
                    case "type" -> {
                    }
                    case "name" -> name = requiredText(node, fieldPath);
                    case "description" -> {
                        if (!node.isNull()) {
                            description = requiredText(node, fieldPath);
                        }
                    }
                    case "input_schema" -> {
                        if (!node.isObject()) {
                            throw invalid(fieldPath, "input_schema must be a JSON Schema object");
                        }
                        schema = MAPPER.writeValueAsString(node);
                    }
                    case "cache_control" -> cacheControl(value, path);
                    default -> throw unknown(fieldPath);
                }
            }
            if (name == null || !TOOL_NAME.matcher(name).matches()) {
                throw invalid(path + ".name", "tool name must match [a-zA-Z0-9_-]{1,128}");
            }
            if (!names.add(name)) {
                throw invalid(path + ".name", "duplicate tool name: " + name);
            }
            if (schema == null) {
                throw invalid(path + ".input_schema", "custom tool requires input_schema");
            }
            return new ToolSpec(name, description, schema);
        }

        private String decodeToolChoice(JsonNode value, boolean thinkingMode) {
            String path = "tool_choice";
            if (!value.isObject()) {
                throw invalid(path, "tool_choice must be a JSON object");
            }
            JsonNode typeNode = value.path("type");
            if (!typeNode.isTextual()) {
                throw invalid(path + ".type", "tool_choice type must be a string");
            }
            String type = typeNode.asString();
            Set<String> allowed = switch (type) {
                case "auto", "none", "any" -> Set.of("type", "disable_parallel_tool_use");
                case "tool" -> Set.of("type", "name", "disable_parallel_tool_use");
                default -> throw invalid(path + ".type", "unsupported tool_choice type: " + type);
            };
            requireKeys(value, path, allowed);
            JsonNode parallel = value.path("disable_parallel_tool_use");
            if (!parallel.isMissingNode() && !parallel.isNull()) {
                if (!parallel.isBoolean()) {
                    throw invalid(path + ".disable_parallel_tool_use", "disable_parallel_tool_use must be a boolean");
                }
                // 矩阵 §5.3 I：上游无显式开关，忽略不改变可观察语义。
                ignore(path + ".disable_parallel_tool_use");
            }
            return switch (type) {
                case "auto", "none" -> {
                    accept(path);
                    yield type;
                }
                case "any" -> forced("required", path, thinkingMode);
                case "tool" -> {
                    String name = requiredText(value.path("name"), path + ".name");
                    if (!TOOL_NAME.matcher(name).matches()) {
                        throw invalid(path + ".name", "tool name must match [a-zA-Z0-9_-]{1,128}");
                    }
                    yield forced("tool:" + name, path, thinkingMode);
                }
                default -> throw new AssertionError("unhandled tool_choice type: " + type);
            };
        }

        /** 思考模式 any/具名上游 400：未授权→拒绝，授权→退化为 auto（矩阵 §5.3/§11）。 */
        private String forced(String choice, String path, boolean thinkingMode) {
            if (!thinkingMode) {
                accept(path);
                return choice;
            }
            degrade(path, FORCE_TO_AUTO, FORCE_AUTO_REASON);
            return "auto";
        }
    }

    /**
     * 每请求一个有状态实例（矩阵 §6.4）：连续块索引、块生命周期闭合、恰一次 message_stop。
     * 共享单例 {@link MessagesEntryCodec} 不持有任何请求状态。
     *
     * <p>Messages 的 {@code content_block_stop} 不携带累积文本，每个增量即时成帧并绑定自己的块索引；
     * 但续轮信封摘要需要思考全文，故只持有**当前 thinking 块**的文本缓冲，重开块时清空
     * （复刻 Responses 的 A1：绝不用上一轮脏文本铸信封）。
     *
     * <p>Messages 的 usage 恒在 message_start（SDK 必需初始占位＋已知输入侧）与 message_delta（最终真实值），
     * 与 include_usage 无关，故不存储该开关；SDK accumulator 以累计总量**覆盖**而非相加（矩阵 §9/S10）。
     */
    private static final class Encoder implements StreamEncoder {

        private final String model;
        private final String id = "msg_" + UUID.randomUUID();
        /** 每请求上下文：续轮信封铸造须用本请求的 projectId + envelope（codec 无状态，密钥不落 codec 字段）。 */
        private final CapabilityContext ctx;

        private boolean preambleSent;
        private boolean terminalSeen;
        private boolean failed;
        private boolean doneSent;
        private Usage usage;

        /** 块索引连续递增（0,1,2…），与块类型无关。 */
        private int nextBlockIndex;
        private boolean thinkingOpen;
        private int thinkingIndex;
        private String thinkingItemId;
        /** 当前 thinking 块的累计全文：signature_delta 的信封摘要输入（重开块清空）。 */
        private final StringBuilder thinkingText = new StringBuilder();
        private boolean textOpen;
        private int textIndex;

        /** 按语义 index 保序：每个工具调用恰一个 tool_use 块。 */
        private final Map<Integer, ToolState> tools = new LinkedHashMap<>();
        private int openToolIndex = -1;

        private Encoder(String model, boolean includeUsage, CapabilityContext ctx) {
            this.model = model;
            this.ctx = Objects.requireNonNull(ctx, "ctx");
        }

        @Override
        public List<String> onEvent(SemanticEvent event) {
            Objects.requireNonNull(event, "event");
            if (terminalSeen) {
                return List.of();
            }
            return switch (event) {
                case TextDelta text -> {
                    List<String> lines = new ArrayList<>(preamble());
                    closeOtherBlocks(lines, false);
                    if (!textOpen) {
                        openText(lines);
                    }
                    lines.add(emit("content_block_delta", blockIndex(textIndex)
                            .set("delta", MAPPER.createObjectNode().put("type", "text_delta")
                                    .put("text", text.text()))));
                    yield lines;
                }
                case ThinkingDelta thinking -> {
                    List<String> lines = new ArrayList<>(preamble());
                    closeOtherBlocks(lines, true);
                    if (!thinkingOpen) {
                        openThinking(lines);
                    }
                    thinkingText.append(thinking.reasoning());
                    lines.add(emit("content_block_delta", blockIndex(thinkingIndex)
                            .set("delta", MAPPER.createObjectNode().put("type", "thinking_delta")
                                    .put("thinking", thinking.reasoning()))));
                    yield lines;
                }
                case ToolCallDelta call -> {
                    List<String> lines = new ArrayList<>(preamble());
                    closeThinking(lines);
                    closeText(lines);
                    ToolState state = tools.get(call.index());
                    if (state == null) {
                        state = new ToolState();
                        tools.put(call.index(), state);
                    }
                    // 已闭合 index 再次到达＝上游非连续增量（§6.1 每 index 连续）：落恰一次 error 终态，
                    // 不静默丢弃迟到 arguments（块已闭合，迟到增量无法回填）。
                    if (state.closed) {
                        fail(lines, UpstreamException.UPSTREAM_ERROR,
                                "non-contiguous tool call deltas for index " + call.index());
                        yield lines;
                    }
                    if (call.id() != null) {
                        state.callId = call.id();
                    }
                    if (call.name() != null) {
                        state.name = call.name();
                    }
                    if (openToolIndex != call.index()) {
                        if (openToolIndex >= 0) {
                            closeTool(lines, openToolIndex);
                        }
                        openTool(lines, state, call.index());
                        // 缺 id/name 时 openTool 已落 error 终态：此后不得再发增量或成功终态。
                        if (terminalSeen) {
                            yield lines;
                        }
                        openToolIndex = call.index();
                    }
                    if (call.arguments() != null) {
                        lines.add(emit("content_block_delta", blockIndex(state.blockIndex)
                                .set("delta", MAPPER.createObjectNode().put("type", "input_json_delta")
                                        .put("partial_json", call.arguments()))));
                    }
                    yield lines;
                }
                // usage 只缓冲到 message_start/message_delta，绝不单独成帧（矩阵 §6.4）。
                case UsageEvent usageEvent -> {
                    usage = usageEvent.usage();
                    yield List.of();
                }
                case TerminalEvent end -> terminal(end.stop());
            };
        }

        @Override
        public List<String> onComplete() {
            if (doneSent || failed) {
                return List.of();
            }
            if (!terminalSeen) {
                throw new IllegalStateException("stream ended without a terminal event; refusing to fabricate success");
            }
            doneSent = true;
            // Messages 无 [DONE] 终止符：message_stop 即结束（矩阵 §6.4）。
            return List.of();
        }

        @Override
        public List<String> onError(String code, String message) {
            if (terminalSeen || doneSent) {
                return List.of();
            }
            // 空 code/message 不得抛裸异常逃逸成 500：兜底为稳定错误帧，仍是一次终态且不再发成功终态。
            String wireCode = code == null || code.isBlank() ? FALLBACK_ERROR_CODE : code;
            String wireMessage = message == null || message.isBlank() ? FALLBACK_ERROR_MESSAGE : message;
            List<String> lines = new ArrayList<>();
            fail(lines, wireCode, wireMessage);
            return lines;
        }

        @Override
        public boolean terminalSeen() {
            return terminalSeen;
        }

        /**
         * 落恰一次 error 终态（failed/terminalSeen）：onError、异常 stop 与畸形工具调用共用同一路径，
         * 已终态则幂等返回，绝不追加 message_stop 伪造成功。
         */
        private void fail(List<String> lines, String code, String message) {
            if (terminalSeen) {
                return;
            }
            failed = true;
            terminalSeen = true;
            lines.add(event("error", errorBody(code, message, null)));
        }

        // ---------- 事件渲染 ----------

        private String emit(String type, ObjectNode fields) {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("type", type);
            payload.setAll(fields);
            return event(type, payload);
        }

        private ObjectNode blockIndex(int index) {
            return MAPPER.createObjectNode().put("index", index);
        }

        private List<String> preamble() {
            if (preambleSent) {
                return List.of();
            }
            preambleSent = true;
            ObjectNode message = MAPPER.createObjectNode();
            message.put("id", id);
            message.put("type", "message");
            message.put("role", "assistant");
            if (model != null) {
                message.put("model", model);
            }
            message.putArray("content");
            message.putNull("stop_reason");
            message.putNull("stop_sequence");
            // SDK 必填 usage：此刻输入侧通常未知 → 初始占位，由 message_delta 的真实累计值覆盖（见 startUsage）。
            message.set("usage", startUsage(usage));
            return List.of(emit("message_start", MAPPER.createObjectNode().set("message", message)));
        }

        // ---------- 块生命周期 ----------

        /** Messages 一次只开一个块：开新块前必须闭合另一类块与未闭合的工具块。 */
        private void closeOtherBlocks(List<String> lines, boolean keepThinking) {
            if (keepThinking) {
                closeText(lines);
            } else {
                closeThinking(lines);
            }
            if (openToolIndex >= 0) {
                closeTool(lines, openToolIndex);
            }
        }

        private void openThinking(List<String> lines) {
            // A1：重开块必须清空缓冲，否则用上一轮脏文本铸信封（摘要与本块明文不符，续轮必被拒）。
            thinkingText.setLength(0);
            thinkingItemId = "th_" + UUID.randomUUID();
            thinkingIndex = nextBlockIndex++;
            thinkingOpen = true;
            ObjectNode block = MAPPER.createObjectNode();
            block.put("type", "thinking");
            block.put("thinking", "");
            // SDK ThinkingBlock.signature 必填 str：此刻全文未知，只给初始占位，由 signature_delta 填真实信封。
            block.put("signature", "");
            lines.add(emit("content_block_start", blockIndex(thinkingIndex).set("content_block", block)));
        }

        private void closeThinking(List<String> lines) {
            if (!thinkingOpen) {
                return;
            }
            thinkingOpen = false;
            // 矩阵 §6.4/§7.3：用本块累计全文铸网关信封（llmgw1: + HMAC + 项目绑定），绝不伪造供应商签名。
            String signature = ctx.envelope().mint(ctx.projectId(), thinkingItemId, thinkingText.toString());
            lines.add(emit("content_block_delta", blockIndex(thinkingIndex)
                    .set("delta", MAPPER.createObjectNode().put("type", "signature_delta").put("signature", signature))));
            lines.add(emit("content_block_stop", blockIndex(thinkingIndex)));
        }

        private void openText(List<String> lines) {
            textIndex = nextBlockIndex++;
            textOpen = true;
            ObjectNode block = MAPPER.createObjectNode().put("type", "text").put("text", "");
            lines.add(emit("content_block_start", blockIndex(textIndex).set("content_block", block)));
        }

        private void closeText(List<String> lines) {
            if (!textOpen) {
                return;
            }
            textOpen = false;
            lines.add(emit("content_block_stop", blockIndex(textIndex)));
        }

        private void openTool(List<String> lines, ToolState state, int index) {
            // 上游工具调用缺 id/name（§4.5 首片必带 id+name）＝畸形/不完整：落恰一次 error 终态，
            // 绝不构造 ToolCallPart 触发 NPE 逃逸成 500，也不伪造空名 tool_use 块。
            if (state.callId == null || state.callId.isBlank() || state.name == null || state.name.isBlank()) {
                fail(lines, UpstreamException.UPSTREAM_ERROR,
                        "upstream tool call at index " + index + " is missing id or name");
                return;
            }
            state.blockIndex = nextBlockIndex++;
            ObjectNode block = MAPPER.createObjectNode();
            block.put("type", "tool_use");
            block.put("id", state.callId);
            block.put("name", state.name);
            block.putObject("input");
            lines.add(emit("content_block_start", blockIndex(state.blockIndex).set("content_block", block)));
        }

        private void closeTool(List<String> lines, int index) {
            ToolState state = tools.get(index);
            openToolIndex = -1;
            if (state == null || state.closed) {
                return;
            }
            state.closed = true;
            lines.add(emit("content_block_stop", blockIndex(state.blockIndex)));
        }

        // ---------- 终态 ----------

        private List<String> terminal(StopReason stop) {
            List<String> lines = new ArrayList<>(preamble());
            closeThinking(lines);
            closeText(lines);
            if (openToolIndex >= 0) {
                closeTool(lines, openToolIndex);
            }
            if (terminalSeen) {
                return List.copyOf(lines);
            }
            // 异常/未知终止绝不映射为成功（矩阵 §4.7/§6.4）：流内落 event:error，不发 message_stop。
            if (stop == null || stop == StopReason.UNKNOWN) {
                fail(lines, UpstreamException.UPSTREAM_ERROR,
                        "upstream stream ended with an unexpressible stop reason");
                return List.copyOf(lines);
            }
            terminalSeen = true;
            ObjectNode delta = MAPPER.createObjectNode();
            delta.put("stop_reason", stopReason(stop));
            delta.putNull("stop_sequence");
            ObjectNode messageDelta = MAPPER.createObjectNode().set("delta", delta);
            // 最终 usage 恒存在：输出侧＋已知输入侧真实值，覆盖 message_start 的占位（不双计）。
            messageDelta.set("usage", deltaUsage(usage));
            lines.add(emit("message_delta", messageDelta));
            lines.add(emit("message_stop", MAPPER.createObjectNode()));
            return List.copyOf(lines);
        }

        /** 每个语义工具 index 的累积状态；id/name 在开块时校验，闭合后不再接受增量。 */
        private static final class ToolState {
            private int blockIndex;
            private String callId;
            private String name;
            private boolean closed;
        }
    }
}