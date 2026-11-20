package io.github.leohou0430.llmgw.billing;

/**
 * 该模型在当前售价版本下缺少适用价格：按第十九项确认拒绝调用（显式免费也须配置 零售价）。上游成本未知不记为零，由调用方区分处理。
 */
public class MissingPriceException extends RuntimeException {

	public MissingPriceException(String model, long version) {
		super("no customer price for model '" + model + "' at price version " + version
				+ "; call rejected until a price is configured");
	}

}
