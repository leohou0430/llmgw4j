#!/usr/bin/env bash
# GC / memory-layout experiment driver.
#
# Fixed scope: webflux engine, governance ON, JVM runtime, wire profile
# (MOCK_DELAY_MS=0, MOCK_CHUNK_INTERVAL_MS=0) -- the same profile as the v6
# ZGC A/B in README, so the G1 baseline row is directly comparable to those
# numbers. Every variant gets -Xlog:gc and the gateway log (which receives
# the GC lines) is kept per variant for pause accounting.
#
# Usage: ./bin/bench-gc-matrix.sh            # all variants, sequential
#        VARIANTS="g1 zgc" ./bin/bench-gc-matrix.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/bench/results"
mkdir -p "$OUT"

# name|JVM_OPTS
VARIANTS="${VARIANTS:-g1 zgc g1-coh g1-pause10 parallel serial}"

opts_for() {
  case "$1" in
    g1)         echo "-Xlog:gc" ;;
    zgc)        # gc* (not gc): ZGC pause lines are tagged {gc,phases}; the
                # bare gc selector never logs them and pause accounting
                # silently comes out zero.
                echo "-XX:+UseZGC -Xlog:gc*" ;;
    g1-coh)     echo "-XX:+UseCompactObjectHeaders -Xlog:gc" ;;
    g1-pause10) echo "-XX:MaxGCPauseMillis=10 -Xlog:gc" ;;
    parallel)   echo "-XX:+UseParallelGC -Xlog:gc" ;;
    serial)     echo "-XX:+UseSerialGC -Xlog:gc" ;;
    *)          echo "" ;;
  esac
}

SUMMARY="$OUT/gc-experiment-summary.csv"
echo "variant,run_id,mode,p50_ms,p99_ms,rps,error_rate,peak_rss_kb,rss_source,startup_s" > "$SUMMARY"

for V in $VARIANTS; do
  OPTS="$(opts_for "$V")"
  echo "================ VARIANT $V (JVM_OPTS=\"$OPTS\") ================"
  # bench-matrix.sh is strict (set -e); a dead gateway aborts one variant,
  # not the whole experiment, hence the || continue with log capture.
  if ! JVM_OPTS="$OPTS" \
      ENGINES=webflux GOVS=on RUNTIMES=jvm MODES="json sse" \
      ROUNDS=3 DURATION=30s VUS=50 \
      MOCK_DELAY_MS=0 MOCK_CHUNK_INTERVAL_MS=0 \
      "$ROOT/bin/bench-matrix.sh"; then
    echo "VARIANT $V FAILED -- see last summary-*.csv / gateway-*.log in $OUT" >&2
    continue
  fi
  # bench-matrix.sh prints its CSV path as "Done. Summary: <path>".
  # Re-derive it deterministically instead: newest summary CSV.
  CSV="$(ls -t "$OUT"/summary-*.csv | head -1)"
  RID="$(basename "$CSV" .csv | sed 's/^summary-//')"
  tail -n +2 "$CSV" | while IFS=, read -r _ engine gov runtime mode p50 p99 rps err rss startup source _d1 _d2; do
    echo "$V,$RID,$mode,$p50,$p99,$rps,$err,$rss,$source,$startup" >> "$SUMMARY"
  done
done

echo "================ GC-LOG ACCOUNTING ================"
# The gateway log per variant is the newest gateway-<runid>.log matching each
# variant's run; map via the summary run_ids recorded above.
for V in $VARIANTS; do
  RID=$(awk -F, -v v="$V" '$1==v {print $2; exit}' "$SUMMARY")
  [[ -z "$RID" ]] && continue
  LOG="$OUT/gateway-$RID.log"
  [[ ! -f "$LOG" ]] && continue
  EVENTS=$(grep -cE 'GC\([0-9]+\)' "$LOG" || true)
  # Pause lines end with "<x>ms" and contain the word Pause (G1/Serial/
  # Parallel/ZGC all use that wording); sum + max them.
  read -r PAUSE_N PAUSE_SUM PAUSE_MAX < <(
    grep -E 'Pause .*[0-9.]+ms$' "$LOG" \
      | grep -oE '[0-9.]+ms$' | tr -d 'm s' \
      | awk '{n++; s+=$1; if ($1 > m) m=$1} END {print n+0, s+0, m+0}'
  )
  echo "gc-accounting: variant=$V run=$RID collections=$EVENTS pauses=$PAUSE_N total_pause_ms=$PAUSE_SUM max_pause_ms=$PAUSE_MAX"
  echo "$V,$RID,$EVENTS,$PAUSE_N,$PAUSE_SUM,$PAUSE_MAX" >> "$OUT/gc-experiment-gclog.csv"
done

echo "Done. Summary: $SUMMARY"
