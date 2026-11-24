import { describe, expect, it } from "vitest";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const script = path.join(path.dirname(new URL(import.meta.url).pathname), "carry-recipe-ids.mjs");

const block = (id, name) => ({
  kind: "item",
  id,
  amount: 1,
  displayName: name,
  iconPath: `/t/${id}.png`,
});
// A Crop Breeder recipe whose soil slot shows one of three blocks: which one is the game's pick each launch.
function breeder(
  id,
  shown,
  soilOrder = ["chisel:glowstone@32767", "minecraft:glowstone@32767", "chisel:glowstone2"],
) {
  const alternatives = soilOrder.map((alt) => block(alt, alt));
  const first = alternatives.find((alt) => alt.id === shown);
  return {
    id,
    name: "Crop Breeder: Glowheat Seeds",
    kind: "gregtech_machine",
    category: "gregtech",
    machineType: "Crop Breeder",
    durationTicks: 7,
    eut: 30,
    inputs: [
      { kind: "item", id: "cropsnh:genericseed", amount: 1 },
      { ...first, alternatives: [first, ...alternatives.filter((alt) => alt !== first)] },
    ],
    outputs: [{ kind: "item", id: "cropsnh:genericseed", amount: 1 }],
    source: { rawRecipeId: "cropsnh.recipes.cropBreeder:1" },
  };
}
const oreCell = (id) => ({ kind: "item", id, amount: 1, oreDictionary: [id.slice(8)] });
const crafted = (id, input) => ({
  id,
  name: "Crafting: Plate",
  kind: "crafting",
  category: "crafting",
  machineType: "Shaped Crafting",
  inputs: [input],
  outputs: [{ kind: "item", id: "gregtech:plate", amount: 1 }],
});

function dataset(
  dir,
  name,
  recipes,
  resources = [
    { id: "a:block", kind: "item" },
    { id: "z:block", kind: "item" },
  ],
) {
  const file = path.join(dir, name);
  const rows = (list) =>
    list.map((row, i) => `    ${JSON.stringify(row)}${i < list.length - 1 ? "," : ""}`);
  const text = [
    "{",
    '  "schemaVersion": 1,',
    '  "resources": [',
    ...rows(resources),
    "  ],",
    '  "recipes": [',
    ...rows(recipes),
    "  ]",
    "}",
    "",
  ];
  fs.writeFileSync(file, text.join("\n"));
  return file;
}

function carry(previous, next, previousResources, nextResources) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "carry-"));
  const before = dataset(dir, "before.json", previous, previousResources);
  const after = dataset(dir, "recipes.json", next, nextResources);
  const report = path.join(dir, "report.json");
  const run = spawnSync(process.execPath, [script, before, after, report], { encoding: "utf8" });
  expect(run.status, run.stderr).toBe(0);
  const parsed = JSON.parse(fs.readFileSync(after, "utf8"));
  return {
    recipes: parsed.recipes,
    resources: parsed.resources,
    report: JSON.parse(fs.readFileSync(report, "utf8")),
  };
}

