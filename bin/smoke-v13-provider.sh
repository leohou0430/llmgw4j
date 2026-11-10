#!/usr/bin/env bash
# v13 provider-adapter regression: route.provider selects the upstream
# credential dialect. anthropic -> x-api-key + anthropic-version; default ->
# Authorization: Bearer. Protocol stays a routing dimension (no translation).
# Usage: ./bin/smoke-v13-provider.sh [webflux|vt]   (default: both)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${LLMGW_TEST_JAR:-$ROOT/llmgw-app/target/llmgw-app-0.3.1.jar}"
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
assert_contains() { [[ "$1" == *"$2"* ]] && ok "$3" || bad "$3 (missing: $2; got: ${1:0:220})"; }
assert_not_contains() { [[ "$1" != *"$2"* ]] && ok "$3" || bad "$3 (unexpected: $2)"; }

echo "======== v13 provider adapter: credential dialect per route ========"

for engine in $ENGINES; do
  echo "--- engine=$engine ---"
  start "$engine" \
    '--gateway.routes[0].id=anthropic-primary' \
    '--gateway.routes[0].protocol=chat' \
    '--gateway.routes[0].model=claude-*' \
    '--gateway.routes[0].provider=anthropic' \
    '--gateway.routes[0].api-key=sk-ant-route-secret' \
    '--gateway.routes[1].id=openai-default' \
    '--gateway.routes[1].model=*' \
    '--gateway.routes[1].api-key=route-secret-key'

  # Anthropic dialect: x-api-key + anthropic-version, never Bearer.
  body=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H 'Authorization: Bearer sk-demo' -H 'Content-Type: application/json' \
    -d '{"model":"claude-sonnet-x","messages":[{"content":"hi"}]}')
  assert_contains "$body" '"echoAuthStyle":"x-api-key"' \
    "$engine anthropic route authenticates with x-api-key"
  assert_contains "$body" '"echoAnthropicVersion":"2023-06-01"' \
    "$engine anthropic route sends anthropic-version"
  assert_not_contains "$body" 'echoAuthorization' \
    "$engine anthropic route never sends Authorization"
  assert_not_contains "$body" 'sk-ant-route-secret' \
    "$engine upstream credential never echoed back"

  # Default dialect: Bearer with the route key, unchanged since v1.
  body=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H 'Authorization: Bearer sk-demo' -H 'Content-Type: application/json' \
    -d '{"model":"qwen-plus","messages":[{"content":"hi"}]}')
  assert_contains "$body" '"echoAuthStyle":"bearer"' \
    "$engine default route keeps Bearer auth"
  assert_contains "$body" '"echoAuthorization":"Bearer route-secret-key"' \
    "$engine default route uses the route key"

  # SSE through the anthropic route: in-band probe shows the credential headers.
  body=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H 'Authorization: Bearer sk-demo' -H 'Content-Type: application/json' \
    -d '{"model":"claude-opus-y","stream":true,"messages":[{"content":"hi mock-echo-headers"}]}')
  assert_contains "$body" 'x-api-key=sk-ant-route-secret' \
    "$engine SSE anthropic route forwards x-api-key upstream"
  assert_contains "$body" 'anthropic-version=2023-06-01' \
    "$engine SSE anthropic route forwards anthropic-version upstream"
  assert_not_contains "$body" 'Authorization=' \
    "$engine SSE anthropic route sends no Authorization header"
  assert_contains "$body" 'data: [DONE]' "$engine SSE anthropic route completes"

  # The admin route view exposes the provider field.
  routes=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/admin/routes")
  assert_contains "$routes" '"provider":"anthropic"' \
    "$engine admin routes view carries the provider field"
  assert_not_contains "$routes" 'sk-ant-route-secret' \
    "$engine admin routes view never leaks the provider key"
  stop
done

echo "================================================="
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
