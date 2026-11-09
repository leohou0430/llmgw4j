#!/usr/bin/env bash
# v13 hardening regression: same-route retry/backoff (ForwardingPolicy),
# three-tier timeouts (first-byte / idle-read), SSE heartbeat, bidirectional
# header passthrough, and the video sticky-miss circuit breaker.
# Usage: ./bin/smoke-v13-hardening.sh [webflux|vt]   (default: both)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/llmgw-app/target/llmgw-app-0.1.0-SNAPSHOT.jar"
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

wait_health() {
  for _ in $(seq 1 60); do
    if curl --noproxy '*' -fsS "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "FAILED to become healthy" >&2
  return 1
}

free_ports() {
  for _ in $(seq 1 20); do
    if ! lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1 \
       && ! lsof -nP -iTCP:"$MOCK_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

stop() {
  if [[ -n "$PID" ]]; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
    PID=""
  fi
  free_ports
}

start() {
  local engine="$1"; shift
  stop
  java -jar "$JAR" --spring.profiles.active="$engine" --server.port="$PORT" \
    --gateway.governance.default-quota-tokens=2000000000 \
    --gateway.governance.rate-limit-tokens-per-second=2000000000 \
    --gateway.governance.rate-limit-burst=2000000000 \
    '--gateway.api-keys[0].quota-tokens=1000000000000' \
    "$@" > "$TMP/gateway.log" 2>&1 &
  PID=$!
  wait_health
}

ok()   { PASS=$((PASS + 1)); echo "[PASS] $1"; }
bad()  { FAIL=$((FAIL + 1)); echo "[FAIL] $1"; }

assert_contains() { [[ "$1" == *"$2"* ]] && ok "$3" || bad "$3 (missing: $2; got: ${1:0:200})"; }
assert_not_contains() { [[ "$1" != *"$2"* ]] && ok "$3" || bad "$3 (unexpected: $2)"; }

chat() { # method-agnostic POST helper; extra headers via $3
  curl -s --noproxy '*' -X POST "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H 'Authorization: Bearer sk-demo' -H 'Content-Type: application/json' \
    ${3:+$3} -d "$2"
}

chat_code() {
  curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST \
    "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H 'Authorization: Bearer sk-demo' -H 'Content-Type: application/json' -d "$2"
}

echo "======== v13 hardening: retry / timeouts / heartbeat / headers / breaker ========"

for engine in $ENGINES; do
  echo "--- engine=$engine -- retry + header passthrough (defaults otherwise) ---"
  start "$engine" \
    --gateway.upstream.retry.attempts=3 \
    --gateway.upstream.retry.backoff-base-ms=50 \
    --gateway.upstream.retry.backoff-max-ms=200 \
    '--gateway.routes[0].api-key=route-secret-key'

  # 5xx on the first attempt is retried on the SAME route and recovers.
  body=$(chat x '{"model":"m","messages":[{"content":"retry-probe mock-fail-once-status-500"}]}')
  assert_contains "$body" '"object":"chat.completion"' \
    "$engine non-stream same-route retry recovers from 5xx"

  body=$(chat x '{"model":"m","stream":true,"messages":[{"content":"retry-sse-probe mock-fail-once-status-500"}]}')
  assert_contains "$body" 'data: [DONE]' "$engine SSE same-route retry recovers from 5xx"

  # 4xx is never retried: even though a second attempt would succeed.
  body=$(chat x '{"model":"m","messages":[{"content":"no-retry-4xx mock-fail-once-status-400"}]}')
  assert_contains "$body" 'upstream status 400' "$engine 4xx is not retried"

  # B9: upstream Retry-After extends the same-route retry delay. 429 carries
  # Retry-After: 1 (mock) — with a 50ms planned backoff the first retry lands
  # BEFORE 1s only if the header was ignored. Assert the recovery happened
  # AND took >= 1s wall clock (header honored), < 5s (not the whole stall).
  start_ms=$(python3 -c 'import time; print(int(time.time()*1000))')
  body=$(chat x '{"model":"m","messages":[{"content":"retry-after probe mock-fail-once-status-429 mock-retry-after"}]}')
  end_ms=$(python3 -c 'import time; print(int(time.time()*1000))')
  assert_contains "$body" '"object":"chat.completion"' "$engine 429 with Retry-After still recovers via retry"
  elapsed=$((end_ms - start_ms))
  if [[ "$elapsed" -ge 1000 && "$elapsed" -lt 5000 ]]; then
    ok "$engine Retry-After honored (recovered in ${elapsed}ms >= 1000ms)"
  else
    bad "$engine Retry-After timing expected 1000..5000ms, got ${elapsed}ms"
  fi

  # Ingress: allowlisted client headers reach the upstream, protected ones do
  # not. The mock echoes what it received INTO THE BODY (header echoes would
  # be filtered by the gateway's egress allowlist -- by design). The ROUTE's
  # api-key legitimately becomes the upstream Authorization; the CALLER's
  # token (sk-demo) must never travel.
  body=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H 'Authorization: Bearer sk-demo' -H 'Content-Type: application/json' \
    -H 'anthropic-version: 2023-06-01' -H 'x-private: hush' \
    -d '{"model":"m","messages":[{"content":"ingress-probe"}]}')
  assert_contains "$body" '"echoAnthropicVersion":"2023-06-01"' \
    "$engine ingress allowlist forwards anthropic-version upstream"
  assert_contains "$body" '"echoAuthorization":"Bearer route-secret-key"' \
    "$engine route credentials become the upstream Authorization"
  assert_not_contains "$body" 'Bearer sk-demo' \
    "$engine caller token never forwarded upstream"

  # SSE ingress: the mock's first frame lists the probe headers it received.
  body=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H 'Authorization: Bearer sk-demo' -H 'Content-Type: application/json' \
    -H 'anthropic-version: 2023-06-01' -H 'x-private: hush' \
    -d '{"model":"m","stream":true,"messages":[{"content":"sse-ingress-probe mock-echo-headers"}]}')
  assert_contains "$body" 'anthropic-version=2023-06-01' \
    "$engine SSE ingress forwards anthropic-version upstream"
  assert_contains "$body" 'Authorization=Bearer route-secret-key' \
    "$engine SSE upstream Authorization is the route key"
  assert_not_contains "$body" 'sk-demo' \
    "$engine SSE caller token never forwarded upstream"
  assert_not_contains "$body" 'x-private=' \
    "$engine SSE ingress strips non-allowlisted headers upstream"

  # Egress: allowlisted upstream headers reach the client, private ones do
  # not. Unique body: a cache hit would serve no upstream headers at all.
  hdrs=$(curl -s --noproxy '*' -D - -o /dev/null -X POST \
    "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H 'Authorization: Bearer sk-demo' -H 'Content-Type: application/json' \
    -d '{"model":"m","messages":[{"content":"egress-probe"}]}')
  assert_contains "$hdrs" 'x-ratelimit-remaining: 424242' \
    "$engine egress allowlist forwards x-ratelimit-* to client"
  assert_not_contains "$hdrs" 'x-private-upstream' \
    "$engine non-allowlisted upstream header never reaches client"
  stop

  echo "--- engine=$engine -- three-tier timeouts ---"
  start "$engine" \
    --gateway.upstream.first-byte-timeout-ms=300 \
    --gateway.upstream.idle-read-timeout-ms=300

  # A hung upstream fails BEFORE the 200 commits: 502 with the tier-1 reason,
  # long before the mock's 5s stall ends (a missing watchdog would return 200).
  body=$(curl -s --noproxy '*' --max-time 15 -X POST \
    "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H 'Authorization: Bearer sk-demo' -H 'Content-Type: application/json' \
    -d '{"model":"m","stream":true,"messages":[{"content":"hi mock-stall-headers-5000"}]}')
  assert_contains "$body" 'upstream first-byte timeout' \
    "$engine first-byte watchdog fails a hung upstream as 502"

  # Mid-stream silence is aborted by the idle-read watchdog and reported
  # in-band (a chunk already reached the client).
  body=$(curl -s --noproxy '*' --max-time 15 -X POST \
    "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H 'Authorization: Bearer sk-demo' -H 'Content-Type: application/json' \
    -d '{"model":"m","stream":true,"messages":[{"content":"hi mock-stall-mid-5000"}]}')
  assert_contains "$body" 'event: error' "$engine idle-read stall reports in-band error"
  assert_contains "$body" 'upstream idle read timeout' "$engine idle-read reason is precise"
  assert_not_contains "$body" 'data: \[DONE\]' \
    "$engine idle-read abort never fabricates a terminator"
  stop

  echo "--- engine=$engine -- SSE heartbeat ---"
  start "$engine" \
    --gateway.upstream.heartbeat-interval-ms=150 \
    --gateway.upstream.idle-read-timeout-ms=5000

  # A 400ms-per-chunk stream accumulates ~2 comment frames per gap. Heartbeat
  # frames must be transparent: protocol data and terminator still flow.
  body=$(curl -s --noproxy '*' --max-time 20 -X POST \
    "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H 'Authorization: Bearer sk-demo' -H 'Content-Type: application/json' \
    -d '{"model":"m","stream":true,"messages":[{"content":"hi mock-chunk-interval-400"}]}')
  beats=$(printf '%s' "$body" | grep -c ': keep-alive' || true)
  if [[ "$beats" -ge 5 ]]; then ok "$engine heartbeat injects comment frames ($beats)"; else
    bad "$engine heartbeat comment frames missing ($beats)"; fi
  assert_contains "$body" '"object":"chat.completion.chunk"' \
    "$engine heartbeat coexists with protocol data"
  assert_contains "$body" 'data: [DONE]' "$engine heartbeat does not break the terminator"
  stop

  echo "--- engine=$engine -- video sticky-miss circuit breaker ---"
  start "$engine" \
    --gateway.governance.circuit-failure-threshold=1 \
    --gateway.governance.circuit-cooldown-ms=60000 \
    --gateway.routes[0].id=videos-primary \
    --gateway.routes[0].protocol=videos \
    --gateway.routes[0].target-url=http://127.0.0.1:19999/mock/v1

  # Sticky miss + dead wildcard route: first poll fails (circuit opens)...
  code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' \
    -H 'Authorization: Bearer sk-demo' \
    "http://127.0.0.1:$PORT/v1/videos/unknown-task")
  [[ "$code" == "502" ]] && ok "$engine unknown-task video poll fails 502" \
    || bad "$engine unknown-task video poll expected 502, got $code"
  # ...the next poll must be refused locally, without touching the network.
  body=$(curl -s --noproxy '*' -H 'Authorization: Bearer sk-demo' \
    "http://127.0.0.1:$PORT/v1/videos/unknown-task-2")
  assert_contains "$body" 'circuit open' "$engine sticky-miss circuit breaker trips"
  stop
done

echo "================================================="
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
