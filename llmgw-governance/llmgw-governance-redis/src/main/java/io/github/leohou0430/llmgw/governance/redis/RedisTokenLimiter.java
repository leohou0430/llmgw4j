package io.github.leohou0430.llmgw.governance.redis;

import io.github.leohou0430.llmgw.core.config.GatewayProperties;
import io.github.leohou0430.llmgw.core.governance.TokenLimiter;
import io.github.leohou0430.llmgw.governance.GovernanceLimits;
import io.github.leohou0430.llmgw.governance.GovernancePacks;
import redis.clients.jedis.JedisPooled;

import java.util.List;

/** Cross-replica token bucket: the whole refill-and-debit is one Lua script. */
public final class RedisTokenLimiter implements TokenLimiter {

	private final JedisPooled redis;

	private final RedisScripts scripts;

	private final String prefix;

	private final long capacity;

	private final double ratePerSecond;

	/** Chapter-9 §9.2.7: the DEFINED failure branch for bucket reads/writes. */
	private final RedisFailurePolicy failurePolicy;

	public RedisTokenLimiter(JedisPooled redis, RedisScripts scripts, GatewayProperties properties) {
		GovernanceLimits limits = GovernanceLimits.from(properties);
		this(redis, scripts, GovernancePacks.DEFAULT_REDIS_KEY_PREFIX, limits.bucketCapacity(),
				limits.tokensPerSecond(), new RedisFailurePolicy(properties.getRedis().isFailOpenLimits()));
	}

	private RedisTokenLimiter(JedisPooled redis, RedisScripts scripts, String prefix, GovernanceLimits limits) {
		this(redis, scripts, prefix, limits.bucketCapacity(), limits.tokensPerSecond());
	}

	public RedisTokenLimiter(JedisPooled redis, RedisScripts scripts, String prefix, long capacity,
			double ratePerSecond) {
		this(redis, scripts, prefix, capacity, ratePerSecond, RedisFailurePolicy.failClosed());
	}

	private RedisTokenLimiter(JedisPooled redis, RedisScripts scripts, String prefix, long capacity,
			double ratePerSecond, RedisFailurePolicy failurePolicy) {
		this.redis = redis;
		this.scripts = scripts;
		this.prefix = prefix;
		this.capacity = capacity;
		this.ratePerSecond = ratePerSecond;
		this.failurePolicy = failurePolicy;
	}

	private String key(String keyId) {
		return prefix + "bucket:" + keyId;
	}

	@Override
	public boolean tryAcquire(String keyId, int tokens) {
		return acquire(keyId, tokens, capacity, ratePerSecond);
	}

	@Override
	public boolean tryAcquire(String keyId, int tokens, long tokensPerSecond, long burst) {
		return acquire(keyId, tokens, Math.max(1, burst > 0 ? burst : tokensPerSecond), Math.max(0, tokensPerSecond));
	}

	private boolean acquire(String keyId, int tokens, long bucketCapacity, double bucketRate) {
		// Defined branch (Chapter-9 §9.2.7): fail-closed unless the deployment
		// explicitly chose fail-open-limits for this read.
		return failurePolicy.limitsRead("token-bucket acquire", () -> {
			Long allowed = scripts.execute(redis, scripts.acquire, List.of(key(keyId)), Long.toString(bucketCapacity),
					Double.toString(bucketRate), "0", // Legacy clock argument: refill
														// time comes from Redis.
					Integer.toString(Math.max(1, tokens)));
			return allowed != null && allowed == 1L;
		}, true);
	}

	@Override
	public void refund(String keyId, int tokens) {
		if (tokens <= 0) {
			return;
		}
		// Defined branch: a refund loss only leaks bucket capacity until refill;
		// it never mis-authenticates or under-charges the ledger. Best effort.
		failurePolicy.bestEffortRun(() -> scripts.execute(redis, scripts.refund, List.of(key(keyId)),
				Long.toString(capacity), Integer.toString(tokens)));
	}

	@Override
	public long remaining(String keyId) {
		Object value = redis.hget(key(keyId), "tokens");
		if (value == null) {
			return (long) capacity;
		}
		try {
			return (long) Double.parseDouble(value.toString());
		}
		catch (NumberFormatException e) {
			return 0;
		}
	}

}
