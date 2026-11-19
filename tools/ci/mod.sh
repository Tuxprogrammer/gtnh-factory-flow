#!/usr/bin/env bash
# Build the mod's jar for a release. Usage:
#   mod.sh --release mod-<version> [--test]
# The checkout must be the tagged commit with nothing changed under jvm/: `git checkout mod-<version>` first. The jar is built
# with VERSION=<version> (the tag without its prefix), so its name and its mod version are the tag's, whatever the clone's
# history looks like. Prints the jar's path and checksum. Without --test the unit tests are skipped (CI has run them for the
# commit); --test runs the whole build as CI's jvm job does.
# This never creates, moves or pushes a tag: the tag is made by whoever releases, from the same commit.
# Needs what tools/ci/jvm.sh needs: JDK 25 as JAVA_HOME, JDKs 8, 17 and 21 findable by Gradle (JAVA_HOME_<n>_X64), no daemon.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
CI_JOB=mod
. "$(dirname "$0")/net.sh"

usage() { fail "usage: mod.sh --release mod-<version> [--test]"; }
[ "${1:-}" = --release ] || usage
tag="${2:-}"; [ -n "$tag" ] || usage
shift 2
run_tests=0
while [ $# -gt 0 ]; do case "$1" in --test) run_tests=1 ;; *) usage ;; esac; shift; done

[[ "$tag" =~ ^mod-[0-9]+\.[0-9]+\.[0-9]+([-.+][0-9A-Za-z.+-]+)?$ ]] || fail "'$tag' is not a mod tag (mod-<major>.<minor>.<patch>[-suffix])"
version="${tag#mod-}"

want=$(git -C "$CI_ROOT" rev-parse -q --verify "refs/tags/$tag^{commit}") || fail "tag $tag does not exist in this clone (git fetch --tags)"
have=$(git -C "$CI_ROOT" rev-parse HEAD)
[ "$want" = "$have" ] || fail "HEAD is ${have:0:10} but $tag is ${want:0:10}: run 'git checkout $tag' first"
[ -z "$(git -C "$CI_ROOT" status --porcelain --untracked-files=no -- jvm)" ] || fail "uncommitted changes under jvm/; a release builds the tagged files only"

cd "$CI_ROOT/jvm"
envs=$(compgen -v | grep -E '^JAVA_HOME_[0-9]+_X64$' | paste -sd, - || true)
extra=("${GRADLE_NET_OPTS[@]}")
if [ -n "$envs" ]; then extra+=("-Porg.gradle.java.installations.fromEnv=$envs"); fi

rm -rf mod/build/libs
if [ "$run_tests" = 1 ]; then tasks=(build); else tasks=(:mod:build -x test); fi
say "building $tag as version $version: gradle ${tasks[*]}"
VERSION="$version" pin ./gradlew --no-daemon --console=plain "${extra[@]}" "${tasks[@]}" || fail "gradle failed"

# The jar to ship is the one named for the version; the sources, dev and similar jars sit beside it.
mapfile -t jars < <(find mod/build/libs -maxdepth 1 -name "*-$version.jar" ! -name '*-dev*' ! -name '*-sources*' ! -name '*-api*' | sort)
[ ${#jars[@]} -eq 1 ] || fail "expected one jar named *-$version.jar in jvm/mod/build/libs, found ${#jars[@]}"
jar="${jars[0]}"
say "jar: $CI_ROOT/jvm/$jar"
say "sha256: $(sha256sum "$jar" | cut -d' ' -f1)"
say "PASS"
