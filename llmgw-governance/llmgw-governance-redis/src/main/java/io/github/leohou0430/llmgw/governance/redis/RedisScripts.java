package io.github.leohou0430.llmgw.governance.redis;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisNoScriptException;
import java.util.List;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * All multi-step state changes are one Lua script: read-modify-write on a shared counter
 * must be ATOMIC across gateway replicas. A Java-side get-then-incr (or a pipeline
 * without Lua) would reintroduce the very quota race that v5 fixed for the in-memory
 * ledger.
 */
public final class RedisScripts {

	public final Script acquire = script("""
			local capacity = tonumber(ARGV[1])
			local rate = tonumber(ARGV[2])
			-- ARGV[3] is retained for callers of the old script shape; it is not a clock authority.
			local clock = redis.call('TIME')
			local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
			local want = tonumber(ARGV[4])
			local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
			local tokens = tonumber(data[1])
			local ts = tonumber(data[2])
			if tokens == nil then tokens = capacity end
			if ts == nil then ts = now end
			now = math.max(now, ts)
			local elapsed = math.max(0, now - ts)
			tokens = math.min(capacity, tokens + elapsed * rate / 1000.0)
			local allowed = 0
			if tokens >= want then
			  tokens = tokens - want
			  allowed = 1
			end
			redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now, 'capacity', capacity)
			redis.call('EXPIRE', KEYS[1], 3600)
			return allowed
			""");

	public final Script refund = script("""
			local capacity = tonumber(redis.call('HGET', KEYS[1], 'capacity')) or tonumber(ARGV[1])
			local back = tonumber(ARGV[2])
			local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
			if tokens == nil then tokens = capacity end
			redis.call('HSET', KEYS[1], 'tokens', math.min(capacity, tokens + back))
			return 1
			""");

	private static final String LEDGER_TYPES = """
			if KEYS[1] == KEYS[2] then return redis.error_reply('usage data/index key collision') end
			local dataType = redis.call('TYPE', KEYS[1]).ok
			local indexType = redis.call('TYPE', KEYS[2]).ok
			if (dataType ~= 'none' and dataType ~= 'hash') or
			   (indexType ~= 'none' and indexType ~= 'set') then
			  return redis.error_reply('invalid usage data/index type')
			end
			local data = redis.call('HMGET', KEYS[1], 'requests', 'prompt', 'completion', 'errors', 'used', 'reserved')
			for i = 1, 6 do
			  if data[i] then
			    local value = tonumber(data[i])
			    if value == nil or value < 0 or value ~= math.floor(value) then
			      return redis.error_reply('invalid usage counter')
			    end
			  end
			end
			""";

	public final Script reserve = script(LEDGER_TYPES + """
			local want = tonumber(ARGV[1])
			local quota = tonumber(ARGV[2])
			local used = tonumber(data[5] or '0')
			local reserved = tonumber(data[6] or '0')
			if used + reserved + want > quota then return 0 end
			redis.call('HINCRBY', KEYS[1], 'reserved', want)
			redis.call('SADD', KEYS[2], ARGV[3])
			return 1
			""");

	public final Script settle = script(LEDGER_TYPES + """
			local released = tonumber(ARGV[1])
			local success = ARGV[2]
			local prompt = tonumber(ARGV[3])
			local completion = tonumber(ARGV[4])
			local reserved = tonumber(data[6] or '0')
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

	public final Script restore = script("""
			local indexType = redis.call('TYPE', KEYS[1]).ok
			if indexType ~= 'none' and indexType ~= 'set' then return -1 end
			for i = 2, #KEYS do
			  local dataType = redis.call('TYPE', KEYS[i]).ok
			  if dataType ~= 'none' and dataType ~= 'hash' then return -1 end
			  local reserved = tonumber(redis.call('HGET', KEYS[i], 'reserved') or '0')
			  if reserved == nil or reserved < 0 then return -1 end
			  if reserved ~= 0 then return 0 end
			end
			for i = 2, #KEYS do
			  local base = (i - 2) * 6
			  redis.call('HSET', KEYS[i], 'requests', ARGV[base + 2],
			    'prompt', ARGV[base + 3], 'completion', ARGV[base + 4],
			    'errors', ARGV[base + 5], 'used', ARGV[base + 6], 'reserved', '0')
			  redis.call('SADD', KEYS[1], ARGV[base + 1])
			end
			return 1
			""");

	public final Script breakerAllow = script("""
			if redis.call('EXISTS', KEYS[1]) == 1 then
			  redis.call('SET', KEYS[3], '1')
			  return 0
			end
			if redis.call('EXISTS', KEYS[3]) == 1 then
			  if redis.call('SETNX', KEYS[2], '1') == 1 then
			    redis.call('EXPIRE', KEYS[2], 10)
			    return 1
			  end
			  return 0
			end
			return 1
			""");

	public final Script breakerFailure = script("""
			local n = redis.call('INCR', KEYS[1])
			if n >= tonumber(ARGV[1]) or redis.call('EXISTS', KEYS[3]) == 1 then
			  redis.call('SET', KEYS[2], '1', 'PX', ARGV[2])
			  redis.call('SET', KEYS[3], '1')
			  redis.call('DEL', KEYS[4])
			  redis.call('DEL', KEYS[1])
			end
			return n
			""");

	public record Script(String text, String sha1) {
	}

	public Long execute(JedisPooled redis, Script script, List<String> keys, String... arguments) {
		Object result;
		try {
			result = redis.evalsha(script.sha1(), keys, List.of(arguments));
		}
		catch (JedisNoScriptException missing) {
			result = redis.eval(script.text(), keys, List.of(arguments));
		}
		return result == null ? null : ((Number) result).longValue();
	}

	private static Script script(String lua) {
		try {
			String sha1 = HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-1").digest(lua.getBytes(StandardCharsets.UTF_8)));
			return new Script(lua, sha1);
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

}
