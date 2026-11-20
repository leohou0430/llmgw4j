package io.github.leohou0430.llmgw.conversion.openaichat;

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
import java.util.List;
import java.util.Objects;

/**
 * Task-8 generic OpenAI Chat Completions upstream codec（命名 {@code openai-chat}）， 与
 * DeepSeek 专用 codec 分别命名：语义合同 → 通用 messages 载荷与回。usage 只取一次（usage-only chunk
 * 不重复累计）；工具/思考为骨架拒绝，官方 fixtures 合同在工具迭代接入。
 */
public final class ChatUpstreamCodec implements UpstreamCodec {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private static final String CHAT_PATH = "/chat/completions";

	@Override
	public UpstreamPayload encodeRequest(SemanticRequest req, String targetModel) {
		Objects.requireNonNull(req, "req");
		Objects.requireNonNull(targetModel, "targetModel");
		if (targetModel.isBlank()) {
			throw new IllegalArgumentException("targetModel must not be blank");
		}
		ObjectNode root = MAPPER.createObjectNode();
		root.put("model", targetModel);
		if (req.stream()) {
			root.put("stream", true);
		}
		if (req.tools() != null && !req.tools().isEmpty()) {
			encodeTools(root.putArray("tools"), req.tools());
		}
		ArrayNode messages = root.putArray("messages");
		for (SemanticMessage message : req.messages()) {
			ObjectNode node = messages.addObject();
			node.put("role", switch (message.role()) {
				case SYSTEM -> "system";
				case USER -> "user";
				case ASSISTANT -> "assistant";
				case TOOL -> "tool";
			});
			// Chat 工具合同：ASSISTANT 的 tool_calls 与 TOOL 角色的结果消息。
			List<ToolCallPart> calls = new ArrayList<>();
			List<ImagePart> imageParts = new ArrayList<>();
			StringBuilder textBuffer = new StringBuilder();
			for (ContentPart part : message.parts()) {
				if (part instanceof TextPart text) {
					textBuffer.append(text.text());
				}
				else if (part instanceof ToolCallPart call) {
					calls.add(call);
				}
				else if (part instanceof ToolResultPart result) {
					// TOOL 角色消息：结果内容即该消息的 content（Chat 合同）。
					textBuffer.append(result.content() == null ? "" : result.content());
				}
				else if (part instanceof ImagePart image) {
					// 图片仅允许在 user 消息（system/assistant 含图上游 400，前置拒绝）。
					if (message.role() != Role.USER) {
						throw new UpstreamException("images are only allowed in user messages");
					}
					validateImage(image, "messages[" + textBuffer.hashCode() + "].image_url");
					imageParts.add(image);
				}
				else if (part instanceof ThinkingPart) {
					throw new UpstreamException("openai-chat does not accept client-fabricated thinking");
				}
				else {
					throw new UpstreamException("unsupported content part: " + part.getClass().getSimpleName());
				}
			}
			if (message.role() == Role.TOOL) {
				if (textBuffer.length() > 0) {
					node.put("content", textBuffer.toString());
				}
				else {
					node.putNull("content");
				}
				continue;
			}
			ArrayNode content = node.putArray("content");
			if (textBuffer.length() > 0 || (calls.isEmpty() && imageParts.isEmpty())) {
				ObjectNode item = content.addObject();
				item.put("type", "text");
				item.put("text", textBuffer.toString());
			}
			for (ImagePart image : imageParts) {
				ObjectNode item = content.addObject();
				item.put("type", "image_url");
				String dataUrl = "data:" + image.mediaType() + ";base64," + image.base64Data();
				ObjectNode url = item.putObject("image_url");
				url.put("url", dataUrl);
				if (image.detail() != null) {
					url.put("detail", image.detail());
				}
			}
			if (!calls.isEmpty()) {
				ArrayNode toolCalls = node.putArray("tool_calls");
				for (ToolCallPart call : calls) {
					ObjectNode entry = toolCalls.addObject();
					entry.put("id", call.id());
					entry.put("type", "function");
					ObjectNode function = entry.putObject("function");
					function.put("name", call.name());
					function.put("arguments", call.argumentsJson() == null ? "{}" : call.argumentsJson());
				}
			}
		}
		if (req.maxOutputTokens() != null) {
			root.put("max_tokens", req.maxOutputTokens());
		}
		if (req.temperature() != null) {
			root.put("temperature", req.temperature());
		}
		if (req.topP() != null) {
			root.put("top_p", req.topP());
		}
		if (req.stop() != null && !req.stop().isEmpty()) {
			ArrayNode stop = root.putArray("stop");
			req.stop().forEach(stop::add);
		}
		return new UpstreamPayload(MAPPER.writeValueAsString(root), CHAT_PATH);
	}

