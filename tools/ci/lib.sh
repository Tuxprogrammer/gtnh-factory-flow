# Sourced by every script in tools/ci/. The scripts are the one definition of each CI job:
# .github/workflows/ci.yml and the build box's pipeline both call them and nothing else.
CI_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

say() { echo "[ci:${CI_JOB:-ci}] $*"; }
fail() { echo "[ci:${CI_JOB:-ci}] FAIL: $*" >&2; exit 1; }

# Run a command pinned to the cores in CI_CORES (a taskset list, "0-3"), so a local run sees the
# four cores a hosted runner has. Unset on GitHub.
pin() {
  if [ -n "${CI_CORES:-}" ]; then taskset -c "$CI_CORES" "$@"; else "$@"; fi
}

# The branch a diff is judged against: main for main and fix/**, restructure for everything else.
ci_base_ref() {
  local b="${CI_BRANCH:-$(git -C "$CI_ROOT" branch --show-current 2>/dev/null || true)}"
  case "$b" in
    main | fix/*) echo origin/main ;;
    *) echo origin/restructure ;;
  esac
}
