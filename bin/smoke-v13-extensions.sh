#!/usr/bin/env bash
# v13 extensions regression: W4.2 semantic cache (embedding-bucket keys) and
# W4.3 protocol-surface expansion (embeddings / rerank / audio speech).
# Usage: ./bin/smoke-v13-extensions.sh [webflux|vt]   (default: both)
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
  local -a defaults=("--gateway.mock.port=$MOCK_PORT"
    "--gateway.upstream.base-url=http://127.0.0.1:$MOCK_PORT/mock/v1")
  local target="http://127.0.0.1:$MOCK_PORT/mock/v1"
  local arg
  for arg in "$@"; do
    [[ "$arg" == '--gateway.routes[0].target-url='* ]] && target=""
  done
  [[ -z "$target" ]] || defaults+=("--gateway.routes[0].target-url=$target")
  java -jar "$JAR" --spring.profiles.active="$engine" --server.port="$PORT" "${defaults[@]}" \
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

post() { # path body [key]
  curl -s --noproxy '*' -X POST "http://127.0.0.1:$PORT$1" \
    -H "Authorization: Bearer ${3:-sk-demo}" -H 'Content-Type: application/json' -d "$2"
}

echo "======== v13 extensions: semantic cache + new protocols ========"

for engine in $ENGINES; do
  echo "--- engine=$engine -- W4.2 semantic cache ---"
  start "$engine" \
    --gateway.governance.cache-max-size=100 \
    --gateway.governance.cache-ttl-seconds=300 \
    "--gateway.cache.semantic-url=http://127.0.0.1:$MOCK_PORT/mock/v1/embeddings" \
    '--gateway.cache.semantic-threshold=0.85'

  # Reordered text may share a semantic entry only with identical sampling parameters.
  a=$(post "/v1/chat/completions" '{"model":"m","temperature":0.1,"messages":[{"content":"the quick brown fox jumps over the lazy dog"}]}')
  assert_contains "$a" '"object":"chat.completion"' "$engine semantic: request A served"
  sleep 0.1
  b=$(post "/v1/chat/completions" '{"model":"m","temperature":0.1,"messages":[{"content":"dog lazy the over jumps fox brown quick the"}]}')
  assert_contains "$b" '"object":"chat.completion"' "$engine semantic: reordered request B served"
  snap=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/admin/snapshot")
  assert_contains "$snap" '"cacheSize":1' "$engine semantic: both bodies share ONE cache entry"
  different=$(post "/v1/chat/completions" '{"model":"m","temperature":0.9,"messages":[{"content":"dog lazy the over jumps fox brown quick the"}]}')
  sleep 0.1
  snap=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/admin/snapshot")
  assert_contains "$snap" '"cacheSize":2' "$engine different sampling has its own cache entry"
  # A genuinely different question must NOT share the entry.
  c=$(post "/v1/chat/completions" '{"model":"m","messages":[{"content":"zebra unicorn gallops through neon rainbows"}]}')
  snap=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/admin/snapshot")
  assert_contains "$snap" '"cacheSize":3' "$engine semantic: a different question gets its own entry"
  stop

  echo "--- engine=$engine -- W4.3 new protocols ---"
  start "$engine"

  body=$(post "/v1/embeddings" '{"model":"embed","input":"hello world"}')
  assert_contains "$body" '"object":"list"' "$engine embeddings pass-through served"
  body=$(post "/v1/rerank" '{"model":"rerank","query":"q","documents":["a","b"]}')
  assert_contains "$body" '"relevance_score"' "$engine rerank pass-through served"

  code=$(curl -s --noproxy '*' -o "$TMP/speech.bin" -w '%{http_code}' -X POST \
    "http://127.0.0.1:$PORT/v1/audio/speech" \
    -H 'Authorization: Bearer sk-demo' -H 'Content-Type: application/json' \
    -d '{"model":"tts","input":"hello","voice":"alloy"}')
  ctype=$(file -b --mime-type "$TMP/speech.bin" 2>/dev/null || echo unknown)
  bytes=$(wc -c < "$TMP/speech.bin" | tr -d ' ')
  [[ "$code" == "200" && "$bytes" -gt 100 ]] && ok "$engine speech binary pass-through ($bytes bytes)" \
    || bad "$engine speech expected 200 + bytes, got $code / $bytes bytes"

  # B10: context-overflow errors skip same-route retries and fall through to
  # the fallback chain (with a single route: clean 502 naming the upstream).
  body=$(post "/v1/chat/completions" '{"model":"m","messages":[{"content":"probe mock-status-400 mock-context-overflow"}]}')
  assert_contains "$body" 'maximum context length' "$engine context-overflow surfaces the upstream reason"

  # Governance covers every new endpoint.
  code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST \
    "http://127.0.0.1:$PORT/v1/embeddings" -H 'Content-Type: application/json' \
    -d '{"model":"e","input":"x"}')
  [[ "$code" == "401" ]] && ok "$engine embeddings without key -> 401" \
    || bad "$engine embeddings without key expected 401, got $code"
  code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -X POST \
    "http://127.0.0.1:$PORT/v1/audio/speech" -H 'Content-Type: application/json' \
    -d '{"model":"t","input":"x"}')
  [[ "$code" == "401" ]] && ok "$engine speech without key -> 401" \
    || bad "$engine speech without key expected 401, got $code"

  # Protocol routing: new kinds resolve their own routes (metric tag style).
  snap=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/admin/metrics")
  assert_contains "$snap" 'protocol=embeddings' "$engine embeddings metered under its protocol"

  # B11: models catalog / batch cancel / file content pass-through.
  body=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/v1/models" -H 'Authorization: Bearer sk-demo')
  assert_contains "$body" '"id":"qwen-plus"' "$engine GET /v1/models catalog pass-through"

  echo 'test-owned-file' > "$TMP/owned.txt"
  uploaded=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$PORT/v1/files" \
    -H 'Authorization: Bearer sk-demo' -F purpose=batch -F file=@"$TMP/owned.txt")
  ownedId=$(printf '%s' "$uploaded" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
  batch=$(post "/v1/batches" "{\"model\":\"m\",\"input_file_id\":\"$ownedId\"}")
  batchId=$(printf '%s' "$batch" | grep -o '"id":"batch-[0-9]*"' | head -1 | cut -d'"' -f4)
  body=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$PORT/v1/batches/$batchId/cancel" \
    -H 'Authorization: Bearer sk-demo')
  assert_contains "$body" '"status":"cancelled"' "$engine batch cancel pass-through (sticky)"

  code=$(curl -s --noproxy '*' -o "$TMP/file-content.bin" -w '%{http_code}' \
    "http://127.0.0.1:$PORT/v1/files/$ownedId/content" -H 'Authorization: Bearer sk-demo')
  bytes=$(wc -c < "$TMP/file-content.bin" | tr -d ' ')
  [[ "$code" == "200" && "$bytes" -gt 100 ]] && ok "$engine file content pass-through ($bytes bytes)" \
    || bad "$engine file content expected 200 + bytes, got $code / $bytes"

  # B8: cache invalidation endpoint + X-LLMGW-No-Cache bypass header.
  a=$(post "/v1/chat/completions" '{"model":"m","messages":[{"content":"inv probe alpha"}]}')
  assert_contains "$a" '"object":"chat.completion"' "$engine cache fill before invalidation"
  cleared=$(curl -s --noproxy '*' -X DELETE "http://127.0.0.1:$PORT/admin/cache")
  assert_contains "$cleared" '"invalidated":true' "$engine DELETE /admin/cache clears the store"
  snap=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/admin/snapshot")
  assert_contains "$snap" '"cacheSize":0' "$engine cacheSize is 0 after invalidation"
  b=$(post "/v1/chat/completions" '{"model":"m","messages":[{"content":"nocache bypass probe"}]}' )
  c=$(post "/v1/chat/completions" '{"model":"m","messages":[{"content":"nocache bypass probe"}]}' )
  c2=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$PORT/v1/chat/completions" \
    -H 'Authorization: Bearer sk-demo' -H 'Content-Type: application/json' \
    -H 'X-LLMGW-No-Cache: 1' \
    -d '{"model":"m","messages":[{"content":"nocache bypass probe"}]}')
  snap=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/admin/snapshot")
  assert_contains "$snap" '"cacheSize":1' "$engine no-cache request never enters the store"

  # W4.3 completion: multipart upload / files lifecycle / batches sticky job.
  echo "fake-audio-pcm-data-here" > "$TMP/audio.wav"
  body=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$PORT/v1/audio/transcriptions" \
    -H "Authorization: Bearer sk-demo" \
    -F model=whisper-1 -F file=@"$TMP/audio.wav")
  assert_contains "$body" '"text":"mock transcript' "$engine transcriptions multipart upload served"
  assert_contains "$body" '"model":"whisper-1"' "$engine transcriptions probe extracted the model field"

  body=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$PORT/v1/files" \
    -H "Authorization: Bearer sk-demo" \
    -F purpose=fine-tune -F file=@"$TMP/audio.wav")
  assert_contains "$body" '"object":"file"' "$engine file upload served"
  fileId=$(printf '%s' "$body" | grep -o '"id":"file-[0-9]*"' | head -1 | cut -d'"' -f4)
  body=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/v1/files/$fileId" \
    -H 'Authorization: Bearer sk-demo')
  assert_contains "$body" "\"id\":\"$fileId\"" "$engine file retrieve reaches the uploading upstream"
  body=$(curl -s --noproxy '*' -X DELETE "http://127.0.0.1:$PORT/v1/files/$fileId" \
    -H 'Authorization: Bearer sk-demo')
  assert_contains "$body" '"deleted":true' "$engine file DELETE pass-through served"

  body=$(post "/v1/batches" "{\"model\":\"m\",\"input_file_id\":\"$ownedId\"}")
  assert_contains "$body" '"object":"batch"' "$engine batch create served"
  batchId=$(printf '%s' "$body" | grep -o '"id":"batch-[0-9]*"' | head -1 | cut -d'"' -f4)
  body=$(curl -s --noproxy '*' "http://127.0.0.1:$PORT/v1/batches/$batchId" \
    -H 'Authorization: Bearer sk-demo')
  assert_contains "$body" "\"id\":\"$batchId\"" "$engine batch poll is sticky-routed to the creating upstream"
  code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' \
    -H 'Authorization: Bearer sk-demo' "http://127.0.0.1:$PORT/v1/batches/batch-unknown")
  [[ "$code" == "404" ]] && ok "$engine unknown batch id -> 404 without upstream access" \
    || bad "$engine unknown batch id expected 404, got $code"
  stop
done

echo "================================================="
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