	@Override
	public SemanticResponse decodeResponse(String upstreamJson) {
		JsonNode root;
		try {
			root = MAPPER.readTree(upstreamJson);
		}
		catch (RuntimeException e) {
			throw new UpstreamException("upstream returned invalid JSON", e);
		}
		if (root.has("error")) {
			throw new UpstreamException("upstream error: " + root.path("error").path("message").asText("unknown"));
		}
		JsonNode choices = root.path("choices");
		if (!choices.isArray() || choices.isEmpty()) {
			throw new UpstreamException("upstream response carries no choices");
		}
		JsonNode choice = choices.get(0);
		JsonNode message = choice.path("message");
		StringBuilder text = new StringBuilder();
		List<ContentPart> semanticParts = new ArrayList<>();
		JsonNode content = message.path("content");
		if (content.isTextual()) {
			text.append(content.asText());
		}
		else if (content.isArray()) {
			for (JsonNode part : content) {
				if ("text".equals(part.path("type").asText())) {
					text.append(part.path("text").asText());
				}
			}
		}
		if (text.length() > 0) {
			semanticParts.add(new TextPart(text.toString()));
		}
		JsonNode toolCalls = message.path("tool_calls");
		if (toolCalls.isArray()) {
			for (JsonNode call : toolCalls) {
				String id = call.path("id").asText("");
				JsonNode function = call.path("function");
				String name = function.path("name").asText("");
				if (name.isEmpty()) {
					throw new UpstreamException("upstream tool_call carries no function name");
				}
				semanticParts.add(new ToolCallPart(id, name, function.path("arguments").asText("{}")));
			}
		}
		List<SemanticMessage> messages = List.of(new SemanticMessage(Role.ASSISTANT, List.copyOf(semanticParts)));
		return new SemanticResponse(root.path("id").asText(null), messages,
				stopOf(choice.path("finish_reason").asText("stop")), usageOf(root));
	}

	@Override
	public StreamDecoder newStreamDecoder() {
		return new ChatStreamDecoder();
	}

	private static final java.util.Set<String> IMAGE_MEDIA_TYPES = java.util.Set.of("image/jpeg", "image/png",
			"image/gif", "image/webp");

	private static final java.util.Set<String> IMAGE_DETAILS = java.util.Set.of("low", "high", "auto", "original");

	/** 图片内联校验（与 DeepSeek 同一合同）：白名单媒体类型 + 标准 base64 + detail 合法。 */
	private static void validateImage(ImagePart image, String fieldPath) {
		if (!IMAGE_MEDIA_TYPES.contains(image.mediaType())) {
			throw new UpstreamException("unsupported image media_type: " + image.mediaType()
					+ " (allowed: image/jpeg, image/png, image/gif, image/webp)");
		}
		try {
			java.util.Base64.getDecoder().decode(image.base64Data());
		}
		catch (IllegalArgumentException e) {
			throw new UpstreamException("invalid base64 image data: " + e.getMessage(), e);
		}
		if (image.detail() != null && !IMAGE_DETAILS.contains(image.detail())) {
			throw new UpstreamException(
					"unsupported image detail: " + image.detail() + " (allowed: low, high, auto, original)");
		}
	}

