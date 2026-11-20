package io.github.leohou0430.llmgw.billing;

/**
 * 严格预算模式（Chapter-9 §9.2.6，ADR-0009）要求可证明的最大可计费消耗， 而 token 调用缺少请求显式输出上界（max_tokens /
 * max_output_tokens / max_completion_tokens）。此时「最大值并不可靠」，只能拒绝而不能静默回退
 * 到软预算——静默回退会把软预算伪装成严格承诺。调用未发上游即拒绝。
 */
public class StrictBoundRequiredException extends RuntimeException {

	public StrictBoundRequiredException(String model) {
		super("strict budget requires an explicit output bound (max_tokens) for model '" + model
				+ "'; call rejected because the worst-case cost is not provable");
	}

}
