#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/llmgw-app/target/llmgw-app-0.1.0-SNAPSHOT.jar"
NATIVE_WEBFLUX="${NATIVE_WEBFLUX:-$ROOT/bin/native/llmgw-webflux}"
NATIVE_VT="${NATIVE_VT:-$ROOT/bin/native/llmgw-vt}"
RESULTS="$ROOT/bench/results"
mkdir -p "$RESULTS"

RUN_ID="$(date +%Y%m%d-%H%M%S)"
VUS="${VUS:-50}"
DURATION="${DURATION:-30s}"
WARMUP_DURATION="${WARMUP_DURATION:-5s}"
ROUNDS="${ROUNDS:-3}"
PORT="${PORT:-8080}"
MOCK_PORT="${MOCK_PORT:-9090}"
# Upstream delay profile. Defaults (20ms + 8x10ms) reproduce the v1..v4
# baseline: ~100ms of fixed upstream time that deliberately drowns gateway
# overhead. Set both to 0 for the separate "wire" experiment, which measures
# the gateway itself. NEVER compare a wire CSV with a baseline CSV row-by-row:
# the upstream profile is a different variable, not a version improvement.
MOCK_DELAY_MS="${MOCK_DELAY_MS:-20}"
MOCK_CHUNK_INTERVAL_MS="${MOCK_CHUNK_INTERVAL_MS:-10}"
# Realistic-prompt profile: pads each k6 request body with this many bytes.
# 0 is the historical wire profile (byte-identical payload); >0 models a
# RAG-sized prompt and is a SEPARATE experiment, never mixed with 0 in a table.
export PAYLOAD_BYTES="${PAYLOAD_BYTES:-0}"
LOG="$RESULTS/gateway-$RUN_ID.log"
CSV="$RESULTS/summary-$RUN_ID.csv"

# Which JVM binary to launch. Override for cross-distribution GC experiments
# (e.g. Temurin for Shenandoah):  JAVA_BIN=.../Temurin/.../bin/java ./bin/bench-matrix.sh
JAVA_BIN="${JAVA_BIN:-java}"

# Which arms of the matrix to run. Defaults keep the full 2 x 2 x 2 matrix;
# override for a smoke test or to reproduce one case, e.g.
#   ENGINES=webflux GOVS=on RUNTIMES=native MODES=json DURATION=8s ROUNDS=1 ./bin/bench-matrix.sh
ENGINES="${ENGINES:-webflux vt}"
GOVS="${GOVS:-on off}"
RUNTIMES="${RUNTIMES:-jvm native}"
MODES="${MODES:-json sse}"

# ---------------------------------------------------------------------------
# Benchmark-only admission overrides.
#
# The demo defaults (quota 1e6, 1e5 tokens/s, burst 2e5) are correct for a
# demo, but a 50-VU / 30s run burns through them within seconds. With
# governance ON the arm then degrades into measuring 429-rejection latency
# (p50 ~0.25ms, ~24k rps, error_rate=1) instead of governance cost -- see the
# invalid summary-20261106-075524.csv.
#
# These overrides make the admission thresholds UNREACHABLE for the whole
# matrix. Every governance step still executes (API-key lookup, quota compare,
# atomic token-bucket CAS, cache lookup, virtual-thread side-car billing /
# audit / metrics); it simply never denies. They are passed to EVERY arm, so
# gateway.governance-enabled remains the only variable between on and off.
# application.yml keeps its small demo defaults on purpose.
# ---------------------------------------------------------------------------
BENCH_DEFAULT_QUOTA="${BENCH_DEFAULT_QUOTA:-2000000000}"
BENCH_KEY_QUOTA="${BENCH_KEY_QUOTA:-1000000000000}"
BENCH_LIMIT_QPS="${BENCH_LIMIT_QPS:-2000000000}"
BENCH_LIMIT_BURST="${BENCH_LIMIT_BURST:-2000000000}"
BENCH_ARGS=(
  "--gateway.governance.default-quota-tokens=$BENCH_DEFAULT_QUOTA"
  "--gateway.governance.rate-limit-tokens-per-second=$BENCH_LIMIT_QPS"
  "--gateway.governance.rate-limit-burst=$BENCH_LIMIT_BURST"
  "--gateway.api-keys[0].quota-tokens=$BENCH_KEY_QUOTA"
  "--gateway.mock.delay-ms=$MOCK_DELAY_MS"
  "--gateway.mock.chunk-interval-ms=$MOCK_CHUNK_INTERVAL_MS"
)

PID=""
RSS_FILE=""

