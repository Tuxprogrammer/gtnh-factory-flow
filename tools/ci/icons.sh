#!/usr/bin/env bash
# Job `icons`: the icon-sizing check in Chromium and Firefox. Needs Node 24 and `npm ci` done.
# CI_INSTALL_BROWSERS=1 installs the two browsers and their system packages first (a hosted runner has neither).
set -euo pipefail
. "$(dirname "$0")/lib.sh"
CI_JOB=icons
cd "$CI_ROOT"
[ -d node_modules ] || fail "node_modules is missing: run npm ci first"
if [ "${CI_INSTALL_BROWSERS:-0}" = 1 ]; then
  say "installing browsers"
  # The download comes from a CDN and the system packages from the distribution's mirrors; either can stall (the install once
  # ran past nine minutes). Each try is bounded and a stalled one is abandoned and tried again, three times at most. A browser
  # already in ~/.cache/ms-playwright (the workflow restores it) is not downloaded again.
  if command -v sudo >/dev/null 2>&1 && [ -d /etc/apt/apt.conf.d ]; then
    echo 'Acquire::Retries "5"; Acquire::http::Timeout "30"; Acquire::https::Timeout "30";' | sudo tee /etc/apt/apt.conf.d/80-ci-retries >/dev/null || true
  fi
  ok=0
  for try in 1 2 3; do
    if timeout 480 node node_modules/playwright-core/cli.js install --with-deps --only-shell chromium firefox; then ok=1; break; fi
    say "browser install failed or timed out (try $try of 3)"; sleep $((try * 15))
  done
  [ "$ok" = 1 ] || fail "browser install failed three times"
fi
say "icon sizing in Chromium and Firefox"
pin npm run test:icons:browsers || fail "icon sizing failed"
say "PASS"
