package io.github.leohou0430.llmgw.governance.redis;

import io.github.leohou0430.llmgw.core.governance.GovernanceUnavailableException;

import java.util.function.Supplier;

/**
 * Chapter-9 §9.2.7：Redis 故障分支必须逐类显式定义，不能用一个「故障时放行」 或「故障时拒绝」的标签覆盖全部分支。
 *
 * <ul>
 * <li>{@code required} —— 配额/账本/认证：任何读、写、超时都失败关闭 （{@link GovernanceUnavailableException}
 * → 503 + Retry-After）。未知 消耗不允许绕过账本。</li>
 * <li>{@code limitsRead} —— 令牌桶准入读：默认同样失败关闭； {@code fail-open-limits}
 * 策略下放行（本次请求不计入桶，文档声明）。</li>
 * <li>{@code bestEffort} —— 可降级面（退款回补、粘性路由、熔断计数）： 失败降级为「未发生」，留痕后继续；降级行为各自文档声明。</li>
 * </ul>
 *
 * <p>
 * 恢复后的对账：Redis 计数是派生视图，SQLite 权威账本不因 Redis 故障 失真；fail-open 放行的请求不产生账本缺口（账本写仍在 Redis 恢复后重试
 * 或由 pending-verify 对账兜底）。
 */
public final class RedisFailurePolicy {

	private final boolean failOpenLimits;

	public RedisFailurePolicy(boolean failOpenLimits) {
		this.failOpenLimits = failOpenLimits;
	}

	public static RedisFailurePolicy failClosed() {
		return new RedisFailurePolicy(false);
	}

	/** 配额/账本/认证路径：失败关闭，无例外。 */
	public <T> T required(String operation, Supplier<T> op) {
		try {
			return op.get();
		}
		catch (RuntimeException e) {
			throw new GovernanceUnavailableException("redis", operation);
		}
	}

	public void requiredRun(String operation, Runnable op) {
		required(operation, () -> {
			op.run();
			return null;
		});
	}

	/**
	 * 令牌桶准入读：fail-closed（默认）与 {@link #required} 一致； fail-open-limits 下放行并返回
	 * {@code openValue}。
	 */
	public boolean limitsRead(String operation, Supplier<Boolean> op, boolean openValue) {
		try {
			return op.get();
		}
		catch (RuntimeException e) {
			if (failOpenLimits) {
				return openValue;
			}
			throw new GovernanceUnavailableException("redis", operation);
		}
	}

	/** 可降级面：失败降级为返回 {@code fallback}，不阻断主流程。 */
	public <T> T bestEffort(Supplier<T> op, T fallback) {
		try {
			return op.get();
		}
		catch (RuntimeException e) {
			return fallback;
		}
	}

	public void bestEffortRun(Runnable op) {
		bestEffort(() -> {
			op.run();
			return null;
		}, null);
	}

}
