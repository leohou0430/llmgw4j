#!/usr/bin/env bash
# v13 platform regression: $ hard budgets, governance hot reload, request log,
# admin console, runtime key lifecycle (create/use/revoke) and group spend.
# Usage: ./bin/smoke-v13-platform.sh [webflux|vt]   (default: both)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${LLMGW_TEST_JAR:-$ROOT/llmgw-app/target/llmgw-app-0.3.0.jar}"
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
    "$@" > "$TMP/gateway.log" 2>&1 &
  PID=$!
  wait_health
}

ok()   { PASS=$((PASS + 1)); echo "[PASS] $1"; }
bad()  { FAIL=$((FAIL + 1)); echo "[FAIL] $1"; }
assert_contains() { [[ "$1" == *"$2"* ]] && ok "$3" || bad "$3 (missing: $2; got: ${1:0:220})"; }
assert_not_contains() { [[ "$1" != *"$2"* ]] && ok "$3" || bad "$3 (unexpected: $2)"; }

chat() {
  curl -s --noproxy '*' -X POST "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H "Authorization: Bearer ${2-sk-demo}" -H 'Content-Type: application/json' -d "$1"
}

echo "======== v13 platform: budget / hot reload / requests / console / keys ========"

for engine in $ENGINES; do
  echo "--- engine=$engine ---"
  # Model catalog prices everything at $1/M in+out; demo key budget = 300 micro-USD.
  # One request (est ~250 micro reserved, ~127 settled) exhausts it on the next call.
  start "$engine" \
    --gateway.governance.snapshot-path="$TMP/snapshot.json" \
    --gateway.keys-store-path="$TMP/keys.json" \
    '--gateway.models[0].model=*' \
    '--gateway.models[0].inputPriceMicroUsdPerMillion=1000000' \
    '--gateway.models[0].outputPriceMicroUsdPerMillion=1000000' \
    '--gateway.api-keys[0].id=demo' \
    '--gateway.api-keys[0].token=sk-demo' \
    '--gateway.api-keys[0].group=team-a' \
    '--gateway.api-keys[0].budget-micro-usd=250' \
    '--gateway.api-keys[0].quota-tokens=100000000' \
    '--gateway.auth.jwt-secret=jwt-bench-secret' \
    '--gateway.auth.jwt-issuer=bench' \
    '--gateway.auth.rotate-overlap-seconds=2' \
    '--gateway.model-limits[0].model=limited-*' \
    '--gateway.model-limits[0].tokens-per-second=1' \
    '--gateway.model-limits[0].burst=2' \
    '--gateway.group-budgets[0].group=team-b' \
    '--gateway.group-budgets[0].budget-micro-usd=500000'

  # 1. Budget, with exact arithmetic: a 400-char ASCII prompt estimates to
  # ceil(400/4)=100 tokens. Admission reserves cost(100,100)=200 micro-USD;
  # settle books cost(100 prompt, 1 completion)=101. Budget 250 -> request 1
  # fits (200<=250), request 2 denies (101+200=301>250). Deterministic.
  longPrompt=""
  longPrompt=$(printf 'B%.0s' $(seq 1 400))
  body=$(chat "{\"model\":\"m\",\"messages\":[{\"content\":\"$longPrompt\"}]}")
  assert_contains "$body" '"object":"chat.completion"' "$engine first request inside budget succeeds"
  body=$(chat "{\"model\":\"m\",\"messages\":[{\"content\":\"$longPrompt\"}]}")
  assert_contains "$body" 'spend budget exceeded' "$engine second request hits the hard budget stop"

  # 2. Spend bookkeeping lands in the snapshot, rolled up by group.
  snap=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/admin/snapshot")
  assert_contains "$snap" '"spendMicroUsd"' "$engine snapshot carries per-key spend"
  assert_contains "$snap" '"team-a"' "$engine group rollup answers which team burned what"

  # 3. Runtime key lifecycle: create with a group + budget, use it, revoke it.
  created=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$PORT/admin/keys" \
    -H 'Content-Type: application/json' \
    -d '{"name":"e2e","group":"team-b","budgetMicroUsd":900000000,"quotaTokens":900000000,"ttlSeconds":null,"metadata":{"team":"qa"}}')
  newToken=$(printf '%s' "$created" | jq -r '.token // empty')
  newId=$(printf '%s' "$created" | jq -r '.id // empty')
  oldTokenStill="$newToken"
  rotId="$newId"
  assert_contains "$created" '"metadata":{"team":"qa"}' "$engine key metadata accepted"
  if [[ -n "$newToken" && -n "$newId" ]]; then
    ok "$engine key created ($newId)"
  else
    bad "$engine key creation failed: ${created:0:160}"
  fi
  body=$(chat '{"model":"m","messages":[{"content":"hello from the new key"}]}' "$newToken")
  assert_contains "$body" '"object":"chat.completion"' "$engine freshly created key authenticates"
  revoke=$(curl -s --noproxy '*' -X DELETE "http://127.0.0.1:$PORT/admin/keys/$newId")
  assert_contains "$revoke" '"revoked":true' "$engine key revoke acknowledged"
  code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST \
    "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H "Authorization: Bearer $newToken" -H 'Content-Type: application/json' \
    -d '{"model":"m","messages":[{"content":"should fail now"}]}')
  [[ "$code" == "401" ]] && ok "$engine revoked key is 401" || bad "$engine revoked key expected 401, got $code"

  # 4. Governance hot reload: strangle the rate limit, then release it.
  stop
  start "$engine" \
    --gateway.governance.snapshot-path="$TMP/snapshot.json" \
    --gateway.keys-store-path="$TMP/keys.json" \
    '--gateway.models[0].model=*' \
    '--gateway.models[0].inputPriceMicroUsdPerMillion=1000000' \
    '--gateway.models[0].outputPriceMicroUsdPerMillion=1000000' \
    --gateway.governance.rate-limit-tokens-per-second=1 \
    --gateway.governance.rate-limit-burst=1 \
    '--gateway.api-keys[0].quota-tokens=900000000' \
    '--gateway.auth.jwt-secret=jwt-bench-secret' \
    '--gateway.auth.jwt-issuer=bench' \
    '--gateway.model-limits[0].model=limited-*' \
    '--gateway.model-limits[0].tokens-per-second=1' \
    '--gateway.model-limits[0].burst=2' \
    '--gateway.group-budgets[0].group=team-b' \
    '--gateway.group-budgets[0].budget-micro-usd=500000'
  body=$(chat '{"model":"m","messages":[{"content":"warm the bucket"}]}')
  body=$(chat '{"model":"m","messages":[{"content":"should be rate limited"}]}')
  assert_contains "$body" 'token rate limit exceeded' "$engine tiny bucket rejects before reload"
  swapped=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$PORT/admin/governance" \
    -H 'Content-Type: application/json' \
    -d '{"rateLimitBurst":900000,"rateLimitTokensPerSecond":900000}')
  assert_contains "$swapped" '"rateLimitBurst":900000' "$engine governance hot swap acknowledged"
  sleep 1
  body=$(chat '{"model":"m","messages":[{"content":"should pass after hot reload"}]}')
  assert_contains "$body" '"object":"chat.completion"' "$engine request passes WITHOUT restart after hot reload"

  # B1 rotation: a FRESH key is rotated; old token works inside the overlap
  # window, new token works, old token dies after the window (2s here).
  rot=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$PORT/admin/keys" \
    -H 'Content-Type: application/json' \
    -d '{"name":"rot-e2e","group":"team-b","quotaTokens":900000000}')
  rotId=$(printf '%s' "$rot" | jq -r '.id // empty')
  oldTokenStill=$(printf '%s' "$rot" | jq -r '.token // empty')
  rot=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$PORT/admin/keys/$rotId/rotate" \
    -H 'Content-Type: application/json' -d '{"overlapSeconds":2}')
  newTok=$(printf '%s' "$rot" | jq -r '.token // empty')
  if [[ -n "$newTok" ]]; then ok "$engine rotate minted a new token"; else
    bad "$engine rotate failed: ${rot:0:160}"; fi
  body=$(chat '{"model":"m","messages":[{"content":"post-rotate new"}]}' "$newTok")
  assert_contains "$body" '"object":"chat.completion"' "$engine rotated new token authenticates"
  body=$(chat '{"model":"m","messages":[{"content":"overlap window"}]}' "$oldTokenStill")
  assert_contains "$body" '"object":"chat.completion"' "$engine old token works in overlap window"
  sleep 2.5
  code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST \
    "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H "Authorization: Bearer $oldTokenStill" -H 'Content-Type: application/json' \
    -d '{"model":"m","messages":[{"content":"overlap expired"}]}')
  [[ "$code" == "401" ]] && ok "$engine old token dies after overlap" \
    || bad "$engine old token expected 401 after overlap, got $code"

  # B1 JWT: HS256 token from an inline generator authenticates (virtual key).
  jwt=$(python3 - <<'JEOF'
import base64, hashlib, hmac, json, time
secret = b"jwt-bench-secret"
h = base64.urlsafe_b64encode(json.dumps({"alg":"HS256","typ":"JWT"}).encode()).rstrip(b"=")
c = base64.urlsafe_b64encode(json.dumps({"exp":int(time.time())+600,"iss":"bench","sub":"bench-user"}).encode()).rstrip(b"=")
sig = base64.urlsafe_b64encode(hmac.new(secret, h+b"."+c, hashlib.sha256).digest()).rstrip(b"=")
print((h+b"."+c+b"."+sig).decode())
JEOF
)
  body=$(chat '{"model":"m","messages":[{"content":"jwt probe"}]}' "$jwt")
  assert_contains "$body" '"object":"chat.completion"' "$engine JWT (HS256) authenticates as virtual key"

  # B4 per-model rate limit: model "limited-*" has burst=2 tokens/s. Three
  # quick requests (est ~1 token each) exhaust the 2-token burst -> 429.
  for probe in p1 p2 p3; do
    code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST \
      "http://127.0.0.1:$PORT/v1/chat/completions" \
      -H 'Authorization: Bearer sk-demo' -H 'Content-Type: application/json' \
      -d "{\"model\":\"limited-model\",\"messages\":[{\"content\":\"$probe\"}]}")
  done
  [[ "$code" == "429" ]] && ok "$engine per-model rate limit rejects (429)" \
    || bad "$engine per-model limit expected 429 on 3rd request, got $code"

  # 5. Request log captured both sides; newest first. The side car writes
  # asynchronously, so give the last finish a moment before reading.
  sleep 2
  logs=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/admin/requests?limit=10")
  assert_contains "$logs" '"success":true' "$engine request log carries served requests"
  assert_contains "$logs" '"success":false' "$engine request log carries denied requests"

  # 6. Console page loads.
  page=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/admin/console")
  assert_contains "$page" 'llmgw4j console' "$engine admin console serves"
  stop

  # 7. W4.1 guardrails: local pattern deny + external engine deny, clean passes.
  start "$engine" \
    '--gateway.guardrails.deny-patterns[0]=secret-project' \
    '--gateway.guardrails.http-url=http://127.0.0.1:9090/mock/v1/guardrail'
  body=$(chat '{"model":"m","messages":[{"content":"tell me about the secret-project"}]}')
  assert_contains "$body" 'blocked by guardrail pattern-deny' "$engine pattern guardrail blocks locally"
  body=$(chat '{"model":"m","messages":[{"content":"deny-me please"}]}')
  assert_contains "$body" 'blocked by guardrail http-engine' "$engine external engine blocks"
  body=$(chat '{"model":"m","messages":[{"content":"perfectly innocent question"}]}')
  assert_contains "$body" '"object":"chat.completion"' "$engine clean request passes both guardrails"
  stop
done

echo "================================================="
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
