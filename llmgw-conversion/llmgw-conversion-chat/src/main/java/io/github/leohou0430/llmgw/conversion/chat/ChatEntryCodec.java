package io.github.leohou0430.llmgw.conversion.chat;

import io.github.leohou0430.llmgw.conversion.CapabilityContext;
import io.github.leohou0430.llmgw.conversion.CapabilityException;
import io.github.leohou0430.llmgw.conversion.CapabilityPolicy;
import io.github.leohou0430.llmgw.conversion.Classification;
import io.github.leohou0430.llmgw.conversion.ContentPart;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * OpenAI Chat 入口编解码（映射矩阵 §5.1 全量分类 / §6.2 SSE / §9 usage / §10.2 错误形状 / §11 降级）。
 * 无状态可共享：每请求状态归 {@link CapabilityContext#audit()} 与 {@link StreamEncoder} 实例。
 */
public final class ChatEntryCodec implements EntryCodec {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 矩阵 §5.1/§4.4/§4.5 的入口守卫：越界前置 400，不把非法值丢给上游。 */
    private static final int MAX_OUTPUT_TOKENS = 393216;
    private static final int MAX_STOP = 16;
    private static final int MAX_USER_BYTES = 512;
    private static final int MAX_IMAGES = 600;
    private static final long MAX_IMAGE_BYTES = 32L * 1024 * 1024;
    private static final long MAX_TOTAL_IMAGE_BYTES = 64L * 1024 * 1024;
    private static final Set<String> IMAGE_MEDIA_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    private static final Set<String> IMAGE_DETAILS = Set.of("low", "high", "auto", "original");
    private static final Set<String> EFFORTS =
            Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra");
    private static final Pattern TOOL_NAME = Pattern.compile("[a-zA-Z0-9_-]{1,128}");

    private static final String LEGACY_FUNCTION = "tool_call.legacy_function";
    private static final String FORCE_TO_AUTO = "tool_choice.force_to_auto";
    private static final String SCHEMA_TO_OBJECT = "response_format.schema_to_object";
    private static final String LEGACY_REASON = "legacy 函数调用形态降级为 tool_calls，损失 legacy 形态";
    private static final String FORCE_AUTO_REASON = "思考模式不强制工具调用，required/具名退化为 auto";
    private static final String SCHEMA_REASON = "不强制 JSON Schema，仅保证 json_object";

    /** 错误帧兜底（矩阵 §10.1 internal_error→500）：上游给了空 code/message 也必须落合法错误帧。 */
    private static final String FALLBACK_ERROR_CODE = "internal_error";
    private static final String FALLBACK_ERROR_MESSAGE = "stream failed without an error detail";

    /** 图片容量守卫的客户端原因；由实际上限推导，避免文案与常量漂移。 */
    private static final String IMAGE_TOO_LARGE = "inline image exceeds " + MAX_IMAGE_BYTES / (1024 * 1024) + " MiB";
    private static final String IMAGES_TOO_LARGE =
            "inline images exceed " + MAX_TOTAL_IMAGE_BYTES / (1024 * 1024) + " MiB in total";

    @Override
    public ProtocolKind kind() {
        return ProtocolKind.CHAT;
    }

    @Override
    public SemanticRequest decode(String rawJson, CapabilityContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        return new RequestDecoder(ctx.audit()).decode(parseRequest(rawJson));
    }

    @Override
    public String encodeResponse(SemanticResponse resp, String model, CapabilityContext ctx) {
        Objects.requireNonNull(resp, "resp");
        Objects.requireNonNull(ctx, "ctx");
        // Chat 原生 reasoning_content 透传（矩阵 §7.2）：不铸不验信封，忽略 ctx.envelope()。
        // Chat 入口只有 choices[0]：多候选不可表达，绝不静默丢弃其余消息。
        if (resp.messages().size() > 1) {
            throw new UpstreamException("chat entry expresses a single choice, got " + resp.messages().size()
                    + " messages", "messages", null);
        }
        // choices[0].message 恒为 assistant：非 assistant 角色是上游/语义合同缺陷，绝不静默改写。
        if (!resp.messages().isEmpty() && resp.messages().get(0).role() != Role.ASSISTANT) {
            throw new UpstreamException("chat entry expresses an assistant message, got role "
                    + resp.messages().get(0).role(), "choices[0].message.role", null);
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", responseId(resp.id()));
        root.put("object", "chat.completion");
        root.put("created", Instant.now().getEpochSecond());
        if (model != null) {
            root.put("model", model);
        }
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        choice.set("message", assistantMessage(resp.messages().isEmpty() ? List.of() : resp.messages().get(0).parts()));
        choice.put("finish_reason", finishReason(resp.stop()));
        JsonNode usage = usageJson(resp.usage());
        if (usage != null) {
            root.set("usage", usage);
        }
        return MAPPER.writeValueAsString(root);
    }

    @Override
    public StreamEncoder newStreamEncoder(String model, boolean includeUsage, CapabilityContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        return new Encoder(model, includeUsage);
    }

    @Override
    public String errorJson(CapabilityException e) {
        Objects.requireNonNull(e, "error");
        Violation first = e.violations().get(0);
        return MAPPER.writeValueAsString(errorBody(first.code(), first.reason(), first.fieldPath(), e.status()));
    }

    @Override
    public String errorEvent(CapabilityException e) {
        return "data: " + errorJson(e);
    }

    // ========== 共享渲染 ==========

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

    private static String responseId(String id) {
        return id == null || id.isBlank() ? "chatcmpl-" + UUID.randomUUID() : id;
    }

    private static ObjectNode assistantMessage(List<ContentPart> parts) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "assistant");
        StringBuilder text = null;
        StringBuilder reasoning = null;
        ArrayNode toolCalls = null;
        for (ContentPart part : parts) {
            if (part instanceof TextPart textPart) {
                text = text == null ? new StringBuilder() : text;
                text.append(textPart.text());
            } else if (part instanceof ThinkingPart thinking) {
                reasoning = reasoning == null ? new StringBuilder() : reasoning;
                reasoning.append(thinking.reasoning());
            } else if (part instanceof ToolCallPart call) {
                toolCalls = toolCalls == null ? message.putArray("tool_calls") : toolCalls;
                ObjectNode node = toolCalls.addObject();
                node.put("id", call.id());
                node.put("type", "function");
                ObjectNode function = node.putObject("function");
                function.put("name", call.name());
                function.put("arguments", call.argumentsJson() == null ? "" : call.argumentsJson());
            } else {
                throw new UpstreamException("chat entry cannot express assistant part: "
                        + part.getClass().getSimpleName(), "choices[0].message", null);
            }
        }
        if (text == null) {
            message.putNull("content");
        } else {
            message.put("content", text.toString());
        }
        if (reasoning != null) {
            message.put("reasoning_content", reasoning.toString());
        }
        return message;
    }

    /** 异常/未知终止绝不映射为成功（矩阵 §4.7/§6.2）。 */
    private static String finishReason(StopReason stop) {
        if (stop == null) {
            throw new UpstreamException("upstream response has no finish_reason (incomplete response)",
                    "choices[0].finish_reason", null);
        }
        return switch (stop) {
            case COMPLETED -> "stop";
            case LENGTH -> "length";
            case TOOL_CALL -> "tool_calls";
            case CONTENT_FILTER -> "content_filter";
            case UNKNOWN -> throw new UpstreamException("upstream finish_reason is not expressible: UNKNOWN",
                    "choices[0].finish_reason", null);
        };
    }

    /** 矩阵 §9：null=未知→省略字段，绝不填 0；total 仅在输入/输出都已知时给出。 */
    private static JsonNode usageJson(Usage usage) {
        if (usage == null) {
            return null;
        }
        ObjectNode node = MAPPER.createObjectNode();
        if (usage.inputTokens() != null) {
            node.put("prompt_tokens", usage.inputTokens().longValue());
        }
        if (usage.outputTokens() != null) {
            node.put("completion_tokens", usage.outputTokens().longValue());
        }
        if (usage.inputTokens() != null && usage.outputTokens() != null) {
            node.put("total_tokens", usage.inputTokens() + usage.outputTokens());
        }
        if (usage.cachedInputTokens() != null) {
            node.putObject("prompt_tokens_details").put("cached_tokens", usage.cachedInputTokens().longValue());
        }
        if (usage.reasoningTokens() != null) {
            node.putObject("completion_tokens_details").put("reasoning_tokens", usage.reasoningTokens().longValue());
        }
        return node.isEmpty() ? null : node;
    }

    private static ObjectNode errorBody(String code, String message, String param, int status) {
        ObjectNode error = MAPPER.createObjectNode();
        error.put("message", message);
        error.put("type", errorType(status));
        error.put("code", code);
        if (param == null) {
            error.putNull("param");
        } else {
            error.put("param", param);
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.set("error", error);
        return root;
    }

    private static String errorType(int status) {
        if (status == 429) {
            return "rate_limit_error";
        }
        // 501＝客户端请求了不可表达的资源操作，与 4xx 同归 invalid_request_error；其余 5xx 才是服务端故障。
        if (status >= 500 && status != 501) {
            return "server_error";
        }
        return "invalid_request_error";
    }

    /** 稳定码 → HTTP（矩阵 §10.1）；流式错误帧只有码，没有状态可带。 */
    private static int statusForCode(String code) {
        return switch (code) {
            case CapabilityException.UNKNOWN_FIELD, CapabilityException.NOT_SUPPORTED,
                    CapabilityException.DEGRADATION_NOT_AUTHORIZED, CapabilityException.INVALID_VALUE,
                    CapabilityException.CONTINUATION_INVALID -> 400;
            case "resource_not_found" -> 404;
            case "resource_operation_not_supported" -> 501;
            case "upstream_rate_limited" -> 429;
            case UpstreamException.UPSTREAM_ERROR -> 502;
            case "upstream_overloaded" -> 503;
            default -> 500;
        };
    }

    /** 只产 bare {@code data:} 行；换行与帧间隔归上层 transcoder（与 StreamDecoder 的行框架归属一致）。 */
    private static String line(JsonNode payload) {
        return "data: " + MAPPER.writeValueAsString(payload);
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

    private static String joinText(List<ContentPart> parts) {
        StringBuilder text = new StringBuilder();
        for (ContentPart part : parts) {
            if (part instanceof TextPart textPart) {
                text.append(textPart.text());
            }
        }
        return text.toString();
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

        private String model;
        private List<SemanticMessage> messages;
        private Integer maxTokens;
        private Integer maxCompletionTokens;
        private List<String> stop = List.of();
        private boolean stream;
        private boolean streamOptions;
        private Boolean includeUsage;
        private List<ToolSpec> tools;
        private List<ToolSpec> legacyTools;
        private String toolChoice;
        private String legacyChoice;
        private String effort;
        private Double temperature;
        private Double topP;
        private String responseFormat;
        private String user;
        private String safetyIdentifier;
        private int imageCount;
        private long imageBytes;

        private RequestDecoder(DegradationAudit audit) {
            this.audit = audit;
        }

        private SemanticRequest decode(JsonNode root) {
            // 分类用的思考模式：省略 effort 时上游默认开启思考（矩阵 §4.2），故 temperature 属 I、top_p 属 E。
            boolean thinkingMode = isThinkingMode(root);
            for (Map.Entry<String, JsonNode> entry : root.properties()) {
                field(entry.getKey(), entry.getValue(), thinkingMode);
            }
            return assemble();
        }

        private static boolean isThinkingMode(JsonNode root) {
            JsonNode effort = root.path("reasoning_effort");
            return !effort.isTextual() || !"none".equals(effort.asString());
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
                case "messages" -> {
                    accept("messages");
                    messages = decodeMessages(value);
                }
                case "max_tokens" -> maxTokens = outputTokens(value, "max_tokens");
                case "max_completion_tokens" -> maxCompletionTokens = outputTokens(value, "max_completion_tokens");
                case "stop" -> stop = decodeStop(value);
                case "stream" -> {
                    accept("stream");
                    if (!value.isBoolean()) {
                        throw invalid("stream", "stream must be a boolean");
                    }
                    stream = value.booleanValue();
                }
                case "stream_options" -> {
                    streamOptions = true;
                    includeUsage = decodeStreamOptions(value);
                }
                case "tools" -> {
                    accept("tools");
                    tools = decodeTools(value);
                }
                case "reasoning_effort" -> effort = decodeEffort(value);
                case "temperature" -> {
                    if (thinkingMode) {
                        ignore("temperature");
                    } else {
                        accept("temperature");
                        temperature = sampling(value, "temperature", 2.0d);
                    }
                }
                case "top_p" -> {
                    if (thinkingMode) {
                        accept("top_p");
                        topP = sampling(value, "top_p", 1.0d);
                    } else {
                        ignore("top_p");
                    }
                }
                case "tool_choice" -> {
                    accept("tool_choice");
                    toolChoice = decodeToolChoice(value, "tool_choice", thinkingMode);
                }
                case "response_format" -> responseFormat = decodeResponseFormat(value);
                case "user" -> user = decodeUserId(value, "user");
                case "safety_identifier" -> safetyIdentifier = decodeUserId(value, "safety_identifier");
                case "functions" -> legacyTools = decodeFunctions(value);
                case "function_call" -> legacyChoice = decodeLegacyChoice(value, thinkingMode);
                case "n" -> decodeChoiceCount(value);
                case "frequency_penalty", "presence_penalty", "parallel_tool_calls", "logprobs", "top_logprobs",
                        "prompt_cache_key", "prompt_cache_retention", "metadata", "service_tier" -> ignore(name);
                case "seed" -> throw reject(name, "上游无 seed，随机种子不可表达");
                case "store" -> throw reject(name, "网关不引入对话存储");
                case "modalities" -> throw reject(name, "上游仅文本输出，多模态输出不可表达");
                case "audio" -> throw reject(name, "音频输出不支持");
                case "prediction" -> throw reject(name, "预测输出不可表达");
                case "web_search_options" -> throw reject(name, "上游无服务端搜索");
                case "moderation" -> throw reject(name, "上游无审核能力");
                case "verbosity" -> throw reject(name, "上游无 verbosity 控制");
                case "logit_bias" -> throw reject(name, "上游无 logit_bias");
                case "prompt_cache_options" -> throw reject(name, "上游自动上下文缓存，无显式缓存选项");
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
            Integer maxOutputTokens = maxTokens;
            if (maxCompletionTokens != null) {
                if (maxTokens != null && !maxTokens.equals(maxCompletionTokens)) {
                    throw invalid("max_tokens", "max_tokens and max_completion_tokens must carry the same value");
                }
                maxOutputTokens = maxCompletionTokens;
            }
            if (tools != null && legacyTools != null) {
                throw invalid("tools", "tools and legacy functions must not be combined");
            }
            if (toolChoice != null && legacyChoice != null) {
                throw invalid("tool_choice", "tool_choice and legacy function_call must not be combined");
            }
            if (user != null && safetyIdentifier != null && !user.equals(safetyIdentifier)) {
                throw invalid("safety_identifier", "user and safety_identifier must carry the same value");
            }
            if (streamOptions && !stream) {
                throw invalid("stream_options", "stream_options requires stream=true");
            }
            // 省略 effort 时不伪造开关：thinkingEnabled=null 交上游默认（矩阵 §4.2）。
            Boolean thinkingEnabled = effort == null ? null : !"none".equals(effort);
            return new SemanticRequest(model, messages,
                    tools != null ? tools : legacyTools,
                    toolChoice != null ? toolChoice : legacyChoice,
                    thinkingEnabled, maxOutputTokens, stream, effort, stop, temperature, topP, responseFormat,
                    user != null ? user : safetyIdentifier, includeUsage);
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

        // ---------- 标量守卫 ----------

        private Integer outputTokens(JsonNode value, String fieldPath) {
            if (value.isNull()) {
                return null;
            }
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                throw invalid(fieldPath, fieldPath + " must be an integer");
            }
            int tokens = value.intValue();
            if (tokens < 1 || tokens > MAX_OUTPUT_TOKENS) {
                throw invalid(fieldPath, fieldPath + " must be within 1.." + MAX_OUTPUT_TOKENS);
            }
            accept(fieldPath);
            return tokens;
        }

        private List<String> decodeStop(JsonNode value) {
            accept("stop");
            if (value.isNull()) {
                return List.of();
            }
            if (value.isTextual()) {
                return List.of(value.asString());
            }
            if (!value.isArray()) {
                throw invalid("stop", "stop must be a string or an array of strings");
            }
            if (value.size() > MAX_STOP) {
                throw invalid("stop", "stop accepts at most " + MAX_STOP + " sequences");
            }
            List<String> sequences = new ArrayList<>();
            for (int i = 0; i < value.size(); i++) {
                sequences.add(requiredText(value.get(i), "stop[" + i + "]"));
            }
            return List.copyOf(sequences);
        }

        private Double sampling(JsonNode value, String fieldPath, double max) {
            if (value.isNull()) {
                return null;
            }
            if (!value.isNumber()) {
                throw invalid(fieldPath, fieldPath + " must be a number");
            }
            double number = value.doubleValue();
            if (!Double.isFinite(number) || number < 0 || number > max) {
                throw invalid(fieldPath, fieldPath + " must be within 0.." + max);
            }
            return number;
        }

        private String decodeEffort(JsonNode value) {
            if (value.isNull()) {
                return null;
            }
            String effort = requiredText(value, "reasoning_effort");
            if (!EFFORTS.contains(effort)) {
                throw invalid("reasoning_effort", "unsupported reasoning_effort: " + effort);
            }
            accept("reasoning_effort");
            return effort;
        }

        private String decodeUserId(JsonNode value, String fieldPath) {
            String id = requiredText(value, fieldPath);
            if (id.getBytes(StandardCharsets.UTF_8).length > MAX_USER_BYTES) {
                throw invalid(fieldPath, fieldPath + " must not exceed " + MAX_USER_BYTES + " UTF-8 bytes");
            }
            accept(fieldPath);
            return id;
        }

        /** 矩阵 §5.1 分类 E：原样承载 include_usage 的真实布尔值；缺键＝null（未指定），不得丢成 true。 */
        private Boolean decodeStreamOptions(JsonNode value) {
            accept("stream_options");
            if (!value.isObject()) {
                throw invalid("stream_options", "stream_options must be a JSON object");
            }
            Boolean preference = null;
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                if (!"include_usage".equals(entry.getKey())) {
                    throw unknown("stream_options." + entry.getKey());
                }
                if (!entry.getValue().isBoolean()) {
                    throw invalid("stream_options.include_usage", "include_usage must be a boolean");
                }
                preference = entry.getValue().booleanValue();
            }
            return preference;
        }

        private void decodeChoiceCount(JsonNode value) {
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                throw invalid("n", "n must be an integer");
            }
            int count = value.intValue();
            if (count < 1) {
                throw invalid("n", "n must be at least 1");
            }
            if (count > 1) {
                throw reject("n", "上游单 choice，多候选不可表达");
            }
            ignore("n");
        }

        // ---------- 消息 ----------

        private List<SemanticMessage> decodeMessages(JsonNode value) {
            if (!value.isArray() || value.isEmpty()) {
                throw invalid("messages", "messages must be a non-empty array");
            }
            List<SemanticMessage> decoded = new ArrayList<>();
            for (int i = 0; i < value.size(); i++) {
                decoded.add(decodeMessage(value.get(i), i));
            }
            return List.copyOf(decoded);
        }

        private SemanticMessage decodeMessage(JsonNode value, int index) {
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

            List<ContentPart> content = List.of();
            boolean contentSeen = false;
            ThinkingPart thinking = null;
            List<ToolCallPart> calls = List.of();
            ToolCallPart legacyCall = null;
            String toolCallId = null;
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                String key = entry.getKey();
                JsonNode node = entry.getValue();
                String fieldPath = path + "." + key;
                switch (key) {
                    case "role" -> {
                    }
                    case "content" -> {
                        accept(fieldPath);
                        contentSeen = true;
                        content = decodeContent(node, role, path);
                    }
                    case "reasoning_content" -> {
                        requireAssistant(role, fieldPath);
                        accept(fieldPath);
                        if (!node.isNull()) {
                            // Chat 原生回传推理明文，不带网关续轮信封（矩阵 §7.2：原生透传，不铸不验）。
                            thinking = new ThinkingPart(requiredText(node, fieldPath), null);
                        }
                    }
                    case "tool_calls" -> {
                        requireAssistant(role, fieldPath);
                        accept(fieldPath);
                        calls = decodeToolCalls(node, fieldPath);
                    }
                    case "function_call" -> {
                        requireAssistant(role, fieldPath);
                        legacyCall = decodeLegacyCall(node, fieldPath, index);
                    }
                    case "tool_call_id" -> {
                        if (role != Role.TOOL) {
                            throw invalid(fieldPath, "tool_call_id is only allowed in tool messages");
                        }
                        accept(fieldPath);
                        toolCallId = requiredText(node, fieldPath);
                        if (toolCallId.isBlank()) {
                            throw invalid(fieldPath, "tool_call_id must not be blank");
                        }
                    }
                    case "refusal" -> throw reject(fieldPath, "拒绝文本不可表达");
                    case "audio" -> throw reject(fieldPath, "音频消息不可表达");
                    default -> throw unknown(fieldPath);
                }
            }
            return switch (role) {
                case SYSTEM -> new SemanticMessage(Role.SYSTEM, content);
                case USER -> {
                    if (!contentSeen || content.isEmpty()) {
                        throw invalid(path + ".content", "user message must carry at least one content part");
                    }
                    yield new SemanticMessage(Role.USER, content);
                }
                case ASSISTANT -> {
                    List<ContentPart> parts = new ArrayList<>();
                    if (thinking != null) {
                        parts.add(thinking);
                    }
                    parts.addAll(content);
                    parts.addAll(calls);
                    if (legacyCall != null) {
                        parts.add(legacyCall);
                    }
                    yield new SemanticMessage(Role.ASSISTANT, parts);
                }
                case TOOL -> {
                    if (toolCallId == null) {
                        throw invalid(path + ".tool_call_id", "tool message requires tool_call_id");
                    }
                    yield new SemanticMessage(Role.TOOL,
                            List.of(new ToolResultPart(toolCallId, joinText(content), false)));
                }
            };
        }

        private Role role(String value, String fieldPath) {
            return switch (value) {
                case "system", "developer" -> Role.SYSTEM;
                case "user" -> Role.USER;
                case "assistant" -> Role.ASSISTANT;
                case "tool" -> Role.TOOL;
                case "function" -> throw reject(fieldPath, "legacy function 角色不可表达");
                default -> throw invalid(fieldPath, "unsupported message role: " + value);
            };
        }

        private void requireAssistant(Role role, String fieldPath) {
            if (role != Role.ASSISTANT) {
                throw invalid(fieldPath, fieldPath + " is only allowed in assistant messages");
            }
        }

        private List<ContentPart> decodeContent(JsonNode value, Role role, String messagePath) {
            if (value.isNull()) {
                return List.of();
            }
            if (value.isTextual()) {
                return List.of(new TextPart(value.asString()));
            }
            String contentPath = messagePath + ".content";
            if (!value.isArray()) {
                throw invalid(contentPath, "content must be a string or an array of content parts");
            }
            List<ContentPart> parts = new ArrayList<>();
            for (int j = 0; j < value.size(); j++) {
                String partPath = contentPath + "[" + j + "]";
                JsonNode part = value.get(j);
                if (!part.isObject()) {
                    throw invalid(partPath, "content part must be a JSON object");
                }
                JsonNode typeNode = part.path("type");
                if (!typeNode.isTextual()) {
                    throw invalid(partPath + ".type", "content part type must be a string");
                }
                String type = typeNode.asString();
                switch (type) {
                    case "text" -> {
                        requireKeys(part, partPath, Set.of("type", "text"));
                        parts.add(new TextPart(requiredText(part.path("text"), partPath + ".text")));
                    }
                    case "image_url" -> {
                        // 矩阵 §4.4/§8：图片仅允许在 user 消息，其余角色上游 400 → 前置拒绝。
                        if (role != Role.USER) {
                            throw invalid(partPath, "images are only allowed in user messages");
                        }
                        parts.add(decodeImage(part, partPath));
                    }
                    default -> throw reject(partPath, "不支持的内容块类型: " + type);
                }
            }
            return List.copyOf(parts);
        }

        private ImagePart decodeImage(JsonNode part, String partPath) {
            requireKeys(part, partPath, Set.of("type", "image_url"));
            JsonNode imageUrl = part.path("image_url");
            String imagePath = partPath + ".image_url";
            if (!imageUrl.isObject()) {
                throw invalid(imagePath, "image_url must be a JSON object");
            }
            requireKeys(imageUrl, imagePath, Set.of("url", "detail"));
            String urlPath = imagePath + ".url";
            String url = requiredText(imageUrl.path("url"), urlPath);
            if (!url.startsWith("data:")) {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    throw reject(urlPath, "第一阶段仅支持内联 base64 data URL，不支持外部图片 URL");
                }
                throw invalid(urlPath, "image url must be an inline base64 data URL");
            }
            int comma = url.indexOf(',');
            if (comma < 0) {
                throw invalid(urlPath, "image data URL must carry a comma separator");
            }
            String header = url.substring("data:".length(), comma);
            String data = url.substring(comma + 1);
            if (!header.endsWith(";base64")) {
                throw invalid(urlPath, "image data URL must declare ;base64");
            }
            String mediaType = header.substring(0, header.length() - ";base64".length());
            if (!IMAGE_MEDIA_TYPES.contains(mediaType)) {
                throw invalid(urlPath, "unsupported image media_type: " + mediaType
                        + " (allowed: image/jpeg, image/png, image/gif, image/webp)");
            }
            if (data.isEmpty()) {
                throw invalid(urlPath, "image base64 data must not be empty");
            }
            imageCount++;
            if (imageCount > MAX_IMAGES) {
                throw invalid("messages", "at most " + MAX_IMAGES + " images per request");
            }
            // 容量守卫前置：先按 base64 字符数估算，超限直接拒绝，不为超大图片分配整块解码缓冲。
            long estimated = base64LowerBoundBytes(data);
            if (estimated > MAX_IMAGE_BYTES) {
                throw invalid(urlPath, IMAGE_TOO_LARGE);
            }
            if (imageBytes + estimated > MAX_TOTAL_IMAGE_BYTES) {
                throw invalid("messages", IMAGES_TOO_LARGE);
            }
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(data);
            } catch (IllegalArgumentException e) {
                throw invalid(urlPath, "invalid base64 image data: " + e.getMessage());
            }
            // 估算只是下界：临界载荷仍以解码后的真实字节数为准（上限含等号）。
            if (decoded.length > MAX_IMAGE_BYTES) {
                throw invalid(urlPath, IMAGE_TOO_LARGE);
            }
            imageBytes += decoded.length;
            if (imageBytes > MAX_TOTAL_IMAGE_BYTES) {
                throw invalid("messages", IMAGES_TOO_LARGE);
            }
            String detail = null;
            JsonNode detailNode = imageUrl.path("detail");
            if (!detailNode.isMissingNode() && !detailNode.isNull()) {
                detail = requiredText(detailNode, imagePath + ".detail");
                if (!IMAGE_DETAILS.contains(detail)) {
                    throw invalid(imagePath + ".detail", "unsupported image detail: " + detail
                            + " (allowed: low, high, auto, original)");
                }
            }
            return new ImagePart(mediaType, data, null, detail);
        }

        private List<ToolCallPart> decodeToolCalls(JsonNode value, String fieldPath) {
            if (!value.isArray()) {
                throw invalid(fieldPath, "tool_calls must be an array");
            }
            List<ToolCallPart> calls = new ArrayList<>();
            for (int i = 0; i < value.size(); i++) {
                String callPath = fieldPath + "[" + i + "]";
                JsonNode call = value.get(i);
                if (!call.isObject()) {
                    throw invalid(callPath, "tool call must be a JSON object");
                }
                String id = null;
                JsonNode function = null;
                for (Map.Entry<String, JsonNode> entry : call.properties()) {
                    switch (entry.getKey()) {
                        case "id" -> id = requiredText(entry.getValue(), callPath + ".id");
                        case "type" -> {
                            String type = requiredText(entry.getValue(), callPath + ".type");
                            if (!"function".equals(type)) {
                                throw reject(callPath + ".type", "仅支持 function 工具调用");
                            }
                        }
                        case "function" -> function = entry.getValue();
                        default -> throw unknown(callPath + "." + entry.getKey());
                    }
                }
                if (id == null || id.isBlank()) {
                    throw invalid(callPath + ".id", "tool call id is required");
                }
                if (function == null || !function.isObject()) {
                    throw invalid(callPath + ".function", "tool call function must be a JSON object");
                }
                String name = null;
                String arguments = null;
                for (Map.Entry<String, JsonNode> entry : function.properties()) {
                    switch (entry.getKey()) {
                        case "name" -> name = requiredText(entry.getValue(), callPath + ".function.name");
                        case "arguments" -> {
                            JsonNode node = entry.getValue();
                            if (!node.isNull()) {
                                arguments = requiredText(node, callPath + ".function.arguments");
                            }
                        }
                        default -> throw unknown(callPath + ".function." + entry.getKey());
                    }
                }
                if (name == null || !TOOL_NAME.matcher(name).matches()) {
                    throw invalid(callPath + ".function.name", "tool name must match [a-zA-Z0-9_-]{1,128}");
                }
                calls.add(new ToolCallPart(id, name, arguments));
            }
            return List.copyOf(calls);
        }

        private ToolCallPart decodeLegacyCall(JsonNode value, String fieldPath, int index) {
            if (!value.isObject()) {
                throw invalid(fieldPath, "function_call must be a JSON object");
            }
            String name = null;
            String arguments = null;
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                switch (entry.getKey()) {
                    case "name" -> name = requiredText(entry.getValue(), fieldPath + ".name");
                    case "arguments" -> {
                        JsonNode node = entry.getValue();
                        if (!node.isNull()) {
                            arguments = requiredText(node, fieldPath + ".arguments");
                        }
                    }
                    default -> throw unknown(fieldPath + "." + entry.getKey());
                }
            }
            if (name == null || !TOOL_NAME.matcher(name).matches()) {
                throw invalid(fieldPath + ".name", "function name must match [a-zA-Z0-9_-]{1,128}");
            }
            degrade(fieldPath, LEGACY_FUNCTION, LEGACY_REASON);
            // legacy 形态无 id：合成稳定标识，保持 tool_call/tool_result 配对可续轮。
            return new ToolCallPart("llmgw-legacy-" + index, name, arguments);
        }

        // ---------- 工具与 tool_choice ----------

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
                if (!typeNode.isTextual()) {
                    throw invalid(path + ".type", "tool type must be a string");
                }
                if (!"function".equals(typeNode.asString())) {
                    throw reject(path, "仅支持 function 工具");
                }
                JsonNode function = null;
                for (Map.Entry<String, JsonNode> entry : tool.properties()) {
                    switch (entry.getKey()) {
                        case "type" -> {
                        }
                        case "function" -> function = entry.getValue();
                        default -> throw unknown(path + "." + entry.getKey());
                    }
                }
                if (function == null || !function.isObject()) {
                    throw invalid(path + ".function", "tool function must be a JSON object");
                }
                specs.add(functionSpec(function, path + ".function", names));
            }
            return List.copyOf(specs);
        }

        private List<ToolSpec> decodeFunctions(JsonNode value) {
            if (!value.isArray()) {
                throw invalid("functions", "functions must be an array");
            }
            List<ToolSpec> specs = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (int i = 0; i < value.size(); i++) {
                String path = "functions[" + i + "]";
                JsonNode function = value.get(i);
                if (!function.isObject()) {
                    throw invalid(path, "function must be a JSON object");
                }
                specs.add(functionSpec(function, path, names));
            }
            degrade("functions", LEGACY_FUNCTION, LEGACY_REASON);
            return List.copyOf(specs);
        }

        private ToolSpec functionSpec(JsonNode value, String path, Set<String> names) {
            String name = null;
            String description = null;
            String schema = null;
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                JsonNode node = entry.getValue();
                switch (entry.getKey()) {
                    case "name" -> name = requiredText(node, path + ".name");
                    case "description" -> {
                        if (!node.isNull()) {
                            description = requiredText(node, path + ".description");
                        }
                    }
                    case "parameters" -> {
                        if (!node.isNull()) {
                            if (!node.isObject()) {
                                throw invalid(path + ".parameters", "parameters must be a JSON Schema object");
                            }
                            schema = MAPPER.writeValueAsString(node);
                        }
                    }
                    case "strict" -> {
                        if (!node.isBoolean()) {
                            throw invalid(path + ".strict", "strict must be a boolean");
                        }
                        if (node.booleanValue()) {
                            throw reject(path + ".strict", "第一阶段不启用 strict 模式");
                        }
                    }
                    default -> throw unknown(path + "." + entry.getKey());
                }
            }
            if (name == null || !TOOL_NAME.matcher(name).matches()) {
                throw invalid(path + ".name", "tool name must match [a-zA-Z0-9_-]{1,128}");
            }
            if (!names.add(name)) {
                throw invalid(path + ".name", "duplicate tool name: " + name);
            }
            return new ToolSpec(name, description, schema);
        }

        private String decodeToolChoice(JsonNode value, String path, boolean thinkingMode) {
            if (value.isTextual()) {
                String choice = value.asString();
                return switch (choice) {
                    case "none", "auto" -> choice;
                    case "required" -> forced(choice, path, thinkingMode);
                    default -> throw invalid(path, "unsupported tool_choice: " + choice);
                };
            }
            if (!value.isObject()) {
                throw invalid(path, "tool_choice must be a string or a JSON object");
            }
            JsonNode typeNode = value.path("type");
            if (!typeNode.isTextual()) {
                throw invalid(path + ".type", "tool_choice type must be a string");
            }
            String type = typeNode.asString();
            if ("allowed_tools".equals(type)) {
                throw reject(path, "工具子集约束不可表达");
            }
            if (!"function".equals(type)) {
                throw invalid(path + ".type", "unsupported tool_choice type: " + type);
            }
            requireKeys(value, path, Set.of("type", "function"));
            JsonNode function = value.path("function");
            if (!function.isObject()) {
                throw invalid(path + ".function", "tool_choice.function must be a JSON object");
            }
            requireKeys(function, path + ".function", Set.of("name"));
            String name = requiredText(function.path("name"), path + ".function.name");
            if (name.isBlank()) {
                throw invalid(path + ".function.name", "named tool_choice requires a tool name");
            }
            return forced("tool:" + name, path, thinkingMode);
        }

        private String decodeLegacyChoice(JsonNode value, boolean thinkingMode) {
            String path = "function_call";
            String choice;
            if (value.isTextual()) {
                choice = switch (value.asString()) {
                    case "none", "auto" -> value.asString();
                    default -> throw invalid(path, "unsupported function_call: " + value.asString());
                };
            } else if (value.isObject()) {
                requireKeys(value, path, Set.of("name"));
                String name = requiredText(value.path("name"), path + ".name");
                if (!TOOL_NAME.matcher(name).matches()) {
                    throw invalid(path + ".name", "function name must match [a-zA-Z0-9_-]{1,128}");
                }
                choice = "tool:" + name;
            } else {
                throw invalid(path, "function_call must be a string or a JSON object");
            }
            degrade(path, LEGACY_FUNCTION, LEGACY_REASON);
            return forced(choice, path, thinkingMode);
        }

        /** 思考模式 required/具名上游 400：未授权→拒绝，授权→退化为 auto（矩阵 §5.1/§11）。 */
        private String forced(String choice, String path, boolean thinkingMode) {
            if (!thinkingMode) {
                return choice;
            }
            degrade(path, FORCE_TO_AUTO, FORCE_AUTO_REASON);
            return "auto";
        }

        private String decodeResponseFormat(JsonNode value) {
            String path = "response_format";
            if (!value.isObject()) {
                throw invalid(path, "response_format must be a JSON object");
            }
            JsonNode typeNode = value.path("type");
            if (!typeNode.isTextual()) {
                throw invalid(path + ".type", "response_format.type must be a string");
            }
            String type = typeNode.asString();
            switch (type) {
                case "text", "json_object" -> accept(path);
                case "json_schema" -> degrade(path, SCHEMA_TO_OBJECT, SCHEMA_REASON);
                default -> throw invalid(path + ".type", "unsupported response_format type: " + type);
            }
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                String key = entry.getKey();
                // 已授权降级整体丢弃 schema，不再逐字段判定其内部结构。
                if ("type".equals(key) || ("json_schema".equals(key) && "json_schema".equals(type))) {
                    continue;
                }
                throw unknown(path + "." + key);
            }
            return "json_schema".equals(type) ? "json_object" : type;
        }

        private void requireKeys(JsonNode value, String path, Set<String> allowed) {
            for (Map.Entry<String, JsonNode> entry : value.properties()) {
                if (!allowed.contains(entry.getKey())) {
                    throw unknown(path + "." + entry.getKey());
                }
            }
        }
    }

    // ========== 每请求流式编码状态（矩阵 §6.2） ==========

    private static final class Encoder implements StreamEncoder {

        private final String model;
        private final boolean includeUsage;
        private final String id = "chatcmpl-" + UUID.randomUUID();
        private final long created = Instant.now().getEpochSecond();

        private boolean roleSent;
        private boolean terminalSeen;
        private boolean failed;
        private boolean doneSent;
        private Usage usage;

        private Encoder(String model, boolean includeUsage) {
            this.model = model;
            this.includeUsage = includeUsage;
        }

        @Override
        public List<String> onEvent(SemanticEvent event) {
            Objects.requireNonNull(event, "event");
            if (terminalSeen) {
                return List.of();
            }
            return switch (event) {
                case TextDelta text -> {
                    ObjectNode delta = MAPPER.createObjectNode();
                    delta.put("content", text.text());
                    yield List.of(contentChunk(delta));
                }
                case ThinkingDelta thinking -> {
                    ObjectNode delta = MAPPER.createObjectNode();
                    delta.put("reasoning_content", thinking.reasoning());
                    yield List.of(contentChunk(delta));
                }
                case ToolCallDelta call -> {
                    ObjectNode delta = MAPPER.createObjectNode();
                    ObjectNode node = delta.putArray("tool_calls").addObject();
                    node.put("index", call.index());
                    if (call.id() != null) {
                        node.put("id", call.id());
                        node.put("type", "function");
                    }
                    ObjectNode function = node.putObject("function");
                    if (call.name() != null) {
                        function.put("name", call.name());
                    }
                    if (call.arguments() != null) {
                        function.put("arguments", call.arguments());
                    }
                    yield List.of(contentChunk(delta));
                }
                // DeepSeek 把 usage 放在最后一个内容 chunk：缓冲到终态帧之后再还原 OpenAI 的独立 usage 帧。
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
            return List.of("data: [DONE]");
        }

        @Override
        public List<String> onError(String code, String message) {
            if (terminalSeen || doneSent) {
                return List.of();
            }
            // 空 code/message 不得抛裸异常逃逸成 500：兜底为稳定错误帧，仍是一次终态且不发 [DONE]。
            String wireCode = code == null || code.isBlank() ? FALLBACK_ERROR_CODE : code;
            String wireMessage = message == null || message.isBlank() ? FALLBACK_ERROR_MESSAGE : message;
            failed = true;
            terminalSeen = true;
            return List.of(line(errorBody(wireCode, wireMessage, null, statusForCode(wireCode))));
        }

        @Override
        public boolean terminalSeen() {
            return terminalSeen;
        }

        private List<String> terminal(StopReason stop) {
            String wire;
            try {
                wire = finishReason(stop);
            } catch (UpstreamException e) {
                return onError(e.code(), e.getMessage());
            }
            terminalSeen = true;
            List<String> lines = new ArrayList<>();
            ObjectNode chunk = envelope();
            ObjectNode choice = chunk.putArray("choices").addObject();
            choice.put("index", 0);
            ObjectNode delta = MAPPER.createObjectNode();
            // 只有终态帧的流（如 content_filter/空文本）也必须声明一次 role，否则客户端拿不到 assistant。
            if (!roleSent) {
                delta.put("role", "assistant");
                roleSent = true;
            }
            choice.set("delta", delta);
            choice.put("finish_reason", wire);
            lines.add(line(chunk));
            if (includeUsage) {
                JsonNode usageNode = usageJson(usage);
                if (usageNode != null) {
                    ObjectNode usageChunk = envelope();
                    usageChunk.putArray("choices");
                    usageChunk.set("usage", usageNode);
                    lines.add(line(usageChunk));
                }
            }
            return List.copyOf(lines);
        }

        private String contentChunk(ObjectNode delta) {
            ObjectNode chunk = envelope();
            ObjectNode choice = chunk.putArray("choices").addObject();
            choice.put("index", 0);
            ObjectNode payload = MAPPER.createObjectNode();
            if (!roleSent) {
                payload.put("role", "assistant");
                roleSent = true;
            }
            payload.setAll(delta);
            choice.set("delta", payload);
            choice.putNull("finish_reason");
            return line(chunk);
        }

        private ObjectNode envelope() {
            ObjectNode chunk = MAPPER.createObjectNode();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", created);
            if (model != null) {
                chunk.put("model", model);
            }
            return chunk;
        }
    }
}