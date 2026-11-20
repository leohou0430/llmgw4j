package io.github.leohou0430.llmgw.billing;

import java.util.List;
import java.util.Optional;

/**
 * 客户售价版本簿（B2 平台配置，独立于上游成本）。价格版本化：改价不追溯已有 调用——每次调用在准入时冻结 {@link Price}。缺价返回 empty，由调用方按
 * 第十九项确认拒绝（不自动记零）。
 */
public interface PriceBook {

	/** 冻结并返回该模型当前生效的售价版本（版本号递增）。 */
	Price freeze(String model);

	/** 读取某模型某版本的冻结价格。 */
	Optional<Price> priceFor(String model, long version);

	/** 全部冻结版本（最新在前）。 */
	List<Price> all();

}
