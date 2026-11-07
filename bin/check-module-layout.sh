#!/usr/bin/env bash
# Enforce the project's module layout: every capability domain is an
# aggregator POM with ONE shared *-api module and one module per
# implementation. Run locally or from CI; no build, no network, no side effects.
#
# Rules (all discovered automatically -- adding a new domain needs no edit here):
#   1. A top-level llmgw-* directory with packaging=pom is a capability domain.
#   2. It must list a <domain>-api module, and that api must depend on llmgw-core.
#   3. The api must not depend on ANY other llmgw-* module.
#   4. Every implementation module must have the aggregator as its parent and
#      depend on <domain>-api.
#   5. No implementation may depend on a sibling implementation.
#   6. No implementation may declare the bare api Java package (split package).
#   7. The root POM must list aggregators only, never nested module paths.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PASS=0
FAIL=0

ok()  { PASS=$((PASS + 1)); }
bad() { FAIL=$((FAIL + 1)); printf '[FAIL] %s\n' "$1"; }

expect_file() {
  if [ -f "$1" ]; then ok; else bad "missing file: ${1#$ROOT/}"; fi
}
expect_grep() {
  if grep -Eq "$2" "$1" 2>/dev/null; then ok; else bad "$3"; fi
}
expect_no_grep() {
  if grep -Eq "$2" "$1" 2>/dev/null; then bad "$3"; else ok; fi
}
modules_of() {
  sed -n 's/.*<module>\([^<]*\)<\/module>.*/\1/p' "$1"
}

DOMAINS=()
for dir in "$ROOT"/llmgw-*/; do
  [ -f "${dir}pom.xml" ] || continue
  grep -q '<packaging>pom</packaging>' "${dir}pom.xml" || continue
  DOMAINS+=("$(basename "$dir")")
done

if [ "${#DOMAINS[@]}" -eq 0 ]; then
  bad 'no capability aggregator found'
fi

for domain in "${DOMAINS[@]}"; do
  agg="$ROOT/$domain/pom.xml"
  api_module="${domain}-api"
  api_pom="$ROOT/$domain/$api_module/pom.xml"
  echo "--- $domain ---"

  expect_file "$api_pom"
  expect_grep "$agg" "<module>${api_module}</module>" \
    "$domain aggregator must list $api_module"
  if [ -f "$api_pom" ]; then
    expect_grep "$api_pom" '<artifactId>llmgw-core</artifactId>' \
      "$api_module must depend on llmgw-core"
    # The api is the bottom of its subsystem: no other internal module allowed.
    # Limit the scan to <dependencies> so the parent's artifactId is not read
    # as a dependency.
    deps_block="$(sed -n '/<dependencies>/,/<\/dependencies>/p' "$api_pom")"
    while IFS= read -r dep; do
      [ -z "$dep" ] && continue
      [ "$dep" = 'llmgw-core' ] && continue
      bad "$api_module must not depend on $dep"
    done < <(printf '%s\n' "$deps_block" \
      | sed -n 's/.*<artifactId>\(llmgw-[^<]*\)<\/artifactId>.*/\1/p' | sort -u)
  fi

  impls="$(modules_of "$agg" | grep -v "^${api_module}$")"
  if [ -z "$impls" ]; then
    bad "$domain has no implementation module"
  fi

  for impl in $impls; do
    pom="$ROOT/$domain/$impl/pom.xml"
    expect_file "$pom"
    [ -f "$pom" ] || continue
    expect_grep "$pom" "<artifactId>${domain}</artifactId>" \
      "$impl parent must be the $domain aggregator"
    expect_grep "$pom" "<artifactId>${api_module}</artifactId>" \
      "$impl must depend on $api_module"
    for other in $impls; do
      [ "$other" = "$impl" ] && continue
      expect_no_grep "$pom" "<artifactId>${other}</artifactId>" \
        "$impl must not depend on sibling $other"
    done
    # Implementations live in a SUBpackage; sharing the bare api package would
    # create a split package across jars (and break native image / JPMS).
    pkg_suffix="${domain#llmgw-}"
    if grep -Rqs "^package io\.github\.leohou0430\.llmgw\.${pkg_suffix};" \
        "$ROOT/$domain/$impl/src/main/java" 2>/dev/null; then
      bad "$impl must not declare the bare api package io.github.leohou0430.llmgw.${pkg_suffix}"
    else
      ok
    fi
  done
done

expect_no_grep "$ROOT/pom.xml" '<module>llmgw-[a-z-]+/' \
  'root POM must list aggregators only, not nested module paths'

echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