start_gateway() {
  local engine="$1" gov="$2" runtime="$3"
  : > "$LOG"
  local -a cmd
  if [[ "$runtime" == "native" ]]; then
    if [[ "$engine" == "webflux" ]]; then
      cmd=("$NATIVE_WEBFLUX")
    else
      cmd=("$NATIVE_VT")
    fi
  else
    # JVM_OPTS (word-split list of JVM flags) enables GC A/B experiments, e.g.
    #   JVM_OPTS="-XX:+UseZGC" ./bin/bench-matrix.sh ...
    # Must be quoted as a single word per flag when exporting.
    if [[ -n "${JVM_OPTS:-}" ]]; then
      # shellcheck disable=SC2086
      cmd=("$JAVA_BIN" $JVM_OPTS -jar "$JAR")
    else
      cmd=("$JAVA_BIN" -jar "$JAR")
    fi
  fi
  "${cmd[@]}" --spring.profiles.active="$engine" \
    --gateway.governance-enabled="$gov" \
    --server.port="$PORT" \
    "${BENCH_ARGS[@]}" > "$LOG" 2>&1 &
  PID=$!
}

wait_port_free() {
  local port="$1" i
  for i in $(seq 1 30); do
    if ! lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "Port $port still busy after 30s" >&2
  return 1
}

wait_ready() {
  local i
  for i in $(seq 1 60); do
    if ! kill -0 "$PID" 2>/dev/null; then
      echo "Gateway process died. Last log lines:" >&2
      tail -30 "$LOG" >&2
      return 1
    fi
    # Require OUR process to have logged startup; a stray process on the same
    # port must never be mistaken for a healthy gateway.
    if grep -q 'Started LlmGatewayApplication' "$LOG" 2>/dev/null \
       && curl --noproxy '*' -fsS "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "Gateway did not become healthy in 60s" >&2
  return 1
}

stop_gateway() {
  if [[ -n "$PID" ]]; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
    PID=""
  fi
  wait_port_free "$PORT"
  wait_port_free "$MOCK_PORT"
}
trap stop_gateway EXIT

# $! may point at a wrapper shell under some sandboxes; the PID actually
# LISTENing on the gateway port is authoritative.
gateway_pid() {
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1 || true
}

# Sample process memory in KB.
#
# Preferred: real RSS via `ps -o rss= -p`. In this sandbox /bin/ps and
# /usr/bin/top are denied ("operation not permitted"), so the fallback is
# macOS `footprint -f bytes`, whose `phys_footprint` is the physical-memory
# figure Activity Monitor shows. It is NOT identical to Linux RSS (it also
# counts compressed pages), so the CSV carries a `rss_source` column and the
# two must never be mixed in one comparison. If neither works, write "NA" --
# never fabricate a number.
sample_rss() {
  local pid rss="" fp="" source=""
  pid="$(gateway_pid)"
  [[ -z "$pid" ]] && pid="$PID"
  [[ -z "$pid" || -z "$RSS_FILE" ]] && return 0

  rss=$(/bin/ps -o rss= -p "$pid" 2>/dev/null | tr -d ' ' || true)
  if [[ "$rss" =~ ^[0-9]+$ ]] && (( rss > 0 )); then
    source="ps"
  else
    fp=$(/usr/bin/footprint -f bytes --noCategories -p "$pid" 2>/dev/null \
      | awk '/phys_footprint:/ { print $2; exit }' || true)
    if [[ "$fp" =~ ^[0-9]+$ ]] && (( fp > 0 )); then
      rss=$(( fp / 1024 ))
      source="footprint"
    else
      rss=""
    fi
  fi

  if [[ "$rss" =~ ^[0-9]+$ ]] && (( rss > 0 )); then
    printf '%s\n' "$rss" >> "$RSS_FILE"
    printf '%s\n' "$source" >> "$RSS_FILE.source"
  fi
}

peak_rss() {
  if [[ -s "$RSS_FILE" ]]; then
    sort -n "$RSS_FILE" | tail -1
  else
    echo "NA"
  fi
}

rss_source() {
  if [[ -s "$RSS_FILE.source" ]]; then
    sort -u "$RSS_FILE.source" | paste -sd, -
  else
    echo "NA"
  fi
}

median() {
  printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END {
    if (NR == 0) print 0;
    else if (NR % 2) print a[(NR+1)/2];
    else print (a[NR/2] + a[NR/2+1]) / 2;
  }'
}

run_k6() {
  local script="$1" case_id="$2" suffix="$3"
  local export_file="$RESULTS/${case_id}-${suffix}.json"
  ENGINE="$ENGINE_CASE" GOVERNANCE="$GOV_CASE" RUNTIME="$RUNTIME_CASE" \
    DURATION="$DURATION" VUS="$VUS" NO_PROXY='*' no_proxy='*' \
    k6 run --no-color \
      --summary-trend-stats="avg,p(50),p(95),p(99),max" \
      --summary-export="$export_file" \
      "$ROOT/bench/$script" > "$RESULTS/${case_id}-${suffix}.log" 2>&1 || true
  sample_rss
  if [[ ! -s "$export_file" ]]; then
    echo "0 0 0 0"
    return
  fi
  local p50 p99 rps err
  p50=$(jq -r '.metrics.gateway_latency_ms["p(50)"] // 0' "$export_file")
  p99=$(jq -r '.metrics.gateway_latency_ms["p(99)"] // 0' "$export_file")
  rps=$(jq -r '.metrics.http_reqs.rate // 0' "$export_file")
  err=$(jq -r '.metrics.gateway_failure_rate.value // 0' "$export_file")
  echo "$p50 $p99 $rps $err"
}

