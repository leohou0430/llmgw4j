package io.github.leohou0430.llmgw.engine;

import io.github.leohou0430.llmgw.idempotency.IdempotencyStore;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chapter-9 §9.4.5 / CONTEXT.md「客户幂等键」的数据面闸门。幂等键在**认证之后、
 * 金额预留之前**判读——重复请求不占配额不预留，且不能借幂等键绕过授权。
 *
 * <p>
 * 判读语义（ADR-0007）：FRESH 放行并持一次性推进令牌；EXISTING_*（在途/ 已完成/结果未知）与 MISMATCH 都是重复请求 → 数据面返回 409
 * {@code idempotent_request_exists}（流式不重放 SSE，只附原调用标识）； 结果未知附
 * {@code state=unknown}（不自动重发，交由业务查询/对账）。
 * </p>
 *
 * <p>
 * 终态推进恰好一次：{@link #complete} 以 begin 签发的令牌守卫（内部 AtomicBoolean
 * 幂等），在计费终态处调用——按证据结算/未知挂账/未提交撤回 分别以 known=true/false 收口。
 * </p>
 */
public final class IdempotencyGate {

	/** 防重窗口：CONTEXT.md 约定默认 24h；TTL 只限客户请求防重，不缩短账务保留。 */
	static final long DEFAULT_TTL_MS = 24L * 60 * 60 * 1000;

	private final IdempotencyStore store;

	private final String scopeKey;

	private final String idempotencyKey;

	private final String digest;

	private final String requestId;

	private final IdempotencyStore.BeginResult result;

	private final AtomicBoolean settled = new AtomicBoolean();

	private IdempotencyGate(IdempotencyStore store, String scopeKey, String idempotencyKey, String digest,
			String requestId, IdempotencyStore.BeginResult result) {
		this.store = store;
		this.scopeKey = scopeKey;
		this.idempotencyKey = idempotencyKey;
		this.digest = digest;
		this.requestId = requestId;
		this.result = result;
	}

	/**
	 * @return null 当存储缺席或调用未携带幂等键（无闸门，行为与历史版本一致）； 否则返回闸门——调用方必须检查 {@link #isDuplicate()}。
	 */
	public static IdempotencyGate enter(IdempotencyStore store, String scopeKey, String idempotencyKey, String digest,
			String requestId) {
		if (store == null || idempotencyKey == null || idempotencyKey.isBlank()) {
			return null;
		}
		IdempotencyStore.BeginResult result = store.begin(scopeKey, idempotencyKey, digest, DEFAULT_TTL_MS,
				System.currentTimeMillis());
		return new IdempotencyGate(store, scopeKey, idempotencyKey, digest, requestId, result);
	}

	/** true = 重复请求（在途/已完成/结果未知/摘要冲突），调用方必须 409 终止。 */
	public boolean isDuplicate() {
		return result.status() != IdempotencyStore.Status.FRESH;
	}

	/** 判读结果（供 409 响应携带原调用标识与状态）。 */
	public IdempotencyStore.BeginResult result() {
		return result;
	}

	/** 请求摘要的规范输入：kind|model|upstreamPath|body。 */
	public static String digest(String kind, String model, String upstreamPath, String rawBody) {
		String input = kind + "|" + (model == null ? "" : model) + "|" + (upstreamPath == null ? "" : upstreamPath)
				+ "|" + (rawBody == null ? "" : rawBody);
		try {
			java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
			byte[] bytes = sha.digest(input.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(bytes.length * 2);
			for (byte b : bytes) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		}
		catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 unavailable", impossible);
		}
	}

	/**
	 * 终态推进（恰好一次）：计费已提交但用量未知 → known=false（挂账待对账， 不自动重发）；已按证据结算/缓存命中/未提交撤回 → known=true。
	 */
	public void complete(boolean outcomeKnown, String billingCallId) {
		if (store == null || isDuplicate() || !settled.compareAndSet(false, true)) {
			return;
		}
		store.complete(scopeKey, idempotencyKey, digest, result.beginToken(), billingCallId, requestId, outcomeKnown,
				System.currentTimeMillis());
	}

}
