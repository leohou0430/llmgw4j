#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

PROFILE="${1:-webflux}"
if [[ "$PROFILE" != "webflux" && "$PROFILE" != "vt" ]]; then
  echo "Usage: $0 [webflux|vt]" >&2
  exit 1
fi
IMAGE="llmgw-${PROFILE}"

# NATIVE_GC=g1 selects the experimental G1 build (-Pnative-g1, Oracle GraalVM
# only). Default: Serial GC (GraalVM default, comparable with all prior runs).
MVN_PROFILE="native"
if [[ "${NATIVE_GC:-}" == "g1" ]]; then
  MVN_PROFILE="native-g1"
  echo "GC mode: G1 (experimental)"
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "JAVA_HOME is not set" >&2
  exit 1
fi
NATIVE_IMAGE="$JAVA_HOME/bin/native-image"
if [[ ! -x "$NATIVE_IMAGE" ]]; then
  echo "native-image not found at $NATIVE_IMAGE" >&2
  echo "This project needs GraalVM 25+. Current JAVA_HOME=$JAVA_HOME" >&2
  exit 1
fi

echo "Using $($NATIVE_IMAGE --version | head -1)"
echo "Building native image for profile=$PROFILE -> $IMAGE"
echo "NOTE: Spring AOT evaluates @Profile at BUILD time, so each engine needs its own native binary."

mvn -B -f "$ROOT/pom.xml" -pl llmgw-app -am -DskipTests -P$MVN_PROFILE \
  -Dllmgw.native.profile="$PROFILE" \
  -Dllmgw.native.image="$IMAGE" \
  clean package

echo "Native binary: $ROOT/llmgw-app/target/$IMAGE"
NATIVE_DIR="$ROOT/bin/native"
mkdir -p "$NATIVE_DIR"
cp "$ROOT/llmgw-app/target/$IMAGE" "$NATIVE_DIR/$IMAGE"
chmod +x "$NATIVE_DIR/$IMAGE"
echo "Stable copy: $NATIVE_DIR/$IMAGE"
echo "Run it with: $NATIVE_DIR/$IMAGE --spring.profiles.active=$PROFILE --server.port=8080"
