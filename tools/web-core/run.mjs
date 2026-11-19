#!/usr/bin/env node
// The conformance run of the compiled core, in Node: vector files and the random cases, for each target.
//   node tools/web-core/run.mjs --build jvm/web/build/generated/teavm [--random FILE] [--targets wasm,js]
// Prints one line per group and target, then the differences; exit 1 when there is any difference, a target does not
// load, or a group checked nothing. Needs Node 24 (Wasm GC, ES module detection).
import { readFileSync } from "node:fs";
import path from "node:path";
import { Tally } from "./compare.mjs";
import { TARGETS, loadTarget } from "./load.mjs";
import { runFormat } from "./format.mjs";
import { runMachines } from "./machines.mjs";
import { checkJvmAgainstNode, readCases, runRandom } from "./random.mjs";
import { loadVectors, runBuildProbes, runVectors, vectorFile } from "./vectors.mjs";

function args() {
  const out = { build: "jvm/web/build/generated/teavm", random: null, targets: ["wasm", "js"] };
  const a = process.argv.slice(2);
  for (let i = 0; i < a.length; i += 2) {
    if (a[i] === "--build") out.build = a[i + 1];
    else if (a[i] === "--random") out.random = a[i + 1];
    else if (a[i] === "--targets") out.targets = a[i + 1].split(",");
    else throw new Error(`unknown argument ${a[i]}`);
  }
  return out;
}

const opts = args();
const say = (s) => console.log(`[web-core] ${s}`);
let failed = false;

say(`node ${process.version}`);
const known = JSON.parse(readFileSync(new URL("./known-differences.json", import.meta.url), "utf8")).known;
const vectors = loadVectors();
say(`vectors: ${path.relative(process.cwd(), vectorFile("numberText"))}, ${path.relative(process.cwd(), vectorFile("uuid"))}`);
const cases = opts.random ? readCases(opts.random) : [];
if (opts.random) {
  say(`random cases: ${cases.length} from ${opts.random}`);
  const jvm = new Tally("jvm");
  checkJvmAgainstNode(cases, jvm);
  report("core on the JVM against Node", jvm);
}

for (const target of opts.targets) {
  const label = TARGETS[target].label;
  const tally = new Tally(target, known);
  let api;
  try {
    api = await loadTarget(opts.build, target);
  } catch (e) {
    say(`${target}: FAIL to load: ${e?.stack ?? e}`);
    failed = true;
    continue;
  }
  runBuildProbes(api, tally);
  runVectors(api, vectors, tally);
  runFormat(api, tally);
  if (opts.random) runRandom(api, cases, tally);
  runMachines(api, tally);
  report(`${target} (${label})`, tally);
}

function report(title, tally) {
  for (const [name, g] of [...tally.groups].sort()) {
    const bad = g.differ > 0 || g.checked === 0;
    say(`${title}  ${name.padEnd(24)} ${String(g.checked).padStart(7)} checked  ${g.differ} differ${bad ? "  <-- FAIL" : ""}`);
    for (const d of g.first) say(`    ${d}`);
    if (bad) failed = true;
  }
  for (const k of tally.known) {
    const hits = tally.knownHits.get(k.id);
    const stale = hits === 0;
    say(`${title}  KNOWN ${k.id}: ${hits} cases${stale ? "  <-- FAIL: no longer happens, delete the entry from known-differences.json" : ""}`);
    say(`    ${k.finding}`);
    if (stale) failed = true;
  }
  say(`${title}  total ${tally.checks} checked, ${tally.differences} differ`);
}

if (failed) {
  say("FAIL: the compiled core differs from the vectors or from the JVM");
  process.exit(1);
}
say("PASS");
