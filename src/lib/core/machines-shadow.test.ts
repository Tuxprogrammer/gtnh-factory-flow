import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { gunzipSync } from "node:zlib";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { FactoryNode, Recipe } from "@/lib/model/types";
import { getGameRates, setActiveGameRates, type ActiveGameRates } from "@/lib/model/game-rates";
import { machineReadout, type ReadoutNode } from "@/lib/machines/readout";
import { core, loadCore, resetCoreForTests, wasmGcSupported, type CoreTarget } from "./loader";
import { cardMachineMathCore, cardMachineMathTs, keepRecipe, websiteOnly } from "./machines";

/**
 * The shadow of the website's machine maths (restructure/adoption.md, step one): every input runs through the
 * TypeScript and through the compiled core, on both targets, and the two must agree. Inputs: the machine goldens
 * (conformance/machines/machine-goldens.jsonl.gz, a grid of real recipes and settings) and every input the website's
 * own machine tests hand the maths (website-test-inputs.jsonl.gz, tools/audits/machine-test-inputs/record.mjs), which
 * cover behaviours the grid may not. Two answers are compared per input: the golden readout (machineReadout against
 * machinesReadout) and the card's call (cardMachineMathTs against machinesCard). The JavaScript target, about four
 * times slower, takes every eighth golden and every test input; the WebAssembly target takes all of them.
 */
const root = process.cwd();
const present = existsSync(path.join(root, "public", "core", "manifest.json"));

interface Input {
  name: string;
  recipe: Recipe;
  node: ReadoutNode;
  gameRates?: Partial<ActiveGameRates>;
}

function jsonl(rel: string): unknown[] {
  return gunzipSync(readFileSync(path.join(root, "conformance", rel)))
    .toString("utf8")
    .split("\n")
    .filter(Boolean)
    .map((line) => JSON.parse(line));
}

function goldenInputs(): Input[] {
  const recipes = new Map<string, Recipe>();
  return (
    jsonl("machines/machine-goldens.jsonl.gz") as Array<{
      case: number;
      variant: string;
      recipe?: Recipe;
      recipeId: string;
      node: ReadoutNode;
      gameRates?: ActiveGameRates;
    }>
  ).map((line) => {
    if (line.recipe) recipes.set(line.recipeId, line.recipe);
    return {
      name: `golden ${line.case} (${line.variant})`,
      recipe: recipes.get(line.recipeId)!,
      node: line.node,
      gameRates: line.gameRates,
    };
  });
}

function testInputs(): Input[] {
  return (
    jsonl("machines/website-test-inputs.jsonl.gz") as Array<{
      recipe: Recipe;
      node: ReadoutNode;
      gameRates?: ActiveGameRates;
    }>
  ).map((line, i) => ({ name: `test input ${i} (${line.recipe.id})`, ...line }));
}

/** Numbers to 1e-9 relative, non-finite words read back, a missing key the same as undefined. */
function difference(want: unknown, got: unknown, where: string, oneSided: boolean): string | null {
  const num = (v: unknown) =>
    v === "Infinity" ? Infinity : v === "-Infinity" ? -Infinity : v === "NaN" ? NaN : v;
  const w = num(want);
  const g = num(got);
  if (typeof w === "number" && typeof g === "number") {
    if (w === g || (Number.isNaN(w) && Number.isNaN(g))) return null;
    return Number.isFinite(w) &&
      Number.isFinite(g) &&
      Math.abs(w - g) <= 1e-9 * Math.max(Math.abs(w), Math.abs(g))
      ? null
      : `${where}: ${String(g)} for ${String(w)}`;
  }
  if (Array.isArray(w) || Array.isArray(g)) {
    if (!Array.isArray(w) || !Array.isArray(g))
      return `${where}: ${JSON.stringify(g)} for ${JSON.stringify(w)}`;
    if (w.length !== g.length) return `${where}: ${g.length} items for ${w.length}`;
    for (let i = 0; i < w.length; i++) {
      const d = difference(w[i], g[i], `${where}[${i}]`, oneSided);
      if (d) return d;
    }
    return null;
  }
  if (w && g && typeof w === "object" && typeof g === "object") {
    const wo = w as Record<string, unknown>;
    const go = g as Record<string, unknown>;
    const keys = oneSided
      ? Object.keys(go)
      : [...new Set([...Object.keys(wo), ...Object.keys(go)])];
    for (const k of keys) {
      const d = difference(wo[k] ?? null, go[k] ?? null, `${where}.${k}`, oneSided);
      if (d) return d;
    }
    return null;
  }
  return (w ?? null) === (g ?? null)
    ? null
    : `${where}: ${JSON.stringify(g)} for ${JSON.stringify(w)}`;
}

