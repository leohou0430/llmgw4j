package io.github.leohou0430.llmgw.conversion.gemini;

import io.github.leohou0430.llmgw.conversion.CapabilityContext;
import io.github.leohou0430.llmgw.conversion.CapabilityException;
import io.github.leohou0430.llmgw.conversion.EntryCodec;
import io.github.leohou0430.llmgw.core.protocol.ProtocolKind;
import io.github.leohou0430.llmgw.conversion.Role;
import io.github.leohou0430.llmgw.conversion.SemanticEvent;
import io.github.leohou0430.llmgw.conversion.SemanticMessage;
import io.github.leohou0430.llmgw.conversion.SemanticRequest;
import io.github.leohou0430.llmgw.conversion.SemanticResponse;
import io.github.leohou0430.llmgw.conversion.StreamEncoder;
import io.github.leohou0430.llmgw.conversion.TextDelta;
import io.github.leohou0430.llmgw.conversion.TextPart;
import io.github.leohou0430.llmgw.conversion.ImagePart;
import io.github.leohou0430.llmgw.conversion.ToolCallPart;
import io.github.leohou0430.llmgw.conversion.ToolResultPart;
import io.github.leohou0430.llmgw.conversion.Usage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Task-7 Gemini entry codec（骨架，文本语义闭环）：Gemini 线协议 ↔ 语义合同。 contents[].parts[].text 与
 * systemInstruction 映射为语义消息；候选/用量映射 回 candidates + usageMetadata。工具调用/多模态图片/思考在后续迭代接入（骨架
 * 明确拒绝而非伪造），双模态与工具合同按官方 fixtures 补齐。
 *
 * <p>
 * 安全合同：model 由 URL 路径给出（{@link GeminiPaths}），请求体内 model 字段不被信任。
 */
public final class GeminiEntryCodec implements EntryCodec {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private static final java.util.Set<String> IMAGE_MEDIA_TYPES = java.util.Set.of("image/jpeg", "image/png",
			"image/gif", "image/webp");

	@Override
	public ProtocolKind kind() {
		return ProtocolKind.GEMINI;
	}

	@Override
	public SemanticRequest decode(String rawJson, CapabilityContext ctx) {
		Objects.requireNonNull(ctx, "ctx");
		JsonNode root;
		try {
			root = MAPPER.readTree(rawJson == null || rawJson.isBlank() ? "{}" : rawJson);
		}
		catch (RuntimeException e) {
			throw new CapabilityException("invalid_request", 400, "body", "request body must be valid JSON");
		}
		List<SemanticMessage> messages = new ArrayList<>();
		JsonNode system = root.path("systemInstruction");
		String systemText = textOf(system);
		if (!systemText.isEmpty()) {
			messages.add(new SemanticMessage(Role.SYSTEM, List.of(new TextPart(systemText))));
		}
		JsonNode contents = root.path("contents");
		if (!contents.isArray()) {
			throw new CapabilityException("invalid_request", 400, "contents",
					"gemini request must carry a contents array");
		}
		for (JsonNode content : contents) {
			String role = content.path("role").asText("user");
			Role semanticRole = "model".equals(role) || "assistant".equals(role) ? Role.ASSISTANT : Role.USER;
			StringBuilder text = new StringBuilder();
			List<io.github.leohou0430.llmgw.conversion.ContentPart> partsOut = new ArrayList<>();
			for (JsonNode part : content.path("parts")) {
				if (part.has("text")) {
					text.append(part.path("text").asText());
				}
				else if (part.has("functionCall")) {
					// Gemini 工具调用没有独立 id：id 与 name 同值（Gemini 按 name 关联）。
					JsonNode call = part.path("functionCall");
					String name = call.path("name").asText("");
					if (name.isEmpty()) {
						throw new CapabilityException("invalid_request", 400, "contents.parts.functionCall.name",
								"functionCall carries no name");
					}
					String args = call.path("args").isObject() ? MAPPER.writeValueAsString(call.path("args")) : "{}";
					partsOut.add(new ToolCallPart(name, name, args));
				}
				else if (part.has("functionResponse")) {
					JsonNode response = part.path("functionResponse");
					String toolName = response.path("name").asText("");
					if (toolName.isEmpty()) {
						throw new CapabilityException("invalid_request", 400, "contents.parts.functionResponse.name",
								"functionResponse carries no name");
					}
					JsonNode payload = response.path("response");
					String resultJson = payload.isMissingNode() || payload.isNull() ? ""
							: MAPPER.writeValueAsString(payload);
					partsOut.add(new ToolResultPart(toolName, resultJson, false));
				}
				else if (part.has("inlineData")) {
					// 图片引用（内联 base64）：媒体类型白名单 + 合法 base64 校验。
					JsonNode inline = part.path("inlineData");
					ImagePart image = new ImagePart(inline.path("mimeType").asText(""), inline.path("data").asText(""),
							null, null);
					validateImage(image, "contents.parts.inlineData");
					partsOut.add(image);
				}
				else {
					// 未知载荷显式拒绝：绝不静默丢弃（不伪造语义）。
					throw new CapabilityException("unsupported_part", 400, "contents.parts",
							"unknown gemini part shape (expected text/inlineData/" + "functionCall/functionResponse)");
				}
			}
			// 每个 content 恰一条语义消息：文本与工具部件可共存（文本在前）。
			if (text.length() > 0) {
				partsOut.add(0, new TextPart(text.toString()));
			}
			if (partsOut.isEmpty()) {
				partsOut.add(new TextPart(""));
			}
			messages.add(new SemanticMessage(semanticRole, List.copyOf(partsOut)));
		}
		JsonNode config = root.path("generationConfig");
		List<String> stop = new ArrayList<>();
		JsonNode stops = config.path("stopSequences");
		if (stops.isArray()) {
			for (JsonNode s : stops) {
				stop.add(s.asText());
			}
		}
		return new SemanticRequest("default", // 路径 model 由数据面覆盖；body 里的 model 不被信任
				List.copyOf(messages), List.of(), null, null,
				config.has("maxOutputTokens") && !config.path("maxOutputTokens").isNull()
						? config.path("maxOutputTokens").asInt() : null,
				false, null, stop.isEmpty() ? null : List.copyOf(stop),
				config.hasNonNull("temperature") ? config.path("temperature").asDouble() : null,
				config.hasNonNull("topP") ? config.path("topP").asDouble() : null, null, null, null);
	}

