#!/usr/bin/env bash
# v10 hot-config regression: new requests immediately use the new route table,
# while long-lived SSE streams that were already in flight are never drained,
# re-routed or spliced. Also guards the admin token and config validation.
# Usage: ./bin/smoke-hot-reload.sh [webflux|vt]
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${LLMGW_TEST_JAR:-$ROOT/llmgw-app/target/llmgw-app-0.3.0.jar}"
PORT="${PORT:-8080}"
MOCK_PORT="${MOCK_PORT:-9090}"
ENGINE="${1:-webflux}"
GOOD_URL="http://127.0.0.1:$MOCK_PORT/mock/v1"
ADMIN_TOKEN="hot-reload-test-token"
STREAMS="${STREAMS:-40}"
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
  stop
  java -jar "$JAR" --spring.profiles.active="$ENGINE" \
    --gateway.governance-enabled=true --server.port="$PORT" \
    --gateway.admin-token="$ADMIN_TOKEN" \
    --gateway.mock.delay-ms=30 --gateway.mock.chunks=12 \
    --gateway.mock.chunk-interval-ms=60 \
    --gateway.governance.default-quota-tokens=2000000000 \
    --gateway.governance.rate-limit-tokens-per-second=2000000000 \
    --gateway.governance.rate-limit-burst=2000000000 \
    '--gateway.api-keys[0].quota-tokens=1000000000000' \
    > "$TMP/gateway.log" 2>&1 &
  PID=$!
  for _ in $(seq 1 60); do
    if curl --noproxy '*' -fsS "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "FAILED to become healthy" >&2
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

admin_get() {
  curl --noproxy '*' -s -D "$TMP/h" -o "$TMP/b" \
    -H "X-Admin-Token: $ADMIN_TOKEN" \
    "http://127.0.0.1:$PORT/admin/routes"
}

admin_post() {
  # $1 = json body, $2 = token value (empty omits the header)
  local body="$1" token="$2"
  local -a args=(-s -o "$TMP/post" -w '%{http_code}'
      -X POST "http://127.0.0.1:$PORT/admin/routes/reload"
      -H 'Content-Type: application/json' -d "$body")
  [[ -n "$token" ]] && args+=(-H "X-Admin-Token: $token")
  curl --noproxy '*' "${args[@]}"
}

version_header() { tr -d '\r' < "$TMP/h" | grep -i '^X-LLMGW-Config-Version:' | awk '{print $2}'; }

route_counts() {
  # counters are cumulative since process start, so callers must use deltas
  curl --noproxy '*' -s -H "X-Admin-Token: $ADMIN_TOKEN" \
    "http://127.0.0.1:$PORT/admin/metrics" | python3 -c '
import sys, json
d = json.load(sys.stdin)
counts = {"r-a": 0, "r-b": 0}
for c in d.get("counters", []):
    if c["name"] == "llmgw.requests":
        tags = dict(kv.split("=", 1) for kv in c["tags"].split(",") if "=" in kv)
        route = tags.get("route", "")
        if route in counts:
            counts[route] += int(c["value"])
print(counts["r-a"], counts["r-b"])
' 2>/dev/null || echo "0 0"
}

share_delta() {
  BEFORE="$1" AFTER="$2" python3 -c '
import os
b = list(map(int, os.environ["BEFORE"].split()))
a = list(map(int, os.environ["AFTER"].split()))
da, db = a[0] - b[0], a[1] - b[1]
total = da + db
print(f"{da / total:.3f}" if total > 0 else "0.000")
' 2>/dev/null || echo "0.000"
}

post_jsons() {
  local n="$1"
  seq 1 "$n" | PORT="$PORT" xargs -P 20 -n 1 bash -c '
    curl --noproxy "*" -s -o /dev/null \
      -X POST "http://127.0.0.1:$PORT/v1/chat/completions" \
      -H "Content-Type: application/json" -H "Authorization: Bearer sk-demo" \
      -d "{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"hot reload\"}],\"stream\":false}"'
}

CFG_A="[{\"id\":\"r-a\",\"model\":\"*\",\"targetUrl\":\"$GOOD_URL\",\"weight\":1},{\"id\":\"r-b\",\"model\":\"*\",\"targetUrl\":\"$GOOD_URL\",\"weight\":3}]"
CFG_B="[{\"id\":\"r-a\",\"model\":\"*\",\"targetUrl\":\"$GOOD_URL\",\"weight\":3},{\"id\":\"r-b\",\"model\":\"*\",\"targetUrl\":\"$GOOD_URL\",\"weight\":1}]"

echo "=== engine=$ENGINE streams=$STREAMS ==="
start || { echo "PASS=0 FAIL=1"; exit 1; }

