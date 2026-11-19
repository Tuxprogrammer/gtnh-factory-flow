// The machine, bee and crop goldens, run against one loaded target through MachinesExports (JSON in, JSON out):
//   conformance/machines/machine-goldens.jsonl.gz  a recipe on a node: tier, overclocks, duration, EU/t, parallels, outputs, power, steam, controls
//   conformance/machines/bee-goldens.jsonl.gz      a bee card: housing, product and specialty multipliers, Industrial Apiary duration
//   conformance/machines/crop-goldens.jsonl.gz     a crop card: setup, environment, growth, harvest, machines, power
// The same files, with the same tolerances, that the JVM tests read (MachineGoldensTest, BeesGoldensTest,
// CropsNhGoldensTest): relative 1e-9 for machines and crops (crops from max(1, |want|)), 1e-6 for bees, and exact for
// counts, tiers, keys, text and flags. Both sides' comparisons are written to the same rule list (GoldenCompare).
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { gunzipSync } from "node:zlib";

const ROOT = fileURLToPath(new URL("../../", import.meta.url));
const lines = (rel) =>
  gunzipSync(readFileSync(path.join(ROOT, "conformance", rel)))
    .toString("utf8")
    .split("\n")
    .filter(Boolean);

/** Non-finite numbers are text in the files and in the readouts. */
const number = (x) => (x === "Infinity" ? Infinity : x === "-Infinity" ? -Infinity : x === "NaN" ? NaN : x);

/** Integer-valued readout fields that are counts or positions: no tolerance. */
const MACHINE_EXACT = new Set(["overclockSteps", "perfectOverclockSteps", "voltageOrdinal", "minimumIndex"]);
/** The crop readout's fractional fields; every other number there is a count. */
const CROP_NEAR = new Set(["ticks", "rounds", "speed", "eutPerCrop", "farmEut", "managerEu", "durationMultiplier", "draw", "outputMultipliers"]);

function closeMachine(w, g) {
  if (Number.isNaN(w) || Number.isNaN(g)) return Number.isNaN(w) && Number.isNaN(g);
  if (!Number.isFinite(w) || !Number.isFinite(g)) return w === g;
  return w === g || Math.abs(w - g) <= 1e-9 * Math.max(Math.abs(w), Math.abs(g));
}

/**
 * The first difference between a golden's value and the readout's, or null. Objects compare the readout's keys (the
 * golden records more than the tests read); a key the golden lacks reads as null. `near(key)` says whether a number
 * under that key has a tolerance, `close` is the tolerance.
 */
function difference(want, got, where, near, close) {
  if (want == null || got == null) return want == null && got == null ? null : `${where}: ${JSON.stringify(got)} for ${JSON.stringify(want)}`;
  if (Array.isArray(got) || Array.isArray(want)) {
    if (!Array.isArray(got) || !Array.isArray(want)) return `${where}: ${JSON.stringify(got)} for ${JSON.stringify(want)}`;
    if (want.length !== got.length) return `${where}: ${got.length} items for ${want.length}`;
    for (let i = 0; i < got.length; i++) {
      const d = difference(want[i], got[i], `${where}[${i}]`, near, close);
      if (d) return d;
    }
    return null;
  }
  if (typeof got === "object") {
    if (typeof want !== "object") return `${where}: ${JSON.stringify(got)} for ${JSON.stringify(want)}`;
    for (const k of Object.keys(got)) {
      const d = difference(want[k], got[k], where ? `${where}.${k}` : k, near, close);
      if (d) return d;
    }
    return null;
  }
  if (typeof got === "string" && typeof want === "string") {
    const w = number(want);
    const g = number(got);
    if (typeof w === "number" || typeof g === "number") return numberDifference(w, g, where, near, close);
    return w === g ? null : `${where}: ${got} for ${want}`;
  }
  if (typeof got === "number" || typeof want === "number" || typeof number(got) === "number") return numberDifference(number(want), number(got), where, near, close);
  return want === got ? null : `${where}: ${JSON.stringify(got)} for ${JSON.stringify(want)}`;
}

function numberDifference(w, g, where, near, close) {
  if (typeof w !== "number" || typeof g !== "number") return `${where}: ${JSON.stringify(g)} for ${JSON.stringify(w)}`;
  const key = where.replace(/\[\d+\]/g, "").split(".").pop();
  if (near(key) ? close(w, g) : w === g || (Number.isNaN(w) && Number.isNaN(g))) return null;
  return `${where}: ${g} for ${w}`;
}

function timed(tally, name, fn) {
  const t = performance.now();
  fn();
  const ms = Math.round(performance.now() - t);
  tally.group(name).ms = ms;
  // Read, parse, call into the core and compare, for the whole group.
  console.log(`[web-core]   ${name} took ${ms} ms`);
}

// ---- machines

