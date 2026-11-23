import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { writeDatasetJson } from "./dataset-json-writer.mjs";
import { rewriteAssetIconHints } from "./asset-icon-hints.mjs";

const custom = (tag, iconPath) => ({ id: `thaumcraft:aspect:${tag}`, kind: "aspect", displayName: tag, iconPath });

describe("asset icon notes in a dataset too large to load whole", () => {
  it("become the extracted texture or go, on every line that carries one, and nothing else changes", async () => {
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), "asset-hints-"));
    const file = path.join(dir, "recipes.json");
    const dataset = {
      schemaVersion: 1,
      datasetVersionId: "test",
      resources: [
        custom("aer", "/nei/thaumcraft/aspects/aer.png"),
        custom("custom1", "asset:gregtech:textures/aspects/AEQUALITAS.png"),
        custom("caelum", "asset:thaumicboots:textures/aspects/space.png"),
      ],
      recipes: [
        { id: "r1", inputs: [custom("custom1", "asset:gregtech:textures/aspects/AEQUALITAS.png")], outputs: [] },
        { id: "r2", inputs: [{ kind: "item", id: "minecraft:stone", iconPath: "/x.png" }], outputs: [] },
      ],
      // More than 32 keys: the writer streams it one entry a line.
      resourceIndex: Object.fromEntries(
        Array.from({ length: 40 }, (_, index) => [
          `k${index}`,
          index === 3 ? custom("caelum", "asset:thaumicboots:textures/aspects/space.png") : { n: index },
        ]),
      ),
    };
    await writeDatasetJson(file, dataset);
    const asked = [];
    const result = await rewriteAssetIconHints(file, async (resource) => {
      asked.push(resource.iconPath);
      return resource.iconPath.includes("AEQUALITAS") ? "/datasets/gtnh/test/textures/aspect/custom1.png" : undefined;
    });
    const text = await fs.readFile(file, "utf8");
    const after = JSON.parse(text);
    expect(text).not.toContain("asset:");
    expect(result).toEqual({ resolved: 2, dropped: 2 });
    // One lookup per distinct note.
    expect(asked.sort()).toEqual([
      "asset:gregtech:textures/aspects/AEQUALITAS.png",
      "asset:thaumicboots:textures/aspects/space.png",
    ]);
    expect(after.resources.map((resource) => resource.iconPath)).toEqual([
      "/nei/thaumcraft/aspects/aer.png",
      "/datasets/gtnh/test/textures/aspect/custom1.png",
      undefined,
    ]);
    expect(after.recipes[0].inputs[0].iconPath).toBe("/datasets/gtnh/test/textures/aspect/custom1.png");
    expect(after.resourceIndex.k3.iconPath).toBeUndefined();
    const untouched = { ...dataset, resources: after.resources, recipes: after.recipes, resourceIndex: after.resourceIndex };
    expect(after).toEqual(untouched);
    expect(after.recipes[1]).toEqual(dataset.recipes[1]);
    expect(after.resourceIndex.k39).toEqual({ n: 39 });
    await fs.rm(dir, { recursive: true, force: true });
  });
});