admin_get
V0="$(version_header)"
[[ "$V0" == "0" ]]
ok=$?
check "initial config version is 0" "$ok" "version=$V0"

STATUS="$(admin_post "$CFG_A" "$ADMIN_TOKEN")"
VER1="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("version"))' "$TMP/post" 2>/dev/null || echo err)"
[[ "$STATUS" == "200" && "$VER1" == "1" ]]
ok=$?
check "first reload applied atomically" "$ok" "status=$STATUS version=$VER1"
if grep -q '"apiKey"' "$TMP/post"; then
  check "reload response never echoes provider key" "1" "raw apiKey field found"
else
  check "reload response never echoes provider key" "0" "only apiKeyConfigured exposed"
fi

BEFORE="$(route_counts)"
post_jsons 200
AFTER="$(route_counts)"
SHARE_A="$(share_delta "$BEFORE" "$AFTER")"
python3 -c "import sys; sys.exit(0 if abs(float(sys.argv[1]) - 0.25) <= 0.12 else 1)" "$SHARE_A"
ok=$?
check "new requests use 1:3 weights" "$ok" "r-a share=$SHARE_A (expected ~0.25)"

# Long-lived SSE streams start, then a second reload lands while they are in flight.
BODY_SSE='{"model":"qwen-plus","messages":[{"role":"user","content":"hot reload sse"}],"stream":true}'
PIDS=()
for i in $(seq 1 "$STREAMS"); do
  curl --noproxy '*' -s --max-time 20 -N -D "$TMP/h.$i" -o "$TMP/b.$i" \
    -w '%{http_code}' > "$TMP/s.$i" \
    -X POST "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-demo' \
    -d "$BODY_SSE" &
  PIDS+=("$!")
done
sleep 0.25
STATUS="$(admin_post "$CFG_B" "$ADMIN_TOKEN")"
VER2="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("version"))' "$TMP/post" 2>/dev/null || echo err)"
wait "${PIDS[@]}"

BAD=0
DONE=0
for i in $(seq 1 "$STREAMS"); do
  code="$(cat "$TMP/s.$i" 2>/dev/null)"
  if [[ "$code" != "200" ]] || ! grep -q 'data: \[DONE\]' "$TMP/b.$i" 2>/dev/null \
     || grep -q 'event: error' "$TMP/b.$i" 2>/dev/null; then
    BAD=$((BAD + 1))
  else
    DONE=$((DONE + 1))
  fi
done
[[ "$BAD" -eq 0 && "$DONE" -eq "$STREAMS" ]]
ok=$?
check "in-flight SSE streams survive reload" "$ok" "complete=$DONE bad=$BAD streams=$STREAMS"
[[ "$STATUS" == "200" && "$VER2" == "2" ]]
ok=$?
check "reload landed while streams were active" "$ok" "status=$STATUS version=$VER2"

admin_get
VNOW="$(version_header)"
[[ "$VNOW" == "2" ]]
ok=$?
check "config version header advances" "$ok" "version=$VNOW"

BEFORE="$(route_counts)"
post_jsons 200
AFTER="$(route_counts)"
SHARE_B="$(share_delta "$BEFORE" "$AFTER")"
python3 -c "import sys; sys.exit(0 if abs(float(sys.argv[1]) - 0.75) <= 0.12 else 1)" "$SHARE_B"
ok=$?
check "new requests use 3:1 weights" "$ok" "r-a share=$SHARE_B (expected ~0.75)"

INVALID_OK=1
for body in '[]' \
  '[{"id":"dup","model":"*"},{"id":"dup","model":"*"}]' \
  "[{\"id\":\"one\",\"model\":\"*\",\"targetUrl\":\"$GOOD_URL\",\"fallbacks\":[\"ghost\"]}]" \
  '[{"id":"one","model":"*","targetUrl":"file:///etc/passwd"}]'; do
  code="$(admin_post "$body" "$ADMIN_TOKEN")"
  [[ "$code" == "400" ]] || INVALID_OK=0
done
admin_get
VAFTER="$(version_header)"
[[ "$INVALID_OK" -eq 1 && "$VAFTER" == "2" ]]
ok=$?
check "invalid configs rejected without changing table" "$ok" "version=$VAFTER all400=$INVALID_OK"

NOAUTH="$(admin_post "$CFG_A" "")"
[[ "$NOAUTH" == "401" ]]
ok=$?
check "admin token gates reload" "$ok" "status=$NOAUTH"

stop
echo "PASS=$PASS FAIL=$FAIL"
[[ "$FAIL" -eq 0 ]]
