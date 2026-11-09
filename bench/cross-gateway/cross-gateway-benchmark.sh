#!/usr/bin/env bash
# Cross-gateway throughput benchmark: llmgw4j vs LiteLLM vs Bifrost (Docker),
# all proxying the SAME standalone mock upstream, measured with the SAME k6
# harness (bench/k6-json.js / bench/k6-sse.js, 50 VU, 30s).
#
# Every gateway serves the identical OpenAI-compatible request
# (model=qwen-plus, stream true/false) with identical auth work (static
# bearer). This is a RELATIVE THROUGHPUT comparison under one host — see
# bench/README.md data-quality rules: single-host numbers are directional.
#
# Usage: bash bench/cross-gateway/benchmark.sh [json|sse|both]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MODE="${1:-both}"
MOCK_PORT=9099
OUT="$ROOT/docs/verification/perf/cross-gateway"
mkdir -p "$OUT"
RESULTS="$OUT/results.txt"
: > "$RESULTS"

cleanup() {
  for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null; done
  docker rm -f bench-litellm bench-bifrost >/dev/null 2>&1
}
trap cleanup EXIT

wait_http() {
  for _ in $(seq 1 60); do
    curl --noproxy '*' -fsS "$1" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "FAILED to become healthy: $1" >&2
  return 1
}

declare -a PIDS=()

echo "== mock upstream :$MOCK_PORT =="
java -cp "$ROOT/bench/mock" "$ROOT/bench/mock/StandaloneMockUpstream.java" "$MOCK_PORT" \
  > "$OUT/mock.log" 2>&1 &
PIDS+=($!)
wait_http "http://127.0.0.1:$MOCK_PORT/v1/models" || exit 1

run_k6() { # name base key engine-tag
  local name="$1" base="$2" key="$3" tag="$4"
  for load in json sse; do
    [[ "$MODE" != "both" && "$load" != "$MODE" ]] && continue
    echo "--- $name [$load] ---" | tee -a "$RESULTS"
    local out
    out=$(ENGINE="$tag" RUNTIME=jvm BASE_URL="$base" API_KEY="$key" \
      k6 run --quiet "$ROOT/bench/k6-$load.js" 2>&1)
    echo "$out" | grep -E "iterations\.\.|http_req_duration\.\.|failure_rate\.\." | tee -a "$RESULTS"
  done
}

## llmgw4j
echo "== llmgw4j :8080 =="
java -jar "$ROOT/llmgw-app/target/llmgw-app-0.1.0-SNAPSHOT.jar" \
  --spring.profiles.active=webflux --server.port=8080 \
  '--gateway.routes[0].target-url=http://127.0.0.1:9099/v1' \
  --gateway.upstream.type=mock \
  --gateway.mock.enabled=false \
  --gateway.governance.rate-limit-tokens-per-second=2000000000 \
  --gateway.governance.rate-limit-burst=2000000000 \
  '--gateway.api-keys[0].quota-tokens=1000000000000' \
  > "$OUT/llmgw4j.log" 2>&1 &
PIDS+=($!)
wait_http "http://127.0.0.1:8080/actuator/health" || exit 1
run_k6 "llmgw4j" "http://127.0.0.1:8080" "sk-demo" "webflux"

## LiteLLM (docker)
echo "== LiteLLM :4000 =="
docker rm -f bench-litellm >/dev/null 2>&1
docker run -d --name bench-litellm -p 4000:4000 \
  -e LITELLM_MASTER_KEY=sk-demo \
  -v "$ROOT/bench/cross-gateway/litellm-config.yaml:/app/config.yaml:ro" \
  --add-host=host.docker.internal:host-gateway \
  litellm/litellm:latest --config /app/config.yaml --port 4000 \
  > "$OUT/litellm-container.log" 2>&1
wait_http "http://127.0.0.1:4000/health/liveliness" || { docker logs bench-litellm | tail -20 >&2; exit 1; }
run_k6 "LiteLLM" "http://127.0.0.1:4000" "sk-demo" "litellm"
docker rm -f bench-litellm >/dev/null

## Bifrost (docker) — SKIPPED for live measurement: the current image hangs
## in -config file mode on this host (tried /workspace and /app mounts; see
## docs/verification/perf/cross-gateway/README-bifrost.md). Its numbers below
## come from the project's own published benchmarks, cited in the report.
if false; then
echo "== Bifrost :9080 =="
docker rm -f bench-bifrost >/dev/null 2>&1
docker run -d --name bench-bifrost -p 9080:8080 \
  -v "$ROOT/bench/cross-gateway/bifrost-config.json:/app/config.json:ro" \
  --add-host=host.docker.internal:host-gateway \
  -e BIFROST_LOG_LEVEL=error \
  maximhq/bifrost:latest -config /app/config.json \
  > "$OUT/bifrost-container.log" 2>&1
wait_http "http://127.0.0.1:9080/v1/models" || { docker logs bench-bifrost | tail -20 >&2; exit 1; }
run_k6 "Bifrost" "http://127.0.0.1:9080" "sk-mock" "bifrost"
fi

echo "== benchmark complete =="
