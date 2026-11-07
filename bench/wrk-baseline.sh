#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VUS="${VUS:-50}"
DURATION="${DURATION:-30s}"
CONNECTIONS="${CONNECTIONS:-200}"
wrk -t4 -c"$CONNECTIONS" -d"$DURATION" -s "$ROOT/bench/wrk-post.lua" http://127.0.0.1:8080/v1/chat/completions
