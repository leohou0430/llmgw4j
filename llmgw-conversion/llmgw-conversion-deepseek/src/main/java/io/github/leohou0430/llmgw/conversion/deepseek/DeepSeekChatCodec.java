package io.github.leohou0430.llmgw.conversion.deepseek;

import io.github.leohou0430.llmgw.conversion.CapabilityException;
import io.github.leohou0430.llmgw.conversion.ContentPart;
import io.github.leohou0430.llmgw.conversion.ImagePart;
import io.github.leohou0430.llmgw.conversion.Role;
import io.github.leohou0430.llmgw.conversion.SemanticEvent;
import io.github.leohou0430.llmgw.conversion.SemanticMessage;
import io.github.leohou0430.llmgw.conversion.SemanticRequest;
import io.github.leohou0430.llmgw.conversion.SemanticResponse;
import io.github.leohou0430.llmgw.conversion.StopReason;
import io.github.leohou0430.llmgw.conversion.StreamDecoder;
import io.github.leohou0430.llmgw.conversion.TerminalEvent;
import io.github.leohou0430.llmgw.conversion.TextDelta;
import io.github.leohou0430.llmgw.conversion.TextPart;
import io.github.leohou0430.llmgw.conversion.ThinkingDelta;
import io.github.leohou0430.llmgw.conversion.ThinkingPart;
import io.github.leohou0430.llmgw.conversion.ToolCallDelta;
import io.github.leohou0430.llmgw.conversion.ToolCallPart;
import io.github.leohou0430.llmgw.conversion.ToolResultPart;
import io.github.leohou0430.llmgw.conversion.ToolSpec;
import io.github.leohou0430.llmgw.conversion.UpstreamCodec;
import io.github.leohou0430.llmgw.conversion.UpstreamException;
import io.github.leohou0430.llmgw.conversion.UpstreamPayload;
import io.github.leohou0430.llmgw.conversion.Usage;
import io.github.leohou0430.llmgw.conversion.UsageEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** DeepSeek Chat 上游编解码（映射矩阵 §4/§5/§6.1/§9/§10）。无状态共享；流式状态归每请求 Decoder。 */
public final class DeepSeekChatCodec implements UpstreamCodec {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

	/**
	 * 矩阵 §4.4/§8：仅 JPEG/PNG/GIF/WebP；不支持格式/损坏 base64 → capability_invalid_value，不静默去图。
	 */
	private static final Set<String> IMAGE_MEDIA_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

	private static final Set<String> IMAGE_DETAILS = Set.of("low", "high", "auto", "original");

	@Override
	public UpstreamPayload encodeRequest(SemanticRequest req, String targetModel) {
		Objects.requireNonNull(req, "req");
		Objects.requireNonNull(targetModel, "targetModel");
		if (targetModel.isBlank()) {
			throw new IllegalArgumentException("targetModel must not be blank");
		}
		ObjectNode root = MAPPER.createObjectNode();
		root.put("model", targetModel);
		ArrayNode messages = root.putArray("messages");
		List<SemanticMessage> source = req.messages();
		for (int i = 0; i < source.size(); i++) {
			encodeMessage(messages, source.get(i), "messages[" + i + "]");
		}
		if (!req.tools().isEmpty()) {
			encodeTools(root.putArray("tools"), req.tools());
		}
		if (req.toolChoice() != null) {
			encodeToolChoice(root, req.toolChoice());
		}
		if (req.thinkingEnabled() != null) {
			root.putObject("thinking").put("type", req.thinkingEnabled() ? "enabled" : "disabled");
		}
		if (req.thinkingEffort() != null) {
			root.put("reasoning_effort", upstreamEffort(req.thinkingEffort()));
		}
		if (req.maxOutputTokens() != null) {
			root.put("max_tokens", req.maxOutputTokens().intValue());
		}
		// 空 stop/tools 省略字段，不写空数组改变上游默认。
		if (!req.stop().isEmpty()) {
			ArrayNode stop = root.putArray("stop");
			req.stop().forEach(stop::add);
		}
		if (req.temperature() != null) {
			root.put("temperature", req.temperature().doubleValue());
		}
		if (req.topP() != null) {
			root.put("top_p", req.topP().doubleValue());
		}
		if (req.responseFormat() != null) {
			root.putObject("response_format").put("type", upstreamResponseFormat(req.responseFormat()));
		}
		if (req.userId() != null) {
			root.put("user_id", req.userId());
		}
		root.put("stream", req.stream());
		if (req.stream()) {
			// 网关流式固定注入 usage（矩阵 §4.6）；DeepSeek 把 usage 放在最后一个内容 chunk。
			root.putObject("stream_options").put("include_usage", true);
		}
		return new UpstreamPayload(MAPPER.writeValueAsString(root), CHAT_COMPLETIONS_PATH);
	}

