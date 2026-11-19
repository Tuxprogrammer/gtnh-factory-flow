#!/usr/bin/env bash
# Job `website`: typecheck, then the full vitest suite. Needs Node 24 on PATH and `npm ci` done.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
CI_JOB=website
cd "$CI_ROOT"
[ -d node_modules ] || fail "node_modules is missing: run npm ci first"
say "typecheck"
npm run typecheck || fail "typecheck failed"
say "full suite${CI_CORES:+ on cores $CI_CORES}"
pin npm run test || fail "vitest failed"
say "PASS"
