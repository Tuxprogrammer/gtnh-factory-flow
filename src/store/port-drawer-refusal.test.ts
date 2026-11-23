/**
 * A port takes one drawer. A second one, wired to the port or dropped off it, is refused, and the board's notices say
 * why.
 */
import { beforeEach, describe, expect, it } from "vitest";

import { drawer, handle, node, project, recipe, wire } from "@/lib/solver/plan/adapter/test-kit";
import { registerBoardRefusalSink } from "./board-refusal";
import { useFactoryStore } from "./factory-store";

const plan = () =>
  project({
    recipes: [recipe("mk", [["raw", 1]], [["x", 1]])],
    nodes: [node("m", "mk")],
    storages: [drawer("d1", "x"), drawer("d2", "x"), drawer("r1", "raw")],
    edges: [wire("w1", "m", "d1", "x", {}, { toDrawer: true })],
  });

let said: string[] = [];
registerBoardRefusalSink((text) => said.push(text));
const notice = () => said.at(-1);

beforeEach(() => {
  useFactoryStore.getState().setProject(plan());
  said = [];
});

describe("a second drawer on a port", () => {
  it("wired to the port, is refused with a notice", () => {
    useFactoryStore.getState().connectNodes("m", "d2", {
      kind: "item",
      id: "x",
      sourceHandle: handle("output", "x"),
    });
    expect(useFactoryStore.getState().project.edges.map((e) => e.id)).toEqual(["w1"]);
    expect(notice()).toBe("This port already has a drawer.");
  });

  it("dropped off the port, is not placed and the notice says why", () => {
    useFactoryStore
      .getState()
      .addStorageForConnection(
        { kind: "item", id: "x", displayName: "x" },
        "m",
        "output",
        { x: 400, y: 0 },
        handle("output", "x"),
      );
    const ids = (useFactoryStore.getState().project.storages ?? []).map((s) => s.id);
    expect(ids.filter((id) => !["d1", "d2", "r1"].includes(id))).toEqual([]);
    expect(ids).toContain("d1");
    expect(notice()).toBe("This port already has a drawer.");
  });

  it("a free port takes its drawer and nothing is said", () => {
    useFactoryStore.getState().connectNodes("r1", "m", {
      kind: "item",
      id: "raw",
      targetHandle: handle("input", "raw"),
    });
    expect(useFactoryStore.getState().project.edges).toHaveLength(2);
    expect(notice()).toBeUndefined();
  });
});
