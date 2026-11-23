/**
 * Twin cards (the same recipe twice, each wired to its own buffer drawers) solve the same after a player folds them:
 * Combine gives one card with two sections of that recipe, each keeping its own drawers, and a card folded down to one
 * section with both twins' buffers on its ports does the work of both. A port takes one drawer by hand (the board
 * refuses a second), so a fold by hand leaves the other twin's drawers on their other cards. Both engines.
 */
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";

import { CORE_LOAD_MS, useCoreSolver } from "@/test-support/core-solve";
import type { FactoryEdge, FactoryProject, MachineHandler, Recipe } from "@/lib/model/types";
import { calculateThroughput } from "@/lib/solver/throughput";
import { setSolveCore, typescriptSolveCore } from "@/lib/solver/plan/adapter/engine";
import { drawer, handle, node, project, recipe, wire } from "@/lib/solver/plan/adapter/test-kit";
import { useFactoryStore } from "./factory-store";

beforeAll(() => useCoreSolver(), CORE_LOAD_MS);
afterAll(() => setSolveCore(undefined));

const LCR: MachineHandler = {
  id: "large-chemical-reactor",
  label: "Large Chemical Reactor",
  machineType: "Large Chemical Reactor",
  minimumTier: "LV",
  kind: "multiblock",
};
const reactor = (r: Recipe): Recipe => ({ ...r, machineHandlers: [LCR] });
const recipes = [
  recipe("mx", [["raw", 1]], [["x", 1]]),
  reactor(
    recipe(
      "ty",
      [
        ["x", 1],
        ["s", 1],
      ],
      [
        ["y", 1],
        ["w", 1],
      ],
      "Large Chemical Reactor",
    ),
  ),
  recipe("up", [["y", 1]], [["p", 1]]),
];
const storages = [
  drawer("r1", "raw"),
  drawer("r2", "raw"),
  drawer("s1", "s"),
  drawer("s2", "s"),
  drawer("bx1", "x"),
  drawer("bx2", "x"),
  drawer("by1", "y"),
  drawer("by2", "y"),
  drawer("wd", "w"),
  drawer("p1", "p", { targetMode: "at-least", targetPerSecond: 1 }),
  drawer("p2", "p", { targetMode: "at-least", targetPerSecond: 2 }),
];
const fromDrawer = { fromDrawer: true };
const toDrawer = { toDrawer: true };
/** Everything but the twins' own wires: two makers into buffers bx1, bx2, two users out of buffers by1, by2. */
const around: FactoryEdge[] = [
  wire("r1-m1", "r1", "m1", "raw", {}, fromDrawer),
  wire("r2-m2", "r2", "m2", "raw", {}, fromDrawer),
  wire("m1-bx1", "m1", "bx1", "x", {}, toDrawer),
  wire("m2-bx2", "m2", "bx2", "x", {}, toDrawer),
  wire("by1-u1", "by1", "u1", "y", {}, fromDrawer),
  wire("by2-u2", "by2", "u2", "y", {}, fromDrawer),
  wire("u1-p1", "u1", "p1", "p", {}, toDrawer),
  wire("u2-p2", "u2", "p2", "p", {}, toDrawer),
];
/** One twin's wires: its own x buffer, s source and y buffer, and the byproduct drawer both share. */
const twinWires = (card: string, n: 1 | 2, section = 0): FactoryEdge[] => [
  wire(`bx${n}-${card}`, `bx${n}`, card, "x", { to: section }, fromDrawer),
  wire(`s${n}-${card}`, `s${n}`, card, "s", { to: section }, fromDrawer),
  wire(`${card}-by${n}`, card, `by${n}`, "y", { from: section }, toDrawer),
  wire(`${card}-wd-${n}`, card, "wd", "w", { from: section }, toDrawer),
];
const others = [node("m1", "mx"), node("m2", "mx"), node("u1", "up"), node("u2", "up")];

