#!/usr/bin/env bash
# Job `web-core`: the TeaVM build of the core (WebAssembly GC and JavaScript), its JVM-side tests and override check, and
# the conformance run in Node: the committed vectors and a few hundred thousand random cases the JVM made, against each
# target (the JavaScript one a sample unless the diff needs the full run). Any difference fails the job. Prints the size (raw and gzip) of each file the website would ship and the times.
# Needs JDK 25 as JAVA_HOME (it runs Gradle and TeaVM), Node 24 on PATH, and no Gradle daemon.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
CI_JOB=web-core
. "$(dirname "$0")/net.sh"
cd "$CI_ROOT"

node_major=$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)
[ "$node_major" -ge 24 ] || fail "Node 24 or newer is needed (Wasm GC, module detection); found $(node -v 2>/dev/null || echo none)"

envs=$(compgen -v | grep -E '^JAVA_HOME_[0-9]+_X64$' | paste -sd, - || true)
extra=("${GRADLE_NET_OPTS[@]}")
if [ -n "$envs" ]; then extra+=("-Porg.gradle.java.installations.fromEnv=$envs"); fi

# Output of an earlier run must never count.
build=jvm/web/build
rm -rf "$build/generated" "$build/random" "$build/test-results" "$build/overrides"

started=$(date +%s)
say "gradle :web:check :web:webCore :web:webRandomCases${CI_CORES:+ on cores $CI_CORES}"
(cd jvm && pin ./gradlew --no-daemon --console=plain "${extra[@]}" :web:check :web:webCore :web:webRandomCases) \
  || fail "the web build failed (compile, override check or JVM tests)"
built=$(date +%s)

# The JVM-side tests (export convention) must have run: `check` can pass on NO-SOURCE.
ran=$(python3 -I - "$build/test-results/test" <<'PY'
import sys, glob
import xml.etree.ElementTree as ET
n = 0
for f in glob.glob(sys.argv[1] + "/*.xml"):
    r = ET.parse(f).getroot()
    n += int(r.get("tests", 0)) - int(r.get("skipped", 0))
print(n)
PY
)
[ "$ran" -ge 3 ] || fail "the web module's JVM tests ran $ran tests, expected at least 3"
say "web module JVM tests: $ran ran"

teavm="$build/generated/teavm"
for f in wasm-gc/core.wasm wasm-gc/core.wasm-runtime.js js/core.js; do
  [ -s "$teavm/$f" ] || fail "$teavm/$f was not built"
done
say "sizes (raw / gzip -9, bytes):"
for f in wasm-gc/core.wasm wasm-gc/core.wasm-runtime.js js/core.js; do
  say "  $(printf '%-28s %9d %9d' "$f" "$(wc -c < "$teavm/$f")" "$(gzip -9 -c "$teavm/$f" | wc -c)")"
done

# Every case on the JavaScript target too when the diff touches the web build or the TeaVM version (changes.sh says so),
# or WEB_CORE_FULL=1 asks; otherwise a sample of the machine, bee and crop goldens and of the solver corpus (sample.mjs).
full=()
if [ "${CI_WEB_CORE_FULL:-}" = true ] || [ "${WEB_CORE_FULL:-}" = 1 ]; then full=(--full); fi
say "conformance: ${full[*]:-sampled on the JavaScript target}"
node tools/web-core/run.mjs --build "$teavm" --random "$build/random/cases.jsonl" "${full[@]}" || fail "the compiled core differs from the vectors or from the JVM (above)"
done=$(date +%s)
say "build $((built - started))s, conformance $((done - built))s"
say "PASS"
