import { describe, expect, it } from "vitest";
import { listConformance, readConformanceJson } from "@/test-support/conformance";
import { plansImportRequest } from "./delivery-schemas";
import { checkSnapshot } from "./handoff";
import { THING_ID_PATTERN } from "./slim";

/**
 * The game's Ask Dave panel sends the open plan as a slim snapshot. conformance/slim/mod holds what the mod's own code
 * makes of its demo plans (SlimSnapshotTest in jvm/mod); each must pass the validation `plans/import` runs on a request
 * before it reads the dataset, so the two sides cannot drift.
 */
describe("the mod's slim snapshots", () => {
  const files = listConformance("slim", "mod").filter((name) => name.endsWith(".json"));

  it("are there", () => {
    expect(files.length).toBeGreaterThanOrEqual(3);
  });

  for (const file of files) {
    it(`${file} is a plans/import request the site accepts`, () => {
      const parsed = plansImportRequest.safeParse({ snapshot: readConformanceJson("slim", "mod", file) });
      expect(parsed.success ? "" : JSON.stringify(parsed.error.issues.slice(0, 5))).toBe("");
      if (!parsed.success) return;
      expect(() => checkSnapshot(parsed.data.snapshot)).not.toThrow();
      const snapshot = parsed.data.snapshot;
      expect(snapshot.cards.length).toBeGreaterThan(0);
      expect(snapshot.wires.length).toBeGreaterThan(0);
      // The game names cards and drawers by its board ids, which a proposal's wires name back.
      for (const thing of [...snapshot.cards, ...snapshot.drawers]) expect(thing.id).toMatch(THING_ID_PATTERN);
      // Within the bridge's attachment limit with room to spare.
      expect(JSON.stringify(snapshot).length).toBeLessThan(256 * 1024);
    });
  }
});
