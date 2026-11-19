#!/usr/bin/env node
// The conformance run of the compiled core, in Node: vector files and the random cases, for each target.
//   node tools/web-core/run.mjs --build jvm/web/build/generated/teavm [--random FILE] [--targets wasm,js]
//        [--solver-jvm DIR] [--solver-report FILE] [--solver-runs N] [--solver-only NAME,...] [--bench] [--full]
// `--solver-only` runs just the solver cases whose name contains one of the given texts (all of them, not the sample).
// The WebAssembly target always runs every case; the JavaScript target runs a sample of the machine, bee and crop goldens,
// the layout boards and the solver corpus (sample.mjs, solver.mjs) unless --full. The pipeline passes --full when jvm/web or the TeaVM version changes.
// Prints one line per group and target, then the differences; exit 1 when there is any difference, a target does not
// load, or a group checked nothing. Needs Node 24 (Wasm GC, ES module detection).
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { Tally } from "./compare.mjs";
import { TARGETS, loadTarget } from "./load.mjs";
import { runFormat } from "./format.mjs";
import { runMachines } from "./machines.mjs";
import { SAMPLE_EVERY } from "./sample.mjs";
import { jsSample, runSolver } from "./solver.mjs";
import { runLayout, runPower } from "./goldens.mjs";
import { checkJvmAgainstNode, readCases, runRandom } from "./random.mjs";
import { loadVectors, runBuildProbes, runVectors, vectorFile } from "./vectors.mjs";

function args() {
  const out = { build: "jvm/web/build/generated/teavm", random: null, targets: ["wasm", "js"], solverJvm: null, solverReport: null, solverRuns: 1, solverOnly: null, bench: false, full: false };
  const a = process.argv.slice(2);
  for (let i = 0; i < a.length; i++) {
    if (a[i] === "--build") out.build = a[++i];
    else if (a[i] === "--random") out.random = a[++i];
    else if (a[i] === "--targets") out.targets = a[++i].split(",");
    else if (a[i] === "--solver-jvm") out.solverJvm = a[++i];
    else if (a[i] === "--solver-report") out.solverReport = a[++i];
    else if (a[i] === "--solver-runs") out.solverRuns = Number(a[++i]);
    else if (a[i] === "--solver-only") out.solverOnly = a[++i].split(",");
    else if (a[i] === "--bench") out.bench = true;
    else if (a[i] === "--full") out.full = true;
    else throw new Error(`unknown argument ${a[i]}`);
  }
  return out;
}

const opts = args();
const say = (s) => console.log(`[web-core] ${s}`);
let failed = false;
const solverRows = {};

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
  const every = opts.full || target === "wasm" ? 1 : SAMPLE_EVERY;
  if (!opts.solverOnly) {
    runBuildProbes(api, tally);
    runVectors(api, vectors, tally);
    runFormat(api, tally);
    if (opts.random) runRandom(api, cases, tally);
    runMachines(api, tally, { every });
    runPower(api, tally);
    runLayout(api, tally, { every });
  }
  const select = opts.solverOnly ? (all) => all.filter((c) => opts.solverOnly.some((n) => c.name.includes(n))) : opts.full || target === "wasm" ? undefined : jsSample;
  solverRows[target] = runSolver(api, tally, { bench: opts.bench, jvmDir: opts.solverJvm, runs: opts.solverRuns, select });
  if (opts.solverJvm) {
    const n = (b) => solverRows[target].filter((r) => r.bits === b).length;
    const drift = Math.max(0, ...solverRows[target].filter((r) => r.bits === "near").map((r) => r.drift ?? 0));
    say(`${target}: solver answers against the JVM: ${n("identical")} bit-identical, ${n("near")} within 1e-11 (largest ${drift}), ${n("differs")} differ`);
  }
  for (const g of ["solver goldens", "solver up to"]) {
    const rows = solverRows[target].filter((r) => (r.kind === "golden") === (g === "solver goldens"));
    if (tally.groups.has(g)) tally.groups.get(g).ms = Math.round(rows.reduce((n, r) => n + r.ms, 0));
  }
  report(`${target} (${label})`, tally);
}

function report(title, tally) {
  for (const [name, g] of [...tally.groups].sort()) {
    const bad = g.differ > 0 || g.checked === 0;
    say(`${title}  ${name.padEnd(24)} ${String(g.checked).padStart(7)} checked  ${g.differ} differ${g.ms === undefined ? "" : `  ${g.ms} ms`}${bad ? "  <-- FAIL" : ""}`);
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

if (opts.solverReport) writeFileSync(opts.solverReport, JSON.stringify(solverRows, null, 1));

if (failed) {
  say("FAIL: the compiled core differs from the vectors or from the JVM");
  process.exit(1);
}
say("PASS");
