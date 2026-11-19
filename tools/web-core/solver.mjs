// The solver corpus, run against one loaded target through SolverExports (JSON in, JSON out): the same cases, compared
// the same way, as the JVM's SolverGoldenTest and UpToParityTest.
//   conformance/solver/corpus/inputs/*.json     31 plans, each with its golden under corpus/goldens (the mod's answers)
//   platline-x4, platline-x10                   the platline tiled 4 and 10 times (the inputs are built here as the mod's
//                                               SolverCorpus.tile does); x10 only with `bench`
//   conformance/solver/up-to/*.json             74 plans, each with the website's answer
// The rule, from SolverCorpus.differences: the same keys, the same array lengths and order, and numbers equal when
// p == q or |p - q| <= 1e-6 * max(1, |p|, |q|). Three Up to plans are held to the part of the answer the JVM test holds
// them to (UpToParityTest.WITHIN_TOLERANCE). With `jvmDir` (digests the JVM's DigestDump wrote) each answer is also
// compared with the JVM's own, to 1e-11, and counted as bit-identical when every number is equal.
import { createHash } from "node:crypto";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { sampleOf } from "./sample.mjs";

const ROOT = fileURLToPath(new URL("../../", import.meta.url));
const solverDir = (rel) => path.join(ROOT, "conformance", "solver", rel);
const readJson = (file) => JSON.parse(readFileSync(file, "utf8"));

/** Plans the website's engine and the mod's answer alike only to within the LP's tolerance (UpToParityTest). */
const WITHIN_TOLERANCE = new Set(["mv-every-count-up-to.json", "idle-noise.json", "idle-noise-beside-a-lone-card.json"]);

export const TILES = { "platline-x4": 4, "platline-x10": 10 };

/** The golden rule's number equality. */
const near = (p, q) => p === q || Math.abs(p - q) <= 1e-6 * Math.max(1, Math.abs(p), Math.abs(q));

/** SolverCorpus.differences: the first few places two digests differ. */
export function differences(expected, actual, close = near) {
  const out = [];
  compare("", expected, actual, out, close);
  return out;
}

function compare(where, a, b, out, close) {
  if (out.length > 20) return;
  const abbreviate = (x) => (JSON.stringify(x) ?? "undefined").slice(0, 300);
  if (a && b && typeof a === "object" && typeof b === "object" && !Array.isArray(a) && !Array.isArray(b)) {
    for (const k of [...new Set([...Object.keys(a), ...Object.keys(b)])].sort()) {
      if (!(k in a) || !(k in b)) out.push(`${where}/${k}: ${abbreviate(a[k])} / ${abbreviate(b[k])}`);
      else compare(`${where}/${k}`, a[k], b[k], out, close);
    }
    return;
  }
  if (Array.isArray(a) && Array.isArray(b)) {
    if (a.length !== b.length) {
      out.push(`${where}: ${a.length} items / ${b.length}: ${abbreviate(a)} / ${abbreviate(b)}`);
      return;
    }
    for (let i = 0; i < a.length; i++) compare(`${where}[${i}]`, a[i], b[i], out, close);
    return;
  }
  if (typeof a === "number" && typeof b === "number") {
    if (!close(a, b)) out.push(`${where}: ${a} / ${b}`);
    return;
  }
  if (a !== b) out.push(`${where}: ${abbreviate(a)} / ${abbreviate(b)}`);
}

/** UpToParityTest.normalised: the shortfalls' limiting nodes and drawers are sets, sorted by their text. */
export function normalised(digest) {
  if (Array.isArray(digest)) digest.forEach(normalised);
  else if (digest && typeof digest === "object") {
    for (const key of ["limitingNodes", "limitingDrawers"]) if (Array.isArray(digest[key])) digest[key].sort();
    Object.values(digest).forEach(normalised);
  }
  return digest;
}