	private static void encodeMessage(ArrayNode out, SemanticMessage message, String fieldPath) {
		List<ContentPart> parts = message.parts();
		switch (message.role()) {
			case SYSTEM -> {
				rejectImages(parts, fieldPath);
				ObjectNode node = out.addObject();
				node.put("role", "system");
				String text = joinText(parts);
				node.put("content", text == null ? "" : text);
			}
			case USER -> {
				// 空用户消息无内容可发：前置拒绝，而不是发 content:[] 让上游 400。
				if (parts.isEmpty()) {
					throw new CapabilityException(CapabilityException.INVALID_VALUE, 400, fieldPath,
							"user message must carry at least one content part");
				}
				ObjectNode node = out.addObject();
				node.put("role", "user");
				if (parts.size() == 1 && parts.get(0) instanceof TextPart text) {
					node.put("content", text.text());
				}
				else {
					ArrayNode content = node.putArray("content");
					for (int j = 0; j < parts.size(); j++) {
						encodeUserPart(content, parts.get(j), fieldPath + ".content[" + j + "]");
					}
				}
			}
			case ASSISTANT -> {
				rejectImages(parts, fieldPath);
				ObjectNode node = out.addObject();
				node.put("role", "assistant");
				String text = joinText(parts);
				if (text != null) {
					node.put("content", text);
				}
				StringBuilder reasoning = new StringBuilder();
				ArrayNode toolCalls = null;
				for (ContentPart part : parts) {
					if (part instanceof ThinkingPart thinking) {
						reasoning.append(thinking.reasoning());
					}
					else if (part instanceof ToolCallPart call) {
						if (toolCalls == null) {
							toolCalls = node.putArray("tool_calls");
						}
						ObjectNode callNode = toolCalls.addObject();
						callNode.put("id", call.id());
						callNode.put("type", "function");
						ObjectNode function = callNode.putObject("function");
						function.put("name", call.name());
						function.put("arguments", call.argumentsJson() == null ? "" : call.argumentsJson());
					}
					else if (!(part instanceof TextPart)) {
						throw new CapabilityException(CapabilityException.INVALID_VALUE, 400, fieldPath,
								"assistant message only carries text/thinking/tool_call parts");
					}
				}
				// 续轮信封（continuationToken）是网关命名空间产物，绝不透传上游；只回传明文推理（§4.3）。
				if (!reasoning.isEmpty()) {
					node.put("reasoning_content", reasoning.toString());
				}
			}
			case TOOL -> {
				// 没有 tool result 就没有 tool_call_id：显式拒绝，绝不静默丢弃整条 tool 消息造成配对断裂。
				if (parts.isEmpty()) {
					throw new CapabilityException(CapabilityException.INVALID_VALUE, 400, fieldPath,
							"tool message requires at least one tool result carrying tool_call_id");
				}
				for (ContentPart part : parts) {
					if (!(part instanceof ToolResultPart result)) {
						throw new CapabilityException(CapabilityException.INVALID_VALUE, 400, fieldPath,
								"tool message only carries tool results");
					}
					ObjectNode node = out.addObject();
					node.put("role", "tool");
					node.put("tool_call_id", result.toolCallId());
					// 空/null 结果仍发 content:""，保持 tool_call_id 配对完整。
					node.put("content", result.content() == null ? "" : result.content());
				}
			}
		}
	}

