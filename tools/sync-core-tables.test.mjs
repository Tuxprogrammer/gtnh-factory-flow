import { describe, expect, it } from "vitest";
import { CORE_TABLES, divergedTables } from "./sync-core-tables.mjs";

describe("generated tables shared with the core modules", () => {
  it("lists the four tables", () => {
    expect(CORE_TABLES.map(([, site]) => site.split("/").pop())).toEqual([
      "power-planner-data.json",
      "resource-map.json",
      "machine-icons.json",
      "fusion-startups.json",
    ]);
  });

  it("keeps each website copy byte-identical to the core module's file", () => {
    expect(divergedTables()).toEqual([]);
  });
});
