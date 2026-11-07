package io.github.leohou0430.llmgw.governance.redis;

import io.github.leohou0430.llmgw.core.governance.UsageLedger;
import io.github.leohou0430.llmgw.governance.GovernancePacks;
import io.github.leohou0430.llmgw.governance.QuotaMath;
import io.github.leohou0430.llmgw.governance.UsageSummaries;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-replica quota with IN-FLIGHT reservations. The admission check
 * (used + reserved + want <= quota) and the reservation increment happen in
 * one Lua script, so N replicas cannot each spend the same last token.
 */
public final class RedisUsageLedger implements UsageLedger {

    private final StringRedisTemplate redis;
    private final RedisScripts scripts;
    private final String prefix;

    public RedisUsageLedger(StringRedisTemplate redis, RedisScripts scripts) {
        this(redis, scripts, GovernancePacks.DEFAULT_REDIS_KEY_PREFIX);
    }

    public RedisUsageLedger(StringRedisTemplate redis, RedisScripts scripts, String prefix) {
        this.redis = redis;
        this.scripts = scripts;
        this.prefix = prefix;
    }

    private String usageKey(String keyId) {
        return prefix + "usage:" + keyId;
    }

    private String keysKey() {
        return prefix + "usage:keys";
    }

    @Override
    public void record(UsageRecord record) {
        redis.execute(scripts.settle, List.of(usageKey(record.keyId()), keysKey()),
                Integer.toString(record.reservedTokens()),
                record.success() ? "1" : "0",
                Integer.toString(record.promptTokens()),
                Integer.toString(record.completionTokens()),
                record.keyId());
    }

    @Override
    public Map<String, UsageSummary> snapshot() {
        Map<String, UsageSummary> out = new LinkedHashMap<>();
        Set<String> ids = redis.opsForSet().members(keysKey());
        if (ids == null) {
            return out;
        }
        for (String id : ids) {
            Map<Object, Object> values = redis.opsForHash().entries(usageKey(id));
            out.put(id, UsageSummaries.of(UsageSummaries.number(values.get("requests")),
                    UsageSummaries.number(values.get("prompt")), UsageSummaries.number(values.get("completion")),
                    UsageSummaries.number(values.get("errors"))));
        }
        return out;
    }

    @Override
    public long used(String keyId) {
        Map<Object, Object> values = redis.opsForHash().entries(usageKey(keyId));
        return UsageSummaries.number(values.get("used")) + UsageSummaries.number(values.get("reserved"));
    }

    @Override
    public boolean tryReserve(String keyId, long tokens, long quota) {
        Long allowed = redis.execute(scripts.reserve,
                List.of(usageKey(keyId), keysKey()),
                Long.toString(QuotaMath.normalize(tokens)), Long.toString(quota), keyId);
        return allowed != null && allowed == 1L;
    }

    /** REPLACE semantics, matching InMemoryUsageLedger.load(). */
    @Override
    public void load(Map<String, UsageSummary> data) {
        Set<String> ids = redis.opsForSet().members(keysKey());
        if (ids != null) {
            for (String id : ids) {
                redis.delete(usageKey(id));
            }
        }
        redis.delete(keysKey());
        for (Map.Entry<String, UsageSummary> entry : data.entrySet()) {
            UsageSummary summary = entry.getValue();
            String key = usageKey(entry.getKey());
            redis.opsForHash().putAll(key, Map.of(
                    "requests", Long.toString(summary.requests()),
                    "prompt", Long.toString(summary.promptTokens()),
                    "completion", Long.toString(summary.completionTokens()),
                    "errors", Long.toString(summary.errors()),
                    "used", Long.toString(UsageSummaries.billed(summary)),
                    "reserved", "0"));
            redis.opsForSet().add(keysKey(), entry.getKey());
        }
    }

}
