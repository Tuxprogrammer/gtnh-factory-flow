import { describe, expect, it } from "vitest";
import { servedDatasetPath, withServedDatasetPaths } from "./served-paths";

const served = new Set(["new", "kept"]);
const OLD = "/datasets/gtnh/old/textures/rendered/dust-abc.png";
const NEW = "/datasets/gtnh/new/textures/rendered/dust-abc.png";

describe("servedDatasetPath", () => {
  it("moves a path into a version the site does not serve onto the current one, same file", () => {
    expect(servedDatasetPath(OLD, served, "new")).toBe(NEW);
  });

  it("leaves served versions, the current one, other paths and odd shapes alone", () => {
    for (const path of [
      NEW,
      "/datasets/gtnh/kept/textures/rendered/dust-abc.png",
      "/mock.png",
      "https://example.invalid/datasets/gtnh/old/x.png",
      "/datasets/gtnh/",
      "/datasets/gtnh//x.png",
      "/datasets/gtnh/old",
    ]) {
      expect(servedDatasetPath(path, served, "new")).toBe(path);
    }
    expect(servedDatasetPath("/datasets/gtnh/solo/x.png", new Set(), "solo")).toBe("/datasets/gtnh/solo/x.png");
  });
});

describe("withServedDatasetPaths", () => {
  const port = (id: string) => ({ kind: "item", id, amount: 1, iconPath: OLD });
  const plan = {
    id: "p",
    icon: { kind: "item", resourceId: "a", iconPath: OLD },
    recipes: [
      {
        id: "oracle:old:gregtech:x",
        inputs: [{ ...port("a"), alternatives: [{ kind: "item", id: "b", iconPath: OLD }] }],
        outputs: [port("c")],
        nei: { iconPath: OLD, backgroundImage: "/datasets/gtnh/old/nei/bg.png" },
        machineConfigControls: [{ tiers: [{ resource: { kind: "item", id: "coil", iconAtlas: { imagePath: OLD } } }] }],
        machineHandlers: [{ id: "h", machineConfigControls: [{ tiers: [{ resource: port("coil2") }] }] }],
        source: { datasetVersionId: "old" },
      },
    ],
    nodes: [
      {
        id: "n",
        recipeId: "oracle:old:gregtech:x",
        recipeInputOverrides: { "0": port("a2") },
        extraRecipes: [{ recipeId: "y", recipeInputOverrides: { "1": port("a3") } }],
        gtnhplanner: { iconPath: OLD },
      },
    ],
    storages: [{ id: "d", kind: "item", resourceId: "c", iconPath: OLD }],
    gtnhplanner: { kept: OLD },
  };

  it("moves every dataset path in a plan: ports, alternatives, overrides, drawers, settings faces, atlases, NEI art", () => {
    const moved = withServedDatasetPaths(plan, served, "new");
    const text = JSON.stringify({ ...moved, gtnhplanner: undefined, nodes: moved.nodes.map(({ gtnhplanner: _g, ...n }) => n) });
    expect(text).not.toContain("/datasets/gtnh/old/");
    expect(moved.storages[0]!.iconPath).toBe(NEW);
    expect(moved.nodes[0]!.recipeInputOverrides["0"].iconPath).toBe(NEW);
    expect(moved.nodes[0]!.extraRecipes[0]!.recipeInputOverrides["1"].iconPath).toBe(NEW);
    expect(moved.recipes[0]!.inputs[0]!.alternatives[0]!.iconPath).toBe(NEW);
    expect(moved.recipes[0]!.machineConfigControls[0]!.tiers[0]!.resource.iconAtlas.imagePath).toBe(NEW);
    expect(moved.recipes[0]!.nei.backgroundImage).toBe("/datasets/gtnh/new/nei/bg.png");
    expect(moved.icon.iconPath).toBe(NEW);
    // Ids and version stamps are not paths.
    expect(moved.recipes[0]!.id).toBe("oracle:old:gregtech:x");
    expect(moved.recipes[0]!.source.datasetVersionId).toBe("old");
  });

  it("never touches the in-game planner's own data", () => {
    const moved = withServedDatasetPaths(plan, served, "new");
    expect(moved.gtnhplanner).toBe(plan.gtnhplanner);
    expect(moved.nodes[0]!.gtnhplanner).toBe(plan.nodes[0]!.gtnhplanner);
  });

  it("keeps the identity of everything it did not move, and of the whole plan when nothing moves", () => {
    const current = withServedDatasetPaths(plan, served, "new");
    expect(withServedDatasetPaths(current, served, "new")).toBe(current);
    const mixed = { a: { iconPath: NEW }, b: { iconPath: OLD } };
    const moved = withServedDatasetPaths(mixed, served, "new");
    expect(moved.a).toBe(mixed.a);
    expect(moved.b).not.toBe(mixed.b);
    expect(mixed.b.iconPath).toBe(OLD);
  });
});