	private static void encodeUserPart(ArrayNode content, ContentPart part, String fieldPath) {
		if (part instanceof TextPart text) {
			content.addObject().put("type", "text").put("text", text.text());
		}
		else if (part instanceof ImagePart image) {
			if (image.mediaType() == null || image.base64Data() == null) {
				throw new CapabilityException(CapabilityException.NOT_SUPPORTED, 400, fieldPath,
						"stage 1 only supports inline base64 images, not file references");
			}
			validateImage(image, fieldPath);
			ObjectNode imageUrl = content.addObject().put("type", "image_url").putObject("image_url");
			imageUrl.put("url", "data:" + image.mediaType() + ";base64," + image.base64Data());
			if (image.detail() != null) {
				imageUrl.put("detail", image.detail());
			}
		}
		else {
			throw new CapabilityException(CapabilityException.INVALID_VALUE, 400, fieldPath,
					"user message only carries text/image parts");
		}
	}

	/** 矩阵 §4.4/§8：media_type 白名单 + base64 必须可解码 + detail 枚举；违规一律 400，不静默透传/去图。 */
	private static void validateImage(ImagePart image, String fieldPath) {
		if (!IMAGE_MEDIA_TYPES.contains(image.mediaType())) {
			throw new CapabilityException(CapabilityException.INVALID_VALUE, 400, fieldPath + ".mediaType",
					"unsupported image media_type: " + image.mediaType()
							+ " (allowed: image/jpeg, image/png, image/gif, image/webp)");
		}
		String data = image.base64Data();
		if (data.isBlank()) {
			throw new CapabilityException(CapabilityException.INVALID_VALUE, 400, fieldPath + ".base64Data",
					"image base64 data must not be empty");
		}
		try {
			// data URL 只接受标准 base64 字母表：空白/换行/URL-safe 变体与损坏填充都属非法值。
			Base64.getDecoder().decode(data);
		}
		catch (IllegalArgumentException e) {
			throw new CapabilityException(CapabilityException.INVALID_VALUE, 400, fieldPath + ".base64Data",
					"invalid base64 image data: " + e.getMessage());
		}
		if (image.detail() != null && !IMAGE_DETAILS.contains(image.detail())) {
			throw new CapabilityException(CapabilityException.INVALID_VALUE, 400, fieldPath + ".detail",
					"unsupported image detail: " + image.detail() + " (allowed: low, high, auto, original)");
		}
	}

	private static void rejectImages(List<ContentPart> parts, String fieldPath) {
		// 矩阵 §4.4/§8：图片仅允许在 user 消息，system/assistant 含图上游 400，网关前置拒绝。
		for (ContentPart part : parts) {
			if (part instanceof ImagePart) {
				throw new CapabilityException(CapabilityException.INVALID_VALUE, 400, fieldPath,
						"images are only allowed in user messages");
			}
		}
	}

	private static String joinText(List<ContentPart> parts) {
		StringBuilder text = new StringBuilder();
		boolean any = false;
		for (ContentPart part : parts) {
			if (part instanceof TextPart textPart) {
				text.append(textPart.text());
				any = true;
			}
		}
		return any ? text.toString() : null;
	}

	private static void encodeTools(ArrayNode tools, List<ToolSpec> specs) {
		for (int i = 0; i < specs.size(); i++) {
			ToolSpec spec = specs.get(i);
			ObjectNode function = tools.addObject().put("type", "function").putObject("function");
			function.put("name", spec.name());
			if (spec.description() != null) {
				function.put("description", spec.description());
			}
			String schema = spec.parametersJsonSchema();
			if (schema != null && !schema.isBlank()) {
				JsonNode parameters;
				try {
					parameters = MAPPER.readTree(schema);
				}
				catch (RuntimeException e) {
					throw new CapabilityException(CapabilityException.INVALID_VALUE, 400,
							"tools[" + i + "].function.parameters", "invalid JSON Schema: " + e.getMessage());
				}
				if (!parameters.isObject()) {
					throw new CapabilityException(CapabilityException.INVALID_VALUE, 400,
							"tools[" + i + "].function.parameters", "JSON Schema must be an object");
				}
				function.set("parameters", parameters);
			}
		}
	}

	private static void encodeToolChoice(ObjectNode root, String toolChoice) {
		switch (toolChoice) {
			case "none", "auto", "required" -> root.put("tool_choice", toolChoice);
			default -> {
				if (toolChoice.startsWith("tool:")) {
					String name = toolChoice.substring("tool:".length());
					if (name.isBlank()) {
						throw new CapabilityException(CapabilityException.INVALID_VALUE, 400, "toolChoice",
								"named tool_choice requires a tool name");
					}
					root.putObject("tool_choice").put("type", "function").putObject("function").put("name", name);
				}
				else {
					throw new CapabilityException(CapabilityException.INVALID_VALUE, 400, "toolChoice",
							"unsupported tool_choice: " + toolChoice);
				}
			}
		}
	}

