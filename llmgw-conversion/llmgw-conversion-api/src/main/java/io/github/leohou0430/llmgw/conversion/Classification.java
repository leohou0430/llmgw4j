package io.github.leohou0430.llmgw.conversion;

/** 映射矩阵 §3 的字段分类：E 等价、I 可忽略、R 默认拒绝、D 需显式授权降级。 */
public enum Classification {

	EQUIVALENT, IGNORABLE, REJECT, DEGRADABLE

}