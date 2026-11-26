/**
 * A card never shows one setting twice. Every machine of every recipe map in the shipped machine data (the core's
 * handlers.json, built from the dataset: the same handlers and controls the website's recipes carry) gets a card on an
 * LV recipe, composed as the website composes it (settingControls: the dataset's controls, the machine table's over
 * them, the table's hidden ones dropped). No two of its settings may share a label, and no setting the dataset added
 * to a machine-table machine may be one no formula reads. The mod's side is CardControlsTest in jvm/core/core-machines;
 * tools/audits/card-controls-audit.mjs measures a whole dataset.
 */
import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { settingControls, type SettingControl } from "@/components/flow/clean/menus/settings-model";
import { calculateThroughput } from "@/lib/solver/throughput";
import { normalizeLoadedProject } from "@/lib/model/project-normalize";
import { applyMachineHandlerToRecipe } from "@/lib/model/recipe-rules";
import type {
  FactoryNode,
  FactoryProject,
  MachineConfigControl,
  MachineHandler,
  Recipe,
} from "@/lib/model/types";
import { getMachineStructuralParallels } from "@/lib/solver/machine-effects";
import { getOverclockedRecipeStats } from "@/lib/solver/overclock";
import { getMachineBehaviour, getMachineTableControls } from "./machine-table";

interface CompactControl extends Omit<MachineConfigControl, "tiers"> {
  tiers: Array<Record<string, unknown> & { key: string; label: string }>;
}
interface Template {
  id: string;
  label: string;
  kind?: MachineHandler["kind"];
  minimumTier?: string;
  maximumTier?: string;
  availableTiers?: string[];
  controls?: string[];
}
interface MapEntry {
  id: string;
  name: string;
  handlersOnRecipes?: boolean;
  handlers?: Template[];
  controls?: string[];
}

const data = JSON.parse(
  fs.readFileSync(
    path.resolve(
      "jvm/core/core-machines/src/main/resources/assets/gtnhplanner/machines/handlers.json",
    ),
    "utf8",
  ),
) as { controls: Record<string, CompactControl>; maps: MapEntry[] };

/** A control as the dataset carries it (the file's icon and name stand in for a resource the card does not need). */
function control(ref: string): MachineConfigControl {
  const { tiers, ...rest } = data.controls[ref];
  return {
    ...rest,
    tiers: tiers.map(({ icon: _icon, name: _name, ...tier }) => tier),
  } as unknown as MachineConfigControl;
}

/** The map's LV recipe with its handlers, as HandlerData builds the game's (MapRecipes in the core's tests). */
function mapRecipe(entry: MapEntry): Recipe {
  const recipeControls = (entry.controls ?? []).map(control);
  const machineHandlers: MachineHandler[] | undefined =
    entry.handlersOnRecipes && entry.handlers
      ? entry.handlers.map((t) => {
          const own = (t.controls ?? []).map(control);
          return {
            id: t.id,
            label: t.label,
            kind: t.kind,
            machineType: t.label,
            minimumTier: t.minimumTier ?? "LV",
            maximumTier: t.maximumTier,
            availableTiers: t.availableTiers,
            ...(t.controls
              ? {
                  machineConfigControls: [
                    ...own,
                    ...recipeControls.filter((c) => !own.some((o) => o.id === c.id)),
                  ],
                }
              : {}),
          } as MachineHandler;
        })
      : undefined;
  return {
    id: `${entry.id}:test`,
    name: "test",
    machineType: entry.name,
    minimumTier: "LV",
    durationTicks: 100,
    eut: 30,
    inputs: [],
    outputs: [],
    source: { recipeMap: entry.name },
    metadata: { recipeMapId: entry.id, specialValue: 0 },
    machineConfigControls: recipeControls,
    ...(machineHandlers ? { machineHandlers } : {}),
  } as Recipe;
}

function node(handlerId: string | undefined, tiers: Record<string, string> = {}): FactoryNode {
  return {
    id: "n",
    recipeId: "r",
    machineHandlerId: handlerId,
    machineCount: 1,
    parallel: 1,
    overclockTier: "LV",
    enabled: true,
    position: { x: 0, y: 0 },
    machineConfigTiers: tiers,
  };
}

interface Card {
  recipe: Recipe;
  handlerId: string | undefined;
  machineType: string;
  shown: SettingControl[];
}