	private static String upstreamEffort(String effort) {
		// 矩阵 §4.2：上游仅 none/low/high/max；minimal→low、medium/xhigh→high、ultra→max。
		return switch (effort) {
			case "none" -> "none";
			case "minimal", "low" -> "low";
			case "medium", "high", "xhigh" -> "high";
			case "ultra", "max" -> "max";
			default -> throw new CapabilityException(CapabilityException.INVALID_VALUE, 400, "thinkingEffort",
					"unsupported thinking effort: " + effort);
		};
	}

	private static String upstreamResponseFormat(String responseFormat) {
		return switch (responseFormat) {
			case "text", "json_object" -> responseFormat;
			default -> throw new CapabilityException(CapabilityException.INVALID_VALUE, 400, "responseFormat",
					"unsupported response format: " + responseFormat);
		};
	}

	@Override
	public SemanticResponse decodeResponse(String upstreamJson) {
		try {
			return decodeCompletion(upstreamJson);
		}
		catch (UpstreamException e) {
			throw e;
		}
		catch (RuntimeException e) {
			// 不吞栈：未预期的底层异常归一为 upstream_error 并保留 cause。
			throw new UpstreamException(
					"invalid upstream response: " + e.getClass().getSimpleName() + ": " + e.getMessage(), null, e);
		}
	}

	/** 不完整响应（缺 choices/message/finish_reason）按失败处理，绝不返回 stop=null 的"成功"（矩阵 §6/§10.1）。 */
	private static SemanticResponse decodeCompletion(String upstreamJson) {
		JsonNode root = parseObject(upstreamJson, "response");
		JsonNode choices = optionalArray(root, "choices", "choices");
		if (choices == null || choices.isEmpty()) {
			throw new UpstreamException("upstream response has no choices", "choices", null);
		}
		JsonNode choice = elementObject(choices, 0, "choices[0]");
		JsonNode message = requiredObject(choice, "message", "choices[0].message");
		List<ContentPart> parts = new ArrayList<>();
		String reasoning = optionalText(message, "reasoning_content", "choices[0].message.reasoning_content");
		if (reasoning != null) {
			parts.add(new ThinkingPart(reasoning, null));
		}
		String content = optionalText(message, "content", "choices[0].message.content");
		if (content != null) {
			parts.add(new TextPart(content));
		}
		JsonNode toolCalls = optionalArray(message, "tool_calls", "choices[0].message.tool_calls");
		if (toolCalls != null) {
			for (int i = 0; i < toolCalls.size(); i++) {
				String path = "choices[0].message.tool_calls[" + i + "]";
				JsonNode call = elementObject(toolCalls, i, path);
				String id = optionalText(call, "id", path + ".id");
				JsonNode function = optionalObject(call, "function", path + ".function");
				String name = function == null ? null : optionalText(function, "name", path + ".function.name");
				if (id == null || name == null) {
					throw new UpstreamException("upstream tool call missing id/name",
							id == null ? path + ".id" : path + ".function.name", null);
				}
				parts
					.add(new ToolCallPart(id, name, optionalText(function, "arguments", path + ".function.arguments")));
			}
		}
		String finishReason = optionalText(choice, "finish_reason", "choices[0].finish_reason");
		if (finishReason == null) {
			throw new UpstreamException("upstream response has no finish_reason (incomplete response)",
					"choices[0].finish_reason", null);
		}
		if (abnormal(finishReason)) {
			throw new UpstreamException(abnormalMessage(finishReason), "choices[0].finish_reason", null);
		}
		// 合法空文本 + 有效 finish_reason（如纯 tool_calls）仍是成功响应。
		return new SemanticResponse(optionalText(root, "id", "id"), List.of(new SemanticMessage(Role.ASSISTANT, parts)),
				stopReason(finishReason), usageOrNull(root.path("usage")));
	}

	@Override
	public StreamDecoder newStreamDecoder() {
		return new Decoder();
	}

