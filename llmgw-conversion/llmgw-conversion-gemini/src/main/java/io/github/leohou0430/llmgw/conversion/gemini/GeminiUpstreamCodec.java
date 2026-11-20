package io.github.leohou0430.llmgw.conversion.gemini;

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
 * Task-8 Gemini 上游编解码：语义合同 ↔ Gemini 线协议（generateContent 形态）。 system →
 * systemInstruction；USER→user、ASSISTANT→model；usage 只取一次 （usage-only
 * 尾帧不重复累计）。工具/图片/思考为骨架拒绝（不伪造语义）， 官方 fixtures 工具合同在后续迭代接入。
 */
public final class GeminiUpstreamCodec implements UpstreamCodec {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private static final String GENERATE_PATH = "/models/%s:generateContent";

	@Override
	public UpstreamPayload encodeRequest(SemanticRequest req, String targetModel) {
		Objects.requireNonNull(req, "req");
		Objects.requireNonNull(targetModel, "targetModel");
		if (targetModel.isBlank()) {
			throw new IllegalArgumentException("targetModel must not be blank");
		}
		ObjectNode root = MAPPER.createObjectNode();
		if (req.tools() != null && !req.tools().isEmpty()) {
			ArrayNode tools = root.putArray("tools");
			ObjectNode group = tools.addObject();
			ArrayNode declarations = group.putArray("functionDeclarations");
			encodeTools(declarations, req.tools());
		}
		ArrayNode contents = root.putArray("contents");
		for (int i = 0; i < req.messages().size(); i++) {
			encodeMessage(contents, req.messages().get(i), i);
		}
		// SYSTEM 消息聚合进 systemInstruction（Gemini 语义），不是 contents。
		StringBuilder system = new StringBuilder();
		for (SemanticMessage message : req.messages()) {
			if (message.role() == Role.SYSTEM) {
				for (ContentPart part : message.parts()) {
					if (part instanceof TextPart text) {
						system.append(text.text());
					}
					else if (part instanceof ThinkingPart) {
						throw new UpstreamException("gemini does not accept client-fabricated thinking");
					}
				}
			}
		}
		if (system.length() > 0) {
			ObjectNode instruction = root.putObject("systemInstruction");
			instruction.putArray("parts").addObject().put("text", system.toString());
		}
		ObjectNode config = root.putObject("generationConfig");
		if (req.maxOutputTokens() != null) {
			config.put("maxOutputTokens", req.maxOutputTokens());
		}
		if (req.temperature() != null) {
			config.put("temperature", req.temperature());
		}
		if (req.topP() != null) {
			config.put("topP", req.topP());
		}
		if (req.stop() != null && !req.stop().isEmpty()) {
			ArrayNode stops = config.putArray("stopSequences");
			req.stop().forEach(stops::add);
		}
		return new UpstreamPayload(MAPPER.writeValueAsString(root), String.format(GENERATE_PATH, targetModel));
	}

