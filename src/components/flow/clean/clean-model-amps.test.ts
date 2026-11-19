// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import type { RailPort } from "../node-verdict";
import { setActivePowerDisplayUnit } from "@/lib/format/rate-unit";
import { makePortKey } from "@/lib/solver/plan/port-key";
import { buildPortView } from "./clean-model";

afterEach(() => setActivePowerDisplayUnit("eu"));

// A generator's EU port: 100 EU/t is 2,000 EU/s on the books.
const euPort = {
  side: "output",
  key: "o:eu",
  kind: "power",
  resourceId: "eu",
  displayName: "EU",
  handleId: "eu",
  connected: false,
  unsupplied: false,
  boundaryFree: true,
  currentPerSecond: 2000,
  nameplatePerSecond: 2000,
} as unknown as RailPort;

describe("a generator's EU row follows the power dial", () => {
  it("reads EU/t on the EU/t dial, with or without a tier", () => {
    expect(buildPortView("n", euPort, {}, undefined, undefined, "MV")).toMatchObject({
      number: "100",
      unit: "EU/t",
    });
    expect(buildPortView("n", euPort, {}, undefined)).toMatchObject({
      number: "100",
      unit: "EU/t",
    });
  });

  it("reads amps of the card's own tier on the Amps dial", () => {
    setActivePowerDisplayUnit("amps");
    expect(buildPortView("n", euPort, {}, undefined, undefined, "MV")).toMatchObject({
      number: "0.78",
      unit: "A MV",
    });
  });

  it("converts the forced row under it the same way", () => {
    setActivePowerDisplayUnit("amps");
    const forced = new Map([[makePortKey("n", "o", "eu"), 640]]);
    const view = buildPortView("n", euPort, {}, forced, undefined, "MV");
    expect(view.forced).toEqual({ number: "0.25", unit: "A MV" });
    expect(view).toMatchObject({ number: "0.53", unit: "A MV" });
  });
});