	private static JsonNode parseObject(String json, String what) {
		JsonNode root;
		try {
			root = MAPPER.readTree(json);
		}
		catch (RuntimeException e) {
			throw new UpstreamException("invalid upstream " + what + " JSON: " + e.getMessage(), null, e);
		}
		if (root == null || !root.isObject()) {
			throw new UpstreamException("upstream " + what + " must be a JSON object");
		}
		return root;
	}

	private static boolean abnormal(String finishReason) {
		return "aborted".equals(finishReason) || "insufficient_system_resource".equals(finishReason);
	}

	private static String abnormalMessage(String finishReason) {
		return "upstream terminated abnormally: finish_reason=" + finishReason;
	}

	private static StopReason stopReason(String finishReason) {
		return switch (finishReason) {
			case "stop" -> StopReason.COMPLETED;
			case "length" -> StopReason.LENGTH;
			case "tool_calls" -> StopReason.TOOL_CALL;
			case "content_filter" -> StopReason.CONTENT_FILTER;
			default -> StopReason.UNKNOWN;
		};
	}

	/** 缺失/空对象/全字段未知＝未知（null），绝不默认 0，也不发全 null 的 usage 事件（矩阵 §9）。 */
	private static Usage usageOrNull(JsonNode node) {
		if (node == null || !node.isObject() || node.isEmpty()) {
			return null;
		}
		Long cached = longOrNull(node.path("prompt_tokens_details").path("cached_tokens"));
		if (cached == null) {
			cached = longOrNull(node.path("prompt_cache_hit_tokens"));
		}
		Usage usage = new Usage(longOrNull(node.path("prompt_tokens")), longOrNull(node.path("completion_tokens")),
				longOrNull(node.path("completion_tokens_details").path("reasoning_tokens")), cached);
		boolean unknown = usage.inputTokens() == null && usage.outputTokens() == null && usage.reasoningTokens() == null
				&& usage.cachedInputTokens() == null;
		return unknown ? null : usage;
	}

	private static Long longOrNull(JsonNode node) {
		return node.isNumber() ? node.asLong() : null;
	}

	/** 可选对象字段：缺失/null＝未携带；类型不符＝非法载荷（不静默丢弃语义）。 */
	private static JsonNode optionalObject(JsonNode parent, String field, String fieldPath) {
		JsonNode node = parent.path(field);
		if (node.isMissingNode() || node.isNull()) {
			return null;
		}
		if (!node.isObject()) {
			throw new UpstreamException("upstream " + fieldPath + " must be a JSON object", fieldPath, null);
		}
		return node;
	}

	private static JsonNode optionalArray(JsonNode parent, String field, String fieldPath) {
		JsonNode node = parent.path(field);
		if (node.isMissingNode() || node.isNull()) {
			return null;
		}
		if (!node.isArray()) {
			throw new UpstreamException("upstream " + fieldPath + " must be a JSON array", fieldPath, null);
		}
		return node;
	}

	private static String optionalText(JsonNode parent, String field, String fieldPath) {
		JsonNode node = parent.path(field);
		if (node.isMissingNode() || node.isNull()) {
			return null;
		}
		if (!node.isTextual()) {
			throw new UpstreamException("upstream " + fieldPath + " must be a JSON string", fieldPath, null);
		}
		return node.asText();
	}

	private static JsonNode requiredObject(JsonNode parent, String field, String fieldPath) {
		JsonNode node = optionalObject(parent, field, fieldPath);
		if (node == null) {
			throw new UpstreamException("upstream payload is missing required " + fieldPath, fieldPath, null);
		}
		return node;
	}

	private static JsonNode elementObject(JsonNode array, int index, String fieldPath) {
		JsonNode node = array.get(index);
		if (node == null || !node.isObject()) {
			throw new UpstreamException("upstream " + fieldPath + " must be a JSON object", fieldPath, null);
		}
		return node;
	}

	/** index 必须是非负整数：负数/小数/字符串/超 int 范围都属非法 chunk，绝不静默截断（矩阵 §6.1）。 */
	private static int toolCallIndex(JsonNode call, String fieldPath) {
		JsonNode index = call.path("index");
		if (index.isMissingNode() || index.isNull()) {
			throw new UpstreamException("upstream tool call delta missing index", fieldPath, null);
		}
		if (!index.isIntegralNumber()) {
			throw new UpstreamException("upstream tool call index must be an integer, got: " + index, fieldPath, null);
		}
		if (!index.canConvertToInt()) {
			throw new UpstreamException("upstream tool call index out of int range: " + index, fieldPath, null);
		}
		int value = index.intValue();
		if (value < 0) {
			throw new UpstreamException("upstream tool call index must not be negative: " + value, fieldPath, null);
		}
		return value;
	}