const twinCards = (): FactoryProject =>
  project({
    recipes,
    nodes: [...others, node("t1", "ty"), node("t2", "ty")],
    storages,
    edges: [...around, ...twinWires("t1", 1), ...twinWires("t2", 2)],
  });

/** One card, one section, both twins' buffers on its ports (what a hand-edited or imported plan can hold). */
const foldedToOneSection = (): FactoryProject =>
  project({
    recipes,
    nodes: [...others, node("t", "ty")],
    storages: storages.filter((s) => s.id !== "s2"),
    edges: [
      ...around,
      ...twinWires("t", 1),
      wire("bx2-t", "bx2", "t", "x", {}, fromDrawer),
      wire("t-by2", "t", "by2", "y", {}, toDrawer),
    ],
  });

const combined = (): FactoryProject => {
  useFactoryStore.getState().setProject(twinCards());
  useFactoryStore.getState().combineNodesIntoMachine(["t1", "t2"]);
  return useFactoryStore.getState().project;
};

const expectSameWork = (plan: FactoryProject) => {
  const result = calculateThroughput(plan, { generatedAt: "t" });
  expect(result.plan!.failure).toBeUndefined();
  expect(result.plan!.forced).toEqual([]);
  expect(result.plan!.unmetDrawers ?? []).toEqual([]);
  expect(result.storages["p1"]!.producedPerSecond).toBeCloseTo(1, 6);
  expect(result.storages["p2"]!.producedPerSecond).toBeCloseTo(2, 6);
  expect(result.storages["wd"]!.producedPerSecond).toBeCloseTo(3, 6);
  const raw = result.storages["r1"]!.consumedPerSecond + result.storages["r2"]!.consumedPerSecond;
  expect(raw).toBeCloseTo(3, 6);
  return result;
};

describe.each([
  ["the core", () => setSolveCore(undefined)],
  ["the website's engine", () => setSolveCore(typescriptSolveCore)],
])("folded twin cards solve as the twins did (%s)", (_engine, use) => {
  beforeEach(() => use());

  it("two twin cards", () => {
    expectSameWork(twinCards());
  });

  it("Combine: one card, two sections of the same recipe, each on its own drawers", () => {
    const plan = combined();
    expect(plan.nodes.filter((n) => n.recipeId === "ty")).toHaveLength(1);
    expectSameWork(plan);
  });

  it("one section with two buffers on its x input and two on its y output", () => {
    const result = expectSameWork(foldedToOneSection());
    expect(result.storages["s1"]!.consumedPerSecond).toBeCloseTo(3, 6);
  });
});

describe("folding by hand on the board", () => {
  it("a port that has a drawer refuses a second one, so the twin's drawers stay on their other cards", () => {
    const plan = combined();
    const host = plan.nodes.find((n) => n.recipeId === "ty")!.id;
    useFactoryStore.getState().removeRecipeSection(host, 1);
    const before = useFactoryStore.getState().project.edges.length;
    const linkBx2 = () =>
      useFactoryStore.getState().connectNodes("bx2", host, {
        kind: "item",
        id: "x",
        targetHandle: handle("input", "x"),
      });
    linkBx2();
    expect(useFactoryStore.getState().project.edges).toHaveLength(before);
    // The same wire is taken once the port's own drawer is off it, so the refusal above is the one-drawer rule.
    const own = useFactoryStore
      .getState()
      .project.edges.find((e) => e.source === "bx1" && e.target === host)!;
    useFactoryStore.getState().deleteEdge(own.id);
    linkBx2();
    expect(
      useFactoryStore.getState().project.edges.some((e) => e.source === "bx2" && e.target === host),
    ).toBe(true);
    setSolveCore(undefined);
    const result = calculateThroughput(useFactoryStore.getState().project, { generatedAt: "t" });
    expect(result.plan!.failure).toBeUndefined();
    expect(result.storages["p1"]!.producedPerSecond).toBeCloseTo(1, 6);
    expect(result.storages["p2"]!.producedPerSecond).toBeCloseTo(2, 6);
  });
});
