#!/usr/bin/env bash
# Job `jvm`: the whole JVM build under jvm/ (mod and core modules, their tests, spotless, checkstyle) plus the
# mod's Java 8 compatibility check, then the test-count check. Needs JDK 25 as JAVA_HOME (it runs Gradle), JDKs
# 8, 17 and 21 findable by Gradle's toolchains, and no Gradle daemon.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
CI_JOB=jvm
. "$(dirname "$0")/net.sh"
cd "$CI_ROOT/jvm"

# A CI jar is build output for this run, not a release (tools/ci/mod.sh builds a release from its mod-* tag, with the tag's
# version). The hosted checkout is shallow, so no tag reaches HEAD and the jar would be named NO-GIT-TAG-SET; name it for
# the commit instead, so a jar taken from a run says what it was built from. An explicit VERSION still wins.
export VERSION="${VERSION:-0.0.0-ci.$(git -C "$CI_ROOT" rev-parse --short=8 HEAD 2>/dev/null || echo unknown)}"

# Hosted runners keep each JDK in JAVA_HOME_<n>_X64; tell Gradle's toolchain search about them.
envs=$(compgen -v | grep -E '^JAVA_HOME_[0-9]+_X64$' | paste -sd, - || true)
# Gradle's own default heap is 512 MiB, too small for the mod's build (the daemon dies thrashing in downgradeJar).
# net.sh sets it (and the HTTP retry settings) so a runner without a configured Gradle home behaves like the build box.
extra=("${GRADLE_NET_OPTS[@]}")
if [ -n "$envs" ]; then extra+=("-Porg.gradle.java.installations.fromEnv=$envs"); fi
# Extra Gradle arguments for a one-off run (the seed check passes --offline to prove nothing is fetched).
if [ -n "${CI_GRADLE_ARGS:-}" ]; then read -ra more <<< "$CI_GRADLE_ARGS"; extra+=("${more[@]}"); fi

# A result file from an earlier run must never count: a resumed build can report `:test NO-SOURCE` and run nothing.
rm -rf mod/build/test-results core/*/build/test-results web/*/build/test-results

say "gradle build :mod:compatCheck${CI_CORES:+ on cores $CI_CORES}"
pin ./gradlew --no-daemon --console=plain "${extra[@]}" build :mod:compatCheck || fail "gradle build or compatCheck failed"

python3 -I "$CI_ROOT/tools/ci/check-jvm-tests.py" || fail "test count check failed"

# The dataset exporter compiles the mod's readers into its own jar, so a change to either must compile there too.
# Only when the diff touches them (CI_ORACLE=false from changes.sh skips it; unset runs it).
if [ "${CI_ORACLE:-true}" != false ]; then
  "$CI_ROOT/tools/ci/oracle.sh" "${extra[@]}" || fail "dataset exporter (oracle) does not compile"
fi
say "PASS"