function cards(): Card[] {
  const out: Card[] = [];
  for (const entry of data.maps) {
    const recipe = mapRecipe(entry);
    for (const handlerId of recipe.machineHandlers?.map((h) => h.id) ?? [undefined]) {
      const machineType =
        applyMachineHandlerToRecipe(recipe, { machineHandlerId: handlerId }).machineType ?? "";
      out.push({ recipe, handlerId, machineType, shown: settingControls(recipe, node(handlerId)) });
    }
  }
  return out;
}

const norm = (label: string) => label.trim().replace(/\s+/g, " ").toLowerCase();

describe("the settings a machine card shows", () => {
  const all = cards();

  it("never has two with one label", () => {
    expect(all.length).toBeGreaterThan(300);
    const doubled = new Set<string>();
    for (const card of all) {
      card.shown.forEach((a, i) =>
        card.shown.slice(i + 1).forEach((b) => {
          if (norm(a.label) === norm(b.label))
            doubled.add(`${card.machineType}: ${a.id} + ${b.id}`);
        }),
      );
    }
    expect([...doubled]).toEqual([]);
  });

  it("on a machine-table machine, every one the dataset added changes a number", () => {
    const seen = new Set<string>();
    const unread: string[] = [];
    for (const card of all) {
      if (!getMachineBehaviour(card.machineType) || seen.has(card.machineType)) continue;
      seen.add(card.machineType);
      const tableIds = new Set(
        getMachineTableControls(card.machineType, card.recipe).map((c) => c.id),
      );
      for (const shown of card.shown) {
        if (
          tableIds.has(shown.id) ||
          shown.id === "heatingCoil" ||
          shown.numeric ||
          shown.options.length < 2
        )
          continue;
        const prints = new Set(
          shown.options.map((option) => {
            const n = node(card.handlerId, { [shown.id]: option.key });
            const effective = applyMachineHandlerToRecipe(card.recipe, n);
            const stats = getOverclockedRecipeStats(effective, n);
            return [
              stats.durationTicks,
              stats.eut,
              stats.overclockSteps,
              getMachineStructuralParallels(effective, n),
            ].join("/");
          }),
        );
        if (prints.size === 1) unread.push(`${card.machineType}: ${shown.id}`);
      }
    }
    expect(seen.size).toBeGreaterThan(100);
    expect(unread).toEqual([]);
  });

  it("a plan keeps a value on a hidden setting unused and a value on the kept one in force", () => {
    const entry = data.maps.find((m) => m.id === "gt.recipe.electromagneticseparator")!;
    const recipe = {
      ...mapRecipe(entry),
      id: "r",
      inputs: [{ kind: "item", id: "minecraft:iron_ore", amount: 1 }],
      outputs: [{ kind: "item", id: "minecraft:iron_ingot", amount: 1 }],
    } as Recipe;
    const open = (tiers: Record<string, string>) =>
      normalizeLoadedProject(
        JSON.parse(
          JSON.stringify({
            schemaVersion: 1,
            id: "t",
            name: "t",
            recipes: [recipe],
            nodes: [
              { ...node("magnetic-flux-exhibitor", tiers), recipeId: "r", overclockTier: "EV" },
            ],
            fuelProfiles: [],
            storages: [],
            edges: [],
          }),
        ) as FactoryProject,
      );
    const capacity = (tiers: Record<string, string>) => {
      const plan = open(tiers);
      return { plan, rate: calculateThroughput(plan).nodes.n.maxRatePerSecond };
    };
    const base = capacity({});
    const hidden = capacity({ fluxElectromagnet: "neodymium" });
    expect(hidden.plan.nodes[0].machineConfigTiers).toEqual({ fluxElectromagnet: "neodymium" });
    expect(base.rate).toBeGreaterThan(0);
    expect(hidden.rate).toBe(base.rate);
    const shown = settingControls(recipe, hidden.plan.nodes[0]).filter(
      (c) => norm(c.label) === "electromagnet",
    );
    expect(shown.map((c) => [c.id, c.value])).toEqual([["electromagnet", "Iron Electromagnet"]]);

    const kept = capacity({ electromagnet: "neodymium-electromagnet" });
    expect(kept.plan.nodes[0].machineConfigTiers).toEqual({
      electromagnet: "neodymium-electromagnet",
    });
    expect(
      settingControls(recipe, kept.plan.nodes[0]).find((c) => c.id === "electromagnet")?.value,
    ).toBe("Neodymium Electromagnet");
    expect(kept.rate).toBeGreaterThan(base.rate);
  });
});
