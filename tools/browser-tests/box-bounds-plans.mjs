// Synthetic plans for the box-bounds probe, built from the installed dataset (never a player's plan):
//   shared.json   one Large Chemical Reactor card running 27 recipes with two or more products each, long notes
//   lib-NN.json   small plans with long names, to fill the Library and the tab strip
// node tools/browser-tests/box-bounds-plans.mjs [outdir]   (default PLANS_DIR or scratch/probes/box-bounds)
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";

const outDir = process.argv[2] ?? process.env.PLANS_DIR ?? "scratch/probes/box-bounds";
fs.mkdirSync(outDir, { recursive: true });
const root = "public/datasets/gtnh";
const version = fs.readdirSync(root).filter((n) => fs.existsSync(path.join(root, n, "recipes-shards"))).sort().at(-1);
const dir = path.join(root, version, "recipes-shards");

const picked = [];
let handler;
for (const file of fs.readdirSync(dir).sort()) {
  const shard = JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(dir, file))).toString());
  for (const r of shard.recipes) {
    const h = r.machineHandlers?.find((x) => /large-chemical-reactor/.test(x.id));
    if (!h || (r.outputs?.length ?? 0) < 2 || (r.inputs?.length ?? 0) < 1) continue;
    handler ??= h.id;
    if (h.id !== handler) continue;
    if (picked.some((p) => p.name === r.name)) continue;
    picked.push(r);
    if (picked.length === 27) break;
  }
  if (picked.length === 27) break;
}
if (picked.length < 27) throw new Error(`only ${picked.length} recipes`);
const products = picked.reduce((n, r) => n + r.outputs.length, 0);

const longText =
  "A long note that keeps going to see whether every box that shows it keeps its keys on screen. ".repeat(40);
const shared = {
  schemaVersion: 1,
  id: "bounds-shared",
  name: "Shared reactor with a name long enough to run past the edge of any box that shows it in full",
  notes: longText,
  recipes: picked,
  nodes: [
    {
      id: "lcr",
      recipeId: picked[0].id,
      extraRecipes: picked.slice(1).map((r) => ({ recipeId: r.id })),
      machineHandlerId: handler,
      machineCount: 1,
      parallel: 1,
      overclockTier: "HV",
      hatchVoltageTier: "HV",
      hatchAmps: 2,
      enabled: true,
      position: { x: 0, y: 0 },
    },
    {
      id: "single",
      recipeId: picked[1].id,
      machineHandlerId: handler,
      machineCount: 1,
      parallel: 1,
      overclockTier: "HV",
      hatchVoltageTier: "HV",
      hatchAmps: 2,
      enabled: true,
      position: { x: 900, y: 0 },
    },
  ],
  annotations: [
    { id: "note", kind: "text", text: longText, position: { x: 900, y: 600 }, size: { width: 300, height: 200 } },
  ],
  fuelProfiles: [],
  storages: [],
  edges: [],
};
fs.writeFileSync(path.join(outDir, "shared.json"), JSON.stringify(shared));
for (let i = 0; i < 24; i += 1) {
  const r = picked[i % picked.length];
  const plan = {
    schemaVersion: 1,
    id: `bounds-lib-${i}`,
    name: `Library plan ${String(i).padStart(2, "0")} with a very long name that does not fit on one line of any tile`,
    notes: longText.slice(0, 400),
    recipes: [r],
    nodes: [{ id: "n", recipeId: r.id, machineHandlerId: handler, machineCount: 1, parallel: 1, overclockTier: "HV", enabled: true, position: { x: 0, y: 0 } }],
    fuelProfiles: [],
    storages: [],
    edges: [],
  };
  fs.writeFileSync(path.join(outDir, `lib-${String(i).padStart(2, "0")}.json`), JSON.stringify(plan));
}
console.log(JSON.stringify({ version, handler, recipes: picked.length, products }));
