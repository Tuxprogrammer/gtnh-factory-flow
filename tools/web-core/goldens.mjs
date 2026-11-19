// The shared conformance files that need more than a number or an id: the power goldens and the layout router's cases,
// run against one loaded target through its JSON-in, JSON-out exports (PowerExports, LayoutExports).
//   conformance/power/power-goldens.jsonl.gz   one record per line (see src/lib/power/golden-records.ts), numbers to 1e-9
//   conformance/layout/router-tests.json       solves, incremental sequences, rim docks: bit for bit
//   conformance/layout/boards.json.gz          the reference boards, each routed fresh, dragged and dropped: bit for bit
// The same files the JVM tests read (PowerGoldensTest, RouterConformanceTest).
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { gunzipSync } from "node:zlib";
import { sampleOf } from "./sample.mjs";

const ROOT = fileURLToPath(new URL("../../", import.meta.url));
const conformance = (rel) => path.join(ROOT, "conformance", rel);
const readJson = (rel) => {
  const raw = readFileSync(conformance(rel));
  return JSON.parse((rel.endsWith(".gz") ? gunzipSync(raw) : raw).toString("utf8"));
};

/** Non-finite numbers are strings in the files; a number the tests compare is the number they spell. */
const toNumber = (x) => (x === "Infinity" ? Infinity : x === "-Infinity" ? -Infinity : x === "NaN" ? NaN : x);

/**
 * The first difference between what the files say and what the core answered, or null. A missing field and a null are
 * the same thing (JSON.stringify drops undefined). With `tolerance`, finite numbers may differ by that much relative.
 */
export function firstDifference(want, got, where = "", tolerance = 0) {
  if (want == null || got == null) return want == null && got == null ? null : `${where}: ${JSON.stringify(got)} for ${JSON.stringify(want)}`;
  if (typeof want === "number" || typeof got === "number") {
    const w = toNumber(want);
    const g = toNumber(got);
    if (typeof w !== "number" || typeof g !== "number") return `${where}: ${JSON.stringify(got)} for ${JSON.stringify(want)}`;
    if (w === g || (Number.isNaN(w) && Number.isNaN(g))) return null;
    if (tolerance > 0 && Math.abs(w - g) <= tolerance * Math.max(Math.abs(w), Math.abs(g))) return null;
    return `${where}: ${g} for ${w}`;
  }
  if (Array.isArray(want) || Array.isArray(got)) {
    if (!Array.isArray(want) || !Array.isArray(got)) return `${where}: ${JSON.stringify(got)} for ${JSON.stringify(want)}`;
    if (want.length !== got.length) return `${where}: ${got.length} items for ${want.length}`;
    for (let i = 0; i < want.length; i++) {
      const d = firstDifference(want[i], got[i], `${where}[${i}]`, tolerance);
      if (d) return d;
    }
    return null;
  }
  if (typeof want === "object" && typeof got === "object") {
    for (const k of new Set([...Object.keys(want), ...Object.keys(got)])) {
      const d = firstDifference(want[k], got[k], where ? `${where}.${k}` : k, tolerance);
      if (d) return d;
    }
    return null;
  }
  return want === got ? null : `${where}: ${JSON.stringify(got)} for ${JSON.stringify(want)}`;
}

function timed(tally, name, fn) {
  const t = performance.now();
  fn();
  tally.group(name).ms = Math.round(performance.now() - t);
}

// ---- power

export function runPower(api, tally) {
  const power = api.PowerExports;
  const lines = gunzipSync(readFileSync(conformance("power/power-goldens.jsonl.gz")))
    .toString("utf8")
    .split("\n")
    .filter(Boolean);
  const seen = new Set();
  timed(tally, "power/sources", () => {
    for (const line of lines) {
      const r = JSON.parse(line);
      let group;
      let diff;
      try {
        if (r.kind === "cardTier") {
          group = "power/cardTier";
          const got = power.powerCardTier(toNumber(r.euPerTick));
          diff = firstDifference(r.tier, got, "tier");
        } else if (r.kind === "turbineFlow") {
          group = "power/turbineFlow";
          const got = JSON.parse(power.powerTurbineFlow(r.source, JSON.stringify({ settings: r.settings, running: r.running, pin: r.pin })));
          diff = firstDifference({ turbines: r.turbines, may: r.may, check: r.check }, got, "", 1e-9);
        } else if (r.case === undefined) {
          group = "power/describe";
          seen.add(r.source);
          const text = power.powerDescribe(r.source);
          diff = text == null ? "source missing in the core" : firstDifference(r, JSON.parse(text), "");
        } else {
          group = "power/case";
          const text = power.powerCompute(r.source, JSON.stringify(r.settings));
          diff = text == null ? "source missing in the core" : firstDifference(r.model, JSON.parse(text), "model", 1e-9);
        }
      } catch (e) {
        diff = `threw ${e}`;
      }
      tally.check(group, diff == null, () => `${r.source ?? ""} ${r.case ?? r.kind ?? "describe"} ${JSON.stringify(r.settings ?? "")}: ${diff}`);
    }
  });
  const ids = JSON.parse(power.powerSourceIds());
  tally.check("power/sources", ids.length === seen.size && ids.every((id) => seen.has(id)), () => `${ids.length} sources in the core, ${seen.size} in the file`);
}

