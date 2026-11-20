import { describe, expect, it } from "vitest";
import { datasetTimestamp } from "./dataset-time.mjs";

describe("datasetTimestamp", () => {
  it("takes SOURCE_DATE_EPOCH first, so two runs can match byte for byte", () => {
    expect(datasetTimestamp("2026-01-01T00:00:00.000Z", { SOURCE_DATE_EPOCH: "1763600000" })).toBe(
      "2025-11-20T00:53:20.000Z",
    );
  });

  it("else keeps the export's own time, never the clock", () => {
    expect(datasetTimestamp("2026-01-01T00:00:00.000Z", {})).toBe("2026-01-01T00:00:00.000Z");
  });

  it("falls back to now only when the export carries no time", () => {
    const before = Date.now();
    const stamp = Date.parse(datasetTimestamp(undefined, { SOURCE_DATE_EPOCH: "not a number" }));
    expect(stamp).toBeGreaterThanOrEqual(before);
  });
});
