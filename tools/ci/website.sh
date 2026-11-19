#!/usr/bin/env bash
# Job `website`: build the compiled core, typecheck, then the full vitest suite. Needs Node 24 on PATH, `npm ci` done and
# JDK 25 as JAVA_HOME (the core is built by Gradle and TeaVM; the website's tests load it through src/lib/core/loader.ts).
set -euo pipefail
. "$(dirname "$0")/lib.sh"
CI_JOB=website
cd "$CI_ROOT"
[ -d node_modules ] || fail "node_modules is missing: run npm ci first"
say "core build (public/core)"
node tools/copy-core.mjs --build --required || fail "the core did not build"
say "typecheck"
npm run typecheck || fail "typecheck failed"
say "full suite${CI_CORES:+ on cores $CI_CORES}"
GTNH_CORE_REQUIRED=1 pin npm run test || fail "vitest failed"
say "PASS"
