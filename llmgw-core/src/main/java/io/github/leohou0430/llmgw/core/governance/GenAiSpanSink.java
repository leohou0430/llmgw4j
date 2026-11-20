package io.github.leohou0430.llmgw.core.governance;

/**
 * Chapter-9 §9.6.3：生成式 AI 语义约定（{@code gen_ai.*}）观测出口。治理 finish 侧车在结算点把模型与用量事实交给装配层桥接到当前
 * span；属性集 固定为本次文档记录（2026-11-20）的官方 gen-ai 属性名——该约定整体处于 Development
 * 状态，不做「已全部稳定」的声明，属性集变更需显式回归。
 */
public interface GenAiSpanSink {

	/** 一次调用的事实快照（来源区分 reported/estimated/unknown，永不记零伪装）。 */
	record GenAiSpan(String model, long inputTokens, long outputTokens, String usageSource, boolean stream,
			boolean success) {
	}

	void accept(GenAiSpan span);

}