	@Override
	public String encodeResponse(SemanticResponse resp, String model, CapabilityContext ctx) {
		Objects.requireNonNull(resp, "resp");
		Objects.requireNonNull(ctx, "ctx");
		ObjectNode out = MAPPER.createObjectNode();
		ArrayNode candidates = out.putArray("candidates");
		ObjectNode candidate = candidates.addObject();
		candidate.put("index", 0);
		ObjectNode content = candidate.putObject("content");
		content.put("role", "model");
		ArrayNode parts = content.putArray("parts");
		for (SemanticMessage message : resp.messages()) {
			for (var part : message.parts()) {
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
						throw new CapabilityException("invalid_request", 400,
								"candidates.content.parts.functionCall.args",
								"tool call arguments must be a JSON object: " + e.getMessage());
					}
					functionCall.set("args", args);
				}
				else {
					throw new CapabilityException("unsupported_part", 400, "candidates.content.parts",
							"gemini entry cannot express " + part.getClass().getSimpleName());
				}
			}
		}
		if (resp.stop() != null) {
			candidate.put("finishReason", finishReason(resp.stop()));
		}
		ObjectNode meta = out.putObject("usageMetadata");
		if (resp.usage() != null) {
			if (resp.usage().inputTokens() != null) {
				meta.put("promptTokenCount", resp.usage().inputTokens());
			}
			if (resp.usage().outputTokens() != null) {
				meta.put("candidatesTokenCount", resp.usage().outputTokens());
			}
		}
		return MAPPER.writeValueAsString(out);
	}

	@Override
	public StreamEncoder newStreamEncoder(String model, boolean includeUsage, CapabilityContext ctx) {
		Objects.requireNonNull(ctx, "ctx");
		return new GeminiStreamEncoder(includeUsage);
	}

	@Override
	public String errorJson(CapabilityException e) {
		ObjectNode out = MAPPER.createObjectNode();
		ObjectNode error = out.putObject("error");
		error.put("code", 400);
		error.put("message", e.getMessage());
		error.put("status", "INVALID_ARGUMENT");
		return MAPPER.writeValueAsString(out);
	}

	@Override
	public String errorEvent(CapabilityException e) {
		ObjectNode out = MAPPER.createObjectNode();
		ObjectNode error = out.putObject("error");
		error.put("code", 500);
		error.put("message", e.getMessage());
		error.put("status", "INTERNAL");
		return "data: " + MAPPER.writeValueAsString(out);
	}

	private static String finishReason(io.github.leohou0430.llmgw.conversion.StopReason stop) {
		return switch (stop) {
			case COMPLETED -> "STOP";
			case LENGTH -> "MAX_TOKENS";
			case TOOL_CALL -> "STOP"; // 骨架：工具终态在工具合同接入时细化
			case CONTENT_FILTER -> "SAFETY";
			case UNKNOWN -> "STOP";
		};
	}

	/** 图片内联校验（与 DeepSeek 同一合同）：白名单媒体类型 + 标准 base64。 */
	private static void validateImage(ImagePart image, String fieldPath) {
		if (!IMAGE_MEDIA_TYPES.contains(image.mediaType())) {
			throw new CapabilityException("invalid_value", 400, fieldPath + ".mimeType", "unsupported image mimeType: "
					+ image.mediaType() + " (allowed: image/jpeg, image/png, image/gif, image/webp)");
		}
		try {
			java.util.Base64.getDecoder().decode(image.base64Data());
		}
		catch (IllegalArgumentException e) {
			throw new CapabilityException("invalid_value", 400, fieldPath + ".data",
					"invalid base64 image data: " + e.getMessage());
		}
	}

	private static String textOf(JsonNode node) {
		if (node.isMissingNode() || node.isNull()) {
			return "";
		}
		if (node.has("parts")) {
			StringBuilder sb = new StringBuilder();
			for (JsonNode part : node.path("parts")) {
				if (part.has("text")) {
					sb.append(part.path("text").asText());
				}
			}
			return sb.toString();
		}
		return node.asText("");
	}

	/** 骨架流式编码器：文本增量 + 终态 + usage-only 尾帧 + 错误，幂等终态。 */
	private static final class GeminiStreamEncoder implements StreamEncoder {

		private final boolean includeUsage;

		private boolean terminal;

		private Usage pendingUsage;

		GeminiStreamEncoder(boolean includeUsage) {
			this.includeUsage = includeUsage;
		}

		@Override
		public List<String> onEvent(SemanticEvent e) {
			if (terminal) {
				return List.of();
			}
			if (e instanceof TextDelta delta) {
				ObjectNode frame = MAPPER.createObjectNode();
				ObjectNode candidate = frame.putArray("candidates").addObject();
				ObjectNode content = candidate.putObject("content");
				content.put("role", "model");
				content.putArray("parts").addObject().put("text", delta.text());
				return List.of("data: " + MAPPER.writeValueAsString(frame));
			}
			if (e instanceof io.github.leohou0430.llmgw.conversion.UsageEvent usage) {
				pendingUsage = usage.usage();
				return List.of();
			}
			if (e instanceof io.github.leohou0430.llmgw.conversion.ThinkingDelta) {
				throw new CapabilityException("unsupported_part", 400, "stream",
						"gemini thinking streaming is not supported yet");
			}
			if (e instanceof io.github.leohou0430.llmgw.conversion.ToolCallDelta) {
				throw new CapabilityException("unsupported_part", 400, "stream",
						"gemini tool streaming is not supported yet");
			}
			if (e instanceof io.github.leohou0430.llmgw.conversion.TerminalEvent stop) {
				terminal = true;
				ObjectNode frame = MAPPER.createObjectNode();
				ObjectNode candidate = frame.putArray("candidates").addObject();
				candidate.put("finishReason", finishReason(stop.stop()));
				Usage usage = pendingUsage;
				if (usage != null) {
					ObjectNode meta = frame.putObject("usageMetadata");
					if (usage.inputTokens() != null) {
						meta.put("promptTokenCount", usage.inputTokens());
					}
					if (usage.outputTokens() != null) {
						meta.put("candidatesTokenCount", usage.outputTokens());
					}
				}
				List<String> lines = new ArrayList<>();
				lines.add("data: " + MAPPER.writeValueAsString(frame));
				if (!includeUsage) {
					return lines;
				}
				return lines;
			}
			return List.of();
		}

		@Override
		public List<String> onComplete() {
			// Gemini (alt=sse) 不发 [DONE] 哨兵；空行由上层帧间隔处理。
			if (!terminal) {
				throw new IllegalStateException("stream ended without a terminal event");
			}
			return List.of();
		}

		@Override
		public List<String> onError(String code, String message) {
			terminal = true;
			ObjectNode out = MAPPER.createObjectNode();
			ObjectNode error = out.putObject("error");
			error.put("code", 500);
			error.put("message", message);
			error.put("status", "INTERNAL");
			return List.of("data: " + MAPPER.writeValueAsString(out));
		}

		@Override
		public boolean terminalSeen() {
			return terminal;
		}

	}

}
