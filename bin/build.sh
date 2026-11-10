#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mvn -B -f "$ROOT/pom.xml" -DskipTests clean package
echo "Build finished: $ROOT/llmgw-app/target/llmgw-app-0.3.0.jar"
