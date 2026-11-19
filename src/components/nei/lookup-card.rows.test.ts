import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import type { RecipeSummary } from "@/lib/datasets/types";
import { cardCellHeight, recipeCardRows } from "./lookup-card";

const dust = {
  kind: "item" as const,
  id: "gregtech:gt.metaitem.01@2696",
  amount: 1,
  displayName: "Sodium Aluminate Dust",
};

function recipe(overrides: Partial<RecipeSummary>): RecipeSummary {
  return {
    id: "test:recipe",
    name: "Sodium Aluminate Dust",
    recipeMap: "Mixer",
    machineType: "Mixer",
    minimumTier: "MV",
    durationTicks: 20,
    eut: 48,
    inputs: [],
    outputs: [dust],
    slots: [],
    ...overrides,
  };
}

describe("the search page's card rows", () => {
  // A page is counted by its tallest card: a card drawn taller than its count runs past the page's foot, out of sight.
  it("counts the power row a crafting recipe's card draws for the Auto Workbench", () => {
    const shaped = recipe({
      recipeMap: "Shaped Crafting",
      machineType: "Shaped Crafting",
      minimumTier: "LV",
      durationTicks: 1,
      eut: 0,
      inputs: Array.from({ length: 4 }, () => ({
        kind: "item" as const,
        id: "oredict:dustSmallSodiumAluminate",
        amount: 1,
      })),
    });
    expect(recipeCardRows(shaped)).toEqual({ inputs: 2, outputs: 1 });
    expect(cardCellHeight([recipeCardRows(shaped)])).toBe(124);
  });

  it("leaves a recipe that draws no power without a power row", () => {
    const free = recipe({ eut: 0, inputs: [{ kind: "item", id: "minecraft:stone", amount: 1 }] });
    expect(recipeCardRows(free)).toEqual({ inputs: 1, outputs: 1 });
  });

  // The page's width sets its columns. A scrollbar that came and went with the page's cards would take a column away,
  // re-page the recipes, lose the tall card and give the column back, forever: an overflowing page always keeps it.
  it("keeps the scrollbar of a page whose cards may overflow", () => {
    const css = readFileSync(path.resolve(__dirname, "nei-recipe.css"), "utf8").replace(
      /\/\*[\s\S]*?\*\//g,
      "",
    );
    const body = /\.nrw-page\[data-overflow\]\s*\{([^}]*)\}/.exec(css)?.[1] ?? "";
    expect(body).toMatch(/overflow-y:\s*scroll/);
  });
});
