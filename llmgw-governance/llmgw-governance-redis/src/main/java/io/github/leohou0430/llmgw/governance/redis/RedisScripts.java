package io.github.leohou0430.llmgw.governance.redis;

import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * All multi-step state changes are one Lua script: read-modify-write on a
 * shared counter must be ATOMIC across gateway replicas. A Java-side
 * get-then-incr (or a pipeline without Lua) would reintroduce the very quota
 * race that v5 fixed for the in-memory ledger.
 */
public final class RedisScripts {

    public final DefaultRedisScript<Long> acquire = script("""
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local want = tonumber(ARGV[4])
            local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then tokens = capacity end
            if ts == nil then ts = now end
            local elapsed = math.max(0, now - ts)
            tokens = math.min(capacity, tokens + elapsed * rate / 1000.0)
            local allowed = 0
            if tokens >= want then
              tokens = tokens - want
              allowed = 1
            end
            redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now)
            redis.call('EXPIRE', KEYS[1], 3600)
            return allowed
            """);

    public final DefaultRedisScript<Long> refund = script("""
            local capacity = tonumber(ARGV[1])
            local back = tonumber(ARGV[2])
            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
            if tokens == nil then tokens = capacity end
            redis.call('HSET', KEYS[1], 'tokens', math.min(capacity, tokens + back))
            return 1
            """);

    public final DefaultRedisScript<Long> reserve = script("""
            local want = tonumber(ARGV[1])
            local quota = tonumber(ARGV[2])
            local used = tonumber(redis.call('HGET', KEYS[1], 'used') or '0') or 0
            local reserved = tonumber(redis.call('HGET', KEYS[1], 'reserved') or '0') or 0
            if used + reserved + want > quota then return 0 end
            redis.call('HINCRBY', KEYS[1], 'reserved', want)
            redis.call('SADD', KEYS[2], ARGV[3])
            return 1
            """);

    public final DefaultRedisScript<Long> settle = script("""
            local released = tonumber(ARGV[1])
            local success = ARGV[2]
            local prompt = tonumber(ARGV[3])
            local completion = tonumber(ARGV[4])
            local reserved = tonumber(redis.call('HGET', KEYS[1], 'reserved') or '0') or 0
            redis.call('HSET', KEYS[1], 'reserved', math.max(0, reserved - released))
            redis.call('HINCRBY', KEYS[1], 'requests', 1)
            redis.call('SADD', KEYS[2], ARGV[5])
            if success == '1' then
              redis.call('HINCRBY', KEYS[1], 'prompt', prompt)
              redis.call('HINCRBY', KEYS[1], 'completion', completion)
              redis.call('HINCRBY', KEYS[1], 'used', prompt + completion)
            else
              redis.call('HINCRBY', KEYS[1], 'errors', 1)
            end
            return 1
            """);

    public final DefaultRedisScript<Long> breakerAllow = script("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              if redis.call('SETNX', KEYS[2], '1') == 1 then
                redis.call('EXPIRE', KEYS[2], 10)
                return 1
              end
              return 0
            end
            return 1
            """);

    public final DefaultRedisScript<Long> breakerFailure = script("""
            local n = redis.call('INCR', KEYS[1])
            if n >= tonumber(ARGV[1]) then
              redis.call('SET', KEYS[2], '1', 'PX', ARGV[2])
              redis.call('DEL', KEYS[1])
            end
            return n
            """);

    private static DefaultRedisScript<Long> script(String lua) {
        DefaultRedisScript<Long> value = new DefaultRedisScript<>();
        value.setScriptText(lua);
        value.setResultType(Long.class);
        return value;
    }
}
