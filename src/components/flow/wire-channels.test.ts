import { describe, expect, it } from "vitest";
import type { FactoryEdge } from "@/lib/model/types";
import { checklistWireIds, groupWireChannels } from "./wire-channels";

/**
 * One drawn wire per material between two cards: a drawer feeding three recipes of one shared machine the same item
 * (ports `r2:`, `r7:`, `r8:`) is one wire on the board. That wire stands for all three: it carries their summed
 * rate, hovering it lights all three ports, a checklist tick and a delete cover all three.
 */
const ITEM = "gregtech%3Agt.metaitem.01%402018";
const wire = (id: string, source: string, sourceHandle: string, target: string, targetHandle: string): FactoryEdge => ({
  id,
  source,
  sourceHandle,
  target,
  targetHandle,
  resourceKind: "item",
  resourceId: "gregtech:gt.metaitem.01@2018",
});
const edges = [
  wire("w8", "drawer", `output:item:${ITEM}`, "card", `r8:input:item:${ITEM}:0`),
  wire("w2", "drawer", `output:item:${ITEM}`, "card", `r2:input:item:${ITEM}:0`),
  wire("w7", "drawer", `output:item:${ITEM}`, "card", `r7:input:item:${ITEM}:0`),
  wire("other", "drawer", `output:item:${ITEM}`, "card2", `input:item:${ITEM}`),
];
const rates: Record<string, number> = { w8: 1, w2: 2, w7: 4, other: 8 };
const group = (representativeOf: (id: string) => string | undefined = (id) => id) =>
  groupWireChannels(edges, representativeOf, (id) => rates[id] ?? 0, (edge) => 10 * (rates[edge.id] ?? 0));

describe("wire channels", () => {
  it("draws one wire from a drawer into several recipes of one card, standing for all of them", () => {
    const channels = group();
    expect([...channels.skip].sort()).toEqual(["w2", "w7"]);
    expect(channels.idsByRepresentative.get("w8")).toEqual(["w8", "w2", "w7"]);
    expect(channels.totals.get("w8")).toEqual({ transferred: 7, demand: 70, fluid: false });
    expect(channels.portKeysByRepresentative.get("w8")?.sort()).toEqual(
      [
        `card|r2:input:item:${ITEM}`,
        `card|r7:input:item:${ITEM}`,
        `card|r8:input:item:${ITEM}`,
        `drawer|output:item:${ITEM}`,
      ].sort(),
    );
    // A wire to another card is its own.
    expect(channels.idsByRepresentative.has("other")).toBe(false);
    expect(channels.skip.has("other")).toBe(false);
  });

  it("lights no ports on an end folded into a minimized board", () => {
    const channels = group((id) => (id === "card" ? "board" : id));
    expect(channels.idsByRepresentative.get("w8")).toEqual(["w8", "w2", "w7"]);
    expect(channels.portKeysByRepresentative.get("w8")).toEqual([`drawer|output:item:${ITEM}`]);
  });

  it("ticks every member of the drawn wire on the checklist", () => {
    const shown = [{ id: "w8", data: { mergedEdgeIds: ["w8", "w2", "w7"] } }, { id: "other" }];
    expect(checklistWireIds(shown, "w8")).toEqual(["w8", "w2", "w7"]);
    expect(checklistWireIds(shown, "other")).toEqual(["other"]);
  });
});
