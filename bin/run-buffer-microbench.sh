#!/usr/bin/env bash
# Network-free microbenchmark for the v9 response-buffer strategies.
# Usage: ./bin/run-buffer-microbench.sh [iterations-per-round]  (default 50000)
#
# Classpath comes from the CONTROL jar's BOOT-INF/lib, NOT from a Maven
# -pl resolution: single-module dependency:build-classpath cannot resolve the
# project's own SNAPSHOT modules (llmgw-core), and -am produced an incomplete
# list. Extracting the fat jar also guarantees the exact library versions that
# the A/B arms actually run.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTROL="${CONTROL_JAR:-$ROOT/bench/results/v8.1-final.jar}"
LIB="$ROOT/bench/micro/.lib"
if [[ ! -d "$LIB" || -z "$(ls -A "$LIB" 2>/dev/null)" ]]; then
  mkdir -p "$LIB"
  unzip -o -q -j "$CONTROL" 'BOOT-INF/lib/*.jar' -d "$LIB"
fi
CP="$(find "$LIB" -name '*.jar' -print | tr '\n' ':')"
OUT="$ROOT/bench/micro/.classes"
mkdir -p "$OUT"
javac -nowarn -cp "$CP" -d "$OUT" "$ROOT/bench/micro/BufferMicrobench.java"
java -cp "$CP:$OUT" BufferMicrobench "${1:-50000}"