	/** 语义 ToolSpec → Chat tools[{type:function,function:{...}}]（JSON Schema 透传）。 */
	private static void encodeTools(ArrayNode tools, List<ToolSpec> specs) {
		for (int i = 0; i < specs.size(); i++) {
			ToolSpec spec = specs.get(i);
			ObjectNode entry = tools.addObject().put("type", "function");
			ObjectNode function = entry.putObject("function");
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
					throw new UpstreamException("tools[" + i + "] parameters must be valid JSON: " + e.getMessage(), e);
				}
				if (!parameters.isObject()) {
					throw new UpstreamException("tools[" + i + "] parameters must be an object");
				}
				function.set("parameters", parameters);
			}
		}
	}

	/** usage 只取一次：usage-only chunk（空 choices）补 usage，不重复累计。 */
	private static Usage usageOf(JsonNode root) {
		JsonNode usage = root.path("usage");
		if (!usage.isObject() || usage.size() == 0) {
			return null;
		}
		return new Usage(usage.hasNonNull("prompt_tokens") ? usage.path("prompt_tokens").asLong() : null,
				usage.hasNonNull("completion_tokens") ? usage.path("completion_tokens").asLong() : null,
				usage.hasNonNull("reasoning_tokens") ? usage.path("reasoning_tokens").asLong() : null,
				usage.hasNonNull("cached_tokens") ? usage.path("cached_tokens").asLong() : null);
	}

	private static StopReason stopOf(String finishReason) {
		return switch (finishReason) {
			case "stop" -> StopReason.COMPLETED;
			case "length" -> StopReason.LENGTH;
			case "tool_calls", "function_call" -> StopReason.TOOL_CALL;
			case "content_filter" -> StopReason.CONTENT_FILTER;
			default -> StopReason.UNKNOWN;
		};
	}

	/** 通用 chat chunk 解码：delta.content 增量、finish_reason 终态、usage-only 尾帧。 */
	private static final class ChatStreamDecoder implements StreamDecoder {

		private boolean terminal;

		private boolean failed;

		private String failureReason;

		private Usage usage;

		private boolean usageTaken;

		@Override
		public List<SemanticEvent> onChunk(String chunkJson) {
			if (terminal || failed) {
				return List.of();
			}
			JsonNode root;
			try {
				root = MAPPER.readTree(chunkJson);
			}
			catch (RuntimeException malformed) {
				failed = true;
				failureReason = "invalid chat chunk";
				return List.of();
			}
			if (root.has("error")) {
				failed = true;
				failureReason = root.path("error").path("message").asText("upstream error");
				return List.of();
			}
			List<SemanticEvent> events = new ArrayList<>();
			JsonNode choices = root.path("choices");
			if (choices.isArray() && !choices.isEmpty()) {
				JsonNode choice = choices.get(0);
				JsonNode delta = choice.path("delta");
				String content = delta.path("content").asText("");
				if (!content.isEmpty()) {
					events.add(new TextDelta(content));
				}
				JsonNode toolCalls = delta.path("tool_calls");
				if (toolCalls.isArray()) {
					for (JsonNode call : toolCalls) {
						int index = call.path("index").asInt(0);
						JsonNode function = call.path("function");
						// Chat tool_calls 增量：id/name 与 arguments 分片可分开到达。
						events.add(new ToolCallDelta(index, call.hasNonNull("id") ? call.path("id").asText() : null,
								function.hasNonNull("name") ? function.path("name").asText() : null,
								function.hasNonNull("arguments") ? function.path("arguments").asText() : null));
					}
				}
				String finish = choice.path("finish_reason").asText("");
				if (!finish.isEmpty()) {
					terminal = true;
					if (!usageTaken && usageOf(root) != null) {
						usageTaken = true;
						events.add(new UsageEvent(usageOf(root)));
					}
					events.add(new TerminalEvent(stopOf(finish)));
				}
			}
			// usage-only chunk（include_usage）：空 choices 仅 usage，不重复累计。
			if (!terminal && !usageTaken) {
				Usage meta = usageOf(root);
				if (meta != null && meta.inputTokens() != null) {
					usageTaken = true;
					events.add(new UsageEvent(meta));
				}
			}
			return events;
		}

		@Override
		public boolean terminalSeen() {
			return terminal;
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
