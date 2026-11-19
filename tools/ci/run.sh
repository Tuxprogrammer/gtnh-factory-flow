#!/usr/bin/env bash
# Run the CI jobs a diff needs, one after the other, and end with `CI-LOCAL: PASS` or `CI-LOCAL: FAIL (<jobs>)`.
# The build box's `box.sh <stream> ci` is this script; GitHub runs the same job scripts one per job.
#   run.sh                      the jobs the diff against the default base needs (changes.sh decides)
#   run.sh --base REF           the same against REF
#   run.sh --files FILE         the changed file list is in FILE
#   run.sh --all                every job
#   run.sh website jvm ...      exactly these jobs (website icons jvm web-core)
# Env: CI_CORES (taskset list the jobs are pinned to), CI_LOG_DIR (default scratch/ci-logs), CI_VERBOSE=1 (stream every
# job's output; otherwise it goes to <CI_LOG_DIR>/<job>.log and only the failures and the tail are shown).
set -uo pipefail
. "$(dirname "$0")/lib.sh"
cd "$CI_ROOT"

selected=() cargs=() all=false
while [ $# -gt 0 ]; do
  case "$1" in
    --base | --files) cargs+=("$1" "$2"); shift 2 ;;
    --all) all=true; shift ;;
    website | icons | jvm | web-core) selected+=("$1"); shift ;;
    *) echo "run.sh: unknown argument $1" >&2; exit 2 ;;
  esac
done

if [ ${#selected[@]} -eq 0 ]; then
  if [ "$all" = true ]; then
    selected=(website icons jvm web-core)
  else
    decision=$(tools/ci/changes.sh "${cargs[@]}") || { echo "CI-LOCAL: FAIL (changes)"; exit 1; }
    echo "[ci] changes: $(echo "$decision" | paste -sd' ' -)"
    [[ "$decision" == *website=true* ]] && selected+=(website)
    [[ "$decision" == *jvm=true* ]] && selected+=(jvm)
    [[ "$decision" == *web_core=true* ]] && selected+=(web-core)
    [[ "$decision" == *icons=true* ]] && selected+=(icons)
  fi
fi

logdir="${CI_LOG_DIR:-scratch/ci-logs}"; mkdir -p "$logdir"
failed=() summary=()
for job in "${selected[@]}"; do
  script="tools/ci/$job.sh"
  log="$logdir/$job.log"
  echo "[ci] $job: start"
  start=$(date +%s)
  if [ "${CI_VERBOSE:-0}" = 1 ]; then
    "$script" 2>&1 | tee "$log"; rc=${PIPESTATUS[0]}
  else
    "$script" > "$log" 2>&1; rc=$?
  fi
  secs=$(( $(date +%s) - start ))
  plain=$(sed 's/\x1b\[[0-9;]*m//g' "$log")
  if [ "$rc" -ne 0 ]; then
    failed+=("$job"); summary+=("$job: FAIL (${secs}s)")
    if [ "${CI_VERBOSE:-0}" != 1 ]; then
      echo "[ci] $job: failure lines"
      echo "$plain" | grep -E '^ FAIL |^ +× |Error: Test timed out|error TS|\[ci:[a-z-]+\] FAIL|^FAILURE|^> Task .* FAILED|^[^ ]+ > .* FAILED$|What went wrong|^\* What|[Tt]ests? (were )?failed' | sort -u | head -30
      echo "[ci] $job: last lines of $log"
      echo "$plain" | tail -15
    fi
  else
    summary+=("$job: PASS (${secs}s)")
    [ "${CI_VERBOSE:-0}" = 1 ] || echo "$plain" | grep -E '^ +Tests |Test Files|^\[ci:jvm\] (mod|core-|total)' | tail -8
  fi
done

echo "[ci] ----"
for s in "${summary[@]:-}"; do [ -n "$s" ] && echo "[ci] $s"; done
for j in website icons jvm web-core; do
  case " ${selected[*]:-} " in *" $j "*) ;; *) echo "[ci] $j: skipped (the diff does not need it)" ;; esac
done
if [ ${#failed[@]} -eq 0 ]; then
  echo "CI-LOCAL: PASS"
else
  echo "CI-LOCAL: FAIL ($(IFS=,; echo "${failed[*]}"))"
  exit 1
fi
