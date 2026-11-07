#!/usr/bin/env bash
# v9 I/O-layer experiment chain.
#
# Two INDEPENDENT ABBA paired A/Bs, each with a byte-identical control arm
# (v8.1-final.jar) so the two optimisations are never conflated:
#   1. kqueue native transport (clean-load rerun of the load-polluted run)
#   2. pooled direct response buffers (wrap(byte[]) -> allocateBuffer + write)
#
# A host-load sampler runs for the whole chain: the 2026-11-07 afternoon
# experiment was invalidated by vm.loadavg ~111 with no record of WHEN it
# spiked. Every sample is timestamped so a result can be judged against the
# data-quality red line documented in bench/README.md.
#
# Usage: ./bin/bench-v9-io-ab.sh 2>&1 | tee bench/results/v9-io-driver-<tag>.log
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TAG="$(date +%Y%m%d-%H%M%S)"
RESULTS="$ROOT/bench/results"
LOAD_LOG="$RESULTS/v9-load-$TAG.log"
PAIRS="${PAIRS:-4}"
DURATION="${DURATION:-20s}"
VUS="${VUS:-50}"
echo "config: pairs=$PAIRS duration=$DURATION vus=$VUS"

( while :; do
    printf '%s %s\n' "$(date +%H%M%S)" "$(sysctl -n vm.loadavg)"
    sleep 15
  done ) > "$LOAD_LOG" 2>&1 &
SAMPLER=$!
trap 'kill "$SAMPLER" 2>/dev/null || true' EXIT

echo "=== load at start: $(sysctl -n vm.loadavg)"
echo "######## SET 1: kqueue native transport (clean-load rerun) ########"
ARM_A="$RESULTS/v8.1-final.jar" ARM_B="$RESULTS/v9-kqueue.jar" \
  PAYLOAD_BYTES=0 PAIRS="$PAIRS" DURATION="$DURATION" VUS="$VUS" ./bin/bench-paired-ab.sh
echo "######## SET 2: pooled direct response buffers ########"
ARM_A="$RESULTS/v8.1-final.jar" ARM_B="$RESULTS/v9.1-direct.jar" \
  PAYLOAD_BYTES=0 PAIRS="$PAIRS" DURATION="$DURATION" VUS="$VUS" ./bin/bench-paired-ab.sh
echo "=== load at end: $(sysctl -n vm.loadavg)"
echo "load_samples=$LOAD_LOG"
