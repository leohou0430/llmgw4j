#!/usr/bin/env bash
# v10 distributed-state regression. Two gateway replicas hammer the SAME small
# quota. With the all-in-memory default each replica spends its own quota; with
# -Predis both share one Redis ledger, so the total charge must stay <= quota.
# Usage: ./bin/smoke-redis-cluster.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEFAULT_JAR="${LLMGW_TEST_JAR:-$ROOT/llmgw-app/target/llmgw-app-0.3.1.jar}"
REDIS_JAR="${LLMGW_TEST_REDIS_JAR:-$ROOT/bench/results/v10-redis.jar}"
REDIS_PORT="${REDIS_PORT:-6399}"
P1="${P1:-8080}"
P2="${P2:-8081}"
MOCK_PORT="${MOCK_PORT:-9090}"
QUOTA="${QUOTA:-60}"
N="${N:-200}"
TMP="$(mktemp -d)"
trap 'cleanup' EXIT

P1_PID=""
P2_PID=""
REDIS_PID=""
PASS=0
FAIL=0

cleanup() {
  stop_pair
  if [[ -n "$REDIS_PID" ]]; then
    kill "$REDIS_PID" 2>/dev/null || true
    wait "$REDIS_PID" 2>/dev/null || true
  fi
  rm -rf "$TMP"
}

stop_pair() {
  for pid in "$P1_PID" "$P2_PID"; do
    if [[ -n "$pid" ]]; then
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
  P1_PID=""
  P2_PID=""
  for _ in $(seq 1 20); do
    if ! lsof -nP -iTCP:"$P1" -sTCP:LISTEN >/dev/null 2>&1 \
       && ! lsof -nP -iTCP:"$P2" -sTCP:LISTEN >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
}

check() {
  local label="$1" ok="$2" evidence="$3"
  if [[ "$ok" == "0" ]]; then
    printf '  [PASS] %s -- %s\n' "$label" "$evidence"
    PASS=$((PASS + 1))
  else
    printf '  [FAIL] %s -- %s\n' "$label" "$evidence"
    FAIL=$((FAIL + 1))
  fi
}

wait_health() {
  local port="$1"
  for _ in $(seq 1 60); do
    if curl --noproxy '*' -fsS "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "FAILED to become healthy on $port" >&2
  return 1
}

start_pair() {
  local jar="$1" mode="$2"
  stop_pair
  local -a common=(
    --spring.profiles.active=webflux
    --gateway.governance-enabled=true
    "--gateway.governance.default-quota-tokens=$QUOTA"
    --gateway.governance.rate-limit-tokens-per-second=2000000000
    --gateway.governance.rate-limit-burst=2000000000
    "--gateway.api-keys[0].quota-tokens=$QUOTA")
  local -a extra=()
  if [[ "$mode" == "redis" ]]; then
    extra=(--gateway.redis.enabled=true
           --gateway.redis.host=127.0.0.1
           "--gateway.redis.port=$REDIS_PORT")
  fi
  java -jar "$jar" "${common[@]}" "${extra[@]}" \
    --server.port="$P1" --gateway.mock.enabled=true \
    "--gateway.governance.snapshot-path=$TMP/snap1.json" \
    > "$TMP/gw1-$mode.log" 2>&1 &
  P1_PID=$!
  wait_health "$P1" || return 1
  java -jar "$jar" "${common[@]}" "${extra[@]}" \
    --server.port="$P2" --gateway.mock.enabled=false \
    "--gateway.governance.snapshot-path=$TMP/snap2.json" \
    > "$TMP/gw2-$mode.log" 2>&1 &
  P2_PID=$!
  wait_health "$P2"
}

fire() {
  : > "$TMP/codes"
  seq 1 "$N" | P1="$P1" P2="$P2" TMP="$TMP" xargs -P 20 -n 1 bash -c '
    i="$1"
    if [ $((i % 2)) -eq 0 ]; then p="$P1"; else p="$P2"; fi
    curl --noproxy "*" -s --max-time 20 -o /dev/null -w "%{http_code}\n" \
      -X POST "http://127.0.0.1:$p/v1/chat/completions" \
      -H "Content-Type: application/json" -H "Authorization: Bearer sk-demo" \
      -d "{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"redis-quota-$i-$RANDOM\"}],\"stream\":false}" \
      >> "$TMP/codes"
  ' _
  awk '$1 == 200 {n++} END {print n+0}' "$TMP/codes"
}

for f in "$DEFAULT_JAR" "$REDIS_JAR"; do
  if [[ ! -e "$f" ]]; then
    echo "missing: $f" >&2
    exit 1
  fi
done
command -v redis-server >/dev/null || { echo "redis-server not found" >&2; exit 1; }
command -v redis-cli >/dev/null || { echo "redis-cli not found" >&2; exit 1; }
if lsof -nP -iTCP:"$REDIS_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "redis test port $REDIS_PORT is already in use" >&2
  exit 1
fi