describe("carry-recipe-ids", () => {
  it("keeps a recipe's id and the member its slot showed when only the shown member moved", () => {
    const { recipes, resources, report } = carry(
      [breeder("oracle:old:gregtech:breeder:aaa", "minecraft:glowstone@32767")],
      [breeder("oracle:new:gregtech:breeder:bbb", "chisel:glowstone@32767")],
    );
    expect(report.totals).toMatchObject({ kept: 0, keptByShown: 1, new: 0, gone: 0 });
    expect(recipes[0].id).toBe("oracle:old:gregtech:breeder:aaa");
    const soil = recipes[0].inputs[1];
    expect(soil).toMatchObject({
      id: "minecraft:glowstone@32767",
      displayName: "minecraft:glowstone@32767",
      amount: 1,
    });
    expect(soil.iconPath).toBe("/t/minecraft:glowstone@32767.png");
    expect(soil.alternatives.map((alt) => alt.id)).toEqual([
      "minecraft:glowstone@32767",
      "chisel:glowstone@32767",
      "chisel:glowstone2",
    ]);
    // The member shown again joins the catalog in its sorted place: the resource index takes its icon from there.
    expect(resources.map((row) => row.id)).toEqual([
      "a:block",
      "minecraft:glowstone@32767",
      "z:block",
    ]);
    expect(resources[1]).toMatchObject({
      kind: "item",
      iconPath: "/t/minecraft:glowstone@32767.png",
    });
    expect(resources[1].amount).toBeUndefined();
  });

  it("gives each recipe the id of the one with its own NBT when only NBT tells them apart", () => {
    // Two Crop Breeder crosses differ only in their seeds' NBT; the new export lists them in the other order.
    const seed = (crop) => ({
      kind: "item",
      id: "cropsnh:genericseed",
      amount: 1,
      tooltip: [`NBT: {crop:"${crop}"}`],
    });
    const cross = (id, a, b, out) => ({
      id,
      name: `Crop Breeder: ${out}`,
      kind: "gregtech_machine",
      category: "gregtech",
      machineType: "Crop Breeder",
      durationTicks: 7,
      eut: 30,
      inputs: [seed(a), seed(b)],
      outputs: [seed(out)],
    });
    const { recipes, report } = carry(
      [
        cross("oracle:old:x:waterlily", "vine", "sugarCane", "waterLily"),
        cross("oracle:old:x:dayflower", "azureBluet", "blueOrchid", "dayflower"),
      ],
      [
        cross("oracle:new:x:2", "azureBluet", "blueOrchid", "dayflower"),
        cross("oracle:new:x:1", "vine", "sugarCane", "waterLily"),
      ],
    );
    expect(report.totals).toMatchObject({ kept: 2, new: 0, gone: 0 });
    expect(recipes.map((r) => [r.name, r.id])).toEqual([
      ["Crop Breeder: dayflower", "oracle:old:x:dayflower"],
      ["Crop Breeder: waterLily", "oracle:old:x:waterlily"],
    ]);
  });

  it("keeps the catalog row's icon when the new build lists another NBT stack of the item first", () => {
    // Wrenches of two materials: the previous build's catalog showed the Beryllium one, the new export lists the
    // Adamantium one first. A shulker box only the new build sees with NBT keeps nothing it never had.
    const wrench = (material, icon) => ({
      kind: "item",
      id: "gregtech:gt.metatool.01@120",
      amount: 1,
      displayName: "Wrench (LV)",
      iconPath: `/datasets/gtnh/v1/textures/rendered/${icon}`,
      dominantColor: material === "Beryllium" ? "#aaaaaa" : "#bbbbbb",
      tooltip: [`NBT: {GT.ToolStats:{PrimaryMaterial:"${material}"}}`],
    });
    const recipe = (id, material, icon) => ({
      id,
      name: `Crafting: Wrench (${material})`,
      kind: "crafting",
      category: "crafting",
      machineType: "Shaped Crafting",
      inputs: [{ kind: "item", id: `gregtech:plate.${material}`, amount: 4 }],
      outputs: [wrench(material, icon)],
    });
    const row = (material, icon) => {
      const { amount, ...rest } = wrench(material, icon);
      return rest;
    };
    const { resources, report } = carry(
      [
        recipe("oracle:old:b", "Beryllium", "wrench_lv-bddc9cf19ef5.png"),
        recipe("oracle:old:a", "Adamantium", "wrench_lv-95e401a01329.png"),
      ],
      [
        recipe("oracle:new:a", "Adamantium", "wrench_lv-95e401a01329.png"),
        recipe("oracle:new:b", "Beryllium", "wrench_lv-bddc9cf19ef5.png"),
      ],
      [row("Beryllium", "wrench_lv-bddc9cf19ef5.png"), { id: "z:block", kind: "item" }],
      [row("Adamantium", "wrench_lv-95e401a01329.png"), { id: "z:block", kind: "item" }],
    );
    expect(resources[0]).toEqual({
      ...row("Beryllium", "wrench_lv-bddc9cf19ef5.png"),
      iconPath: "/datasets/gtnh/v1/textures/rendered/wrench_lv-bddc9cf19ef5.png",
    });
    expect(resources[1]).toEqual({ id: "z:block", kind: "item" });
    expect(report.catalogIconKept).toEqual(["item:gregtech:gt.metatool.01@120"]);
  });

  it("leaves a catalog row alone when no slot shows the previous icon any more", () => {
    const stack = (icon) => ({
      kind: "item",
      id: "dreamcraft:quantumbread",
      amount: 1,
      iconPath: `/t/${icon}`,
    });
    const recipe = (id, icon) => ({
      id,
      name: "Crafting: Quantum Bread",
      kind: "crafting",
      category: "crafting",
      machineType: "Shaped Crafting",
      inputs: [{ kind: "item", id: "minecraft:bread", amount: 1 }],
      outputs: [stack(icon)],
    });
    const { resources, report } = carry(
      [recipe("oracle:old:q", "quantum_sliced_bread-a4363c428831.png")],
      [recipe("oracle:new:q", "quantum_bread-a4363c428831.png")],
      [
        {
          id: "dreamcraft:quantumbread",
          kind: "item",
          iconPath: "/t/quantum_sliced_bread-a4363c428831.png",
        },
      ],
      [
        {
          id: "dreamcraft:quantumbread",
          kind: "item",
          iconPath: "/t/quantum_bread-a4363c428831.png",
        },
      ],
    );
    expect(resources[0].iconPath).toBe("/t/quantum_bread-a4363c428831.png");
    expect(report.catalogIconKept).toEqual([]);
  });

  it("does not match a slot whose members changed", () => {
    const { report } = carry(
      [breeder("oracle:old:gregtech:breeder:aaa", "minecraft:glowstone@32767")],
      [
        breeder("oracle:new:gregtech:breeder:bbb", "chisel:glowstone@32767", [
          "chisel:glowstone@32767",
          "minecraft:glowstone@32767",
        ]),
      ],
    );
    expect(report.totals).toMatchObject({ keptByShown: 0, new: 1, gone: 1 });
  });

  it("an exact match wins, and an ore dictionary cell that changed its name is not carried", () => {
    const same = breeder("oracle:new:gregtech:breeder:ccc", "chisel:glowstone@32767");
    const { recipes, report } = carry(
      [
        { ...same, id: "oracle:old:gregtech:breeder:ccc" },
        crafted("oracle:old:crafting:1", oreCell("oredict:oreCopper")),
      ],
      [same, crafted("oracle:new:crafting:1", oreCell("oredict:oreAnyCopper"))],
    );
    expect(report.totals).toMatchObject({ kept: 1, keptByShown: 0, new: 1, gone: 1 });
    expect(recipes.map((recipe) => recipe.id)).toEqual([
      "oracle:old:gregtech:breeder:ccc",
      "oracle:new:crafting:1",
    ]);
  });
});