// ---- layout

/** A route as the files spell it, with the fields the Java test defaults filled in so both sides read the same. */
const endpoint = (e) => (e == null ? null : { x: e.x, y: e.y, side: e.side ?? null, penalty: e.penalty ?? 0, stubDepth: e.stubDepth ?? 0 });

function route(r) {
  return { edgeId: r.edgeId, width: r.width ?? null, points: r.points ?? null, vertices: r.vertices ?? null, source: endpoint(r.source), target: endpoint(r.target) };
}

/** What an expected result says, in the shape the core answers: the routes, and the stats and measure keys it lists. */
function expected(c, answer) {
  const stats = {};
  for (const k of Object.keys(c.stats ?? {})) if (k in answer.stats) stats[k] = c.stats[k];
  if ("fresh" in answer.stats) stats.fresh = c.stats?.fresh ?? false;
  const measure = {};
  for (const k of Object.keys(c.measure ?? {})) if (k in answer.measure) measure[k] = c.measure[k];
  return { routes: c.routes.map(route), stats, measure };
}

function compareResult(c, answer) {
  const want = expected(c, answer);
  const got = {
    routes: answer.routes.map(route),
    stats: Object.fromEntries(Object.keys(want.stats).map((k) => [k, answer.stats[k]])),
    measure: Object.fromEntries(Object.keys(want.measure).map((k) => [k, answer.measure[k]])),
  };
  return firstDifference(want, got, "");
}

const strip = (step) => {
  if ("restore" in step || "reset" in step) return step;
  const { routes, stats, measure, ...input } = step;
  return input;
};

// `every` thins the reference boards the way the machine goldens are thinned (sample.mjs): every (every / 4)-th board.
export function runLayout(api, tally, { every = 1 } = {}) {
  const layout = api.LayoutExports;
  const tests = readJson("layout/router-tests.json");
  timed(tally, "layout/solve", () => {
    for (const c of tests.solves) {
      const { routes, stats, measure, name, ...input } = c;
      let diff;
      try {
        diff = compareResult(c, JSON.parse(layout.layoutSolve(JSON.stringify(input))));
      } catch (e) {
        diff = `threw ${e}`;
      }
      tally.check("layout/solve", diff == null, () => `${c.name}: ${diff}`);
    }
  });
  timed(tally, "layout/sequence", () => {
    for (const s of tests.sequences) {
      let diff;
      try {
        const answers = JSON.parse(layout.layoutSequence(JSON.stringify({ steps: s.steps.map(strip) })));
        const solving = s.steps.filter((x) => !("restore" in x) && !("reset" in x));
        diff = answers.length === solving.length ? null : `${answers.length} answers for ${solving.length} steps`;
        for (let i = 0; !diff && i < solving.length; i++) {
          const d = compareResult(solving[i], answers[i]);
          if (d) diff = `step ${i}: ${d}`;
        }
      } catch (e) {
        diff = `threw ${e}`;
      }
      tally.check("layout/sequence", diff == null, () => `${s.name}: ${diff}`);
    }
  });
  timed(tally, "layout/rimDocks", () => {
    for (const c of tests.rimDocks) {
      const got = JSON.parse(layout.layoutRimDocks(JSON.stringify(c.rect)));
      const diff = firstDifference(c.docks.map(endpoint), got.map(endpoint), "");
      tally.check("layout/rimDocks", diff == null, () => `${JSON.stringify(c.rect)}: ${diff}`);
    }
  });
  const boards = sampleOf(readJson("layout/boards.json.gz").boards, every / 4, () => "boards");
  timed(tally, "layout/boards", () => {
    for (const b of boards) {
      let diff;
      try {
        const answers = JSON.parse(layout.layoutBoard(JSON.stringify({ ...b, steps: b.steps.map(({ shift, thorough }) => ({ shift, thorough })) })));
        diff = answers.length === b.steps.length ? null : `${answers.length} answers for ${b.steps.length} steps`;
        for (let i = 0; !diff && i < b.steps.length; i++) {
          const d = compareResult(b.steps[i], answers[i]);
          if (d) diff = `shift ${b.steps[i].shift}: ${d}`;
        }
      } catch (e) {
        diff = `threw ${e}`;
      }
      tally.check("layout/boards", diff == null, () => `${b.name}: ${diff}`);
    }
  });
}
