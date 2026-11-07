#!/usr/bin/env bash
# v12 multi-protocol regression: responses / messages / images / videos across
# BOTH engines, including per-protocol SSE terminator contracts, per-protocol
# metering, protocol routing and video sticky tasks.
# Usage: ./bin/smoke-multi-protocol.sh [webflux|vt]   (default: both)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/llmgw-app/target/llmgw-app-0.1.0-SNAPSHOT.jar"
PORT="${PORT:-8080}"
MOCK_PORT="${MOCK_PORT:-9090}"
MOCK_PORT_2=9091
GOOD_URL="http://127.0.0.1:$MOCK_PORT/mock/v1"
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
       && ! lsof -nP -iTCP:"$MOCK_PORT" -sTCP:LISTEN >/dev/null 2>&1 \
       && ! lsof -nP -iTCP:"$MOCK_PORT_2" -sTCP:LISTEN >/dev/null 2>&1; then
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
  java -jar "$JAR" --spring.profiles.active="$engine" \
    --gateway.governance-enabled=true --server.port="$PORT" \
    --gateway.governance.default-quota-tokens=2000000000 \
    --gateway.governance.rate-limit-tokens-per-second=2000000000 \
    --gateway.governance.rate-limit-burst=2000000000 \
    '--gateway.api-keys[0].quota-tokens=1000000000000' \
    "$@" > "$TMP/gateway-$engine-$RANDOM.log" 2>&1 &
  PID=$!
  wait_health
}

# req <METHOD> <path> [body] [auth] -> prints "<status> <content-type>", body in $TMP/body
req() {
  local method="$1" path="$2" body="${3-}" auth="${4-Bearer sk-demo}"
  : > "$TMP/body"
  local -a args=(-s -o "$TMP/body" -w '%{http_code} %{content_type}'
    -X "$method" "http://127.0.0.1:$PORT$path"
    -H 'Content-Type: application/json')
  [[ -n "$body" ]] && args+=(-d "$body")
  [[ -n "$auth" ]] && args+=(-H "Authorization: $auth")
  curl --noproxy '*' "${args[@]}"
}

check() {
  # $2 is the exit status of the preceding [[ ... ]] assertion: 0 means PASS.
  local label="$1" ok="$2" evidence="$3"
  if [[ "$ok" == "0" ]]; then
    echo "  [PASS] $label -- $evidence"
    PASS=$((PASS + 1))
  else
    echo "  [FAIL] $label -- $evidence"
    FAIL=$((FAIL + 1))
  fi
}

metrics_count() {
  # metrics_count <route-id> <protocol> -> llmgw.requests counter value
  curl --noproxy '*' -s "http://127.0.0.1:$PORT/admin/metrics" | python3 -c "
import sys, json
route, protocol = '$1', '$2'
try:
    d = json.load(sys.stdin)
except Exception:
    print(0); raise SystemExit
total = 0
for c in d.get('counters', []):
    if c['name'] != 'llmgw.requests':
        continue
    tags = dict(kv.split('=', 1) for kv in c['tags'].split(','))
    if tags.get('route') == route and tags.get('protocol') == protocol:
        total += int(c['value'])
print(total)
" 2>/dev/null || echo 0
}

snapshot_field() {
  # snapshot_field <jq-path under .usage.demo>
  curl --noproxy '*' -s "http://127.0.0.1:$PORT/admin/snapshot" \
    | jq -r ".usage.demo.$1 // -1" 2>/dev/null || echo -1
}

