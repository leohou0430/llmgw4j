#!/usr/bin/env bash
# Interleaved paired A/B: cancels host-load drift by running both arms back to
# back in each round and reversing the order on alternate rounds (ABBA design).
#
# Usage:
#   ARM_A=bench/results/v7.2-final.jar ARM_B=bench/results/v8.1-final.jar \
#   PAYLOAD_BYTES=4096 PAIRS=4 DURATION=20s ./bin/bench-paired-ab.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ARM_A="${ARM_A:?set ARM_A to the baseline jar}"
ARM_B="${ARM_B:?set ARM_B to the candidate jar}"
PAYLOAD_BYTES="${PAYLOAD_BYTES:-0}"
PAIRS="${PAIRS:-4}"
DURATION="${DURATION:-20s}"
VUS="${VUS:-50}"
MODES="${MODES:-json sse}"
TAG="$(date +%Y%m%d-%H%M%S)"
MANIFEST="$ROOT/bench/results/paired-ab-$TAG.manifest"
LOG="$ROOT/bench/results/paired-ab-$TAG.log"
OUT="$ROOT/bench/results/paired-ab-$TAG.csv"
: > "$MANIFEST"
: > "$LOG"

run_arm() {
  local arm="$1" jar="$2" round="$3"
  echo "=== round=$round arm=$arm jar=$(basename "$jar") pad=$PAYLOAD_BYTES ===" | tee -a "$LOG"
  cp "$jar" "$ROOT/llmgw-app/target/llmgw-app-0.3.0.jar"
  local out csv
  out="$(cd "$ROOT" && PAYLOAD_BYTES="$PAYLOAD_BYTES" ENGINES=webflux GOVS=on \
        RUNTIMES=jvm MODES="$MODES" ROUNDS=1 DURATION="$DURATION" VUS="$VUS" \
        MOCK_DELAY_MS=0 MOCK_CHUNK_INTERVAL_MS=0 ./bin/bench-matrix.sh 2>&1 | tee -a "$LOG")"
  # printf (not echo): zsh's builtin echo would mangle \n in captured output.
  csv="$(printf '%s' "$out" | sed -n 's/^Done\. Summary: //p' | tail -1)"
  if [[ -z "$csv" || ! -s "$csv" ]]; then
    echo "FAILED: no summary produced for arm=$arm round=$round" >&2
    exit 1
  fi
  printf '%s,%s,%s\n' "$arm" "$round" "$csv" >> "$MANIFEST"
}

for round in $(seq 1 "$PAIRS"); do
  if (( round % 2 == 1 )); then
    run_arm A "$ARM_A" "$round"
    run_arm B "$ARM_B" "$round"
  else
    run_arm B "$ARM_B" "$round"
    run_arm A "$ARM_A" "$round"
  fi
done

python3 - "$MANIFEST" "$OUT" "$PAYLOAD_BYTES" <<'PY'
import csv, statistics, sys
from pathlib import Path
manifest, out, pad = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
by_arm: dict[str, dict[str, list[float]]] = {}
rows = []
for line in manifest.read_text().splitlines():
    arm, rnd, path = line.split(",")
    with open(path) as f:
        for row in csv.DictReader(f):
            mode = row["mode"]
            if mode in ("json", "sse"):
                key = f"{arm}|{mode}"
                vals = by_arm.setdefault(key, {"p50": [], "p99": [], "rps": [], "err": []})
                for k in vals:
                    vals[k].append(float(row[{"p50": "p50_ms", "p99": "p99_ms",
                                             "rps": "rps", "err": "error_rate"}[k]]))
                rows.append([arm, rnd, mode, row["p50_ms"], row["p99_ms"],
                             row["rps"], row["error_rate"], pad])
with open(out, "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["arm", "round", "mode", "p50_ms", "p99_ms", "rps", "error_rate", "payload_bytes"])
    w.writerows(rows)
print(f"pair_runs={len(rows)//2} payload_bytes={pad}")
print("arm,mode,p50_ms,p99_ms,rps,error_rate_max")
for key in sorted(by_arm):
    arm, mode = key.split("|")
    v = by_arm[key]
    print(f"{arm},{mode},{statistics.median(v['p50']):.3f},"
          f"{statistics.median(v['p99']):.3f},{statistics.median(v['rps']):.0f},"
          f"{max(v['err']):.6f}")
print(f"detail_csv={out}")
PY
