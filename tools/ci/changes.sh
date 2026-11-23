#!/usr/bin/env bash
# Decide which CI jobs a diff needs. Prints one line per job, `<job>=true|false`, and appends them to
# $GITHUB_OUTPUT when that is set.
#   changes.sh                  files changed against the default base (see lib.sh), plus uncommitted and untracked
#   changes.sh --base REF       the same against REF (merge base, so a branch that is behind is not charged for it)
#   changes.sh --files FILE     the file list is in FILE, one path per line (`-` reads stdin)
#   changes.sh --list           print the changed file list instead and stop
# Env on GitHub: CI_BASE_SHA (the PR base or the push's before), CI_BRANCH.
#
# Rules (a file counts toward every rule that matches it):
#   conformance/, jvm/conformance-support/, tools/conformance/, src/test-support/, tools/ci/, .github/   every job
#   anything outside jvm/                    website and icons
#   anything under jvm/                      jvm
#   jvm/web/, jvm/core/, jvm/*.kts, jvm/gradle/   also website (its tests load the core built from them)
#   jvm/ outside jvm/mod/, src/lib/core/     web-core
#   jvm/web/, jvm/gradle/, jvm/*.kts, tools/web-core/   web_core_full: the web-core job runs every case on the JavaScript target too
#                                            (a sample otherwise, tools/web-core/sample.mjs); so does a diff that runs every job
#   tools/dataset-pipeline/gtnh-calc-oracle/, the readers it compiles in (jvm/mod/.../read/, core-model's .../model/read/)
#                                            oracle: the jvm job also compiles the dataset exporter (tools/ci/oracle.sh); the
#                                            oracle's own directory also runs jvm. So does a diff that runs every job
# An empty or unknowable diff runs every job: a skipped job must never be a guess.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
CI_JOB=changes

mode=auto base="" files_from=""
while [ $# -gt 0 ]; do
  case "$1" in
    --base) base="$2"; shift 2 ;;
    --files) files_from="$2"; shift 2 ;;
    --list) mode=list; shift ;;
    *) fail "unknown argument $1" ;;
  esac
done

resolve_base() {
  local ref sha
  if [ -n "$base" ]; then ref="$base"
  elif [ -n "${CI_BASE_SHA:-}" ] && ! [[ "$CI_BASE_SHA" =~ ^0+$ ]] \
    && { [ "${GITHUB_EVENT_NAME:-}" = pull_request ] || [ "${CI_BRANCH:-}" = main ] || [ "${CI_BRANCH:-}" = restructure ]; }; then
    ref="$CI_BASE_SHA"
  else ref="$(ci_base_ref)"; fi
  sha=$(git -C "$CI_ROOT" merge-base HEAD "$ref" 2>/dev/null) || return 1
  echo "$sha"
}

list_files() {
  if [ -n "$files_from" ]; then
    if [ "$files_from" = - ]; then cat; else cat "$files_from"; fi
    return
  fi
  local mb
  mb=$(resolve_base) || { echo "[ci:changes] no base to compare against; running everything" >&2; echo ".github/everything"; return; }
  { git -C "$CI_ROOT" diff --name-only "$mb"; git -C "$CI_ROOT" ls-files -o --exclude-standard; } | sort -u
}

files=$(list_files)
if [ "$mode" = list ]; then echo "$files"; exit 0; fi

all=false website=false jvm=false webcore=false webfull=false oracle=false
if [ -z "$files" ]; then all=true; echo "[ci:changes] empty diff; running everything" >&2; fi
while IFS= read -r f; do
  [ -n "$f" ] || continue
  case "$f" in
    conformance/* | jvm/conformance-support/* | tools/conformance/* | src/test-support/* | tools/ci/* | .github/*) all=true ;;
  esac
  case "$f" in
    jvm/*)
      jvm=true
      case "$f" in jvm/mod/*) ;; *) webcore=true ;; esac
      case "$f" in jvm/web/* | jvm/core/* | jvm/*.kts | jvm/gradle/* | jvm/gradle.properties) website=true ;; esac ;;
    *) website=true ;;
  esac
  case "$f" in src/lib/core/*) webcore=true ;; esac
  case "$f" in
    tools/dataset-pipeline/gtnh-calc-oracle/*) oracle=true jvm=true ;;
    jvm/mod/src/main/java/com/gtnhplanner/read/* | jvm/core/core-model/src/main/java/com/gtnhplanner/core/model/read/*) oracle=true ;;
  esac
  case "$f" in jvm/web/* | jvm/gradle/* | jvm/*.kts | jvm/gradle.properties | tools/web-core/*) webfull=true; webcore=true ;; esac
done <<< "$files"
if [ "$all" = true ]; then website=true jvm=true webcore=true webfull=true oracle=true; fi

out="website=$website
icons=$website
jvm=$jvm
web_core=$webcore
web_core_full=$webfull
oracle=$oracle"
echo "$out"
if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "$out" >> "$GITHUB_OUTPUT"; fi
