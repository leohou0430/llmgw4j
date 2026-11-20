package io.github.leohou0430.llmgw.identity;

/** Control-plane actions checked server-side against the caller's role and scope. */
public enum Action {

	TENANT_MANAGE, SOURCE_MANAGE, MEMBER_GRANT, KEY_MANAGE, ROUTE_PUBLISH, ROUTE_ROLLBACK, CATALOG_READ, CATALOG_WRITE,
	USAGE_READ, AUDIT_READ, FILE_DELETE, TASK_CANCEL, READ_CREDENTIAL, READ_PASSWORD_HASH, BODY_READ, BODY_EXPORT,
	/** 任务6：充值/冲正/对账等账务写，仅平台管理员。 */
	BILLING_MANAGE,
	/** 任务6：账户/预留/账单读，租户与项目角色按真实 scope。 */
	BILLING_READ,
	/** 任务6：客户售价版本发布，仅平台管理员。 */
	PRICE_PUBLISH,
	/** 任务6：记录元数据读（正文走 BODY_READ）。 */
	RECORDING_READ,
	/** Chapter-9 §9.5.4：审批工作流读（待决列表），观察者可读。 */
	APPROVAL_READ,
	/** Chapter-9 §9.5.4：审批决策/撤销（批准 ≠ 放行，执行前仍需重校验）。 */
	APPROVAL_DECIDE

}