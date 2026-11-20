package io.github.leohou0430.llmgw.idempotency;

/**
 * 客户幂等键存储（Chapter-9 §9.4.5/§9.5.4、CONTEXT.md「客户幂等键」、ADR-0007）。 调用方显式提供
 * {@code Idempotency-Key}，在约定有效期内按 scope 约束相同键 对应相同请求，防止重复执行；相同键对应不同请求时拒绝；结果未知不自动重发。
 *
 * <p>
 * 键解析规则：
 * </p>
 * <ul>
 * <li>{@code scopeKey}：认证主体范围（如 projectId）。跨 scope 不互读—— 相同幂等键在不同 scope 是相互独立的调用；scope
 * 变更（认证主体变化）后 相同键也不能借原标识绕过授权。</li>
 * <li>{@code requestDigest}：请求摘要（方法+路径+模型+参数），由调用方算好 传入；本存储不解析正文，不做语义去重。相同正文但未带幂等键仍视为新调用，
 * {@code X-Request-Id} 不是幂等键。</li>
 * <li>{@code ttlMs}：防重窗口时长；{@code expiresAtEpochMs = nowEpochMs + ttlMs} 在首见时冻结。TTL
 * 只控制客户请求防重窗口，不缩短账务或未决任务的保留。</li>
 * </ul>
 *
 * <p>
 * 防重边界（ADR-0007）：本能力提供防重而非断线续传或原始响应重放——流式重复 提交只返回原调用标识与状态，不重新播放 SSE，不再次执行或收费；在途流仅交给
 * 原连接。不承诺端到端 exactly-once。仍在执行或结果未知、待对账的调用不因 TTL 失去防重保护；有限期限过期的键可再次作为新调用执行和收费。
 * </p>
 */
public interface IdempotencyStore {

	/** begin 判读结果的状态。 */
	enum Status {

		/** 首见：已占位为在途，返回空句柄（无原标识），调用方据此执行新调用。 */
		FRESH,
		/** 同键同摘要且仍在途：不重放 SSE，调用方只返回原标识与状态。 */
		EXISTING_INFLIGHT,
		/** 同键同摘要且已完成（TTL 窗口内）：带原 billingCallId/requestId，可查原结果。 */
		EXISTING_COMPLETED,
		/** 同键同摘要但结果未知（待对账）：不自动重发，交由业务查询/对账。 */
		EXISTING_UNKNOWN,
		/** 同键异摘要：请求内容冲突，调用方以 409 拒绝。 */
		MISMATCH

	}

	/**
	 * begin 判读结果。{@code billingCallId}/{@code requestId} 为已存原标识： FRESH 时为
	 * {@code null}（空句柄）；在途记录在原调用完成前可能尚无标识， 也为 {@code null}；MISMATCH 时返回已有记录的标识（可为
	 * {@code null}）， 供调用方在冲突响应中引用原调用。
	 */
	record BeginResult(Status status, String billingCallId, String requestId, long expiresAtEpochMs,
			String beginToken) {
		/** 观察者判读（非 FRESH）：无推进权，只携带原标识与状态。 */
		public static BeginResult observed(Status status, String billingCallId, String requestId,
				long expiresAtEpochMs) {
			return new BeginResult(status, billingCallId, requestId, expiresAtEpochMs, null);
		}
	}

	/**
	 * 占位判读（单事务 INSERT-or-判读，并发同键只有一个 FRESH，其余读到既有行）。
	 *
	 * <p>
	 * 判读顺序：先惰性清理同 scope 内已过期的完成态行（见 {@link #purgeExpired(long)} 语义），再按 (scopeKey,
	 * idempotencyKey) 读行：
	 * </p>
	 * <ul>
	 * <li>无行 → 写入在途占位并返回 {@link Status#FRESH}（空句柄）。</li>
	 * <li>有行且摘要相同：在途 → {@link Status#EXISTING_INFLIGHT}；已完成且未过 TTL →
	 * {@link Status#EXISTING_COMPLETED}（带原标识）；结果未知 →
	 * {@link Status#EXISTING_UNKNOWN}（不自动重发）。</li>
	 * <li>有行且摘要不同 → {@link Status#MISMATCH}（调用方 409 拒绝）。</li>
	 * <li>已完成且已过 TTL → 视同无行：删除旧行后按新调用占位返回 {@link Status#FRESH}。在途与结果未知的行不受 TTL 影响，永不因此转
	 * FRESH。</li>
	 * </ul>
	 * @param scopeKey 认证主体范围（如 projectId），非空
	 * @param idempotencyKey 客户幂等键，非空
	 * @param requestDigest 请求摘要（调用方算好），非空
	 * @param ttlMs 防重窗口时长（毫秒），必须为正
	 * @param nowEpochMs 当前时间（epoch 毫秒）
	 * @return 判读结果；{@code FRESH} 携带一次性推进令牌 {@code beginToken} （只有原请求可推进终态；TTL
	 * 后被替换的新调用持有新令牌，迟到 的旧回调令牌不匹配即忽略）；其余状态为观察者判读（beginToken 为 null）。键空、摘要空或 ttlMs 非正时抛
	 * IllegalArgumentException
	 */
	BeginResult begin(String scopeKey, String idempotencyKey, String requestDigest, long ttlMs, long nowEpochMs);

	/**
	 * 终态推进：把在途占位推进到确定终态。{@code outcomeKnown=false} 记结果未知
	 * （EXISTING_UNKNOWN：结果未知不失效、不自动重发，跨重启保留待对账）； {@code outcomeKnown=true} 记已完成并保存原
	 * billingCallId/requestId 供重放查询。
	 *
	 * <p>
	 * 幂等合同：重复推进同一终态是 no-op；已处于终态（完成或未知）的记录不再 被任何后续 complete 改写——摘要不匹配的陈旧回调同样忽略，旧终态回调不能
	 * 结算已被 TTL 替换的新调用。幂等键记录不随调用结束删除（TTL 由 begin 冻结）。
	 * </p>
	 * @param scopeKey 认证主体范围，非空
	 * @param idempotencyKey 客户幂等键，非空
	 * @param requestDigest 与 begin 时一致的请求摘要；不匹配则忽略本次推进
	 * @param beginToken begin（FRESH）签发的一次性推进令牌；不匹配（含 TTL 后同键同摘要的新占位）则本次推进是迟到回调， 一律忽略
	 * @param billingCallId 原计费调用标识（终态归档），可为 null
	 * @param requestId 原请求标识（终态归档），可为 null
	 * @param outcomeKnown true=结果已知（完成）；false=结果未知（待对账）
	 * @param nowEpochMs 当前时间（epoch 毫秒）
	 */
	void complete(String scopeKey, String idempotencyKey, String requestDigest, String beginToken, String billingCallId,
			String requestId, boolean outcomeKnown, long nowEpochMs);

	/**
	 * 过期清理：删除已过 TTL 的完成态行；在途与结果未知的行按 ADR-0007 不因 TTL 失去防重保护，不在此清理。实现可以惰性执行（{@link #begin}
	 * 顺带清理同 scope 过期行），本方法供后台批量回收。
	 * @param nowEpochMs 当前时间（epoch 毫秒）
	 * @return 本次删除的行数
	 */
	int purgeExpired(long nowEpochMs);

}
