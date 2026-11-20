import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { gunzipSync } from "node:zlib";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { FactoryNode, Recipe } from "@/lib/model/types";
import type { CoreExports } from "./exports.generated";
import { core, loadCore, resetCoreForTests, wasmGcSupported } from "./loader";
import {
  cardMachineMath,
  cardMachineMathCounts,
  clearCardMachineMath,
  crossWaitingRecipes,
  isResident,
  keepRecipe,
  resetCardMachineMathCounts,
  websiteOnly,
} from "./machines";
import { withAdopt } from "./adopt";

// The core keeps a plan's recipes resident: each crosses once per content and later calls name only its id.

/** A stand-in core that records what crosses. */
function recordingCore() {
  const recipes: Array<{ id: string; text: string }> = [];
  const controls: Array<{ ref: string; text: string }> = [];
  const handlers: Array<{ ref: string; text: string }> = [];
  const c = {
    MachinesExports: {
      machinesAddRecipe: (id: string, text: string) => recipes.push({ id, text }),
      machinesAddControl: (ref: string, text: string) => controls.push({ ref, text }),
      machinesAddHandler: (ref: string, text: string) => handlers.push({ ref, text }),
    },
  } as unknown as CoreExports;
  return { c, recipes, controls, handlers };
}

const table = { id: "coil", label: "Coil", tiers: [{ key: "cupronickel", heat: 1801 }] };
const recipe = {
  id: "ebf:1",
  name: "Steel",
  machineType: "Electric Blast Furnace",
  durationTicks: 1000,
  eut: 120,
  inputs: [
    {
      kind: "item",
      id: "iron",
      amount: 1,
      displayName: "Iron Dust",
      iconPath: "iron.png",
      dominantColor: "#ccc",
      modId: "gregtech",
      neiSlot: { x: 1, y: 2 },
      alternatives: [{ kind: "item", id: "iron2" }],
    },
  ],
  outputs: [{ kind: "item", id: "steel", amount: 1, chance: 0.5, iconPath: "steel.png" }],
  machineConfigControls: [table],
  machineHandlers: [
    { id: "ebf", label: "EBF", machineType: "Electric Blast Furnace", machineConfigControls: [table] },
  ],
  runtimeCalculation: {
    status: "ok",
    variants: [{ id: "v", durationTicks: 5, eut: 7, outputs: [{ kind: "item", id: "steel", amount: 1, iconPath: "s" }] }],
  },
  metadata: { inputs: [{ iconPath: "kept" }], machineConfigControls: [{ id: "kept" }] },
} as unknown as Recipe;

describe("a recipe resident in the core", () => {
  it("crosses once per content: the same object, an equal copy and a refreshed equal body cross nothing", () => {
    const { c, recipes } = recordingCore();
    keepRecipe(c, recipe);
    keepRecipe(c, recipe);
    keepRecipe(c, structuredClone(recipe));
    expect(recipes).toHaveLength(1);
    // Changed content under the same id crosses again; then the old content again too (the core keeps one per id).
    keepRecipe(c, { ...structuredClone(recipe), eut: 121 });
    keepRecipe(c, recipe);
    expect(recipes.map((r) => r.id)).toEqual(["ebf:1", "ebf:1", "ebf:1"]);
  });

  it("sends each control table and handler once, and only the keys the core reads of a port, nowhere else", () => {
    const { c, recipes, controls, handlers } = recordingCore();
    keepRecipe(c, recipe);
    keepRecipe(c, { ...structuredClone(recipe), id: "ebf:2" });
    expect(controls).toEqual([{ ref: "c0", text: JSON.stringify(table) }]);
    expect(handlers.map((h) => h.ref)).toEqual(["h0"]);
    expect(JSON.parse(handlers[0]!.text)).toEqual({
      ...recipe.machineHandlers![0],
      machineConfigControls: [{ $ref: "c0" }],
    });
    const sent = JSON.parse(recipes[0]!.text);
    expect(recipes[1]!.text).toBe(recipes[0]!.text.replace('"ebf:1"', '"ebf:2"'));
    expect(sent.machineConfigControls).toEqual([{ $ref: "c0" }]);
    expect(sent.machineHandlers).toEqual([{ $ref: "h0" }]);
    expect(sent.inputs).toEqual([{ kind: "item", id: "iron", amount: 1, displayName: "Iron Dust" }]);
    expect(sent.outputs).toEqual([{ kind: "item", id: "steel", amount: 1, chance: 0.5 }]);
    expect(sent.runtimeCalculation.variants[0].outputs).toEqual([{ kind: "item", id: "steel", amount: 1 }]);
    // The metadata map is read whole by the core: nothing in it is rewritten.
    expect(sent.metadata).toEqual(recipe.metadata);
  });
});

