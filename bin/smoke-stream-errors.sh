#!/usr/bin/env bash
# Reproducible regression for the streaming error contract + hot-path parsing.
# Usage: ./bin/smoke-stream-errors.sh [webflux|vt]   (default: both)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${LLMGW_TEST_JAR:-$ROOT/llmgw-app/target/llmgw-app-0.3.0.jar}"
PORT="${PORT:-8080}"
MOCK_PORT="${MOCK_PORT:-9090}"
GOOD_URL="http://127.0.0.1:$MOCK_PORT/mock/v1"
BAD_URL="http://127.0.0.1:19999/mock/v1"
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
  # BINARY=/path/to/native-llmgw-webflux lets the same suite validate the AOT
  # image; native images are compiled per engine profile.
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
    "$@" > "$TMP/gateway-$engine.log" 2>&1 &
  PID=$!
  wait_health
}

# call <body> [auth] -> prints "<status> <content-type>", body in $TMP/body
call() {
  # ${2-...} (not ${2:-...}) so an explicitly empty auth really omits the header.
  local body="$1" auth="${2-Bearer sk-demo}" out="$TMP/body"
  : > "$out"
  local -a args=(-s -o "$out" -w '%{http_code} %{content_type}'
    -X POST "http://127.0.0.1:$PORT/v1/chat/completions"
    -H 'Content-Type: application/json' -d "$body")
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

BODY_JSON='{"model":"qwen-plus","messages":[{"role":"user","content":"smoke json"}],"stream":false}'
BODY_SSE='{"model":"qwen-plus","messages":[{"role":"user","content":"smoke sse"}],"stream":true}'
BODY_MID='{"model":"qwen-plus","messages":[{"role":"user","content":"mock-fail-after-2"}],"stream":true}'
# Clean EOF without [DONE]: no exception is thrown, so only a real terminator
# check can tell this apart from a healthy stream.
BODY_EOF_EMPTY='{"model":"qwen-plus","messages":[{"role":"user","content":"mock-eof-after-0"}],"stream":true}'
BODY_EOF_MID='{"model":"qwen-plus","messages":[{"role":"user","content":"mock-eof-after-2"}],"stream":true}'
# v5 injections
BODY_STATUS_JSON='{"model":"qwen-plus","messages":[{"role":"user","content":"mock-status-429"}],"stream":false}'
BODY_STATUS_SSE='{"model":"qwen-plus","messages":[{"role":"user","content":"mock-status-500"}],"stream":true}'
BODY_DONE_FAIL='{"model":"qwen-plus","messages":[{"role":"user","content":"mock-done-then-fail"}],"stream":true}'
BODY_ECHO_A='{"model":"qwen-plus","messages":[{"role":"user","content":"mock-echo-hash"}],"temperature":0.1,"stream":false}'
BODY_ECHO_B='{"model":"qwen-plus","messages":[{"role":"user","content":"mock-echo-hash"}],"temperature":0.9,"stream":false}'
BIG_BODY="$(python3 -c 'print("{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"" + "x"*300 + "\"}]}")')"

FB_ARGS=(
  '--gateway.routes[0].id=primary-bad'
  '--gateway.routes[0].model=*'
  "--gateway.routes[0].target-url=$BAD_URL"
  '--gateway.routes[0].fallbacks[0]=good'
  '--gateway.routes[1].id=good'
  '--gateway.routes[1].model=__fallback_only__'
  "--gateway.routes[1].target-url=$GOOD_URL"
  '--gateway.routes[1].weight=1'
)
BAD_ARGS=("--gateway.routes[0].target-url=$BAD_URL")
# Primary fails after 2 chunks; a WRONG implementation would splice a second
# upstream's chunks into the already-open stream (tok0 would appear twice).
MIDFALLBACK_ARGS=(
  '--gateway.routes[0].id=primary-midfail'
  '--gateway.routes[0].model=*'
  "--gateway.routes[0].target-url=$GOOD_URL"
  '--gateway.routes[0].fallbacks[0]=second'
  '--gateway.routes[1].id=second'
  '--gateway.routes[1].model=__fallback_only__'
  "--gateway.routes[1].target-url=$GOOD_URL"
  '--gateway.routes[1].weight=1'
)

for ENGINE in $ENGINES; do
  echo "########## $ENGINE ##########"

  echo "-- normal paths (default mock upstream) --"
  start "$ENGINE"
  read -r CODE CTYPE < <(call "$BODY_JSON")
  BODY=$(cat "$TMP/body")
  [[ "$CODE" == "200" && "$CTYPE" == application/json* && "$BODY" == *'"content":"ok"'* ]]
  check "non-stream 200 + JSON body" "$?" "$CODE $CTYPE $(echo "$BODY" | head -c 60)"

  read -r CODE CTYPE < <(call "$BODY_SSE")
  LINES=$(grep -c '^data:' "$TMP/body" || true)
  DONE=$(grep -c '^data: \[DONE\]' "$TMP/body" || true)
  ERR_EVENT=$(grep -c '^event: error' "$TMP/body" || true)
  [[ "$CODE" == "200" && "$CTYPE" == text/event-stream* && "$LINES" -ge 10 && "$DONE" -eq 1 && "$ERR_EVENT" -eq 0 ]]
  check "SSE 200 + chunks + [DONE], no error event" "$?" "$CODE $CTYPE data_lines=$LINES done=$DONE err_event=$ERR_EVENT"

  read -r CODE CTYPE < <(call "$BODY_JSON" "")
  [[ "$CODE" == "401" ]]
  check "missing key -> 401" "$?" "$CODE $(cat "$TMP/body" | head -c 60)"

  read -r CODE CTYPE < <(call 'not-json')
  [[ "$CODE" == "400" ]]
  check "invalid json -> 400" "$?" "$CODE $(cat "$TMP/body" | head -c 60)"

  echo "-- upstream unreachable, NO fallback --"
  start "$ENGINE" "${BAD_ARGS[@]}"
  read -r CODE CTYPE < <(call "$BODY_JSON")
  [[ "$CODE" == "502" && "$CTYPE" == application/json* && "$(cat "$TMP/body")" == *'"error"'* ]]
  check "non-stream upstream failure -> 502 JSON" "$?" "$CODE $CTYPE $(cat "$TMP/body" | head -c 70)"

  read -r CODE CTYPE < <(call "$BODY_SSE")
  BODY=$(cat "$TMP/body")
  [[ "$CODE" == "502" && "$CTYPE" == application/json* && "$BODY" == *'upstream:'* ]]
  check "SSE failure before first chunk -> 502 JSON (no fake 200)" "$?" "$CODE $CTYPE $(echo "$BODY" | head -c 70)"

  echo "-- upstream unreachable, WITH fallback --"
  start "$ENGINE" "${FB_ARGS[@]}"
  read -r CODE CTYPE < <(call "$BODY_JSON")
  [[ "$CODE" == "200" && "$(cat "$TMP/body")" == *'"content":"ok"'* ]]
  check "non-stream fallback -> 200" "$?" "$CODE $(cat "$TMP/body" | head -c 50)"

  read -r CODE CTYPE < <(call "$BODY_SSE")
  DONE=$(grep -c '^data: \[DONE\]' "$TMP/body" || true)
  [[ "$CODE" == "200" && "$DONE" -eq 1 ]]
  check "SSE fallback -> 200 + [DONE]" "$?" "$CODE done=$DONE"

  echo "-- failure AFTER the first chunk --"
  start "$ENGINE"
  read -r CODE CTYPE < <(call "$BODY_MID")
  ERR_EVENT=$(grep -c '^event: error' "$TMP/body" || true)
  DONE=$(grep -c '^data: \[DONE\]' "$TMP/body" || true)
  [[ "$CODE" == "200" && "$CTYPE" == text/event-stream* && "$ERR_EVENT" -eq 1 && "$DONE" -eq 1 ]]
  check "mid-stream failure -> in-band error event + [DONE]" "$?" "$CODE $CTYPE err_event=$ERR_EVENT done=$DONE"

  start "$ENGINE" "${MIDFALLBACK_ARGS[@]}"
  read -r CODE CTYPE < <(call "$BODY_MID")
  TOK0=$(grep -c '"content":"tok0"' "$TMP/body" || true)
  ERR_EVENT=$(grep -c '^event: error' "$TMP/body" || true)
  [[ "$CODE" == "200" && "$TOK0" -eq 1 && "$ERR_EVENT" -eq 1 ]]
  check "no fallback splicing after first chunk" "$?" "$CODE tok0=$TOK0 err_event=$ERR_EVENT"

  echo "-- silent truncation: clean EOF, no [DONE], no exception thrown --"
  start "$ENGINE"
  # Warm the ledger with one healthy stream so the failure delta is visible.
  call "$BODY_SSE" >/dev/null

  read -r CODE CTYPE < <(call "$BODY_EOF_EMPTY")
  BODY=$(cat "$TMP/body")
  [[ "$CODE" == "502" && "$CTYPE" == application/json* && "$BODY" == *'without [DONE]'* ]]
  check "zero-frame EOF -> 502 JSON (not fabricated success)" "$?" "$CODE $CTYPE $(echo "$BODY" | head -c 80)"

  read -r CODE CTYPE < <(call "$BODY_EOF_MID")
  ERR_EVENT=$(grep -c '^event: error' "$TMP/body" || true)
  DONE=$(grep -c '^data: \[DONE\]' "$TMP/body" || true)
  TOK0=$(grep -c '"content":"tok0"' "$TMP/body" || true)
  REASON=$(grep -c 'without \[DONE\]' "$TMP/body" || true)
  [[ "$CODE" == "200" && "$CTYPE" == text/event-stream* && "$ERR_EVENT" -eq 1 \
     && "$DONE" -eq 1 && "$TOK0" -eq 1 && "$REASON" -ge 1 ]]
  check "mid-stream EOF -> in-band error + single [DONE]" "$?" \
    "$CODE $CTYPE err_event=$ERR_EVENT done=$DONE tok0=$TOK0 reason=$REASON"

  start "$ENGINE" "${MIDFALLBACK_ARGS[@]}"
  read -r CODE CTYPE < <(call "$BODY_EOF_MID")
  TOK0=$(grep -c '"content":"tok0"' "$TMP/body" || true)
  ERR_EVENT=$(grep -c '^event: error' "$TMP/body" || true)
  [[ "$CODE" == "200" && "$TOK0" -eq 1 && "$ERR_EVENT" -eq 1 ]]
  check "EOF truncation never triggers fallback splicing" "$?" "$CODE tok0=$TOK0 err_event=$ERR_EVENT"

  # Ledger: 1 success + 2 truncations must be billed as >=2 failures.
  start "$ENGINE"
  call "$BODY_SSE" >/dev/null
  call "$BODY_EOF_EMPTY" >/dev/null
  call "$BODY_EOF_MID" >/dev/null
  sleep 0.4
  SNAP_CODE=$(curl --noproxy '*' -s -o "$TMP/snapshot" -w '%{http_code}' \
    "http://127.0.0.1:$PORT/admin/snapshot")
  SNAP=$(cat "$TMP/snapshot")
  # A native image with missing record reflection hints answers 500 here.
  # Assert the endpoint itself, not just the absence of an empty string.
  [[ "$SNAP_CODE" == "200" && "$SNAP" == *'"usage"'* ]]
  check "admin snapshot 200 + usage (native AOT hints)" "$?" \
    "$SNAP_CODE $(echo "$SNAP" | head -c 60)"
  ERRS=$(printf '%s' "$SNAP" | jq -r '[.usage[]?.errors // 0] | add // 0' 2>/dev/null)
  if ! [[ "$ERRS" =~ ^[0-9]+$ ]]; then
    ERRS=$(printf '%s' "$SNAP" | grep -o '"errors":[0-9]*' | head -1 | cut -d: -f2)
  fi
  [[ "${ERRS:-0}" -ge 2 ]]
  check "truncated streams billed as failures" "$?" "errors=$ERRS (expect >=2)"

  # ---------------------------------------------------------------------
  # v5 hardening: upstream status, completion boundary, cache key, exact
  # metering, admin surface, request-size cap, snapshot restore, cancel.
  # ---------------------------------------------------------------------
  echo "-- v5: non-2xx upstream never becomes SSE data --"
  start "$ENGINE"
  read -r CODE CTYPE < <(call "$BODY_STATUS_JSON")
  BODY=$(cat "$TMP/body")
  [[ "$CODE" == "502" && "$CTYPE" == application/json* && "$BODY" == *'upstream status 429'* ]]
  check "429 upstream -> 502 JSON with status detail" "$?" "$CODE $CTYPE $(echo "$BODY" | head -c 90)"

  read -r CODE CTYPE < <(call "$BODY_STATUS_SSE")
  BODY=$(cat "$TMP/body")
  [[ "$CODE" == "502" && "$CTYPE" == application/json* && "$BODY" == *'upstream status 500'* \
     && "$BODY" != *'data:'* ]]
  check "500 upstream on SSE -> 502 JSON, no error body leaked as data" "$?" \
    "$CODE $CTYPE $(echo "$BODY" | head -c 90)"

  echo "-- v5: [DONE] followed by disconnect is still a success --"
  start "$ENGINE"
  read -r CODE CTYPE < <(call "$BODY_DONE_FAIL")
  DONE=$(grep -c '^data: \[DONE\]' "$TMP/body" || true)
  ERR_EVENT=$(grep -c '^event: error' "$TMP/body" || true)
  [[ "$CODE" == "200" && "$DONE" -eq 1 && "$ERR_EVENT" -eq 0 ]]
  check "late disconnect after [DONE] -> no spurious error event" "$?" \
    "$CODE done=$DONE err_event=$ERR_EVENT"
  sleep 0.4
  DONE_ERRORS=$(curl --noproxy '*' -s "http://127.0.0.1:$PORT/admin/snapshot" \
    | jq -r '[.usage[]?.errors // 0] | add // 0' 2>/dev/null)
  [[ "${DONE_ERRORS:-1}" == "0" ]]
  check "completed stream billed as success" "$?" "errors=$DONE_ERRORS (expect 0)"

  echo "-- v5: cache key covers the whole request --"
  start "$ENGINE" "--gateway.governance.snapshot-path=$TMP/snap-cache-$ENGINE.json"
  call "$BODY_ECHO_A" >/dev/null
  ANSWER_A=$(cat "$TMP/body")
  sleep 0.35   # let the virtual-thread side-car finish writing the cache
  call "$BODY_ECHO_B" >/dev/null
  ANSWER_B=$(cat "$TMP/body")
  [[ "$ANSWER_A" == *'"content":"echo-'* && "$ANSWER_B" == *'"content":"echo-'* \
     && "$ANSWER_A" != "$ANSWER_B" ]]
  check "different temperature never hits the other request's cache" "$?" \
    "A=$(echo "$ANSWER_A" | grep -o 'echo-[0-9a-f]*') B=$(echo "$ANSWER_B" | grep -o 'echo-[0-9a-f]*')"
  CACHE_SIZE=$(curl --noproxy '*' -s "http://127.0.0.1:$PORT/admin/snapshot" \
    | jq -r '.cacheSize // 0' 2>/dev/null)
  [[ "${CACHE_SIZE:-0}" -ge 1 ]]
  check "cache actually populated (so the miss was real)" "$?" "cacheSize=$CACHE_SIZE"

  echo "-- v5: exact completion-token metering --"
  start "$ENGINE" "--gateway.governance.snapshot-path=$TMP/snap-meter-$ENGINE.json"
  call "$BODY_JSON" >/dev/null
  call "$BODY_SSE" >/dev/null
  sleep 0.5
  METER=$(curl --noproxy '*' -s "http://127.0.0.1:$PORT/admin/snapshot")
  CT=$(printf '%s' "$METER" | jq -r '.usage.demo.completionTokens // -1' 2>/dev/null)
  REQ=$(printf '%s' "$METER" | jq -r '.usage.demo.requests // -1' 2>/dev/null)
  ERR=$(printf '%s' "$METER" | jq -r '.usage.demo.errors // -1' 2>/dev/null)
  [[ "$CT" == "9" && "$REQ" == "2" && "$ERR" == "0" ]]
  check "usage chunk replaces delta estimate (1 + 8 = 9)" "$?" \
    "completionTokens=$CT requests=$REQ errors=$ERR"

  echo "-- v5: admin surface (token gate + no upstream key leak) --"
  start "$ENGINE" '--gateway.admin-token=secret' \
    "--gateway.governance.snapshot-path=$TMP/snap-admin-$ENGINE.json"
  NO_TOKEN=$(curl --noproxy '*' -s -o "$TMP/routes-no" -w '%{http_code}' \
    "http://127.0.0.1:$PORT/admin/routes")
  YES_TOKEN=$(curl --noproxy '*' -s -o "$TMP/routes-yes" -w '%{http_code}' \
    -H 'X-Admin-Token: secret' "http://127.0.0.1:$PORT/admin/routes")
  ROUTES=$(cat "$TMP/routes-yes")
  [[ "$NO_TOKEN" == "401" && "$YES_TOKEN" == "200" \
     && "$ROUTES" == *'apiKeyConfigured'* && "$ROUTES" != *'"apiKey":'* \
     && ( -z "${DASHSCOPE_API_KEY:-}" || "$ROUTES" != *"$DASHSCOPE_API_KEY"* ) ]]
  check "admin token gates /admin/routes and key is redacted" "$?" \
    "no_token=$NO_TOKEN with_token=$YES_TOKEN $(echo "$ROUTES" | head -c 90)"

  echo "-- v5: request body cap + idempotent snapshot restore --"
  start "$ENGINE" '--gateway.max-request-bytes=128' \
    "--gateway.governance.snapshot-path=$TMP/snap-load-$ENGINE.json"
  read -r CODE CTYPE < <(call "$BIG_BODY")
  [[ "$CODE" == "413" ]]
  check "oversized request -> 413" "$?" "$CODE $CTYPE"

  start "$ENGINE" "--gateway.governance.snapshot-path=$TMP/snap-load-$ENGINE.json"
  call "$BODY_JSON" >/dev/null
  sleep 0.4
  curl --noproxy '*' -s -X POST "http://127.0.0.1:$PORT/admin/snapshot/save" >/dev/null
  curl --noproxy '*' -s -X POST "http://127.0.0.1:$PORT/admin/snapshot/load" >/dev/null
  curl --noproxy '*' -s -X POST "http://127.0.0.1:$PORT/admin/snapshot/load" >/dev/null
  sleep 0.3
  LOADED=$(curl --noproxy '*' -s "http://127.0.0.1:$PORT/admin/snapshot" \
    | jq -r '.usage.demo.requests // -1' 2>/dev/null)
  [[ "$LOADED" == "1" ]]
  check "repeated snapshot/load replaces instead of doubling" "$?" "requests=$LOADED (expect 1)"

  echo "-- v5: client disconnect still settles the ledger --"
  start "$ENGINE" "--gateway.governance.snapshot-path=$TMP/snap-cancel-$ENGINE.json"
  curl --noproxy '*' --max-time 0.06 -s -o /dev/null \
    -X POST "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-demo' \
    -d "$BODY_SSE" >/dev/null 2>&1 || true
  sleep 0.6
  CANCEL_SNAP=$(curl --noproxy '*' -s "http://127.0.0.1:$PORT/admin/snapshot")
  C_REQ=$(printf '%s' "$CANCEL_SNAP" | jq -r '[.usage[]?.requests // 0] | add // 0' 2>/dev/null)
  C_ERR=$(printf '%s' "$CANCEL_SNAP" | jq -r '[.usage[]?.errors // 0] | add // 0' 2>/dev/null)
  [[ "${C_REQ:-0}" -ge 1 && "${C_ERR:-0}" -ge 1 ]]
  check "aborted stream is billed as a failed request" "$?" "requests=$C_REQ errors=$C_ERR"

  # Regression guard for the v5 cancellation bug: a normal stream completion
  # must NOT cancel the shared HTTP/1.1 connection. Serial curls never expose
  # it -- only real concurrency on a keep-alive connection does.
  echo "-- v5: concurrent SSE must not cancel sibling upstream requests --"
  start "$ENGINE" "--gateway.governance.snapshot-path=$TMP/snap-conc-$ENGINE.json"
  CONC="$TMP/conc-$ENGINE"
  mkdir -p "$CONC"
  seq 1 120 | D="$CONC" BODY="$BODY_SSE" PORT="$PORT" \
    xargs -P 40 -n 1 bash -c '
      curl --noproxy "*" -s -o "$D/b$1" -w "%{http_code}" \
        -X POST "http://127.0.0.1:$PORT/v1/chat/completions" \
        -H "Content-Type: application/json" -H "Authorization: Bearer sk-demo" \
        -d "$BODY" > "$D/c$1"
    ' _
  BAD=$(grep -L '^200$' "$CONC"/c* | wc -l | tr -d ' ')
  CANCELLED=$(grep -l 'Request cancelled' "$CONC"/b* 2>/dev/null | wc -l | tr -d ' ')
  [[ "$BAD" -eq 0 && "$CANCELLED" -eq 0 ]]
  check "40-way concurrent SSE: zero 502 / zero Request cancelled" "$?" \
    "non200=$BAD cancelled=$CANCELLED"

  echo "-- v6: correlation id echoes, generates, and reaches audit --"
  start "$ENGINE" "--gateway.governance.snapshot-path=$TMP/snap-reqid-$ENGINE.json"
  hdr_of() {
    curl --noproxy '*' -s -D - -o /dev/null \
      -X POST "http://127.0.0.1:$PORT/v1/chat/completions" \
      -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-demo' \
      "$@" | tr -d '\r' | awk 'tolower($1)=="x-request-id:"{print $2}'
  }
  ECHO_HDR=$(hdr_of -H 'X-Request-Id: smoke-req-42' -d "$BODY_JSON")
  [[ "$ECHO_HDR" == "smoke-req-42" ]]
  check "incoming X-Request-Id echoed in response" "$?" "got=$ECHO_HDR"
  GEN_HDR=$(hdr_of -d "$BODY_JSON")
  [[ "$GEN_HDR" == gw-* ]]
  check "absent X-Request-Id gets a generated gw- id" "$?" "got=$GEN_HDR"
  hdr_of -H 'X-Request-Id: smoke-audit-77' -d "$BODY_JSON" >/dev/null
  sleep 0.5
  AUDIT_HIT=$(curl --noproxy '*' -s "http://127.0.0.1:$PORT/admin/snapshot" \
    | grep -c 'smoke-audit-77' || true)
  [[ "${AUDIT_HIT:-0}" -ge 1 ]]
  check "request id reaches audit events" "$?" "matches=$AUDIT_HIT"

  echo "-- v6: circuit breaker opens and falls back without network I/O --"
  start "$ENGINE" "--gateway.governance.snapshot-path=$TMP/snap-cb-$ENGINE.json" \
    '--gateway.governance.circuit-failure-threshold=2' \
    '--gateway.governance.circuit-cooldown-ms=60000' \
    '--gateway.routes[0].id=cb-primary' \
    '--gateway.routes[0].model=*' \
    "--gateway.routes[0].target-url=$BAD_URL" \
    '--gateway.routes[0].weight=1' \
    '--gateway.routes[0].fallbacks[0]=cb-backup' \
    '--gateway.routes[1].id=cb-backup' \
    '--gateway.routes[1].model=__cb_only__' \
    "--gateway.routes[1].target-url=$GOOD_URL" \
    '--gateway.routes[1].weight=1'
  # Unique bodies: identical bodies would be swallowed by the semantic cache
  # and never reach the upstream, so the breaker would never see failures.
  cb_call() {
    curl --noproxy '*' -s -o "$TMP/cb-body" -w '%{http_code}' \
      -X POST "http://127.0.0.1:$PORT/v1/chat/completions" \
      -H 'Content-Type: application/json' -H 'Authorization: Bearer sk-demo' \
      -d "{\"model\":\"qwen-plus\",\"messages\":[{\"role\":\"user\",\"content\":\"cb-$1\"}],\"stream\":false}"
  }
  S1=$(cb_call 1); S2=$(cb_call 2); S3=$(cb_call 3)
  OPEN_EVENTS=$(curl --noproxy '*' -s "http://127.0.0.1:$PORT/admin/metrics" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(sum(1 for c in d.get('counters',[]) if c['name']=='llmgw.circuit' and 'event=open' in c['tags']))" 2>/dev/null || echo 0)
  [[ "${S1%% *}" == 200 && "${S2%% *}" == 200 && "${S3%% *}" == 200 && "${OPEN_EVENTS:-0}" -ge 1 ]]
  check "2 failures open the circuit; 3rd request served via fallback" "$?" \
    "s1=${S1%% *} s2=${S2%% *} s3=${S3%% *} open_events=$OPEN_EVENTS"
  CB_LEAK=$(printf '%s' "$S1" | grep -c 'circuit open' || true)
  [[ "${CB_LEAK:-0}" -eq 0 ]]
  check "CircuitOpenException never leaks to the client" "$?" "leak=$CB_LEAK"

  echo "-- v6: weighted random routing follows configured weights --"
  start "$ENGINE" "--gateway.governance.snapshot-path=$TMP/snap-w-$ENGINE.json" \
    '--gateway.routes[0].id=w-light' \
    '--gateway.routes[0].model=*' \
    "--gateway.routes[0].target-url=$GOOD_URL" \
    '--gateway.routes[0].weight=1' \
    '--gateway.routes[1].id=w-heavy' \
    '--gateway.routes[1].model=*' \
    "--gateway.routes[1].target-url=$GOOD_URL" \
    '--gateway.routes[1].weight=3'
  seq 1 200 | PORT="$PORT" BODY_JSON="$BODY_JSON" xargs -P 20 -n 1 bash -c '
    curl --noproxy "*" -s -o /dev/null \
      -X POST "http://127.0.0.1:$PORT/v1/chat/completions" \
      -H "Content-Type: application/json" -H "Authorization: Bearer sk-demo" \
      -d "$BODY_JSON"'
  DIST=$(curl --noproxy '*' -s "http://127.0.0.1:$PORT/admin/metrics" | python3 -c "
import sys, json
d = json.load(sys.stdin)
counts = {}
for c in d.get('counters', []):
    if c['name'] == 'llmgw.requests':
        route = dict(kv.split('=', 1) for kv in c['tags'].split(','))['route']
        counts[route] = int(c['value'])
light = counts.get('w-light', 0)
heavy = counts.get('w-heavy', 0)
total = light + heavy
print(f'{light} {heavy} {light/total:.3f} {heavy/total:.3f}' if total else '0 0 0 0')
" 2>/dev/null || echo "0 0 0 0")
  read -r WL WH PLOT PHEAVY <<< "$DIST"
  OK_DIST=$(python3 -c "
print(1 if abs($PLOT - 0.25) <= 0.12 and abs($PHEAVY - 0.75) <= 0.12 else 0)")
  [[ "$OK_DIST" == "1" && "$WL" -gt 0 && "$WH" -gt 0 ]]
  check "weighted routing distributes ~1:3 (tolerance +/-12pp)" "$?" \
    "w-light=$WL($PLOT) w-heavy=$WH($PHEAVY)"

  stop
done

echo "########## static check: single JSON parse on hot path ##########"
PARSE_IN_CONTROLLERS=$(grep -c 'ProtocolCodec.parse' \
  "$ROOT/llmgw-engine/llmgw-engine-webflux/src/main/java/io/github/leohou0430/llmgw/engine/webflux/WebfluxGatewayController.java" \
  "$ROOT/llmgw-engine/llmgw-engine-vt/src/main/java/io/github/leohou0430/llmgw/engine/vt/VirtualThreadGatewayController.java" \
  2>/dev/null | awk -F: '{s += $2} END {print s + 0}')
[[ "$PARSE_IN_CONTROLLERS" -eq 0 ]]
check "controllers reuse decision.parsed()" "$?" "ProtocolCodec.parse occurrences in controllers=$PARSE_IN_CONTROLLERS"

echo "=========================================="
echo "PASS=$PASS FAIL=$FAIL"
[[ "$FAIL" -eq 0 ]]
