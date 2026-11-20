package io.github.leohou0430.llmgw.conversion;

import java.util.List;
import java.util.Objects;

/**
 * @param toolChoice
 * 编码约定：{@code none}/{@code auto}/{@code required}/{@code tool:<工具名>}（具名）；null=未指定。
 * @param includeUsage 调用方流式 usage 偏好（Chat {@code stream_options.include_usage}，矩阵 §5.1 分类
 * E／§6.2）： {@code TRUE}=终态后补独立 usage-only chunk，{@code FALSE}=不补，null=未指定（不请求）。
 * 入口解码必须原样承载，上游注入策略与本字段无关。
 */
public record SemanticRequest(String model, List<SemanticMessage> messages, List<ToolSpec> tools, String toolChoice,
		Boolean thinkingEnabled, Integer maxOutputTokens, boolean stream, String thinkingEffort, List<String> stop,
		Double temperature, Double topP, String responseFormat, String userId, Boolean includeUsage) {

	public SemanticRequest {
		Objects.requireNonNull(model, "model");
		Objects.requireNonNull(messages, "messages");
		messages = List.copyOf(messages);
		tools = tools == null ? List.of() : List.copyOf(tools);
		stop = stop == null ? List.of() : List.copyOf(stop);
	}
}