#!/usr/bin/env bash
# Cheap repository metadata checks, run by the `changes` job on every push (no dependencies, a second of work):
#   - the licence shipped inside the jar is the root LICENSE, byte for byte (the jar carries the real text, not a template)
#   - package.json names the licence the root LICENSE grants
#   - a web-* tag on HEAD matches APP_VERSION (tools/ci/version-tags.sh)
set -euo pipefail
. "$(dirname "$0")/lib.sh"
CI_JOB=metadata
cd "$CI_ROOT"

cmp -s LICENSE jvm/mod/src/main/resources/LICENSE || fail "jvm/mod/src/main/resources/LICENSE differs from LICENSE; copy the root file over it"
grep -q '^MIT License' LICENSE || fail "LICENSE is not the MIT text"
python3 -I - <<'PY' || fail "package.json license is not MIT"
import json, sys
sys.exit(0 if json.load(open("package.json")).get("license") == "MIT" else 1)
PY
say "licence files agree"
"$(dirname "$0")/version-tags.sh"