echo "run_id,engine,governance,runtime,mode,p50_ms,p99_ms,rps,error_rate,rss_kb,startup_s,rss_source,mock_delay_ms,mock_chunk_interval_ms,payload_bytes" > "$CSV"
echo "Upstream profile: delay_ms=$MOCK_DELAY_MS chunk_interval_ms=$MOCK_CHUNK_INTERVAL_MS (baseline is 20/10; 0/0 is a SEPARATE wire experiment); payload_pad_bytes=$PAYLOAD_BYTES"

if [[ ! -f "$JAR" ]]; then
  echo "Missing $JAR - run bin/build.sh first" >&2
  exit 1
fi

INVALID=()

for ENGINE_CASE in $ENGINES; do
  for GOV_CASE in $GOVS; do
    for RUNTIME_CASE in $RUNTIMES; do
      CASE_ID="${RUN_ID}-${ENGINE_CASE}-${GOV_CASE}-${RUNTIME_CASE}"
      if [[ "$RUNTIME_CASE" == "native" ]]; then
        NATIVE_FOR_CASE=$([[ "$ENGINE_CASE" == "webflux" ]] && echo "$NATIVE_WEBFLUX" || echo "$NATIVE_VT")
        if [[ ! -x "$NATIVE_FOR_CASE" ]]; then
          echo "SKIP native $ENGINE_CASE: $NATIVE_FOR_CASE not built (run bin/build-native.sh $ENGINE_CASE)" >&2
          echo "${CASE_ID},${ENGINE_CASE},${GOV_CASE},${RUNTIME_CASE},SKIPPED,0,0,0,0,0,0,NA,${MOCK_DELAY_MS},${MOCK_CHUNK_INTERVAL_MS},${PAYLOAD_BYTES}" >> "$CSV"
          continue
        fi
      fi

      echo "==> $CASE_ID"
      stop_gateway
      GOV_BOOL=$([[ "$GOV_CASE" == "on" ]] && echo true || echo false)
      start_gateway "$ENGINE_CASE" "$GOV_BOOL" "$RUNTIME_CASE"
      wait_ready

      STARTUP_S=$(awk '/Started LlmGatewayApplication in/ {
        for (i = 1; i <= NF; i++) if ($i == "in") { print $(i + 1); exit }
      }' "$LOG" || true)
      [[ -z "$STARTUP_S" ]] && STARTUP_S=0
      RSS_FILE="$RESULTS/${CASE_ID}.rss"
      : > "$RSS_FILE"
      : > "$RSS_FILE.source"
      sample_rss

      for MODE in $MODES; do
        SCRIPT="k6-${MODE}.js"
        ENGINE="$ENGINE_CASE" GOVERNANCE="$GOV_CASE" RUNTIME="$RUNTIME_CASE" \
          DURATION="$WARMUP_DURATION" VUS="$VUS" NO_PROXY='*' no_proxy='*' \
          k6 run --no-color --summary-mode=disabled "$ROOT/bench/$SCRIPT" \
          > "$RESULTS/${CASE_ID}-${MODE}-warmup.log" 2>&1 || true
        sample_rss

        P50S=(); P99S=(); RPSS=(); ERRS=()
        for ROUND in $(seq 1 "$ROUNDS"); do
          read -r P50 P99 RPS ERR < <(run_k6 "$SCRIPT" "$CASE_ID" "${MODE}-r${ROUND}")
          P50S+=("$P50"); P99S+=("$P99"); RPSS+=("$RPS"); ERRS+=("$ERR")
        done
        ERR_MED=$(median "${ERRS[@]}")
        if awk -v e="$ERR_MED" 'BEGIN { exit !(e > 0.01) }'; then
          INVALID+=("${CASE_ID}-${MODE} (error_rate=${ERR_MED})")
        fi
        echo "${CASE_ID},${ENGINE_CASE},${GOV_CASE},${RUNTIME_CASE},${MODE},$(median "${P50S[@]}"),$(median "${P99S[@]}"),$(median "${RPSS[@]}"),${ERR_MED},$(peak_rss),${STARTUP_S:-0},$(rss_source),${MOCK_DELAY_MS},${MOCK_CHUNK_INTERVAL_MS},${PAYLOAD_BYTES}" >> "$CSV"
      done
    done
  done
done

stop_gateway
echo "Done. Summary: $CSV"
echo "Raw k6 exports and logs: $RESULTS"
if (( ${#INVALID[@]} > 0 )); then
  echo "WARNING: runs with error_rate > 1% - these are INVALID, do not quote them:" >&2
  printf '  %s\n' "${INVALID[@]}" >&2
else
  echo "All runs have error_rate <= 1%."
fi