interface Tally {
  inputs: number;
  readouts: number;
  cards: number;
  leftToWebsite: number;
  findings: string[];
}

function shadow(inputs: Input[]): Tally {
  const c = core();
  const tally: Tally = { inputs: 0, readouts: 0, cards: 0, leftToWebsite: 0, findings: [] };
  const before = getGameRates();
  try {
    for (const input of inputs) {
      tally.inputs++;
      const rates = {
        beesYield: 1,
        ic2CropsYield: 1,
        recipeDurationDivisor: 1,
        ...input.gameRates,
      };
      setActiveGameRates(rates);
      c.MachinesExports.machinesSetRates(
        rates.beesYield,
        rates.ic2CropsYield,
        rates.recipeDurationDivisor,
      );
      const node = input.node as FactoryNode;
      let ts: ReturnType<typeof machineReadout>["expected"];
      let tsCard: ReturnType<typeof cardMachineMathTs>;
      try {
        if (websiteOnly(input.recipe)) {
          // Bees and crops: the website's own card maths, which the core's machine readout does not model (their own
          // goldens judge BeeReadout and CropReadout); the facade never sends them to the core.
          tally.leftToWebsite++;
          continue;
        }
        ts = machineReadout(input.recipe, input.node).expected;
        tsCard = cardMachineMathTs(input.recipe, node);
      } catch {
        // An input the website's own maths throws on (a test's deliberately broken stub) has no answer to hold.
        continue;
      }
      try {
        keepRecipe(c, input.recipe);
        const readout = c.MachinesExports.machinesReadout(input.recipe.id, JSON.stringify(node));
        if (readout == null) {
          tally.leftToWebsite++;
          continue;
        }
        tally.readouts++;
        const d = difference(ts, JSON.parse(readout), "readout", true);
        if (d) tally.findings.push(`${input.name}: ${d}`);
        const card = cardMachineMathCore(c, input.recipe, node);
        if (card) {
          tally.cards++;
          const e = difference(tsCard, card, "card", false);
          if (e) tally.findings.push(`${input.name}: ${e}`);
        }
      } catch (e) {
        tally.findings.push(`${input.name}: the core threw ${String(e).slice(0, 200)}`);
      }
    }
  } finally {
    setActiveGameRates(before);
  }
  return tally;
}

const targets: CoreTarget[] = wasmGcSupported() ? ["wasm-gc", "js"] : ["js"];

describe("machine maths shadow: the TypeScript and the compiled core agree", () => {
  it("has the core when CI requires it", () => {
    if (process.env.GTNH_CORE_REQUIRED === "1") expect(present).toBe(true);
  });

  for (const target of targets) {
    describe.skipIf(!present)(`on the ${target} target`, () => {
      beforeAll(async () => {
        resetCoreForTests();
        await loadCore({ target });
      });
      afterAll(() => resetCoreForTests());

      it("on every input of the website's machine tests", () => {
        const tally = shadow(testInputs());
        expect(tally.findings.slice(0, 20)).toEqual([]);
        expect(tally.readouts).toBeGreaterThan(5000);
        expect(tally.cards).toBe(tally.readouts);
      }, 300_000);

      it(`on ${target === "js" ? "every eighth" : "every"} machine golden`, () => {
        const all = goldenInputs();
        const inputs = target === "js" ? all.filter((_, i) => i % 8 === 0) : all;
        const tally = shadow(inputs);
        expect(tally.findings.slice(0, 20)).toEqual([]);
        expect(tally.readouts).toBeGreaterThan(inputs.length * 0.9);
        expect(tally.cards).toBe(tally.readouts);
      }, 600_000);
    });
  }
});