for ENGINE in $ENGINES; do
  echo "########## $ENGINE ##########"

  echo "-- responses + messages: happy paths --"
  start "$ENGINE"
  read -r CODE CTYPE < <(req POST /v1/responses \
    '{"model":"qwen-plus","input":"smoke json","stream":false}')
  [[ "$CODE" == "200" && "$CTYPE" == application/json* && "$(cat "$TMP/body")" == *'"text":"ok"'* ]]
  check "responses non-stream 200" "$?" "$CODE $CTYPE $(head -c 60 "$TMP/body")"

  read -r CODE CTYPE < <(req POST /v1/responses \
    '{"model":"qwen-plus","input":"smoke sse","stream":true}')
  DELTAS=$(grep -c '^data: {"type":"response.output_text.delta"' "$TMP/body" || true)
  COMPLETED=$(grep -c '^event: response.completed' "$TMP/body" || true)
  DONE=$(grep -c '^data: \[DONE\]' "$TMP/body" || true)
  [[ "$CODE" == "200" && "$CTYPE" == text/event-stream* \
     && "$DELTAS" -ge 1 && "$COMPLETED" -eq 1 && "$DONE" -eq 0 ]]
  check "responses SSE: deltas + response.completed, NO [DONE]" "$?" \
    "$CODE $CTYPE deltas=$DELTAS completed=$COMPLETED done=$DONE"

  read -r CODE CTYPE < <(req POST /v1/messages \
    '{"model":"qwen-plus","max_tokens":32,"messages":[{"role":"user","content":"smoke json"}]}')
  [[ "$CODE" == "200" && "$CTYPE" == application/json* \
     && "$(cat "$TMP/body")" == *'"stop_reason":"end_turn"'* ]]
  check "messages non-stream 200" "$?" "$CODE $CTYPE $(head -c 60 "$TMP/body")"

  read -r CODE CTYPE < <(req POST /v1/messages \
    '{"model":"qwen-plus","max_tokens":32,"messages":[{"role":"user","content":"smoke sse"}],"stream":true}')
  DELTAS=$(grep -c '^data: {"type":"content_block_delta"' "$TMP/body" || true)
  STOP=$(grep -c '^event: message_stop' "$TMP/body" || true)
  DONE=$(grep -c '^data: \[DONE\]' "$TMP/body" || true)
  [[ "$CODE" == "200" && "$CTYPE" == text/event-stream* \
     && "$DELTAS" -ge 1 && "$STOP" -eq 1 && "$DONE" -eq 0 ]]
  check "messages SSE: deltas + message_stop, NO [DONE]" "$?" \
    "$CODE $CTYPE deltas=$DELTAS stop=$STOP done=$DONE"

  echo "-- governance covers every endpoint --"
  read -r CODE CTYPE < <(req POST /v1/responses '{"model":"m","input":"x"}' "")
  [[ "$CODE" == "401" ]]
  check "responses without key -> 401" "$?" "$CODE $(head -c 60 "$TMP/body")"

  read -r CODE CTYPE < <(req POST /v1/messages 'not-json')
  [[ "$CODE" == "400" ]]
  check "messages invalid json -> 400" "$?" "$CODE $(head -c 60 "$TMP/body")"

  echo "-- images: n units, never cached --"
  start "$ENGINE"
  read -r CODE CTYPE < <(req POST /v1/images/generations \
    '{"model":"mock-image","prompt":"a cat","n":3}')
  N=$(grep -o '"b64_json"' "$TMP/body" | wc -l | tr -d ' ')
  [[ "$CODE" == "200" && "$CTYPE" == application/json* && "$N" -eq 3 ]]
  check "images n=3 -> 3 b64 entries" "$?" "$CODE $CTYPE entries=$N"

  read -r CODE CTYPE < <(req POST /v1/images/generations \
    '{"model":"mock-image","prompt":"a cat","n":99}')
  N=$(grep -o '"b64_json"' "$TMP/body" | wc -l | tr -d ' ')
  [[ "$CODE" == "200" && "$N" -eq 10 ]]
  check "images n=99 capped at 10" "$?" "$CODE entries=$N"

  CACHE_SIZE=$(curl --noproxy '*' -s "http://127.0.0.1:$PORT/admin/snapshot" \
    | jq -r '.cacheSize // -1' 2>/dev/null)
  [[ "${CACHE_SIZE:-1}" == "0" ]]
  check "images never enter the semantic cache" "$?" "cacheSize=$CACHE_SIZE"

  echo "-- videos: create -> poll -> content --"
  read -r CODE CTYPE < <(req POST /v1/videos \
    '{"model":"sora","prompt":"a cat runs","stream":false}')
  VID=$(jq -r '.id // ""' "$TMP/body" 2>/dev/null)
  [[ "$CODE" == "200" && "$VID" == video-* ]]
  check "video create 200 + task id" "$?" "$CODE id=$VID"

  STATUS=""
  for _ in $(seq 1 30); do
    req GET "/v1/videos/$VID" >/dev/null
    STATUS=$(jq -r '.status // ""' "$TMP/body" 2>/dev/null)
    [[ "$STATUS" == "completed" ]] && break
    sleep 0.2
  done
  [[ "$STATUS" == "completed" ]]
  check "video poll reaches completed" "$?" "status=$STATUS id=$VID"

  read -r CODE CTYPE < <(req GET "/v1/videos/$VID/content")
  SIZE=$(wc -c < "$TMP/body" | tr -d ' ')
  [[ "$CODE" == "200" && "$CTYPE" == video/mp4* && "$SIZE" -gt 0 ]]
  check "video content 200 video/mp4" "$?" "$CODE $CTYPE bytes=$SIZE"

  CACHE_SIZE=$(curl --noproxy '*' -s "http://127.0.0.1:$PORT/admin/snapshot" \
    | jq -r '.cacheSize // -1' 2>/dev/null)
  [[ "${CACHE_SIZE:-1}" == "0" ]]
  check "video create/poll never cached" "$?" "cacheSize=$CACHE_SIZE"

  echo "-- per-protocol metering (1+8+1+8+0 = 18 completion tokens) --"
  start "$ENGINE"
  req POST /v1/responses '{"model":"qwen-plus","input":"meter json","stream":false}' >/dev/null
  req POST /v1/responses '{"model":"qwen-plus","input":"meter sse","stream":true}' >/dev/null
  req POST /v1/messages '{"model":"qwen-plus","max_tokens":16,"messages":[{"role":"user","content":"meter json"}]}' >/dev/null
  req POST /v1/messages '{"model":"qwen-plus","max_tokens":16,"messages":[{"role":"user","content":"meter sse"}],"stream":true}' >/dev/null
  req POST /v1/images/generations '{"model":"mock-image","prompt":"meter img","n":2}' >/dev/null
  sleep 0.5
  CT=$(snapshot_field completionTokens)
  REQ=$(snapshot_field requests)
  ERR=$(snapshot_field errors)
  [[ "$CT" == "18" && "$REQ" == "5" && "$ERR" == "0" ]]
  check "usage frames from every protocol counted exactly" "$?" \
    "completionTokens=$CT requests=$REQ errors=$ERR (expect 18/5/0)"

  echo "-- protocol routing: each kind lands on its own route --"
  start "$ENGINE" \
    '--gateway.routes[0].id=chat-route' '--gateway.routes[0].protocol=chat' \
    '--gateway.routes[0].model=*' "--gateway.routes[0].target-url=$GOOD_URL" \
    '--gateway.routes[1].id=responses-route' '--gateway.routes[1].protocol=responses' \
    '--gateway.routes[1].model=*' "--gateway.routes[1].target-url=$GOOD_URL" \
    '--gateway.routes[2].id=messages-route' '--gateway.routes[2].protocol=messages' \
    '--gateway.routes[2].model=*' "--gateway.routes[2].target-url=$GOOD_URL" \
    '--gateway.routes[3].id=images-route' '--gateway.routes[3].protocol=images' \
    '--gateway.routes[3].model=*' "--gateway.routes[3].target-url=$GOOD_URL" \
    '--gateway.routes[4].id=videos-route' '--gateway.routes[4].protocol=videos' \
    '--gateway.routes[4].model=*' "--gateway.routes[4].target-url=$GOOD_URL"
  req POST /v1/chat/completions '{"model":"qwen-plus","messages":[{"role":"user","content":"route-chat"}]}' >/dev/null
  req POST /v1/responses '{"model":"qwen-plus","input":"route-responses"}' >/dev/null
  req POST /v1/messages '{"model":"qwen-plus","max_tokens":16,"messages":[{"role":"user","content":"route-messages"}]}' >/dev/null
  req POST /v1/images/generations '{"model":"mock-image","prompt":"route-images"}' >/dev/null
  req POST /v1/videos '{"model":"sora","prompt":"route-videos"}' >/dev/null
  sleep 0.4
  C_CHAT=$(metrics_count chat-route chat)
  C_RESP=$(metrics_count responses-route responses)
  C_MSG=$(metrics_count messages-route messages)
  C_IMG=$(metrics_count images-route images)
  C_VID=$(metrics_count videos-route videos)
  [[ "$C_CHAT" -ge 1 && "$C_RESP" -ge 1 && "$C_MSG" -ge 1 && "$C_IMG" -ge 1 && "$C_VID" -ge 1 ]]
  check "five protocols resolved to five protocol-specific routes" "$?" \
    "chat=$C_CHAT responses=$C_RESP messages=$C_MSG images=$C_IMG videos=$C_VID"

  echo "-- responses truncation contract (no [DONE] ever) --"
  start "$ENGINE"
  read -r CODE CTYPE < <(req POST /v1/responses \
    '{"model":"qwen-plus","input":"mock-fail-after-2","stream":true}')
  ERR_EVENT=$(grep -c '^event: error' "$TMP/body" || true)
  DONE=$(grep -c '^data: \[DONE\]' "$TMP/body" || true)
  COMPLETED=$(grep -c '^event: response.completed' "$TMP/body" || true)
  [[ "$CODE" == "200" && "$CTYPE" == text/event-stream* \
     && "$ERR_EVENT" -eq 1 && "$DONE" -eq 0 && "$COMPLETED" -eq 0 ]]
  check "responses mid-stream crash -> in-band error, NO fabricated terminators" "$?" \
    "$CODE err_event=$ERR_EVENT done=$DONE completed=$COMPLETED"

  read -r CODE CTYPE < <(req POST /v1/responses \
    '{"model":"qwen-plus","input":"mock-eof-after-0","stream":true}')
  [[ "$CODE" == "502" && "$CTYPE" == application/json* \
     && "$(cat "$TMP/body")" == *'without response.completed'* ]]
  check "responses zero-frame EOF -> 502 naming its own terminator" "$?" \
    "$CODE $CTYPE $(head -c 80 "$TMP/body")"

  read -r CODE CTYPE < <(req POST /v1/responses \
    '{"model":"qwen-plus","input":"mock-eof-after-2","stream":true}')
  ERR_EVENT=$(grep -c '^event: error' "$TMP/body" || true)
  DONE=$(grep -c '^data: \[DONE\]' "$TMP/body" || true)
  COMPLETED=$(grep -c '^event: response.completed' "$TMP/body" || true)
  [[ "$CODE" == "200" && "$ERR_EVENT" -eq 1 && "$DONE" -eq 0 && "$COMPLETED" -eq 0 ]]
  check "responses mid-stream EOF -> in-band error only" "$?" \
    "$CODE err_event=$ERR_EVENT done=$DONE completed=$COMPLETED"

  read -r CODE CTYPE < <(req POST /v1/responses \
    '{"model":"qwen-plus","input":"mock-done-then-fail","stream":true}')
  COMPLETED=$(grep -c '^event: response.completed' "$TMP/body" || true)
  ERR_EVENT=$(grep -c '^event: error' "$TMP/body" || true)
  [[ "$CODE" == "200" && "$COMPLETED" -eq 1 && "$ERR_EVENT" -eq 0 ]]
  check "responses disconnect after response.completed stays a success" "$?" \
    "$CODE completed=$COMPLETED err_event=$ERR_EVENT"

  read -r CODE CTYPE < <(req POST /v1/responses '{"model":"qwen-plus","input":"mock-status-429"}')
  [[ "$CODE" == "502" && "$(cat "$TMP/body")" == *'upstream status 429'* ]]
  check "responses upstream 429 -> gateway 502" "$?" "$CODE $(head -c 80 "$TMP/body")"

  echo "-- messages truncation contract (no [DONE] ever) --"
  read -r CODE CTYPE < <(req POST /v1/messages \
    '{"model":"qwen-plus","max_tokens":32,"messages":[{"role":"user","content":"mock-fail-after-2"}],"stream":true}')
  ERR_EVENT=$(grep -c '^event: error' "$TMP/body" || true)
  DONE=$(grep -c '^data: \[DONE\]' "$TMP/body" || true)
  STOP=$(grep -c '^event: message_stop' "$TMP/body" || true)
  [[ "$CODE" == "200" && "$ERR_EVENT" -eq 1 && "$DONE" -eq 0 && "$STOP" -eq 0 ]]
  check "messages mid-stream crash -> in-band error, NO fabricated terminators" "$?" \
    "$CODE err_event=$ERR_EVENT done=$DONE stop=$STOP"

  read -r CODE CTYPE < <(req POST /v1/messages \
    '{"model":"qwen-plus","max_tokens":32,"messages":[{"role":"user","content":"mock-eof-after-0"}],"stream":true}')
  [[ "$CODE" == "502" && "$(cat "$TMP/body")" == *'without message_stop'* ]]
  check "messages zero-frame EOF -> 502 naming its own terminator" "$?" \
    "$CODE $CTYPE $(head -c 80 "$TMP/body")"

  read -r CODE CTYPE < <(req POST /v1/messages \
    '{"model":"qwen-plus","max_tokens":32,"messages":[{"role":"user","content":"mock-done-then-fail"}],"stream":true}')
  STOP=$(grep -c '^event: message_stop' "$TMP/body" || true)
  ERR_EVENT=$(grep -c '^event: error' "$TMP/body" || true)
  [[ "$CODE" == "200" && "$STOP" -eq 1 && "$ERR_EVENT" -eq 0 ]]
  check "messages disconnect after message_stop stays a success" "$?" \
    "$CODE stop=$STOP err_event=$ERR_EVENT"

  echo "-- images/videos failure injection --"
  read -r CODE CTYPE < <(req POST /v1/images/generations \
    '{"model":"mock-image","prompt":"mock-status-500"}')
  [[ "$CODE" == "502" && "$(cat "$TMP/body")" == *'upstream status 500'* ]]
  check "images upstream 500 -> gateway 502" "$?" "$CODE $(head -c 80 "$TMP/body")"

  read -r CODE CTYPE < <(req POST /v1/videos '{"model":"sora","prompt":"mock-status-503"}')
  [[ "$CODE" == "502" && "$(cat "$TMP/body")" == *'upstream status 503'* ]]
  check "video create upstream 503 -> gateway 502" "$?" "$CODE $(head -c 80 "$TMP/body")"

  echo "-- sticky video routing: a task exists only on its creating upstream --"
  start "$ENGINE"
  req POST /v1/videos '{"model":"sora","prompt":"sticky-test"}' >/dev/null
  VID=$(jq -r '.id // ""' "$TMP/body" 2>/dev/null)
  STATUS=""
  for _ in $(seq 1 30); do
    req GET "/v1/videos/$VID" >/dev/null
    STATUS=$(jq -r '.status // ""' "$TMP/body" 2>/dev/null)
    [[ "$STATUS" == "completed" ]] && break
    sleep 0.2
  done
  [[ "$STATUS" == "completed" ]]
  check "GW1: create + poll its own task" "$?" "status=$STATUS id=$VID"

  # application.yml pins the default route's target-url, so GW2 overrides the
  # ROUTE (upstream.base-url only fills blank route urls).
  start "$ENGINE" "--gateway.mock.port=$MOCK_PORT_2" \
    "--gateway.routes[0].target-url=http://127.0.0.1:$MOCK_PORT_2/mock/v1"
  read -r CODE CTYPE < <(req GET "/v1/videos/$VID")
  [[ "$CODE" == "502" ]]
  check "GW2 cannot poll GW1's task (sticky pinning holds)" "$?" \
    "code=$CODE id=$VID on gw2"

  req POST /v1/videos '{"model":"sora","prompt":"gw2-own-task"}' >/dev/null
  VID2=$(jq -r '.id // ""' "$TMP/body" 2>/dev/null)
  STATUS=""
  for _ in $(seq 1 30); do
    req GET "/v1/videos/$VID2" >/dev/null
    STATUS=$(jq -r '.status // ""' "$TMP/body" 2>/dev/null)
    [[ "$STATUS" == "completed" ]] && break
    sleep 0.2
  done
  [[ "$STATUS" == "completed" ]]
  check "GW2 create + poll its own task via wildcard fallback" "$?" \
    "status=$STATUS id=$VID2"

  stop
done

echo "=========================================="
echo "PASS=$PASS FAIL=$FAIL"
[[ "$FAIL" -eq 0 ]]
