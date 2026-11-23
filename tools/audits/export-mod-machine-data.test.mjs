// The mod's handlers.json against the parts of its generator that live in this repository. The rest of it comes from
// a dataset (regenerate with tools/audits/export-mod-machine-data.mjs, see tools/dataset-pipeline/CLAUDE.md).
import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { appMultiblock, fixedTables, tableName } from "./export-mod-machine-data.mjs";

const file = path.resolve(
  __dirname,
  "../../jvm/core/core-machines/src/main/resources/assets/gtnhplanner/machines/handlers.json",
);
const data = JSON.parse(fs.readFileSync(file, "utf8"));

describe("the mod's handlers.json", () => {
  it("has the pipeline's tiers, recipe tiers and coils", () => {
    const fixed = fixedTables();
    expect(data.tiers).toEqual(fixed.tiers);
    expect(data.recipeTiers).toEqual(fixed.recipeTiers);
    expect(data.coils).toEqual(fixed.coils);
  });

  it("names the machine table entry and multiblock flag the website gives each machine", () => {
    const stale = [];
    for (const map of data.maps) {
      for (const handler of map.app ?? []) {
        const table = tableName(handler.machineType);
        const multiblock = appMultiblock(handler);
        if (handler.table !== table || handler.multiblock !== multiblock)
          stale.push(
            `${map.id} ${handler.id}: table ${handler.table} -> ${table}, multiblock ${handler.multiblock} -> ${multiblock}`,
          );
      }
    }
    expect(stale).toEqual([]);
  });

  it("names only controls it defines, and every machine has a block", () => {
    const missing = [];
    for (const map of data.maps) {
      for (const ref of map.controls ?? [])
        if (!data.controls[ref]) missing.push(`${map.id}: ${ref}`);
      for (const t of map.handlers ?? []) {
        for (const ref of t.controls ?? [])
          if (!data.controls[ref]) missing.push(`${map.id} ${t.id}: ${ref}`);
        if (!t.items?.length) missing.push(`${map.id} ${t.id}: no items`);
      }
    }
    expect(missing).toEqual([]);
    expect(Object.values(data.validated).every((n) => typeof n === "number")).toBe(true);
    expect(data.validated.handlerMismatches + data.validated.controlMismatches).toBe(0);
  });
});