	private static void encodeMessage(ArrayNode contents, SemanticMessage message, int index) {
		if (message.role() == Role.SYSTEM) {
			return; // 已聚合进 systemInstruction
		}
		ObjectNode content = contents.addObject();
		content.put("role", message.role() == Role.ASSISTANT ? "model" : "user");
		ArrayNode parts = content.putArray("parts");
		for (ContentPart part : message.parts()) {
			if (part instanceof TextPart text) {
				parts.addObject().put("text", text.text());
			}
			else if (part instanceof ToolCallPart call) {
				ObjectNode functionCall = parts.addObject().putObject("functionCall");
				functionCall.put("name", call.name());
				JsonNode args;
				try {
					args = MAPPER.readTree(call.argumentsJson() == null || call.argumentsJson().isBlank() ? "{}"
							: call.argumentsJson());
				}
				catch (RuntimeException e) {
					throw new UpstreamException("tool call arguments must be a JSON object: " + e.getMessage(), e);
				}
				functionCall.set("args", args);
			}
			else if (part instanceof ToolResultPart result) {
				ObjectNode functionResponse = parts.addObject().putObject("functionResponse");
				// Gemini 按 name 关联：语义 toolCallId 携带它（Gemini 原生闭环 id=name）。
				functionResponse.put("name", result.toolCallId());
				JsonNode payload;
				try {
					payload = MAPPER
						.readTree(result.content() == null || result.content().isBlank() ? "{}" : result.content());
				}
				catch (RuntimeException e) {
					throw new UpstreamException("tool result must be a JSON object: " + e.getMessage(), e);
				}
				functionResponse.set("response", payload);
			}
			else if (part instanceof ImagePart image) {
				// 图片仅允许在 user 消息（system/assistant 含图上游 400，网关前置拒绝）。
				if (message.role() != Role.USER) {
					throw new UpstreamException("images are only allowed in user messages: message[" + index + "]");
				}
				validateImage(image, "contents[" + index + "].inlineData");
				ObjectNode inline = parts.addObject().putObject("inlineData");
				inline.put("mimeType", image.mediaType());
				inline.put("data", image.base64Data());
			}
			else if (part instanceof ThinkingPart) {
				throw new UpstreamException(
						"gemini does not accept client-fabricated thinking: message[" + index + "]");
			}
			else {
				throw new UpstreamException("unsupported content part: " + part.getClass().getSimpleName());
			}
		}
	}

	@Override
	public SemanticResponse decodeResponse(String upstreamJson) {
		JsonNode root;
		try {
			root = MAPPER.readTree(upstreamJson);
		}
		catch (RuntimeException e) {
			throw new UpstreamException("gemini upstream returned invalid JSON", e);
		}
		if (root.has("error")) {
			throw new UpstreamException(
					"gemini upstream error: " + root.path("error").path("message").asText("unknown"));
		}
		JsonNode candidates = root.path("candidates");
		if (!candidates.isArray() || candidates.isEmpty()) {
			// 官方合同：安全拦截/无 candidate 时 Gemini 返回空 candidates + promptFeedback。
			String reason = root.path("promptFeedback").path("blockReason").asText("");
			StopReason stop = reason.isEmpty() ? StopReason.UNKNOWN : StopReason.CONTENT_FILTER;
			return new SemanticResponse(root.path("responseId").asText(null), List.of(), stop, usageOf(root));
		}
		JsonNode candidate = candidates.get(0);
		StringBuilder text = new StringBuilder();
		List<ContentPart> semanticParts = new ArrayList<>();
		JsonNode parts = candidate.path("content").path("parts");
		boolean hasToolCall = false;
		if (parts.isArray()) {
			for (JsonNode part : parts) {
				if (part.has("text")) {
					text.append(part.path("text").asText());
				}
				else if (part.has("functionCall")) {
					hasToolCall = true;
					JsonNode call = part.path("functionCall");
					String name = call.path("name").asText("");
					if (name.isEmpty()) {
						throw new UpstreamException("gemini functionCall carries no name");
					}
					semanticParts.add(new ToolCallPart(name, name,
							call.path("args").isObject() ? MAPPER.writeValueAsString(call.path("args")) : "{}"));
				}
			}
		}
		if (text.length() > 0) {
			semanticParts.add(0, new TextPart(text.toString()));
		}
		List<SemanticMessage> messages = List.of(new SemanticMessage(Role.ASSISTANT, List.copyOf(semanticParts)));
		String finish = candidate.path("finishReason").asText("STOP");
		StopReason stop = hasToolCall && "STOP".equals(finish) ? StopReason.TOOL_CALL : stopOf(finish);
		return new SemanticResponse(root.path("responseId").asText(null), messages, stop, usageOf(root));
	}

	@Override
	public StreamDecoder newStreamDecoder() {
		return new GeminiStreamDecoder();
	}

	/** usageMetadata 只取一次：流式中间帧的 usage 与尾帧不重复累计。 */
	private static Usage usageOf(JsonNode root) {
		JsonNode meta = root.path("usageMetadata");
		if (meta.isMissingNode() || !meta.isObject() || meta.size() == 0) {
			return null;
		}
		return new Usage(meta.hasNonNull("promptTokenCount") ? meta.path("promptTokenCount").asLong() : null,
				meta.hasNonNull("candidatesTokenCount") ? meta.path("candidatesTokenCount").asLong() : null,
				meta.hasNonNull("thoughtsTokenCount") ? meta.path("thoughtsTokenCount").asLong() : null, null);
	}

