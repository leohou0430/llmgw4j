package io.github.leohou0430.llmgw.conversion;

/**
 * 判定三态：accept 透传、ignore 丢弃、degrade 执行已授权降级。 拒绝路径以 {@link CapabilityException}
 * 表达，不占用返回值，避免"看起来成功"的第四态。
 */
public sealed interface Decision {

	record Accept(String fieldPath) implements Decision {
	}

	record Ignore(String fieldPath) implements Decision {
	}

	record Degrade(String fieldPath, String degradationId, String reason) implements Decision {
	}

}