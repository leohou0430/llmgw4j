#!/usr/bin/env bash
# Network-free microbenchmark for the v8 streaming request parser.
# Usage: ./bin/run-parse-microbench.sh [iterations-per-round]  (default 50000)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Resolve the REAL llmgw-core dependency classpath via Maven instead of
# hard-coding Jackson versions (they have moved more than once already).
CP_FILE="$ROOT/bench/micro/.classpath"
if [[ ! -s "$CP_FILE" ]]; then
  mvn -q -f "$ROOT/pom.xml" dependency:build-classpath -pl llmgw-core \
      -Dmdep.outputFile="$CP_FILE" >/dev/null
fi
CP="$ROOT/llmgw-core/target/classes:$(cat "$CP_FILE")"
OUT="$ROOT/bench/micro/.classes"
mkdir -p "$OUT"
javac -nowarn -cp "$CP" -d "$OUT" "$ROOT/bench/micro/ParseMicrobench.java"
java -cp "$CP:$OUT" ParseMicrobench "${1:-50000}"
