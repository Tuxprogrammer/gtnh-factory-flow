#!/usr/bin/env bash
# Compile the dataset exporter (tools/dataset-pipeline/gtnh-calc-oracle, a Forge mod run inside the pack to export
# recipes). It compiles the mod's own readers in at build time (its build.gradle), so a reader change that breaks it is
# caught here instead of on the next export. Compile only: the jar itself is built by whoever runs an export.
# Arguments are extra Gradle options (jvm.sh passes its own). Its settings plugin runs only on JDK 25 or newer and it
# compiles for Java 8 through the toolchains, as the mod's does. A JAVA_HOME older than 25 (the build box runs the jvm job
# on 21) is swapped for a 25 from JAVA_HOME_25_X64 or the Gradle home's toolchain paths.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
CI_JOB=oracle
cd "$CI_ROOT/tools/dataset-pipeline/gtnh-calc-oracle"

major() { sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$1/release" 2>/dev/null; }
m=$(major "${JAVA_HOME:-/nonexistent}")
if [ -z "$m" ] || [ "$m" -lt 25 ]; then
  found=""
  props="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
  paths=$(sed -n 's/^org\.gradle\.java\.installations\.paths=//p' "$props" 2>/dev/null | tr ',' ' ')
  for j in ${JAVA_HOME_25_X64:-} $paths; do
    m=$(major "$j"); if [ -n "$m" ] && [ "$m" -ge 25 ]; then found="$j"; break; fi
  done
  [ -n "$found" ] || fail "no JDK 25 to run the exporter's Gradle (JAVA_HOME is ${JAVA_HOME:-unset})"
  export JAVA_HOME="$found" PATH="$found/bin:$PATH"
fi
say "gradle compileJava${CI_CORES:+ on cores $CI_CORES}"
start=$(date +%s)
pin ./gradlew --no-daemon --console=plain "$@" compileJava || fail "compileJava failed"
say "PASS ($(( $(date +%s) - start ))s)"
