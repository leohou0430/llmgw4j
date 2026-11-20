package io.github.leohou0430.llmgw.governance.redis;

import io.github.leohou0430.llmgw.core.governance.UsageLedger;
import io.github.leohou0430.llmgw.governance.GovernancePacks;
import io.github.leohou0430.llmgw.governance.QuotaMath;
import io.github.leohou0430.llmgw.governance.UsageSummaries;
import redis.clients.jedis.JedisPooled;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-replica quota with IN-FLIGHT reservations. The admission check (used + reserved +
 * want <= quota) and the reservation increment happen in one Lua script, so N replicas
 * cannot each spend the same last token.
 */
public final class RedisUsageLedger implements UsageLedger {

	private final JedisPooled redis;

	private final RedisScripts scripts;

	private final String prefix;

	/** Chapter-9 §9.2.7: quota/budget paths ALWAYS fail closed — no flag. */
	private final RedisFailurePolicy failurePolicy = RedisFailurePolicy.failClosed();

	public RedisUsageLedger(JedisPooled redis, RedisScripts scripts) {
		this(redis, scripts, GovernancePacks.DEFAULT_REDIS_KEY_PREFIX);
	}

	public RedisUsageLedger(JedisPooled redis, RedisScripts scripts, String prefix) {
		this.redis = redis;
		this.scripts = scripts;
		this.prefix = prefix;
	}

	static void validateKeyId(String keyId) {
		if ("keys".equals(keyId))
			throw new IllegalArgumentException("Redis usage key id is reserved for its internal index: keys");
	}

	private String usageKey(String keyId) {
		validateKeyId(keyId);
		return prefix + "usage:" + keyId;
	}

	private String keysKey() {
		return prefix + "usage:keys";
	}

	@Override
	public void record(UsageRecord record) {
		// Defined branch (Chapter-9 §9.2.7): settlement is a ledger write — a
		// Redis failure here fails closed (503), never silently drops usage.
		failurePolicy.requiredRun("usage settle",
				() -> scripts.execute(redis, scripts.settle, List.of(usageKey(record.keyId()), keysKey()),
						Integer.toString(record.reservedTokens()), record.success() ? "1" : "0",
						Integer.toString(record.promptTokens()), Integer.toString(record.completionTokens()),
						record.keyId()));
	}

	@Override
	public Map<String, UsageSummary> snapshot() {
		Map<String, UsageSummary> out = new LinkedHashMap<>();
		Set<String> ids = redis.smembers(keysKey());
		if (ids == null) {
			return out;
		}
		for (String id : ids) {
			Map<String, String> values = redis.hgetAll(usageKey(id));
			out.put(id, UsageSummaries.of(UsageSummaries.number(values.get("requests")),
					UsageSummaries.number(values.get("prompt")), UsageSummaries.number(values.get("completion")),
					UsageSummaries.number(values.get("errors"))));
		}
		return out;
	}

	@Override
	public long used(String keyId) {
		Map<String, String> values = redis.hgetAll(usageKey(keyId));
		return UsageSummaries.number(values.get("used")) + UsageSummaries.number(values.get("reserved"));
	}

	@Override
	public boolean tryReserve(String keyId, long tokens, long quota) {
		if (quota > UsageSummaries.MAX_EXACT_COUNTER)
			throw new IllegalArgumentException("Redis quota exceeds exact integer range");
		// Defined branch: admission is a ledger write — fail closed.
		Long allowed = failurePolicy.required("quota reserve",
				() -> scripts.execute(redis, scripts.reserve, List.of(usageKey(keyId), keysKey()),
						Long.toString(QuotaMath.normalize(tokens)), Long.toString(quota), keyId));
		return allowed != null && allowed == 1L;
	}

	/**
	 * Replace supplied IDs atomically, preserving unrelated IDs and rejecting active
	 * reservations.
	 */
	@Override
	public void load(Map<String, UsageSummary> data) {
		Map<String, UsageSummary> supplied = Map.copyOf(data);
		UsageSummaries.validateSnapshot(supplied);
		if (supplied.isEmpty())
			return;
		var keys = new java.util.ArrayList<String>();
		keys.add(keysKey());
		var args = new java.util.ArrayList<String>();
		supplied.forEach((id, value) -> {
			keys.add(usageKey(id));
			args.add(id);
			args.add(Long.toString(value.requests()));
			args.add(Long.toString(value.promptTokens()));
			args.add(Long.toString(value.completionTokens()));
			args.add(Long.toString(value.errors()));
			args.add(Long.toString(UsageSummaries.billed(value)));
		});
		Long restored = scripts.execute(redis, scripts.restore, keys, args.toArray(String[]::new));
		if (restored == null || restored != 1L) {
			throw new IllegalStateException(restored != null && restored == 0L
					? "cannot restore usage with active reservations" : "invalid Redis usage state for restore");
		}
	}

}
