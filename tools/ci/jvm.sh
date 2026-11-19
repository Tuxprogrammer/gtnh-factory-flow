#!/usr/bin/env bash
# Job `jvm`: the whole JVM build under jvm/ (mod and core modules, their tests, spotless, checkstyle) plus the
# mod's Java 8 compatibility check, then the test-count check. Needs JDK 25 as JAVA_HOME (it runs Gradle), JDKs
# 8, 17 and 21 findable by Gradle's toolchains, and no Gradle daemon.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
CI_JOB=jvm
cd "$CI_ROOT/jvm"

# Hosted runners keep each JDK in JAVA_HOME_<n>_X64; tell Gradle's toolchain search about them.
envs=$(compgen -v | grep -E '^JAVA_HOME_[0-9]+_X64$' | paste -sd, - || true)
extra=()
if [ -n "$envs" ]; then extra+=("-Porg.gradle.java.installations.fromEnv=$envs"); fi

# A result file from an earlier run must never count: a resumed build can report `:test NO-SOURCE` and run nothing.
rm -rf mod/build/test-results core/*/build/test-results

say "gradle build :mod:compatCheck${CI_CORES:+ on cores $CI_CORES}"
pin ./gradlew --no-daemon --console=plain "${extra[@]}" build :mod:compatCheck || fail "gradle build or compatCheck failed"

python3 -I "$CI_ROOT/tools/ci/check-jvm-tests.py" || fail "test count check failed"
say "PASS"
