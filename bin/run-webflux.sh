#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/llmgw-app/target/llmgw-app-0.2.0.jar"
if [[ ! -f "$JAR" ]]; then echo "Run bin/build.sh first" >&2; exit 1; fi
# MaxGCPauseMillis=10 is data-backed (GC matrix 2026-11-07): max GC pause
# 11.1ms -> 6.6ms with no measurable throughput cost. Override with
# GATEWAY_GC_OPTS="" to disable.
GATEWAY_GC_OPTS="${GATEWAY_GC_OPTS:--XX:MaxGCPauseMillis=10}"
# shellcheck disable=SC2086
exec java $GATEWAY_GC_OPTS -jar "$JAR" --spring.profiles.active=webflux --server.port="${PORT:-8080}" "$@"