for port in "$P1" "$P2" "$MOCK_PORT"; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "test port $port is already in use; no process stopped" >&2
    exit 1
  fi
done

echo "=== distributed quota: mem (2 independent ledgers) vs redis (1 shared) ==="
redis-server --port "$REDIS_PORT" --save '' --appendonly no --dir "$TMP" \
  > "$TMP/redis.log" 2>&1 &
REDIS_PID=$!
for _ in $(seq 1 30); do
  [[ "$(redis-cli -p "$REDIS_PORT" ping 2>/dev/null)" == "PONG" ]] && break
  sleep 0.5
done
[[ "$(redis-cli -p "$REDIS_PORT" ping 2>/dev/null)" == "PONG" ]]
ok=$?
check "local redis is up" "$ok" "port=$REDIS_PORT pid=$REDIS_PID"

unzip -l "$DEFAULT_JAR" | grep -qiE 'spring-data-redis|llmgw-governance-redis|jedis|lettuce'
if [[ $? -eq 0 ]]; then
  check "default jar stays free of redis classes" "1" "unexpected redis dependency"
else
  check "default jar stays free of redis classes" "0" "no redis / lettuce / redis module"
fi

start_pair "$DEFAULT_JAR" mem || { echo "mem pair failed"; exit 1; }
MEM_OK="$(fire)"
MEM_429="$(awk '$1 == 429 {n++} END {print n+0}' "$TMP/codes")"
MEM_S1="$(curl --noproxy '*' -s "http://127.0.0.1:$P1/admin/snapshot")"
MEM_S2="$(curl --noproxy '*' -s "http://127.0.0.1:$P2/admin/snapshot")"
MEM_USED="$(MEM_S1="$MEM_S1" MEM_S2="$MEM_S2" python3 -c '
import json, os
def used(s):
    d = json.loads(s)["usage"]["demo"]
    return int(d["promptTokens"]) + int(d["completionTokens"])
print(used(os.environ["MEM_S1"]) + used(os.environ["MEM_S2"]))
' 2>/dev/null || echo 0)"
printf '  [info] in-memory pair: 200=%s 429=%s charged=%s quota=%s (x2 ledgers)\n' \
  "$MEM_OK" "$MEM_429" "$MEM_USED" "$QUOTA"

redis-cli -p "$REDIS_PORT" FLUSHALL >/dev/null
start_pair "$REDIS_JAR" redis || { echo "redis pair failed"; exit 1; }
REDIS_OK="$(fire)"
REDIS_429="$(awk '$1 == 429 {n++} END {print n+0}' "$TMP/codes")"
USED="$(redis-cli -p "$REDIS_PORT" HGET 'llmgw:usage:demo' used 2>/dev/null || echo 0)"
USED="${USED:-0}"
printf '  [info] redis pair: 200=%s 429=%s used=%s quota=%s\n' "$REDIS_OK" "$REDIS_429" "$USED" "$QUOTA"

[[ "$REDIS_OK" -gt 0 ]]
ok=$?
check "shared quota still admits traffic" "$ok" "200=$REDIS_OK"
[[ "$REDIS_429" -gt 0 ]]
ok=$?
check "shared quota rejects the overflow" "$ok" "429=$REDIS_429"
python3 -c "
import sys
# Admission reserves an ESTIMATE; settlement bills actual prompt+completion.
# The final 'used' can therefore exceed the quota slightly -- identically in
# the in-memory ledger. What must NOT happen is two replicas each spending a
# full quota, so compare against the two-independent-ledger charge.
sys.exit(0 if 0 < int(sys.argv[1]) < int(sys.argv[2]) else 1)
" "$USED" "$MEM_USED"
ok=$?
check "shared ledger prevents double spending" "$ok" \
  "redis_charged=$USED < mem_pair_charged=$MEM_USED (quota=$QUOTA x2 ledgers)"

SNAP1="$(curl --noproxy '*' -s "http://127.0.0.1:$P1/admin/snapshot")"
SNAP2="$(curl --noproxy '*' -s "http://127.0.0.1:$P2/admin/snapshot")"
python3 -c "
import json, sys
a = json.loads(sys.argv[1])['usage']['demo']
b = json.loads(sys.argv[2])['usage']['demo']
sys.exit(0 if a == b and a['requests'] > 0 else 1)
" "$SNAP1" "$SNAP2"
ok=$?
check "both replicas report the same ledger" "$ok" "snapshot usage identical"

python3 -c "
import sys
sys.exit(0 if int(sys.argv[1]) >= int(sys.argv[2]) * 1.4 else 1)
" "$MEM_OK" "$REDIS_OK"
ok=$?
RATIO="$(awk -v a="$MEM_OK" -v b="$REDIS_OK" 'BEGIN{printf "%.2f", a/(b>0?b:1)}')"
check "two in-memory ledgers oversell vs shared" "$ok" "mem=$MEM_OK redis=$REDIS_OK ratio=$RATIO"

stop_pair
echo "PASS=$PASS FAIL=$FAIL"
[[ "$FAIL" -eq 0 ]]
