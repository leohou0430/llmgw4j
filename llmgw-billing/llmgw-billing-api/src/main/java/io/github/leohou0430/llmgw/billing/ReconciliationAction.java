package io.github.leohou0430.llmgw.billing;

/** 费用待对账项核实动作（第七项确认）：核实后结算或释放，不采用自动释放或按预留直接结算。 */
public enum ReconciliationAction {

	SETTLE, RELEASE

}
