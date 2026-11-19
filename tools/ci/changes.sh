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

all=false website=false jvm=false webcore=false
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
done <<< "$files"
if [ "$all" = true ]; then website=true jvm=true webcore=true; fi

out="website=$website
icons=$website
jvm=$jvm
web_core=$webcore"
echo "$out"
if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "$out" >> "$GITHUB_OUTPUT"; fi
