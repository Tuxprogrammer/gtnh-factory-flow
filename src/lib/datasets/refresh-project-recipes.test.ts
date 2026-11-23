import { describe, expect, it, vi } from "vitest";
import type { Recipe } from "@/lib/model/types";
import type { DatasetVersion } from "./types";
import {
  noteRecipesRefreshed,
  noteRecipesRequested,
  recipesToRefresh,
  resolveProjectRecipes,
} from "./refresh-project-recipes";

const loader = vi.hoisted(() => ({
  getRecipeDatasetRecipe: vi.fn(),
  resolveRecipeDatasetRecipes: vi.fn(),
  getResourceRenames: vi.fn(async () => new Map<string, string>()),
}));
vi.mock("./browser-loader", () => loader);

const table = {
  sourceKind: "gregtech-overclock-calculator",
  status: "computed",
  oracleEligible: true,
  variants: [{ id: "lv", durationTicks: 100, eut: 30 }],
} as const;
const recipe = (id: string, withTable: boolean) =>
  ({ id, inputs: [], outputs: [], ...(withTable ? { runtimeCalculation: table } : {}) }) as unknown as Recipe;

describe("recipesToRefresh", () => {
  it("fetches each id once per dataset version", () => {
    const checked = new Map<string, boolean>();
    const requested = new WeakSet<Recipe>();
    const first = [recipe("a", true), recipe("b", false)];
    expect(recipesToRefresh(first, "v1", checked, requested)).toEqual(first);
    noteRecipesRequested(first, "v1", checked, requested);
    noteRecipesRefreshed([recipe("a", true), recipe("b", false)], "v1", checked);
    // Same ids again, as new objects with their bodies intact: nothing to do.
    expect(recipesToRefresh([recipe("a", true), recipe("b", false)], "v1", checked, requested)).toEqual([]);
    // A new dataset version checks again.
    expect(recipesToRefresh([recipe("a", true)], "v2", checked, requested)).toHaveLength(1);
  });

  it("fetches again when a synced plan brings a recipe back without its table", () => {
    // A plan pulled from the account travels without GregTech tables, and
    // its recipes can be ids this page already refreshed for another plan.
    const checked = new Map<string, boolean>();
    const requested = new WeakSet<Recipe>();
    noteRecipesRequested([recipe("a", true), recipe("b", false)], "v1", checked, requested);
    noteRecipesRefreshed([recipe("a", true), recipe("b", false)], "v1", checked);
    const pulled = [recipe("a", false), recipe("b", false)];
    expect(recipesToRefresh(pulled, "v1", checked, requested).map((entry) => entry.id)).toEqual(["a"]);
    // The same bare object is never sent twice.
    noteRecipesRequested([pulled[0]!], "v1", checked, requested);
    expect(recipesToRefresh(pulled, "v1", checked, requested)).toEqual([]);
  });
});

describe("resolveProjectRecipes", () => {
  const version = { id: "new" } as DatasetVersion;
  const stored = (id: string) => ({ id, name: id, inputs: [], outputs: [] }) as unknown as Recipe;
  const failed = (status?: number) => Object.assign(new Error("no"), status ? { status } : {});

  it("names the recipes the dataset has neither by id nor by content, and only on a not-found answer", async () => {
    loader.getRecipeDatasetRecipe.mockImplementation(async (_url: string, _v: DatasetVersion, id: string) => {
      if (id === "kept" || id === "moved-new") return stored(id);
      throw id === "offline" ? failed() : failed(404);
    });
    loader.resolveRecipeDatasetRecipes.mockResolvedValue({
      matches: [{ importedId: "moved", recipeId: "moved-new", exact: true }],
    });
    const result = await resolveProjectRecipes("", version, ["kept", "moved", "gone", "offline"].map(stored));
    expect(result.refreshed.map((recipe) => recipe.id)).toEqual(["kept", "moved-new"]);
    expect(result.migration).toEqual({ moved: "moved-new" });
    // A request that failed for another reason says nothing about the dataset.
    expect(result.missing).toEqual(["gone"]);
  });

  it("names none when the content match could not be asked", async () => {
    loader.getRecipeDatasetRecipe.mockRejectedValue(failed(404));
    loader.resolveRecipeDatasetRecipes.mockRejectedValue(failed(500));
    expect((await resolveProjectRecipes("", version, [stored("gone")])).missing).toEqual([]);
  });

  it("matches a stored recipe as the game's item renames write it", async () => {
    loader.getRecipeDatasetRecipe.mockImplementation(async (_url: string, _v: DatasetVersion, id: string) => {
      if (id === "compressor-new") return stored(id);
      throw failed(404);
    });
    loader.getResourceRenames.mockResolvedValueOnce(
      new Map([["ic2:itemfuelplantball@*", "gregtech:gt.metaitem.02@32020"]]),
    );
    loader.resolveRecipeDatasetRecipes.mockResolvedValue({
      matches: [{ importedId: "compressor", recipeId: "compressor-new", exact: true }],
    });
    const compressor = {
      ...stored("compressor"),
      inputs: [{ kind: "item", id: "natura:berry@1", amount: 8 }],
      outputs: [{ kind: "item", id: "ic2:itemfuelplantball", amount: 1 }],
    } as unknown as Recipe;
    const result = await resolveProjectRecipes("", version, [compressor]);
    const refs = loader.resolveRecipeDatasetRecipes.mock.calls.at(-1)![2] as Array<{ outputs: Array<{ id: string }> }>;
    expect(refs[0]!.outputs[0]!.id).toBe("gregtech:gt.metaitem.02@32020");
    expect(result.migration).toEqual({ compressor: "compressor-new" });
    expect(result.renames.get("ic2:itemfuelplantball@*")).toBe("gregtech:gt.metaitem.02@32020");
  });

  it("reads the renames when every recipe is found by its id", async () => {
    // Recipe ids carry across versions: the dataset answers the stored id with a body on the new item, and the
    // plan's wires and slot picks must move with it.
    loader.getRecipeDatasetRecipe.mockImplementation(async (_url: string, _v: DatasetVersion, id: string) => ({
      ...stored(id),
      outputs: [{ kind: "item", id: "gregtech:gt.metaitem.02@32020", amount: 1 }],
    }));
    loader.getResourceRenames.mockResolvedValueOnce(
      new Map([["ic2:itemfuelplantball@*", "gregtech:gt.metaitem.02@32020"]]),
    );
    const result = await resolveProjectRecipes("", version, [stored("compressor")]);
    expect(result.refreshed.map((recipe) => recipe.id)).toEqual(["compressor"]);
    expect(result.renames.get("ic2:itemfuelplantball@*")).toBe("gregtech:gt.metaitem.02@32020");
  });
});
