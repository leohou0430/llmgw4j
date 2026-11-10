#!/usr/bin/env bash
# v10 AI observability regression: response headers, TTFT/upstream/overhead
# meters and audit enrichment. Kept separate from smoke-stream-errors.sh so the
# established 67/67 (JVM) and 34/34 (native) assertion counts stay intact.
# Usage: ./bin/smoke-observability.sh [webflux|vt]   (default: both)
#        BINARY=/path/to/native-llmgw-webflux ./bin/smoke-observability.sh webflux
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/llmgw-app/target/llmgw-app-0.2.0.jar"
PORT="${PORT:-8080}"
MOCK_PORT="${MOCK_PORT:-9090}"
ENGINES="${1:-webflux vt}"
TMP="$(mktemp -d)"
trap 'cleanup' EXIT

PID=""
PASS=0
FAIL=0

cleanup() {
  if [[ -n "$PID" ]]; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  rm -rf "$TMP"
}

stop() {
  if [[ -n "$PID" ]]; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
    PID=""
  fi
  for _ in $(seq 1 20); do
    if ! lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 \
       && ! lsof -nP -iTCP:"$MOCK_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
}

start() {
  local engine="$1"
  stop
  # BINARY=/path/to/native-llmgw-webflux lets the same suite validate the AOT
  # image, including the engine metric label that the environment post-processor
  # must publish before any bean is created in a native image too.
  local -a cmd
  if [[ -n "${BINARY:-}" ]]; then
    cmd=("$BINARY")
  else
    cmd=(java -jar "$JAR")
  fi
  "${cmd[@]}" --spring.profiles.active="$engine" \
    --gateway.governance-enabled=true --server.port="$PORT" \
    --gateway.governance.default-quota-tokens=2000000000 \
    --gateway.governance.rate-limit-tokens-per-second=2000000000 \
    --gateway.governance.rate-limit-burst=2000000000 \
    '--gateway.api-keys[0].quota-tokens=1000000000000' \
    > "$TMP/gateway-$engine.log" 2>&1 &
  PID=$!
  for _ in $(seq 1 60); do
    if curl --noproxy '*' -fsS "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "FAILED to become healthy ($engine)" >&2
  return 1
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

# Unique body per run so an exact-match cache hit cannot mask the upstream path.
unique() { printf 'obs-%s-%s' "$1" "$$-$RANDOM"; }

fetch() {
  # $1 = json|sse, $2 = unique marker; writes headers to $TMP/h, body to $TMP/b
  local stream=false
  [[ "$1" == "sse" ]] && stream=true
  curl --noproxy '*' -s -D "$TMP/h" -o "$TMP/b" \
    -X POST "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-demo' \
    -H 'X-Request-Id: obs-correlation-1' \
    -d "{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"$2\"}],\"stream\":$stream}"
}

for engine in $ENGINES; do
  echo "=== engine=$engine ==="
  start "$engine" || { FAIL=$((FAIL + 1)); continue; }

  fetch json "$(unique json)"
  hdrs="$(tr -d '\r' < "$TMP/h")"
  printf '%s' "$hdrs" | grep -qi '^X-LLMGW-Overhead-Ms: '
  ok=$?
  check "json overhead header" "$ok" "$(printf '%s' "$hdrs" | grep -i '^X-LLMGW-Overhead-Ms' | head -1)"
  printf '%s' "$hdrs" | grep -qi '^X-LLMGW-Upstream-Ms: '
  ok=$?
  check "json upstream header" "$ok" "$(printf '%s' "$hdrs" | grep -i '^X-LLMGW-Upstream-Ms' | head -1)"
  printf '%s' "$hdrs" | grep -qi '^X-Request-Id: obs-correlation-1'
  ok=$?
  check "correlation id echoed" "$ok" "$(printf '%s' "$hdrs" | grep -i '^X-Request-Id' | head -1)"

  fetch sse "$(unique sse)"
  sse_hdrs="$(tr -d '\r' < "$TMP/h")"
  printf '%s' "$sse_hdrs" | grep -qi '^X-LLMGW-Overhead-Ms: '
  ok=$?
  check "sse preflight overhead header" "$ok" "$(printf '%s' "$sse_hdrs" | grep -i '^X-LLMGW-Overhead-Ms' | head -1)"
  if printf '%s' "$sse_hdrs" | grep -qi '^X-LLMGW-Upstream-Ms: '; then
    check "sse omits post-commit upstream header" "1" "headers must only carry pre-commit values"
  else
    check "sse omits post-commit upstream header" "0" "no upstream header (timing lives in meters)"
  fi
  grep -q 'data: \[DONE\]' "$TMP/b"
  ok=$?
  check "sse still completes" "$ok" "data: [DONE] present"

  metrics="$(curl --noproxy '*' -s "http://127.0.0.1:$PORT/admin/metrics")"
  printf '%s' "$metrics" | grep -q '"llmgw.ttft"'
  ok=$?
  check "ttft timer registered" "$ok" "llmgw.ttft"
  printf '%s' "$metrics" | grep -q '"llmgw.upstream"'
  ok=$?
  check "upstream timer registered" "$ok" "llmgw.upstream"
  printf '%s' "$metrics" | grep -q '"llmgw.overhead"'
  ok=$?
  check "gateway overhead timer registered" "$ok" "llmgw.overhead"
  # Engine identity must be published before beans are created. The old
  # @Configuration-time activation produced engine=unknown on every meter.
  engine_tags="$(printf '%s' "$metrics" | grep -o 'engine=[a-z]*' | sort -u | tr '\n' ',')"
  printf '%s' "$metrics" | grep -q "engine=$engine"
  ok=$?
  check "engine metric tag = $engine" "$ok" "engine tags: ${engine_tags:-none}"
  if printf '%s' "$metrics" | grep -q 'engine=unknown'; then
    check "no unresolved engine tag" "1" "engine=unknown leaked into meters"
  else
    check "no unresolved engine tag" "0" "engine tags: ${engine_tags:-none}"
  fi
  printf '%s' "$metrics" | grep -q 'NaN'
  if [[ $? -eq 0 ]]; then
    check "metrics json has no NaN" "1" "non-finite percentile leaked into JSON"
  else
    check "metrics json has no NaN" "0" "finite values only"
  fi

  snapshot="$(curl --noproxy '*' -s "http://127.0.0.1:$PORT/admin/snapshot")"
  printf '%s' "$snapshot" | grep -q 'ttftMs'
  ok=$?
  check "audit carries ttftMs" "$ok" "ttftMs"
  printf '%s' "$snapshot" | grep -q 'gatewayOverheadMs'
  ok=$?
  check "audit carries gatewayOverheadMs" "$ok" "gatewayOverheadMs"
  printf '%s' "$snapshot" | grep -q 'upstreamMs'
  ok=$?
  check "audit carries upstreamMs" "$ok" "upstreamMs"
  stop
done

echo "PASS=$PASS FAIL=$FAIL"
[[ "$FAIL" -eq 0 ]]
