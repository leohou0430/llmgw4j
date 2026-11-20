package io.github.leohou0430.llmgw.conversion.responses;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * OpenAI Responses 入口编解码（映射矩阵 §5.2 全量分类 / §6.3 SSE / §9 usage / §10.2 错误形状 / §11 降级）。
 * 无状态可共享：每请求状态归 {@link CapabilityContext#audit()} 与 {@link StreamEncoder} 实例。
 */
public final class ResponsesEntryCodec implements EntryCodec {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/** 矩阵 §5.2/§4.4 的入口守卫：越界前置 400，不把非法值丢给上游。 */
	private static final int MAX_OUTPUT_TOKENS = 393216;

	private static final int MAX_USER_BYTES = 512;

	private static final int MAX_IMAGES = 600;

	private static final long MAX_IMAGE_BYTES = 32L * 1024 * 1024;

	private static final long MAX_TOTAL_IMAGE_BYTES = 64L * 1024 * 1024;

	private static final Set<String> IMAGE_MEDIA_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

	private static final Set<String> IMAGE_DETAILS = Set.of("low", "high", "auto", "original");

	private static final Set<String> EFFORTS = Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max");

	private static final Pattern TOOL_NAME = Pattern.compile("[a-zA-Z0-9_-]{1,128}");

	private static final String FORCE_TO_AUTO = "tool_choice.force_to_auto";

	private static final String SCHEMA_TO_OBJECT = "response_format.schema_to_object";

	private static final String DISPLAY_RAW = "thinking.display_raw";

	private static final String FORCE_AUTO_REASON = "思考模式不强制工具调用，required/具名退化为 auto";

	private static final String SCHEMA_REASON = "不强制 JSON Schema，仅保证 json_object";

	private static final String DISPLAY_RAW_REASON = "上游无摘要模型，固定回传原文推理而非摘要";

	/** 错误帧兜底（矩阵 §10.1 internal_error→500）：上游给了空 code/message 也必须落合法错误帧。 */
	private static final String FALLBACK_ERROR_CODE = "internal_error";

	private static final String FALLBACK_ERROR_MESSAGE = "stream failed without an error detail";

	/**
	 * json_schema 降级后整体丢弃的 schema 载体键（钉住 SDK ResponseFormatTextJSONSchemaConfigParam）。
	 */
	private static final Set<String> SCHEMA_KEYS = Set.of("name", "schema", "strict", "description");

	/** 图片容量守卫的客户端原因；由实际上限推导，避免文案与常量漂移（矩阵 §4.4/§8）。 */
	private static final String IMAGE_TOO_LARGE = "inline image exceeds " + MAX_IMAGE_BYTES / (1024 * 1024) + " MiB";

	private static final String IMAGES_TOO_LARGE = "inline images exceed " + MAX_TOTAL_IMAGE_BYTES / (1024 * 1024)
			+ " MiB in total";

	@Override
	public ProtocolKind kind() {
		return ProtocolKind.RESPONSES;
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
		// Responses 入口只表达单条 assistant output 序列：多候选不可表达，绝不静默丢弃其余消息。
		if (resp.messages().size() > 1) {
			throw new UpstreamException(
					"responses entry expresses a single assistant turn, got " + resp.messages().size() + " messages",
					"messages", null);
		}
		if (!resp.messages().isEmpty() && resp.messages().get(0).role() != Role.ASSISTANT) {
			throw new UpstreamException(
					"responses entry expresses an assistant turn, got role " + resp.messages().get(0).role(),
					"output[0].role", null);
		}
		List<ContentPart> parts = resp.messages().isEmpty() ? List.of() : resp.messages().get(0).parts();
		// 镜像 Chat：assistant 部件只可表达 Text/Thinking/ToolCall，其余（Image/ToolResult 等）绝不静默丢弃。
		for (ContentPart part : parts) {
			if (!(part instanceof TextPart) && !(part instanceof ThinkingPart) && !(part instanceof ToolCallPart)) {
				throw new UpstreamException(
						"responses entry cannot express assistant part: " + part.getClass().getSimpleName(),
						"output[0].content", null);
			}
		}
		String status = status(resp.stop());

		ObjectNode root = MAPPER.createObjectNode();
		root.put("id", responseId(resp.id()));
		root.put("object", "response");
		root.put("created_at", Instant.now().getEpochSecond());
		root.put("status", status);
		if ("incomplete".equals(status)) {
			root.putObject("incomplete_details").put("reason", incompleteReason(resp.stop()));
		}
		if (model != null) {
			root.put("model", model);
		}
		ArrayNode output = root.putArray("output");
		String reasoning = joinReasoning(parts);
		if (reasoning != null) {
			// 矩阵 §7.3：encodeResponse 为 reasoning 条目铸造续轮信封，绑定本条目 id + ctx 项目 + 推理原文。
			output.add(reasoningItemWithEnvelope(reasoning, "rs_" + UUID.randomUUID(), ctx));
		}
		// 仅纯 tool_call 轮省略 message 条目（对齐流式纯工具轮）；空轮/仅推理轮仍发 content=[] 的 message，属既有非流式行为。
		boolean hasText = parts.stream().anyMatch(p -> p instanceof TextPart);
		boolean toolCallOnly = parts.stream().anyMatch(p -> p instanceof ToolCallPart) && !hasText;
		if (!toolCallOnly) {
			output.add(messageItem(parts));
		}
		for (ContentPart part : parts) {
			if (part instanceof ToolCallPart call) {
				output.add(functionCallItem(call));
			}
		}
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
		return MAPPER.writeValueAsString(errorBody(first.code(), first.reason(), first.fieldPath(), e.status()));
	}

	@Override
	public String errorEvent(CapabilityException e) {
		Objects.requireNonNull(e, "error");
		Violation first = e.violations().get(0);
		return "event: error\ndata: "
				+ MAPPER.writeValueAsString(errorEventPayload(first.code(), first.reason(), first.fieldPath(), 0));
	}

	// ========== 共享渲染 ==========

	private static String responseId(String id) {
		return id == null || id.isBlank() ? "resp_" + UUID.randomUUID() : id;
	}

	/** 异常/未知终止绝不映射为成功（矩阵 §4.7/§6.3）。 */
	private static String status(StopReason stop) {
		if (stop == null) {
			throw new UpstreamException("upstream response has no stop reason (incomplete response)", "status", null);
		}
		return switch (stop) {
			case COMPLETED, TOOL_CALL -> "completed";
			case LENGTH, CONTENT_FILTER -> "incomplete";
			case UNKNOWN ->
				throw new UpstreamException("upstream stop reason is not expressible: UNKNOWN", "status", null);
		};
	}

	private static String incompleteReason(StopReason stop) {
		return switch (stop) {
			case LENGTH -> "max_output_tokens";
			case CONTENT_FILTER -> "content_filter";
			default -> throw new UpstreamException("stop reason is not incomplete: " + stop,
					"incomplete_details.reason", null);
		};
	}

	private static String joinReasoning(List<ContentPart> parts) {
		StringBuilder reasoning = null;
		for (ContentPart part : parts) {
			if (part instanceof ThinkingPart thinking && thinking.reasoning() != null) {
				reasoning = reasoning == null ? new StringBuilder() : reasoning;
				reasoning.append(thinking.reasoning());
			}
		}
		return reasoning == null ? null : reasoning.toString();
	}

	/**
	 * 矩阵 §7.2/§7.4：推理原文走 {@code content[reasoning_text]}，{@code summary} 恒为空（不伪造摘要）。
	 * <p>
	 * 本方法只渲染基础条目（不含信封）；续轮信封 {@code encrypted_content} 由调用方经
	 * {@link #reasoningItemWithEnvelope} 用每请求 {@link CapabilityContext#envelope()} 铸造，
	 * 绝不伪造供应商签名。itemId 由调用方传入并绑定进信封 payload。
	 */
	private static ObjectNode reasoningItem(String reasoning, String itemId) {
		ObjectNode item = MAPPER.createObjectNode();
		item.put("id", itemId);
		item.put("type", "reasoning");
		item.putArray("summary");
		item.putArray("content").addObject().put("type", "reasoning_text").put("text", reasoning);
		item.put("status", "completed");
		return item;
	}

	/**
	 * 矩阵 §7.3：在基础 reasoning 条目上铸造续轮信封 {@code encrypted_content}
	 * （{@code llmgw1:<payload>.<hmac>}），绑定 ctx 的 projectId + 本条目 itemId + 推理原文摘要。 HMAC
	 * 密钥经每请求 {@link CapabilityContext#envelope()} 传入，codec 本身无状态、不持密钥。
	 */
	private static ObjectNode reasoningItemWithEnvelope(String reasoning, String itemId, CapabilityContext ctx) {
		ObjectNode item = reasoningItem(reasoning, itemId);
		item.put("encrypted_content", ctx.envelope().mint(ctx.projectId(), itemId, reasoning));
		return item;
	}

	private static ObjectNode messageItem(List<ContentPart> parts) {
		ObjectNode item = MAPPER.createObjectNode();
		item.put("id", "msg_" + UUID.randomUUID());
		item.put("type", "message");
		item.put("role", "assistant");
		item.put("status", "completed");
		ArrayNode content = item.putArray("content");
		StringBuilder text = null;
		for (ContentPart part : parts) {
			if (part instanceof TextPart textPart) {
				text = text == null ? new StringBuilder() : text;
				text.append(textPart.text());
			}
		}
		if (text != null) {
			content.addObject().put("type", "output_text").put("text", text.toString()).putArray("annotations");
		}
		return item;
	}

	private static ObjectNode functionCallItem(ToolCallPart call) {
		ObjectNode item = MAPPER.createObjectNode();
		item.put("id", "fc_" + UUID.randomUUID());
		item.put("type", "function_call");
		item.put("call_id", call.id());
		item.put("name", call.name());
		item.put("arguments", call.argumentsJson() == null ? "" : call.argumentsJson());
		item.put("status", "completed");
		return item;
	}

	/** 矩阵 §9：null=未知→省略字段，绝不填 0；total 仅在输入/输出都已知时给出。 */
	private static JsonNode usageJson(Usage usage) {
		if (usage == null) {
			return null;
		}
		ObjectNode node = MAPPER.createObjectNode();
		if (usage.inputTokens() != null) {
			node.put("input_tokens", usage.inputTokens().longValue());
		}
		if (usage.outputTokens() != null) {
			node.put("output_tokens", usage.outputTokens().longValue());
		}
		if (usage.inputTokens() != null && usage.outputTokens() != null) {
			node.put("total_tokens", usage.inputTokens() + usage.outputTokens());
		}
		if (usage.cachedInputTokens() != null) {
			node.putObject("input_tokens_details").put("cached_tokens", usage.cachedInputTokens().longValue());
		}
		if (usage.reasoningTokens() != null) {
			node.putObject("output_tokens_details").put("reasoning_tokens", usage.reasoningTokens().longValue());
		}
		return node.isEmpty() ? null : node;
	}

	/** 矩阵 §10.2：Responses 错误体键序 code/message/param/type。 */
	private static ObjectNode errorBody(String code, String message, String param, int status) {
		// 复用 errorFields 保证 code/message/param 同源同序，再补 type（矩阵 §10.2 键序
		// code/message/param/type）。
		ObjectNode error = errorFields(code, message, param);
		error.put("type", errorType(status));
		ObjectNode root = MAPPER.createObjectNode();
		root.set("error", error);
		return root;
	}

	/** 矩阵 §10.2：头已发送后的 Responses 错误事件载荷（带 sequence_number）。 */
	private static ObjectNode errorEventPayload(String code, String message, String param, long sequenceNumber) {
		ObjectNode payload = MAPPER.createObjectNode();
		payload.put("type", "error");
		payload.setAll(errorFields(code, message, param));
		payload.put("sequence_number", sequenceNumber);
		return payload;
	}

	private static ObjectNode errorFields(String code, String message, String param) {
		ObjectNode payload = MAPPER.createObjectNode();
		payload.put("code", code);
		payload.put("message", message);
		if (param == null) {
			payload.putNull("param");
		}
		else {
			payload.put("param", param);
		}
		return payload;
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

	/**
	 * 只产事件行（{@code event: <type>} + {@code data: <json>}），不含结尾换行； 帧间隔归上层 transcoder（与
	 * {@link StreamEncoder} 的行框架归属一致）。
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
		}
		catch (RuntimeException e) {
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
	 * 仅用于解码前的容量守卫（下界已超限即拒绝，不必分配解码缓冲）； 字符合法性与精确长度仍由 {@link Base64#getDecoder()} 判定。
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

		private String instructions;

		private List<SemanticMessage> input;

		private Integer maxOutputTokens;

		private String user;

		private String safetyIdentifier;

		private int imageCount;

		private long imageBytes;

		private boolean stream;

		private boolean streamOptions;

		private Boolean includeUsage;

		private String effort;

		private Double temperature;

		private Double topP;

		private String responseFormat;

		private List<ToolSpec> tools;

		private String toolChoice;

		private RequestDecoder(CapabilityContext ctx) {
			this.audit = ctx.audit();
			this.projectId = ctx.projectId();
			this.envelope = ctx.envelope();
		}

		private SemanticRequest decode(JsonNode root) {
			// 分类用的思考模式：省略 reasoning.effort 时上游默认开启思考（矩阵 §4.2），
			// 故 temperature 属 I、top_p 属 E；effort=none 时反转。
			boolean thinkingMode = isThinkingMode(root);
			for (Map.Entry<String, JsonNode> entry : root.properties()) {
				field(entry.getKey(), entry.getValue(), thinkingMode);
			}
			return assemble();
		}

		private static boolean isThinkingMode(JsonNode root) {
			JsonNode effort = root.path("reasoning").path("effort");
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
				case "instructions" -> {
					// 钉住 SDK 允许 null：按缺省处理（不前置 system 消息），不当 400 invalid_value。
					if (!value.isNull()) {
						accept("instructions");
						instructions = requiredText(value, "instructions");
					}
				}
				case "input" -> {
					accept("input");
					input = decodeInput(value);
				}
				case "max_output_tokens" -> maxOutputTokens = outputTokens(value, "max_output_tokens");
				case "user" -> user = decodeUserId(value, "user");
				case "safety_identifier" -> safetyIdentifier = decodeUserId(value, "safety_identifier");
				case "stream" -> {
					// 钉住 SDK 允许 null：按缺省处理（stream=false），不当 400 invalid_value。
					if (!value.isNull()) {
						accept("stream");
						if (!value.isBoolean()) {
							throw invalid("stream", "stream must be a boolean");
						}
						stream = value.booleanValue();
					}
				}
				case "stream_options" -> {
					streamOptions = true;
					includeUsage = decodeStreamOptions(value);
				}
				case "reasoning" -> decodeReasoning(value);
				case "text" -> responseFormat = decodeTextFormat(value);
				case "tools" -> {
					accept("tools");
					tools = decodeTools(value);
				}
				case "tool_choice" -> {
					accept("tool_choice");
					toolChoice = decodeToolChoice(value, "tool_choice", thinkingMode);
				}
				case "include" -> decodeInclude(value);
				case "temperature" -> {
					if (thinkingMode) {
						ignore("temperature");
					}
					else {
						accept("temperature");
						temperature = sampling(value, "temperature", 2.0d);
					}
				}
				case "top_p" -> {
					if (thinkingMode) {
						accept("top_p");
						topP = sampling(value, "top_p", 1.0d);
					}
					else {
						ignore("top_p");
					}
				}
				case "parallel_tool_calls", "prompt_cache_key", "prompt_cache_retention", "metadata", "service_tier",
						"top_logprobs" ->
					ignore(name);
				case "max_tool_calls" -> throw reject(name, "上游无工具调用次数上限");
				case "previous_response_id" -> throw reject(name, "网关不引入对话存储，无法按响应 id 续轮");
				case "store" -> throw reject(name, "网关不引入对话存储");
				case "background" -> throw reject(name, "不支持后台执行");
				case "conversation" -> throw reject(name, "对话对象存储不可表达");
				case "context_management" -> throw reject(name, "上下文管理不可表达");
				case "prompt" -> throw reject(name, "prompt 模板不可表达");
				case "prompt_cache_options" -> throw reject(name, "上游自动上下文缓存，无显式缓存选项");
				case "truncation" -> throw reject(name, "上游无自动截断策略");
				case "moderation" -> throw reject(name, "上游无审核能力");
				case "image" -> throw reject(name, "上游无图片生成能力");
				case "access_programs" -> throw reject(name, "访问计划不可表达");
				default -> throw unknown(name);
			}
		}

		private SemanticRequest assemble() {
			if (model == null) {
				throw invalid("model", "model is required");
			}
			if (input == null) {
				throw invalid("input", "input is required");
			}
			if (user != null && safetyIdentifier != null && !user.equals(safetyIdentifier)) {
				throw invalid("safety_identifier", "user and safety_identifier must carry the same value");
			}
			if (streamOptions && !stream) {
				throw invalid("stream_options", "stream_options requires stream=true");
			}
			List<SemanticMessage> messages = new ArrayList<>();
			if (instructions != null) {
				messages.add(new SemanticMessage(Role.SYSTEM, List.of(new TextPart(instructions))));
			}
			messages.addAll(input);
			// 省略 effort 时不伪造开关：thinkingEnabled=null 交上游默认（矩阵 §4.2）。
			Boolean thinkingEnabled = effort == null ? null : !"none".equals(effort);
			return new SemanticRequest(model, messages, tools, toolChoice, thinkingEnabled, maxOutputTokens, stream,
					effort, List.of(), temperature, topP, responseFormat, user != null ? user : safetyIdentifier,
					includeUsage);
		}

		// ---------- 分类判定 ----------

		private void accept(String fieldPath) {
			CapabilityPolicy.evaluate(new FieldRule(fieldPath, Classification.EQUIVALENT, null, null), audit);
		}

		private void ignore(String fieldPath) {
			CapabilityPolicy.evaluate(new FieldRule(fieldPath, Classification.IGNORABLE, null, null), audit);
		}

		private void degrade(String fieldPath, String degradationId, String reason) {
			CapabilityPolicy.evaluate(new FieldRule(fieldPath, Classification.DEGRADABLE, degradationId, reason),
					audit);
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

		private String decodeUserId(JsonNode value, String fieldPath) {
			// 钉住 SDK 允许 null：按缺省处理（userId 省略），不当 400 invalid_value。
			if (value.isNull()) {
				return null;
			}
			String id = requiredText(value, fieldPath);
			if (id.getBytes(StandardCharsets.UTF_8).length > MAX_USER_BYTES) {
				throw invalid(fieldPath, fieldPath + " must not exceed " + MAX_USER_BYTES + " UTF-8 bytes");
			}
			accept(fieldPath);
			return id;
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

		/** 矩阵 §5.2 分类 E：原样承载 include_usage 的真实布尔值；缺键＝null（未指定），不得丢成 true。 */
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

		private void decodeReasoning(JsonNode value) {
			if (!value.isObject()) {
				throw invalid("reasoning", "reasoning must be a JSON object");
			}
			for (Map.Entry<String, JsonNode> entry : value.properties()) {
				String fieldPath = "reasoning." + entry.getKey();
				switch (entry.getKey()) {
					case "effort" -> effort = decodeEffort(entry.getValue());
					// 上游无摘要模型：未授权→R，授权 thinking.display_raw→D（固定原文推理，损失摘要形态）。
					// 钉住 SDK 允许 summary:null＝缺省，不触发降级授权（与 effort:null 同约定）。
					case "summary", "generate_summary" -> {
						if (!entry.getValue().isNull()) {
							degrade(fieldPath, DISPLAY_RAW, DISPLAY_RAW_REASON);
						}
					}
					case "context" -> throw reject(fieldPath, "推理上下文范围不可表达");
					case "mode" -> throw reject(fieldPath, "推理模式不可表达");
					default -> throw unknown(fieldPath);
				}
			}
		}

		private String decodeEffort(JsonNode value) {
			if (value.isNull()) {
				return null;
			}
			String effort = requiredText(value, "reasoning.effort");
			if (!EFFORTS.contains(effort)) {
				throw invalid("reasoning.effort", "unsupported reasoning.effort: " + effort);
			}
			accept("reasoning.effort");
			return effort;
		}

		private String decodeTextFormat(JsonNode value) {
			if (!value.isObject()) {
				throw invalid("text", "text must be a JSON object");
			}
			String format = null;
			for (Map.Entry<String, JsonNode> entry : value.properties()) {
				String fieldPath = "text." + entry.getKey();
				switch (entry.getKey()) {
					case "format" -> format = decodeFormat(entry.getValue(), fieldPath);
					default -> throw unknown(fieldPath);
				}
			}
			return format;
		}

		private String decodeFormat(JsonNode value, String path) {
			if (!value.isObject()) {
				throw invalid(path, "text.format must be a JSON object");
			}
			JsonNode typeNode = value.path("type");
			if (!typeNode.isTextual()) {
				throw invalid(path + ".type", "text.format.type must be a string");
			}
			String type = typeNode.asString();
			switch (type) {
				case "text", "json_object" -> accept(path);
				// 上游仅 json_object：未授权→R，授权 response_format.schema_to_object→D（不强制
				// schema）。
				case "json_schema" -> degrade(path, SCHEMA_TO_OBJECT, SCHEMA_REASON);
				default -> throw invalid(path + ".type", "unsupported text.format type: " + type);
			}
			for (Map.Entry<String, JsonNode> entry : value.properties()) {
				String key = entry.getKey();
				// 已授权降级整体丢弃 schema，不再逐字段判定其内部结构。
				if ("type".equals(key) || ("json_schema".equals(type) && SCHEMA_KEYS.contains(key))) {
					continue;
				}
				throw unknown(path + "." + key);
			}
			return "json_schema".equals(type) ? "json_object" : type;
		}

		/** 矩阵 §5.2 E：Responses 的 function 工具是扁平形态（name/parameters/strict 与 type 同级）。 */
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
					throw reject(path, "服务端内建工具一律拒绝，仅支持 function 工具");
				}
				specs.add(functionSpec(tool, path, names));
			}
			return List.copyOf(specs);
		}

		private ToolSpec functionSpec(JsonNode value, String path, Set<String> names) {
			String name = null;
			String description = null;
			String schema = null;
			for (Map.Entry<String, JsonNode> entry : value.properties()) {
				JsonNode node = entry.getValue();
				switch (entry.getKey()) {
					case "type" -> {
					}
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
			if (!"function".equals(type)) {
				// allowed_tools/mcp/custom/内建工具选择一律不可表达（矩阵 §5.2 R）。
				throw reject(path, "不支持的 tool_choice 类型: " + type);
			}
			// Responses 具名形态扁平：{type:"function", name:"…"}，不是 Chat 的嵌套 function 对象。
			requireKeys(value, path, Set.of("type", "name"));
			String name = requiredText(value.path("name"), path + ".name");
			if (!TOOL_NAME.matcher(name).matches()) {
				throw invalid(path + ".name", "tool name must match [a-zA-Z0-9_-]{1,128}");
			}
			return forced("tool:" + name, path, thinkingMode);
		}

		/** 思考模式 required/具名上游 400：未授权→拒绝，授权→退化为 auto（矩阵 §5.2/§11）。 */
		private String forced(String choice, String path, boolean thinkingMode) {
			if (!thinkingMode) {
				return choice;
			}
			degrade(path, FORCE_TO_AUTO, FORCE_AUTO_REASON);
			return "auto";
		}

		/** 矩阵 §5.2：网关固定输出续轮信封，include 的 encrypted_content 门控属 I；其余展开结果不可表达→R。 */
		private void decodeInclude(JsonNode value) {
			if (!value.isArray()) {
				throw invalid("include", "include must be an array of strings");
			}
			for (int i = 0; i < value.size(); i++) {
				String path = "include[" + i + "]";
				String item = requiredText(value.get(i), path);
				if ("reasoning.encrypted_content".equals(item)) {
					ignore(path);
				}
				else {
					throw reject(path, "不支持的 include 展开项: " + item);
				}
			}
		}

		// ---------- 输入条目 ----------

		private List<SemanticMessage> decodeInput(JsonNode value) {
			if (value.isTextual()) {
				return List.of(new SemanticMessage(Role.USER, List.of(new TextPart(value.asString()))));
			}
			if (!value.isArray() || value.isEmpty()) {
				throw invalid("input", "input must be a string or a non-empty array of input items");
			}
			List<SemanticMessage> messages = new ArrayList<>();
			// Responses 的 assistant 轮是扁平条目序列（reasoning / message / function_call）；
			// 上游 DeepSeek 把 reasoning_content 与 tool_calls 放在同一条 assistant 消息（矩阵 §4.3），
			// 故连续的同轮条目必须合并，否则带 tools 续轮会丢 reasoning_content → 上游 400。
			List<ContentPart> turn = new ArrayList<>();
			for (int i = 0; i < value.size(); i++) {
				JsonNode item = value.get(i);
				String path = "input[" + i + "]";
				if (!item.isObject()) {
					throw invalid(path, "input item must be a JSON object");
				}
				String type = itemType(item, path);
				switch (type) {
					case "message" -> {
						SemanticMessage message = decodeMessageItem(item, path);
						if (message.role() == Role.ASSISTANT) {
							turn.addAll(message.parts());
						}
						else {
							flushTurn(messages, turn);
							messages.add(message);
						}
					}
					case "reasoning" -> turn.add(decodeReasoningItem(item, path));
					case "function_call" -> turn.add(decodeFunctionCallItem(item, path));
					case "function_call_output" -> {
						flushTurn(messages, turn);
						messages.add(decodeFunctionCallOutputItem(item, path));
					}
					default -> throw reject(path, "不支持的输入条目类型: " + type);
				}
			}
			flushTurn(messages, turn);
			return List.copyOf(messages);
		}

		/** EasyInputMessage 省略 type：缺省即 message 条目（钉住 SDK EasyInputMessageParam）。 */
		private String itemType(JsonNode item, String path) {
			JsonNode typeNode = item.path("type");
			return typeNode.isMissingNode() || typeNode.isNull() ? "message" : requiredText(typeNode, path + ".type");
		}

		/** 同轮条目归一为 thinking → text → tool_calls（上游是同级字段，跨类顺序不可观察；文本分段顺序保持）。 */
		private void flushTurn(List<SemanticMessage> messages, List<ContentPart> turn) {
			if (turn.isEmpty()) {
				return;
			}
			List<ContentPart> parts = new ArrayList<>();
			for (ContentPart part : turn) {
				if (part instanceof ThinkingPart) {
					parts.add(part);
				}
			}
			for (ContentPart part : turn) {
				if (part instanceof TextPart) {
					parts.add(part);
				}
			}
			for (ContentPart part : turn) {
				if (part instanceof ToolCallPart) {
					parts.add(part);
				}
			}
			messages.add(new SemanticMessage(Role.ASSISTANT, parts));
			turn.clear();
		}

		/**
		 * 矩阵 §5.2/§7：取 reasoning_text 明文 → 上游 reasoning_content；{@code encrypted_content}
		 * 承载网关 命名空间续轮信封（{@code llmgw1:<payload>.<hmac>}），用 {@link ContinuationEnvelope} 验
		 * HMAC + 项目绑定 + 推理文本完整性。缺失/篡改/跨项目/摘要不一致 →
		 * capability_continuation_invalid（400）；校验通过后把令牌 写入
		 * {@link ThinkingPart#continuationToken()}。不伪造供应商签名。
		 */
		private ThinkingPart decodeReasoningItem(JsonNode value, String path) {
			accept(path);
			StringBuilder reasoning = new StringBuilder();
			boolean contentSeen = false;
			String encryptedContent = null;
			for (Map.Entry<String, JsonNode> entry : value.properties()) {
				String key = entry.getKey();
				JsonNode node = entry.getValue();
				String fieldPath = path + "." + key;
				switch (key) {
					case "type" -> {
					}
					case "id", "status" -> ignore(fieldPath);
					case "summary" -> {
						// 网关不伪造摘要（矩阵 §7.4）：只接受空 summary，非摘要文本不可表达。
						if (!node.isArray()) {
							throw invalid(fieldPath, "reasoning summary must be an array");
						}
						if (!node.isEmpty()) {
							throw reject(fieldPath, "上游无推理摘要，summary_text 不可表达");
						}
						ignore(fieldPath);
					}
					case "encrypted_content" -> {
						if (!node.isNull()) {
							encryptedContent = requiredText(node, fieldPath);
						}
						accept(fieldPath);
					}
					case "content" -> {
						contentSeen = true;
						if (!node.isArray()) {
							throw invalid(fieldPath, "reasoning content must be an array");
						}
						for (int j = 0; j < node.size(); j++) {
							String partPath = fieldPath + "[" + j + "]";
							JsonNode part = node.get(j);
							if (!part.isObject()) {
								throw invalid(partPath, "reasoning content part must be a JSON object");
							}
							JsonNode typeNode = part.path("type");
							if (!typeNode.isTextual() || !"reasoning_text".equals(typeNode.asString())) {
								throw reject(partPath, "仅支持 reasoning_text 明文推理块");
							}
							requireKeys(part, partPath, Set.of("type", "text"));
							reasoning.append(requiredText(part.path("text"), partPath + ".text"));
						}
					}
					default -> throw unknown(fieldPath);
				}
			}
			if (!contentSeen || reasoning.isEmpty()) {
				throw new CapabilityException(CapabilityException.CONTINUATION_INVALID, 400, path + ".content",
						"reasoning 条目缺少明文 reasoning_text，无法续轮");
			}
			// 矩阵 §7.3：缺失信封→拒绝；存在则验完整性/项目绑定/推理摘要，失败由信封抛 CONTINUATION_INVALID。
			if (encryptedContent == null) {
				throw new CapabilityException(CapabilityException.CONTINUATION_INVALID, 400,
						path + ".encrypted_content", "reasoning 条目缺少续轮信封 encrypted_content，无法续轮");
			}
			envelope.verify(encryptedContent, projectId, reasoning.toString());
			return new ThinkingPart(reasoning.toString(), encryptedContent);
		}

		private ToolCallPart decodeFunctionCallItem(JsonNode value, String path) {
			accept(path);
			String callId = null;
			String name = null;
			String arguments = null;
			for (Map.Entry<String, JsonNode> entry : value.properties()) {
				String key = entry.getKey();
				JsonNode node = entry.getValue();
				String fieldPath = path + "." + key;
				switch (key) {
					case "type" -> {
					}
					case "id", "status" -> ignore(fieldPath);
					case "call_id" -> callId = requiredText(node, fieldPath);
					case "name" -> name = requiredText(node, fieldPath);
					case "arguments" -> {
						if (!node.isNull()) {
							arguments = requiredText(node, fieldPath);
						}
					}
					default -> throw unknown(fieldPath);
				}
			}
			if (callId == null || callId.isBlank()) {
				throw invalid(path + ".call_id", "function_call requires a non-blank call_id");
			}
			if (name == null || !TOOL_NAME.matcher(name).matches()) {
				throw invalid(path + ".name", "tool name must match [a-zA-Z0-9_-]{1,128}");
			}
			return new ToolCallPart(callId, name, arguments);
		}

		private SemanticMessage decodeFunctionCallOutputItem(JsonNode value, String path) {
			accept(path);
			String callId = null;
			String output = null;
			for (Map.Entry<String, JsonNode> entry : value.properties()) {
				String key = entry.getKey();
				JsonNode node = entry.getValue();
				String fieldPath = path + "." + key;
				switch (key) {
					case "type" -> {
					}
					case "id", "status" -> ignore(fieldPath);
					case "call_id" -> callId = requiredText(node, fieldPath);
					case "output" -> output = decodeToolOutput(node, fieldPath);
					default -> throw unknown(fieldPath);
				}
			}
			if (callId == null || callId.isBlank()) {
				throw invalid(path + ".call_id", "function_call_output requires a non-blank call_id");
			}
			if (output == null) {
				throw invalid(path + ".output", "function_call_output requires output");
			}
			return new SemanticMessage(Role.TOOL, List.of(new ToolResultPart(callId, output, false)));
		}

		/** 工具结果：字符串或 input_text 分段；图片/文件结果经 Chat 上游属待受控验证（矩阵 §4.4），前置拒绝。 */
		private String decodeToolOutput(JsonNode value, String path) {
			if (value.isTextual()) {
				return value.asString();
			}
			if (!value.isArray()) {
				throw invalid(path, "output must be a string or an array of input_text parts");
			}
			StringBuilder text = new StringBuilder();
			for (int j = 0; j < value.size(); j++) {
				String partPath = path + "[" + j + "]";
				JsonNode part = value.get(j);
				if (!part.isObject()) {
					throw invalid(partPath, "output part must be a JSON object");
				}
				JsonNode typeNode = part.path("type");
				if (!typeNode.isTextual() || !"input_text".equals(typeNode.asString())) {
					throw reject(partPath, "工具结果仅支持 input_text");
				}
				requireKeys(part, partPath, Set.of("type", "text"));
				text.append(requiredText(part.path("text"), partPath + ".text"));
			}
			return text.toString();
		}

		private SemanticMessage decodeMessageItem(JsonNode value, String path) {
			JsonNode roleNode = value.path("role");
			if (!roleNode.isTextual()) {
				throw invalid(path + ".role", "message role must be a string");
			}
			Role role = role(roleNode.asString(), path + ".role");
			accept(path + ".role");

			List<ContentPart> content = List.of();
			boolean contentSeen = false;
			for (Map.Entry<String, JsonNode> entry : value.properties()) {
				String key = entry.getKey();
				JsonNode node = entry.getValue();
				String fieldPath = path + "." + key;
				switch (key) {
					case "type", "role" -> {
					}
					// 服务端条目标识/生命周期：SDK 续轮原样回传（矩阵 §7.2），上游无对应语义 → I。
					case "id", "status" -> ignore(fieldPath);
					case "content" -> {
						accept(fieldPath);
						contentSeen = true;
						content = decodeMessageContent(node, role, path);
					}
					default -> throw unknown(fieldPath);
				}
			}
			if (role == Role.USER && (!contentSeen || content.isEmpty())) {
				throw invalid(path + ".content", "user message must carry at least one content part");
			}
			return new SemanticMessage(role, content);
		}

		private Role role(String value, String fieldPath) {
			return switch (value) {
				case "system", "developer" -> Role.SYSTEM;
				case "user" -> Role.USER;
				case "assistant" -> Role.ASSISTANT;
				default -> throw invalid(fieldPath, "unsupported message role: " + value);
			};
		}

		private List<ContentPart> decodeMessageContent(JsonNode value, Role role, String messagePath) {
			String contentPath = messagePath + ".content";
			if (value.isNull()) {
				return List.of();
			}
			if (value.isTextual()) {
				return List.of(new TextPart(value.asString()));
			}
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
					case "input_text" -> {
						requireKeys(part, partPath, Set.of("type", "text"));
						parts.add(new TextPart(requiredText(part.path("text"), partPath + ".text")));
					}
					case "output_text" -> {
						// assistant 续轮条目（矩阵 §7.2 SDK 往返）：原文文本等价映射。
						if (role != Role.ASSISTANT) {
							throw invalid(partPath, "output_text is only allowed in assistant messages");
						}
						requireKeys(part, partPath, Set.of("type", "text", "annotations", "logprobs"));
						JsonNode annotations = part.path("annotations");
						if (!annotations.isMissingNode() && !annotations.isNull()
								&& (!annotations.isArray() || !annotations.isEmpty())) {
							throw reject(partPath + ".annotations", "上游无文本注解，不可表达");
						}
						parts.add(new TextPart(requiredText(part.path("text"), partPath + ".text")));
					}
					case "input_image" -> {
						// 矩阵 §4.4/§8：图片仅允许在 user 消息，其余角色上游 400 → 前置拒绝。
						if (role != Role.USER) {
							throw invalid(partPath, "images are only allowed in user messages");
						}
						parts.add(decodeImage(part, partPath));
					}
					case "input_file" -> throw reject(partPath, "文件输入属阶段 2/3，第一阶段仅内联图片");
					default -> throw reject(partPath, "不支持的内容块类型: " + type);
				}
			}
			return List.copyOf(parts);
		}

		/**
		 * 矩阵 §5.2：Responses 的 {@code input_image.image_url} 是字符串 data URL（detail 平级）， 与
		 * Chat 的 {@code image_url:{url,detail}} 对象形态不同，不可照搬嵌套。 容量/媒体类型/detail/base64 守卫与
		 * Chat 同值同序：先估算再解码，超限不为超大图片分配缓冲。
		 */
		private ImagePart decodeImage(JsonNode part, String partPath) {
			requireKeys(part, partPath, Set.of("type", "image_url", "detail", "file_id"));
			JsonNode fileId = part.path("file_id");
			if (!fileId.isMissingNode() && !fileId.isNull()) {
				throw reject(partPath + ".file_id", "第一阶段仅支持内联图片，file 引用属阶段 2/3");
			}
			String urlPath = partPath + ".image_url";
			String url = requiredText(part.path("image_url"), urlPath);
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
				throw invalid("input", "at most " + MAX_IMAGES + " images per request");
			}
			long estimated = base64LowerBoundBytes(data);
			if (estimated > MAX_IMAGE_BYTES) {
				throw invalid(urlPath, IMAGE_TOO_LARGE);
			}
			if (imageBytes + estimated > MAX_TOTAL_IMAGE_BYTES) {
				throw invalid("input", IMAGES_TOO_LARGE);
			}
			byte[] decoded;
			try {
				decoded = Base64.getDecoder().decode(data);
			}
			catch (IllegalArgumentException e) {
				throw invalid(urlPath, "invalid base64 image data: " + e.getMessage());
			}
			// 估算只是下界：临界载荷仍以解码后的真实字节数为准（上限含等号）。
			if (decoded.length > MAX_IMAGE_BYTES) {
				throw invalid(urlPath, IMAGE_TOO_LARGE);
			}
			imageBytes += decoded.length;
			if (imageBytes > MAX_TOTAL_IMAGE_BYTES) {
				throw invalid("input", IMAGES_TOO_LARGE);
			}
			String detail = null;
			JsonNode detailNode = part.path("detail");
			if (!detailNode.isMissingNode() && !detailNode.isNull()) {
				detail = requiredText(detailNode, partPath + ".detail");
				if (!IMAGE_DETAILS.contains(detail)) {
					throw invalid(partPath + ".detail",
							"unsupported image detail: " + detail + " (allowed: low, high, auto, original)");
				}
			}
			return new ImagePart(mediaType, data, null, detail);
		}

		private void requireKeys(JsonNode value, String path, Set<String> allowed) {
			for (Map.Entry<String, JsonNode> entry : value.properties()) {
				if (!allowed.contains(entry.getKey())) {
					throw unknown(path + "." + entry.getKey());
				}
			}
		}

	}

	/**
	 * 每请求一个有状态实例（矩阵 §6.3）：单调 sequence_number、条目生命周期闭合、恰一次终态。 共享单例
	 * {@link ResponsesEntryCodec} 不持有任何请求状态。
	 */
	private static final class Encoder implements StreamEncoder {

		private final String model;

		private final String id = "resp_" + UUID.randomUUID();

		private final long createdAt = Instant.now().getEpochSecond();

		/** 每请求上下文：续轮信封铸造须用本请求的 projectId + envelope（codec 无状态，密钥不落字段）。 */
		private final CapabilityContext ctx;

		private long sequence;

		private boolean preambleSent;

		private boolean terminalSeen;

		private boolean failed;

		private boolean doneSent;

		private Usage usage;

		private boolean reasoningOpen;

		private String reasoningId;

		private int reasoningIndex;

		private final StringBuilder reasoningText = new StringBuilder();

		private boolean messageOpen;

		private String messageId;

		private int messageIndex;

		private final StringBuilder messageText = new StringBuilder();

		/** 按语义 index 保序：每个工具调用恰一个 function_call 条目。 */
		private final Map<Integer, ToolState> tools = new LinkedHashMap<>();

		private int openToolIndex = -1;

		private int nextOutputIndex;

		/** 按 output_index 保序（A2）：终态 output[] 必须升序，与条目闭合顺序无关。 */
		private final Map<Integer, ObjectNode> output = new TreeMap<>();

		// Responses 的 usage 恒在终态 response.usage（矩阵 §6.3/§9），与 include_usage 无关，故不存储该开关。
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
					closeReasoning(lines);
					if (!messageOpen) {
						openMessage(lines);
					}
					messageText.append(text.text());
					ObjectNode fields = contentFields(messageId, messageIndex).put("delta", text.text());
					fields.putArray("logprobs");
					lines.add(emit("response.output_text.delta", fields));
					yield lines;
				}
				case ThinkingDelta thinking -> {
					List<String> lines = new ArrayList<>(preamble());
					if (!reasoningOpen) {
						openReasoning(lines);
					}
					reasoningText.append(thinking.reasoning());
					lines.add(emit("response.reasoning_text.delta",
							contentFields(reasoningId, reasoningIndex).put("delta", thinking.reasoning())));
					yield lines;
				}
				case ToolCallDelta call -> {
					List<String> lines = new ArrayList<>(preamble());
					closeReasoning(lines);
					closeMessage(lines);
					ToolState state = tools.get(call.index());
					if (state == null) {
						state = new ToolState();
						tools.put(call.index(), state);
					}
					// A4：已闭合 index 再次到达＝上游非连续增量（§6.1 每 index 连续）：落恰一次 error 终态，
					// 不静默丢弃迟到 arguments（条目已在闭合时快照进 output，迟到增量无法回填）。
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
							// A3：closeTool 可能因缺 id/name 落 error 终态；此后不得再开条目或发成功终态。
							if (terminalSeen) {
								yield lines;
							}
						}
						openTool(lines, state);
						openToolIndex = call.index();
					}
					if (call.arguments() != null) {
						state.arguments.append(call.arguments());
						lines.add(emit("response.function_call_arguments.delta",
								itemFields(state.itemId, state.outputIndex).put("delta", call.arguments())));
					}
					yield lines;
				}
				// usage 只缓冲到终态 response.usage，绝不单独成帧（矩阵 §6.3）。
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
			// Responses 无 [DONE] 终止符：终态事件即结束（矩阵 §6.3）。
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

		/**
		 * 落恰一次 error 终态（failed/terminalSeen）：onError 与上游畸形工具调用（A3/A4）共用同一路径，
		 * 已终态则幂等返回，绝不追加成功终态。
		 */
		private void fail(List<String> lines, String code, String message) {
			if (terminalSeen) {
				return;
			}
			failed = true;
			terminalSeen = true;
			lines.add(emit("error", errorFields(code, message, null)));
		}

		@Override
		public boolean terminalSeen() {
			return terminalSeen;
		}

		// ---------- 事件渲染 ----------

		private String emit(String type, ObjectNode fields) {
			ObjectNode payload = MAPPER.createObjectNode();
			payload.put("type", type);
			payload.setAll(fields);
			payload.put("sequence_number", sequence++);
			return event(type, payload);
		}

		private List<String> preamble() {
			if (preambleSent) {
				return List.of();
			}
			preambleSent = true;
			return List.of(emit("response.created", responseEvent("in_progress", false)),
					emit("response.in_progress", responseEvent("in_progress", false)));
		}

		/** Responses 的 created/in_progress/终态事件都把响应对象嵌在 {@code response} 键下（SDK 实测形状）。 */
		private ObjectNode responseEvent(String status, boolean terminal) {
			return MAPPER.createObjectNode().set("response", responseFields(status, terminal));
		}

		private ObjectNode responseFields(String status, boolean terminal) {
			ObjectNode response = MAPPER.createObjectNode();
			response.put("id", id);
			response.put("object", "response");
			response.put("created_at", createdAt);
			response.put("status", status);
			if (model != null) {
				response.put("model", model);
			}
			ArrayNode items = response.putArray("output");
			if (terminal) {
				output.values().forEach(items::add);
			}
			return response;
		}

		private ObjectNode itemFields(String itemId, int outputIndex) {
			ObjectNode fields = MAPPER.createObjectNode();
			fields.put("item_id", itemId);
			fields.put("output_index", outputIndex);
			return fields;
		}

		private ObjectNode contentFields(String itemId, int outputIndex) {
			return itemFields(itemId, outputIndex).put("content_index", 0);
		}

		// ---------- 条目生命周期 ----------

		private void openReasoning(List<String> lines) {
			// A1：重开条目必须清空缓冲，否则复用上一轮脏文本（done/终态文本会拼接历史增量）。
			reasoningText.setLength(0);
			reasoningId = "rs_" + UUID.randomUUID();
			reasoningIndex = nextOutputIndex++;
			reasoningOpen = true;
			ObjectNode item = MAPPER.createObjectNode();
			item.put("id", reasoningId);
			item.put("type", "reasoning");
			item.putArray("summary");
			item.put("status", "in_progress");
			lines.add(emit("response.output_item.added",
					MAPPER.createObjectNode().put("output_index", reasoningIndex).set("item", item)));
			lines.add(emit("response.content_part.added", contentFields(reasoningId, reasoningIndex).set("part",
					MAPPER.createObjectNode().put("type", "reasoning_text").put("text", ""))));
		}

		private void closeReasoning(List<String> lines) {
			if (!reasoningOpen) {
				return;
			}
			reasoningOpen = false;
			String text = reasoningText.toString();
			lines.add(
					emit("response.reasoning_text.done", contentFields(reasoningId, reasoningIndex).put("text", text)));
			lines.add(emit("response.content_part.done", contentFields(reasoningId, reasoningIndex).set("part",
					MAPPER.createObjectNode().put("type", "reasoning_text").put("text", text))));
			// 矩阵 §7.3：流式终态为 reasoning 条目铸造续轮信封，绑定本条目 id + ctx 项目 + 推理原文。
			ObjectNode item = reasoningItemWithEnvelope(text, reasoningId, ctx);
			lines.add(emit("response.output_item.done",
					MAPPER.createObjectNode().put("output_index", reasoningIndex).set("item", item)));
			output.put(reasoningIndex, item);
		}

		private void openMessage(List<String> lines) {
			// A1：重开条目必须清空缓冲，否则复用上一轮脏文本（done/终态文本会拼接历史增量）。
			messageText.setLength(0);
			messageId = "msg_" + UUID.randomUUID();
			messageIndex = nextOutputIndex++;
			messageOpen = true;
			ObjectNode item = MAPPER.createObjectNode();
			item.put("id", messageId);
			item.put("type", "message");
			item.put("role", "assistant");
			item.put("status", "in_progress");
			item.putArray("content");
			lines.add(emit("response.output_item.added",
					MAPPER.createObjectNode().put("output_index", messageIndex).set("item", item)));
			ObjectNode part = MAPPER.createObjectNode().put("type", "output_text").put("text", "");
			part.putArray("annotations");
			lines.add(emit("response.content_part.added", contentFields(messageId, messageIndex).set("part", part)));
		}

		private void closeMessage(List<String> lines) {
			if (!messageOpen) {
				return;
			}
			messageOpen = false;
			String text = messageText.toString();
			ObjectNode done = contentFields(messageId, messageIndex).put("text", text);
			done.putArray("logprobs");
			lines.add(emit("response.output_text.done", done));
			ObjectNode part = MAPPER.createObjectNode().put("type", "output_text").put("text", text);
			part.putArray("annotations");
			lines.add(emit("response.content_part.done", contentFields(messageId, messageIndex).set("part", part)));
			ObjectNode item = messageItem(List.of(new TextPart(text)));
			item.put("id", messageId);
			lines.add(emit("response.output_item.done",
					MAPPER.createObjectNode().put("output_index", messageIndex).set("item", item)));
			output.put(messageIndex, item);
		}

		private void openTool(List<String> lines, ToolState state) {
			state.itemId = "fc_" + UUID.randomUUID();
			state.outputIndex = nextOutputIndex++;
			ObjectNode item = MAPPER.createObjectNode();
			item.put("id", state.itemId);
			item.put("type", "function_call");
			item.put("call_id", state.callId == null ? "" : state.callId);
			item.put("name", state.name == null ? "" : state.name);
			item.put("arguments", "");
			item.put("status", "in_progress");
			lines.add(emit("response.output_item.added",
					MAPPER.createObjectNode().put("output_index", state.outputIndex).set("item", item)));
		}

		private void closeTool(List<String> lines, int index) {
			ToolState state = tools.get(index);
			if (state == null || state.closed) {
				openToolIndex = -1;
				return;
			}
			openToolIndex = -1;
			// A3：上游工具调用缺 id/name（§4.5 首片必带 id+name）＝畸形/不完整：落恰一次 error 终态，
			// 绝不构造 ToolCallPart 触发 NPE 逃逸成 500，也不伪造空名 completed 条目。
			if (state.callId == null || state.callId.isBlank() || state.name == null || state.name.isBlank()) {
				fail(lines, UpstreamException.UPSTREAM_ERROR,
						"upstream tool call at index " + index + " is missing call_id or name");
				return;
			}
			state.closed = true;
			String arguments = state.arguments.toString();
			lines.add(emit("response.function_call_arguments.done",
					itemFields(state.itemId, state.outputIndex).put("call_id", state.callId)
						.put("name", state.name)
						.put("arguments", arguments)));
			ObjectNode item = functionCallItem(new ToolCallPart(state.callId, state.name, arguments));
			item.put("id", state.itemId);
			lines.add(emit("response.output_item.done",
					MAPPER.createObjectNode().put("output_index", state.outputIndex).set("item", item)));
			output.put(state.outputIndex, item);
		}

		// ---------- 终态 ----------

		private List<String> terminal(StopReason stop) {
			List<String> lines = new ArrayList<>(preamble());
			closeReasoning(lines);
			closeMessage(lines);
			if (openToolIndex >= 0) {
				closeTool(lines, openToolIndex);
				// A3：closeTool 可能因缺 id/name 落 error 终态；此后不得再发
				// completed/incomplete/failed。
				if (terminalSeen) {
					return List.copyOf(lines);
				}
			}
			String type;
			String status;
			String incompleteReason = null;
			switch (stop) {
				case COMPLETED, TOOL_CALL -> {
					type = "response.completed";
					status = "completed";
				}
				case LENGTH -> {
					type = "response.incomplete";
					status = "incomplete";
					incompleteReason = "max_output_tokens";
				}
				case CONTENT_FILTER -> {
					type = "response.incomplete";
					status = "incomplete";
					incompleteReason = "content_filter";
				}
				// 异常/未知终止绝不映射为成功（矩阵 §4.7/§6.3）：流内落 response.failed，不抛裸异常。
				case UNKNOWN -> {
					type = "response.failed";
					status = "failed";
				}
				default -> throw new IllegalStateException("unhandled stop reason: " + stop);
			}
			terminalSeen = true;
			ObjectNode response = responseFields(status, true);
			if (incompleteReason != null) {
				response.putObject("incomplete_details").put("reason", incompleteReason);
			}
			if ("failed".equals(status)) {
				response.set("error", errorFields(UpstreamException.UPSTREAM_ERROR,
						"upstream stream ended with an unexpressible stop reason", null));
			}
			JsonNode usageNode = usageJson(usage);
			if (usageNode != null) {
				response.set("usage", usageNode);
			}
			lines.add(emit(type, MAPPER.createObjectNode().set("response", response)));
			return List.copyOf(lines);
		}

		/** 每个语义工具 index 的累积状态；arguments 跨增量拼装，终态条目给出完整值。 */
		private static final class ToolState {

			private String itemId;

			private int outputIndex;

			private String callId;

			private String name;

			private final StringBuilder arguments = new StringBuilder();

			private boolean closed;

		}

	}

}