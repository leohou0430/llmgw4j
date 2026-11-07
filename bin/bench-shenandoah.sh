#!/usr/bin/env bash
# Dedicated Shenandoah experiment.
#
# Why a separate script: Oracle GraalVM 25 refuses Shenandoah (JVMCI
# GraalError), so the collector must run on Eclipse Temurin 25. Comparing
# Temurin-Shenandoah against Oracle-GraalVM-G1 would confound GC with JIT
# (Graal JIT vs C2), so the honest A/B is WITHIN Temurin:
#
#   temurin-g1  : Temurin 25, default G1        (control)
#   temurin-shen: Temurin 25, -XX:+UseShenandoahGC
#
# Both arms use the same C2 JIT, same jar, same wire profile (MOCK 0/0),
# same webflux/on/jvm scope as bench-gc-matrix.sh. Two load levels:
#   VUS=50  (comparable to the main GC matrix)
#   VUS=100 (stress: GC differences widen under allocation pressure)
#
# Everything lands in bench/results/shen-experiment-*.csv plus GC-log
# accounting (collections / total pause / max pause per arm).
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/bench/results"
JDK_JAVA="${JDK_JAVA:-$(ls -d "$ROOT"/tools/jdk-25* 2>/dev/null | head -1)/Contents/Home/bin/java}"
mkdir -p "$OUT"

if [[ ! -x "$JDK_JAVA" ]]; then
  echo "Temurin java not found at: $JDK_JAVA" >&2
  echo "Extract tools/temurin25.tar.gz first." >&2
  exit 1
fi

echo "Temurin JVM:"
"$JDK_JAVA" -version 2>&1 | sed 's/^/  /'
echo "Shenandoah availability check:"
"$JDK_JAVA" -XX:+UseShenandoahGC -version >/dev/null 2>&1 \
  && echo "  OK" || { echo "  NOT AVAILABLE in this build" >&2; exit 1; }

SUMMARY="$OUT/shen-experiment-summary.csv"
GCSUM="$OUT/shen-experiment-gclog.csv"
echo "variant,phase,run_id,mode,p50_ms,p99_ms,rps,error_rate,peak_rss_kb,rss_source,startup_s" > "$SUMMARY"
echo "variant,phase,run_id,gc_events,pause_events,total_pause_ms,max_pause_ms" > "$GCSUM"

run_arm() {
  local variant="$1" opts="$2" vus="$3" phase="$4"
  echo "================ $variant / $phase (VUS=$vus) ================"
  if ! JAVA_BIN="$JDK_JAVA" JVM_OPTS="$opts" \
      ENGINES=webflux GOVS=on RUNTIMES=jvm MODES="json sse" \
      ROUNDS=3 DURATION=30s VUS="$vus" \
      MOCK_DELAY_MS=0 MOCK_CHUNK_INTERVAL_MS=0 \
      "$ROOT/bin/bench-matrix.sh"; then
    echo "ARM $variant/$phase FAILED" >&2
    return
  fi
  local csv rid
  csv="$(ls -t "$OUT"/summary-*.csv | head -1)"
  rid="$(basename "$csv" .csv | sed 's/^summary-//')"
  tail -n +2 "$csv" | while IFS=, read -r _ _ _ _ mode p50 p99 rps err rss startup source _d1 _d2; do
    echo "$variant,$phase,$rid,$mode,$p50,$p99,$rps,$err,$rss,$source,$startup" >> "$SUMMARY"
  done
  # GC-log accounting from that arm's gateway log.
  local log events pn ps pm
  log="$OUT/gateway-$rid.log"
  events=$(grep -cE 'GC\([0-9]+\)' "$log" || true)
  read -r pn ps pm < <(
    grep -E 'Pause .*[0-9.]+ms$' "$log" \
      | grep -oE '[0-9.]+ms$' | tr -d 'm s' \
      | awk '{n++; s+=$1; if ($1 > m) m=$1} END {print n+0, s+0, m+0}'
  )
  echo "$variant,$phase,$rid,$events,$pn,$ps,$pm" >> "$GCSUM"
}

for phase in "50" "100"; do
  run_arm temurin-g1   "-Xlog:gc"                        "$phase" "vu$phase"
  run_arm temurin-shen "-XX:+UseShenandoahGC -Xlog:gc"    "$phase" "vu$phase"
done

echo "Done. Summary: $SUMMARY"
echo "GC accounting: $GCSUM"