export function runMachineGoldens(api, tally, { onlyFirst = Infinity } = {}) {
  const machines = api.MachinesExports;
  const all = lines("machines/machine-goldens.jsonl.gz");
  let skipped = 0;
  timed(tally, "machines/cases", () => {
    const ratesOf = (c) => c.gameRates ?? {};
    for (const line of all.slice(0, onlyFirst)) {
      const c = JSON.parse(line);
      const r = ratesOf(c);
      machines.machinesSetRates(r.beesYield ?? 1, r.ic2CropsYield ?? 1, r.recipeDurationDivisor ?? 1);
      if (c.recipe) machines.machinesAddRecipe(c.recipeId, JSON.stringify(c.recipe));
      let diff;
      let text;
      try {
        text = machines.machinesReadout(c.recipeId, JSON.stringify(c.node));
        if (text == null) {
          skipped++;
          continue;
        }
        diff = machineDifference(c.expected, JSON.parse(text));
      } catch (e) {
        diff = `threw ${e}`;
      }
      tally.check("machines/cases", diff == null, () => `#${c.case} ${c.group} ${c.variant}: ${diff}`);
    }
  });
  machines.machinesSetRates(1, 1, 1);
  tally.check("machines/left to the mod's models", skipped < all.length / 4, () => `${skipped} of ${all.length} cases left out`);
  return { cases: tally.group("machines/cases").checked, skipped };
}

/** One case's difference: the readout's own keys, and the two the website records only when it has them. */
function machineDifference(want, got) {
  const near = (k) => !MACHINE_EXACT.has(k);
  if ("powerState" in want && !("powerState" in got)) return "powerState: website has a report, port none";
  if ("steam" in want !== "steam" in got) return `steam: website ${JSON.stringify(want.steam)}`;
  const g = { ...got };
  // The tier controls are read only when the website recorded them.
  if (!("controls" in want)) delete g.controls;
  return difference(want, g, "", near, closeMachine);
}

// ---- bees

export function runBeeGoldens(api, tally) {
  const machines = api.MachinesExports;
  const all = lines("machines/bee-goldens.jsonl.gz");
  const housings = new Set();
  timed(tally, "bees/cases", () => {
    for (const line of all) {
      const c = JSON.parse(line);
      housings.add(c.housing);
      let diff = null;
      try {
        const got = JSON.parse(machines.machinesBeeReadout(JSON.stringify({ housing: c.housing, tiers: c.tiers, tier: c.tier, beesYield: c.beesYield })));
        if (got.housing !== c.housing) diff = `housing ${got.housing}`;
        else if (!got.skip) {
          for (const k of ["product", "specialty", "duration"]) {
            // Floats on the game's side (0.3f, 17.199268f), doubles on the website's.
            if (Math.abs(c[k] - got[k]) > 1e-6 * Math.max(1, Math.abs(c[k]))) {
              diff = `${k}: model ${got[k]}, website ${c[k]}`;
              break;
            }
          }
        }
      } catch (e) {
        diff = `threw ${e}`;
      }
      tally.check("bees/cases", diff == null, () => `${c.housing} ${JSON.stringify(c.tiers)} yield ${c.beesYield}: ${diff}`);
    }
  });
  tally.check("bees/housings", housings.size === 6, () => `${housings.size} housings in the file (${[...housings]})`);
  return { cases: all.length };
}

// ---- crops

export function runCropGoldens(api, tally) {
  const machines = api.MachinesExports;
  const all = lines("machines/crop-goldens.jsonl.gz");
  timed(tally, "crops/cases", () => {
    for (const line of all) {
      const c = JSON.parse(line);
      let diff = null;
      try {
        const got = JSON.parse(machines.machinesCropReadout(JSON.stringify({ stats: c.stats, handler: c.handler, tiers: c.tiers, crops: c.crops, gameRates: c.gameRates })));
        // The formulas run on every case without failing, and end on the power line.
        const { formulasEndOnPower, ...numbers } = got;
        diff = formulasEndOnPower ? difference(c.expected, numbers, "", (k) => CROP_NEAR.has(k), closeCrop) : "formulas do not end on power";
      } catch (e) {
        diff = `threw ${e}`;
      }
      tally.check("crops/cases", diff == null, () => `${c.crop} ${c.handler} ${JSON.stringify(c.tiers)} x${c.crops}: ${diff}`);
    }
  });
  return { cases: all.length };
}

function closeCrop(w, g) {
  if (!Number.isFinite(w) && !Number.isFinite(g) && !Number.isNaN(w) && !Number.isNaN(g)) return true;
  if (Number.isNaN(w) || Number.isNaN(g)) return Number.isNaN(w) && Number.isNaN(g);
  return Math.abs(w - g) <= 1e-9 * Math.max(1, Math.abs(w));
}

export function runMachines(api, tally) {
  runMachineGoldens(api, tally);
  runBeeGoldens(api, tally);
  runCropGoldens(api, tally);
}
