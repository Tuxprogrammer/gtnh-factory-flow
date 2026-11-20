import { describe, expect, it } from "vitest";
import { readViewCases } from "@/test-support/view-corpus";
import { railsCases, type NodeRailsCase } from "./rails-conformance";

/**
 * conformance/view/rails holds what one card of a solved plan reads (its recipe's slots, the solve's flows, the wires)
 * and the verdict and rows the website's `deriveNodeVerdict` and `buildRailPorts` made of it. The core's `NodeRails`
 * checks the same file from Java (`NodeRailsCasesTest`). That the file is what the website makes now is
 * `view-corpus.test.ts`'s check; this one holds the cases to the kinds of card they are meant to cover.
 */
const file = readViewCases<NodeRailsCase["input"], NodeRailsCase["view"]>("rails", "cases");

describe("the card rails' cases", () => {
  it("takes no measure", () => {
    expect(file.measure).toBe("none");
  });

  it("holds every case the module makes, in order, as the website makes it", () => {
    const made = railsCases();
    expect(file.cases.map((c) => c.name)).toEqual(made.map((c) => c.name));
    expect(file.cases).toEqual(JSON.parse(JSON.stringify(made)));
  });

  it("covers every verdict a plan board reaches, and the host's own ladder", () => {
    const verdicts = new Set(file.cases.map((c) => c.view.verdict));
    expect([...verdicts].sort()).toEqual([
      "balanced",
      "demand-set",
      "legacy",
      "no-recipe",
      "off",
      "unwired",
    ]);
  });

  it("covers a kept input, the free rows, an idle row left out, a bare slot and a section handle", () => {
    const rows = file.cases.flatMap((c) => [...c.view.inputs, ...c.view.outputs]);
    expect(rows.some((r) => r.free)).toBe(true);
    expect(rows.some((r) => r.handleId.startsWith("r2:"))).toBe(true);
    expect(rows.some((r) => r.shortBadge !== undefined)).toBe(true);
    expect(file.cases.some((c) => c.view.bare !== undefined)).toBe(true);
    expect(file.cases.some((c) => c.input.inputs.slots.some((s) => !s.consumed))).toBe(true);
  });
});
