package io.github.leohou0430.llmgw.app;

import io.github.leohou0430.llmgw.core.governance.GenAiSpanSink;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Chapter-9 §9.6.3：把治理 finish 侧车的模型/用量事实桥接到**当前请求线程** 的 server observation 上（gen_ai.* 属性随
 * span 导出，仅 {@code -Potel} profile 生效）。属性集固定为官方 gen-ai 属性名（约定整体处于 Development 状态， 详见
 * {@link GenAiSpanSink} javadoc）：
 *
 * <ul>
 * <li>{@code gen_ai.request.model} —— 准入模型名</li>
 * <li>{@code gen_ai.usage.input_tokens} / {@code gen_ai.usage.output_tokens} ——
 * 权威或估算用量（来源见 {@code gen_ai.usage.source}，永不把缺失记为零）</li>
 * <li>{@code gen_ai.usage.source}（本网关扩展）—— reported | estimated | unknown</li>
 * </ul>
 *
 * <p>
 * 边界：WebFlux 的 finish 侧车可能运行在非请求线程——此时无「当前
 * observation」，属性降级为指标维度（llmgw.usage.source），并在文档声明该 未覆盖范围；VT（请求线程内 finish）完整覆盖。
 * </p>
 */
final class GenAiSpanBridge implements GenAiSpanSink {

	private final ObservationRegistry registry;

	GenAiSpanBridge(ObservationRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void accept(GenAiSpan span) {
		Observation current = registry.getCurrentObservation();
		if (current == null) {
			return;
		}
		Observation.Context context = current.getContext();
		context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.request.model", span.model()));
		context
			.addHighCardinalityKeyValue(KeyValue.of("gen_ai.usage.input_tokens", String.valueOf(span.inputTokens())));
		context
			.addHighCardinalityKeyValue(KeyValue.of("gen_ai.usage.output_tokens", String.valueOf(span.outputTokens())));
		context.addLowCardinalityKeyValue(KeyValue.of("gen_ai.usage.source", span.usageSource()));
	}

}
