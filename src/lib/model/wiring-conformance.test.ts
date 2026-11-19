import { describe, expect, it } from "vitest";
import { readConformanceJson } from "@/test-support/conformance";
import { resourceMatchesInput } from "./resources";
import type { ResourceKind } from "./types";

interface WiringCase {
  name: string;
  slot: string;
  alternatives: string[];
  offered: string;
  takes: boolean;
}

/** "item:minecraft:log@32767" -> kind and id (the id may hold colons). */
function split(key: string): { kind: ResourceKind; id: string } {
  const colon = key.indexOf(":");
  return { kind: key.slice(0, colon) as ResourceKind, id: key.slice(colon + 1) };
}

// The wiring rule the game's planner holds too (core-model Wiring, ResourcesTest): a slot takes its own resource and
// what its recipe lists in its place, nothing that only shares an ore name.
describe("wiring rule (conformance/model/wiring.json)", () => {
  const { cases } = readConformanceJson("model", "wiring.json") as { cases: WiringCase[] };

  it("holds the shared cases", () => {
    expect(cases.length).toBeGreaterThanOrEqual(20);
  });

  it.each(cases.map((c) => [c.name, c] as const))("%s", (_name, c) => {
    const slot = { ...split(c.slot), alternatives: c.alternatives.map(split) };
    expect(resourceMatchesInput(split(c.offered), slot)).toBe(c.takes);
  });
});