	private static StopReason stopOf(String finishReason) {
		return switch (finishReason) {
			case "STOP" -> StopReason.COMPLETED;
			case "MAX_TOKENS" -> StopReason.LENGTH;
			case "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> StopReason.CONTENT_FILTER;
			case "RECITATION" -> StopReason.CONTENT_FILTER;
			default -> StopReason.UNKNOWN;
		};
	}

	private static final java.util.Set<String> IMAGE_MEDIA_TYPES = java.util.Set.of("image/jpeg", "image/png",
			"image/gif", "image/webp");

	/** 图片内联校验（与 DeepSeek 同一合同）：白名单媒体类型 + 标准 base64。 */
	private static void validateImage(ImagePart image, String fieldPath) {
		if (!IMAGE_MEDIA_TYPES.contains(image.mediaType())) {
			throw new UpstreamException("unsupported image mimeType: " + image.mediaType()
					+ " (allowed: image/jpeg, image/png, image/gif, image/webp)");
		}
		try {
			java.util.Base64.getDecoder().decode(image.base64Data());
		}
		catch (IllegalArgumentException e) {
			throw new UpstreamException("invalid base64 image data: " + e.getMessage(), e);
		}
	}

	/** 语义 ToolSpec → Gemini functionDeclarations（JSON Schema 透传，须为对象）。 */
	private static void encodeTools(ArrayNode declarations, List<ToolSpec> specs) {
		for (int i = 0; i < specs.size(); i++) {
			ToolSpec spec = specs.get(i);
			ObjectNode function = declarations.addObject();
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

	/** Gemini (alt=sse) chunk 解码：candidates[0] 的 text 增量、finishReason 终态、usage 尾帧。 */
	private static final class GeminiStreamDecoder implements StreamDecoder {

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
				failureReason = "invalid gemini chunk";
				return List.of();
			}
			if (root.has("error")) {
				failed = true;
				failureReason = root.path("error").path("message").asText("upstream error");
				return List.of();
			}
			List<SemanticEvent> events = new ArrayList<>();
			JsonNode candidates = root.path("candidates");
			if (candidates.isArray() && !candidates.isEmpty()) {
				JsonNode candidate = candidates.get(0);
				JsonNode parts = candidate.path("content").path("parts");
				if (parts.isArray()) {
					int callIndex = 0;
					for (JsonNode part : parts) {
						if (part.has("text") && !part.path("text").asText().isEmpty()) {
							events.add(new TextDelta(part.path("text").asText()));
						}
						else if (part.has("functionCall")) {
							JsonNode call = part.path("functionCall");
							String name = call.path("name").asText("");
							if (name.isEmpty()) {
								failed = true;
								failureReason = "gemini stream functionCall carries no name";
								return List.of();
							}
							String args = call.path("args").isObject() ? MAPPER.writeValueAsString(call.path("args"))
									: "{}";
							// Gemini 每个部分携带完整 functionCall（无参数增量）：index 保留顺序。
							events.add(new ToolCallDelta(callIndex++, name, name, args));
						}
					}
				}
				String finish = candidate.path("finishReason").asText("");
				if (!finish.isEmpty()) {
					terminal = true;
					if (!usageTaken && usage(root) != null) {
						usageTaken = true;
						events.add(new UsageEvent(usage(root)));
					}
					events.add(new TerminalEvent(stopOf(finish)));
				}
			}
			// usage-only 尾帧（candidates 已空/缺省，仅 usageMetadata）：只补 usage，不发终态。
			if (!terminal && !usageTaken) {
				Usage meta = usage(root);
				if (meta != null && meta.inputTokens() != null) {
					usageTaken = true;
					events.add(new UsageEvent(meta));
				}
			}
			return events;
		}

		private Usage usage(JsonNode root) {
			Usage meta = usageOf(root);
			if (meta != null) {
				usage = meta;
			}
			return usage;
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