interface Input {
  recipe: Recipe;
  node: FactoryNode;
}

const INPUTS = gunzipSync(
  readFileSync(path.join(process.cwd(), "conformance", "machines", "website-test-inputs.jsonl.gz")),
)
  .toString("utf8")
  .split("\n")
  .filter(Boolean)
  .map((line) => JSON.parse(line) as Input);

const corePresent = existsSync(path.join(process.cwd(), "public", "core", "manifest.json"));
describe.skipIf(!corePresent)("a resident recipe in the compiled core", () => {
  beforeAll(async () => {
    resetCoreForTests();
    await loadCore({ target: wasmGcSupported() ? "wasm-gc" : "js" });
  });
  afterAll(() => resetCoreForTests());

  it("answers a card from the TypeScript until its recipe is resident, which it becomes in idle time", () => {
    const c = core();
    // A recorded card the core answers, on a recipe object of its own (so not resident yet).
    const input = INPUTS.find(({ recipe: r, node }) => {
      try {
        if (websiteOnly(r) || node.recipeInputOverrides) return false;
        keepRecipe(c, r);
        return c.MachinesExports.machinesCard(r.id, JSON.stringify(node)) != null;
      } catch {
        return false;
      }
    })!;
    const r = { ...structuredClone(input.recipe), id: `${input.recipe.id}:idle` } as Recipe;
    const node = { ...input.node, id: "idle-card" } as FactoryNode;
    withAdopt({ machines: true }, () => {
      clearCardMachineMath();
      resetCardMachineMathCounts();
      void cardMachineMath(r, node).stats;
      expect(cardMachineMathCounts().core).toBe(0);
      expect(isResident(c, r)).toBe(false);
      expect(crossWaitingRecipes(c)).toBe(false);
      expect(isResident(c, r)).toBe(true);
      // A card computed after that (here the same card moved to another design: a new readout) reads the core.
      clearCardMachineMath();
      void cardMachineMath(r, node).stats;
      expect(cardMachineMathCounts().core).toBe(1);
      clearCardMachineMath();
    });
  });

  it("gives every card of the website's machine tests the figures of the whole recipe", () => {
    const c = core();
    let compared = 0;
    for (const { recipe: r, node } of INPUTS) {
      try {
        if (websiteOnly(r)) continue;
      } catch {
        // A recorded recipe the passive-production tests cannot read (no machine type) is never sent.
        continue;
      }
      // The whole recipe as it is, under its own id, against the trimmed one keepRecipe sends.
      c.MachinesExports.machinesAddRecipe(`whole:${r.id}`, JSON.stringify(r));
      keepRecipe(c, r);
      const nodeText = JSON.stringify(node);
      let whole: string | null | undefined;
      try {
        whole = c.MachinesExports.machinesCard(`whole:${r.id}`, nodeText);
      } catch {
        whole = undefined;
      }
      let resident: string | null | undefined;
      try {
        resident = c.MachinesExports.machinesCard(r.id, nodeText);
      } catch {
        resident = undefined;
      }
      expect(resident, r.id).toBe(whole);
      compared++;
    }
    expect(compared).toBeGreaterThan(6000);
  }, 300_000);
});
