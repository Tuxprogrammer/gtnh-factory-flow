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
  node node_modules/playwright-core/cli.js install --with-deps --only-shell chromium firefox || fail "browser install failed"
fi
say "icon sizing in Chromium and Firefox"
pin npm run test:icons:browsers || fail "icon sizing failed"
say "PASS"