	/** 每请求一个实例；[DONE] 与 SSE 行框架归上层 transcoder，这里只消费 chunk JSON 载荷。 */
	private static final class Decoder implements StreamDecoder {

		private boolean terminalSeen;

		private boolean failed;

		private String failureReason;

		@Override
		public List<SemanticEvent> onChunk(String chunkJson) {
			if (terminalSeen || failed) {
				return List.of();
			}
			try {
				return chunkEvents(chunkJson);
			}
			catch (UpstreamException e) {
				return fail(e.getMessage());
			}
			catch (RuntimeException e) {
				// 非法 chunk 不得让原始 IllegalArgumentException/JsonNodeException 逃逸到
				// transport。
				return fail("invalid upstream chunk: " + e.getClass().getSimpleName() + ": " + e.getMessage());
			}
		}

		/**
		 * 非法 chunk 一律 failed=true + failureReason（upstream_error 语义），丢弃该 chunk 且不发成功终态。
		 */
		private List<SemanticEvent> chunkEvents(String chunkJson) {
			if (chunkJson == null) {
				throw new UpstreamException("upstream chunk must not be null");
			}
			JsonNode root;
			try {
				root = MAPPER.readTree(chunkJson);
			}
			catch (RuntimeException e) {
				throw new UpstreamException("invalid upstream chunk JSON: " + e.getMessage(), null, e);
			}
			if (root == null || !root.isObject()) {
				throw new UpstreamException("upstream chunk must be a JSON object");
			}
			JsonNode choices = optionalArray(root, "choices", "choices");
			JsonNode usageNode = optionalObject(root, "usage", "usage");
			if (choices == null && usageNode == null) {
				throw new UpstreamException("upstream chunk carries neither choices nor usage");
			}
			List<SemanticEvent> events = new ArrayList<>();
			JsonNode choice = choices != null && !choices.isEmpty() ? elementObject(choices, 0, "choices[0]") : null;
			if (choice != null) {
				JsonNode delta = optionalObject(choice, "delta", "choices[0].delta");
				if (delta != null) {
					String reasoning = optionalText(delta, "reasoning_content", "choices[0].delta.reasoning_content");
					if (reasoning != null) {
						events.add(new ThinkingDelta(reasoning));
					}
					String content = optionalText(delta, "content", "choices[0].delta.content");
					if (content != null) {
						events.add(new TextDelta(content));
					}
					JsonNode toolCalls = optionalArray(delta, "tool_calls", "choices[0].delta.tool_calls");
					if (toolCalls != null) {
						for (int i = 0; i < toolCalls.size(); i++) {
							String path = "choices[0].delta.tool_calls[" + i + "]";
							JsonNode call = elementObject(toolCalls, i, path);
							int index = toolCallIndex(call, path + ".index");
							JsonNode function = optionalObject(call, "function", path + ".function");
							events.add(new ToolCallDelta(index, optionalText(call, "id", path + ".id"),
									function == null ? null : optionalText(function, "name", path + ".function.name"),
									function == null ? null
											: optionalText(function, "arguments", path + ".function.arguments")));
						}
					}
				}
			}
			Usage usage = usageOrNull(usageNode);
			if (usage != null) {
				events.add(new UsageEvent(usage));
			}
			if (choice != null) {
				String finishReason = optionalText(choice, "finish_reason", "choices[0].finish_reason");
				if (finishReason != null) {
					terminalSeen = true;
					if (abnormal(finishReason)) {
						// 异常终止保留已解出的部分增量与 usage，但绝不发成功终态。
						failed = true;
						failureReason = abnormalMessage(finishReason);
					}
					else {
						events.add(new TerminalEvent(stopReason(finishReason)));
					}
				}
			}
			return events;
		}

		private List<SemanticEvent> fail(String reason) {
			failed = true;
			failureReason = reason;
			return List.of();
		}

		@Override
		public boolean terminalSeen() {
			return terminalSeen;
		}

		@Override
		public boolean failed() {
			return failed;
		}

		@Override
		public String failureReason() {
			return failureReason;
		}

	}

}