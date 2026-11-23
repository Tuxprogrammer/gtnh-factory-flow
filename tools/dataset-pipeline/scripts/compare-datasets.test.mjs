import { describe, expect, it } from "vitest";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import zlib from "node:zlib";
import { LAUNCH_NOISE, compareDatasets } from "./compare-datasets.mjs";

const here = path.dirname(new URL(import.meta.url).pathname);
const item = (id) => ({ kind: "item", id, amount: 1, displayName: id, iconPath: `/t/${id}.png` });
const soil = (shown) => {
  const members = ["chisel:glowstone@32767", "minecraft:glowstone@32767"].map(item);
  const first = members.find((m) => m.id === shown);
  return { ...first, alternatives: [first, ...members.filter((m) => m !== first)] };
};
const breeder = (id, shown) => ({
  id,
  name: "Crop Breeder: Glowheat Seeds",
  kind: "gregtech_machine",
  category: "gregtech",
  eut: 30,
  inputs: [item("cropsnh:genericseed"), soil(shown)],
  outputs: [item("cropsnh:genericseed")],
});
const press = (eut) => ({
  id: "oracle:x:gregtech:press:1",
  name: "Press",
  kind: "gregtech_machine",
  category: "gregtech",
  eut,
  inputs: [item("a")],
  outputs: [item("b")],
});
const essentia = (aspect) => ({
  id: "oracle:x:thaumcraft:essentiasmelting:1",
  name: "Essentia",
  kind: "essentia_smelting",
  category: "thaumcraft",
  durationTicks: 10,
  inputs: [item("dust")],
  outputs: [{ kind: "aspect", id: `thaumcraft:aspect:${aspect}`, amount: 1 }],
});
const infusion = (id, out) => ({
  id,
  name: `Infusion: ${out}`,
  kind: "gregtech_machine",
  category: "thaumcraft",
  inputs: [item("core")],
  outputs: [item(out)],
});

function write(dir, { recipes, resources, timing, extra = {} }) {
  fs.mkdirSync(path.join(dir, "oracle"), { recursive: true });
  fs.mkdirSync(path.join(dir, "recipes-shards"), { recursive: true });
  const lines = (rows) =>
    rows.map((row, i) => `    ${JSON.stringify(row)}${i < rows.length - 1 ? "," : ""}`);
  const text = [
    "{",
    '  "schemaVersion": 1,',
    '  "resources": [',
    ...lines(resources),
    "  ],",
    '  "recipes": [',
    ...lines(recipes),
    "  ]",
    "}",
    "",
  ].join("\n");
  fs.writeFileSync(path.join(dir, "recipes.json.gz"), zlib.gzipSync(text));
  fs.writeFileSync(
    path.join(dir, "resource-index.json.gz"),
    zlib.gzipSync(JSON.stringify({ schemaVersion: 1, resources, resourceIndex: [] })),
  );
  fs.writeFileSync(
    path.join(dir, "recipes-shards", "shard-0000.json.gz"),
    zlib.gzipSync(JSON.stringify(recipes)),
  );
  fs.writeFileSync(
    path.join(dir, "oracle", "oracle-report.json"),
    JSON.stringify({ adapters: [{ id: "gt", durationMillis: timing }] }),
  );
  for (const [file, body] of Object.entries(extra)) fs.writeFileSync(path.join(dir, file), body);
}

function pair(a, b) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "compare-"));
  write(path.join(dir, "A"), a);
  write(path.join(dir, "B"), b);
  return [path.join(dir, "A"), path.join(dir, "B")];
}

const base = (shown, timing, extra) => ({
  recipes: [
    breeder(`oracle:x:gregtech:breeder:${shown.length}`, shown),
    press(30),
    essentia(shown.startsWith("chisel") ? "aer" : "aqua"),
    infusion("oracle:x:thaumcraft:infusion:1", shown.startsWith("chisel") ? "p" : "q"),
    infusion("oracle:x:thaumcraft:infusion:2", shown.startsWith("chisel") ? "q" : "p"),
  ],
  resources: [item("a"), item(shown)],
  timing,
  extra,
});

describe("compare-datasets", () => {
  it("passes two launches that differ only by listed launch noise", async () => {
    const [a, b] = pair(base("chisel:glowstone@32767", 10), base("minecraft:glowstone@32767", 12));
    const report = await compareDatasets(a, b);
    expect(report.unexplained).toEqual([]);
    expect(Object.keys(report.noise).sort()).toEqual(
      [
        "derived",
        "export-timing",
        "shown-member",
        "thaumcraft-generated-aspects",
        "thaumcraft-list-position",
      ].sort(),
    );
    expect(report.noise["shown-member"]).toContain("chisel:glowstone@32767");
    expect(
      spawnSync(process.execPath, [path.join(here, "compare-datasets.mjs"), a, b]).status,
    ).toBe(0);
  });

  it("fails on anything else: a changed recipe, a resource no slot lists, a time stamp", async () => {
    const changed = base("minecraft:glowstone@32767", 10, {
      "game-rates.json": '{"generatedAt":"2026-01-02"}',
    });
    changed.recipes[1] = press(32);
    changed.resources.push(item("stray"));
    const [a, b] = pair(
      base("chisel:glowstone@32767", 10, { "game-rates.json": '{"generatedAt":"2026-01-01"}' }),
      changed,
    );
    const report = await compareDatasets(a, b);
    expect(report.unexplained).toEqual(
      expect.arrayContaining([
        "recipe oracle:x:gregtech:press:1 differs",
        "resource stray only in B",
        "file game-rates.json differs",
      ]),
    );
    expect(
      spawnSync(process.execPath, [path.join(here, "compare-datasets.mjs"), a, b]).status,
    ).toBe(1);
  });

  it("every launch-noise kind is listed in the pipeline's CLAUDE.md", () => {
    const doc = fs.readFileSync(path.join(here, "..", "CLAUDE.md"), "utf8");
    for (const kind of Object.keys(LAUNCH_NOISE)) expect(doc).toContain(`\`${kind}\``);
  });
});