/** The largest relative difference of two digests that agree in shape (max |p-q| / max(1,|p|,|q|)), and how many numbers differ at all. */
export function drift(a, b, acc = { max: 0, unequal: 0, shape: 0 }) {
  if (typeof a === "number" && typeof b === "number") {
    if (a !== b) {
      acc.unequal++;
      acc.max = Math.max(acc.max, Math.abs(a - b) / Math.max(1, Math.abs(a), Math.abs(b)));
    }
  } else if (Array.isArray(a) && Array.isArray(b)) {
    if (a.length !== b.length) acc.shape++;
    else a.forEach((x, i) => drift(x, b[i], acc));
  } else if (a && b && typeof a === "object" && typeof b === "object" && !Array.isArray(a) && !Array.isArray(b)) {
    for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) {
      if (!(k in a) || !(k in b)) acc.shape++;
      else drift(a[k], b[k], acc);
    }
  } else if (a !== b) acc.shape++;
  return acc;
}

// ---- tiling: SolverCorpus.tile, on the input file's JSON

/** Java's UUID.nameUUIDFromBytes: MD5 with the version and variant bits set. */
export function nameUuid(text) {
  const h = createHash("md5").update(text, "utf8").digest();
  h[6] = (h[6] & 0x0f) | 0x30;
  h[8] = (h[8] & 0x3f) | 0x80;
  const x = h.toString("hex");
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`;
}

export function tile(input, k) {
  const out = { mode: input.mode, machines: [], edges: [], pools: [], drawers: [], extentPins: {}, spares: {} };
  for (let c = 0; c < k; c++) {
    const id = (u) => (c === 0 ? u : nameUuid(`${c}:${u}`));
    const remap = (key) => {
      if (key.indexOf(":") !== 36) return key;
      const head = key.slice(0, 36);
      return /^[0-9a-f-]{36}$/i.test(head) ? id(head) + key.slice(36) : key;
    };
    for (const m of input.machines) out.machines.push({ ...m, id: id(m.id) });
    for (const e of input.edges) out.edges.push({ ...e, id: id(e.id), source: id(e.source), target: id(e.target) });
    for (const p of input.pools) out.pools.push({ ...p, machines: p.machines.map(id) });
    for (const d of input.drawers) {
      const link = (l) => ({ ...l, node: id(l.node) });
      const copy = { ...d, id: id(d.id), links: d.links.map(link) };
      if (d.takers) copy.takers = d.takers.map(link);
      out.drawers.push(copy);
    }
    for (const [u, v] of Object.entries(input.extentPins)) out.extentPins[id(u)] = v;
    for (const [key, shares] of Object.entries(input.spares)) {
      out.spares[remap(key)] = Object.fromEntries(Object.entries(shares).map(([s, v]) => [remap(s), v]));
    }
  }
  return out;
}

// ---- the cases

/** Every case: {name, kind: "golden" | "up to", input (JSON text), expected, tolerance?}. */
export function solverCases({ bench = false } = {}) {
  const cases = [];
  const inputs = readdirSync(solverDir("corpus/inputs")).filter((f) => f.endsWith(".json")).sort();
  for (const f of inputs) {
    const name = f.replace(/\.json$/, "");
    cases.push({
      name,
      kind: "golden",
      input: readFileSync(solverDir(`corpus/inputs/${f}`), "utf8"),
      expected: readJson(solverDir(`corpus/goldens/${name}.json`)),
    });
  }
  const platline = readJson(solverDir("corpus/inputs/platline.json"));
  for (const [name, k] of Object.entries(TILES)) {
    if (name === "platline-x10" && !bench) continue;
    const expected = readJson(solverDir(`corpus/goldens/${name}.json`));
    // Recorded before the field existed (conformance/solver/corpus/README.md): the answer's own is dropped to match.
    cases.push({ name, kind: "golden", input: JSON.stringify(tile(platline, k)), expected, dropUnneeded: name === "platline-x10" });
  }
  for (const f of readdirSync(solverDir("up-to")).filter((f) => f.endsWith(".json")).sort()) {
    const plan = readJson(solverDir(`up-to/${f}`));
    cases.push({
      name: `up to: ${plan.name}`,
      file: f,
      kind: "up to",
      input: JSON.stringify(plan.input),
      expected: plan.answer,
      tolerance: WITHIN_TOLERANCE.has(f),
    });
  }
  return cases;
}

// The cases the JavaScript target leaves out of a run that is not `--full`: each takes over 3 s there (WebAssembly runs
// them all, and the other cases check the same Java source on JavaScript). Every other golden runs on JavaScript, so an
// error in the solver's own arithmetic still fails. Of the Up to plans, every hand-written one runs (each is small and
// the only one that reads some rule), and every 4th of the "every count Up to" plans made from the goldens' inputs.
const HEAVY = ["gtnh-flow-palladium_line", "platline-x4"];
const UP_TO_EVERY = 4;

/** The cases of the JavaScript sample. */
export function jsSample(cases) {
  const light = cases.filter((c) => !HEAVY.some((h) => c.name.includes(h)));
  const group = (c) => (c.kind === "up to" && c.file.endsWith("every-count-up-to.json") ? "every count Up to" : c.name);
  return sampleOf(light, UP_TO_EVERY, group);
}

const part = (digest, key) => digest?.solution?.[key] ?? {};

/** machineCounts further apart than a hundred-thousandth of a machine (UpToParityTest.countsDiffer). */
function countsDiffer(expected, actual) {
  const out = [];
  for (const [k, v] of Object.entries(expected)) {
    const got = actual[k];
    if (got === undefined || Math.abs(got - v) > 1e-5) out.push(`machineCounts/${k}: ${v} / ${got}`);
  }
  return out;
}

const jvmFile = (dir, name) => path.join(dir, `${name.replace(/[: ]/g, "_")}.json`);

/**
 * Runs the cases of one target. `select(cases)` may thin them out (the JS sample); `jvmDir` holds the JVM's digests.
 * Fills the tally's groups "solver goldens", "solver up to" and, with jvmDir, "solver against the JVM"; returns one row per
 * case: {name, kind, ms, bits: "identical" | "near" | "differs" | null, drift}.
 */
export function runSolver(api, tally, { bench = false, jvmDir = null, runs = 1, select = (c) => c } = {}) {
  const rows = [];
  const solver = api.SolverExports;
  if (!solver) {
    tally.check("solver goldens", false, () => "the build has no SolverExports");
    return rows;
  }
  const cases = select(solverCases({ bench }));
  for (const c of cases) {
    const group = c.kind === "golden" ? "solver goldens" : "solver up to";
    let actual = null;
    let ms = 0;
    try {
      const t = performance.now();
      const text = solver.solverSolve(c.input);
      ms = performance.now() - t;
      actual = normalised(JSON.parse(text));
      // Timing only: the answer of the first solve is the one checked; the least wall time of `runs` solves is reported.
      for (let i = 1; i < runs; i++) {
        const t2 = performance.now();
        solver.solverSolve(c.input);
        ms = Math.min(ms, performance.now() - t2);
      }
    } catch (e) {
      tally.check(group, false, () => `${c.name}: ${e?.stack ?? e}`);
      rows.push({ name: c.name, kind: c.kind, ms, bits: null });
      continue;
    }
    let diffs;
    if (c.kind === "golden") {
      const expected = c.expected;
      const own = c.dropUnneeded ? structuredClone(actual) : actual;
      if (c.dropUnneeded) delete own.solution.unneeded;
      diffs = differences(expected, own);
    } else if (c.tolerance) {
      const expected = normalised(structuredClone(c.expected));
      diffs = [
        ...differences(part(expected, "upTo"), part(actual, "upTo")),
        ...differences(part(expected, "upToLoops"), part(actual, "upToLoops")),
        ...countsDiffer(part(expected, "machineCounts"), part(actual, "machineCounts")),
      ];
    } else diffs = differences(normalised(structuredClone(c.expected)), actual);
    tally.check(group, diffs.length === 0, () => `${c.name}: ${diffs.slice(0, 3).join("; ")}`);
    const row = { name: c.name, kind: c.kind, ms, bits: null };
    if (jvmDir) {
      const file = jvmFile(jvmDir, c.name);
      if (existsSync(file)) {
        const jvm = JSON.parse(readFileSync(file, "utf8"));
        const d = drift(jvm, actual);
        row.drift = d.max;
        row.bits = d.shape === 0 && d.unequal === 0 ? "identical" : d.shape === 0 && d.max <= 1e-11 ? "near" : "differs";
        tally.check("solver against the JVM", d.shape === 0 && d.max <= 1e-11, () => `${c.name}: ${d.shape} shape differences, drift ${d.max}`);
      }
    }
    rows.push(row);
  }
  return rows;
}
