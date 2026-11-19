#!/usr/bin/env bash
# Job `web-core`: the TeaVM build of the core and the conformance run in Node.
# STUB. The web stream fills this in (its work items T3, T4, T6); until then it passes and says so.
# Needs, once real: JDK 25 as JAVA_HOME and Node 24 on PATH. The workflow job already sets both up.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
CI_JOB=web-core
say "STUB: the TeaVM build and the conformance run in Node are not written yet; nothing was checked"
