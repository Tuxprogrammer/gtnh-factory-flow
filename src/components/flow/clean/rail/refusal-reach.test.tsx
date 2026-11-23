// @vitest-environment jsdom
/**
 * A refused board edit ("This port already has a drawer.", a refused power wire) is told by `flashNotice`, which only
 * the plan-mode notices bar shows. That covers every board a player can open: a Build, Solve or Pool plan lands in
 * the store as a plan-mode board, so no classic board is left to stay silent.
 */
import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { drawer, handle, node, project, recipe, wire } from "@/lib/solver/plan/adapter/test-kit";
import type { FactoryProject } from "@/lib/model/types";
import { POWER_WIRE_REFUSED } from "@/lib/power/power-recipe";
import { PORT_HAS_DRAWER } from "@/store/board-refusal";
import { useFactoryStore } from "@/store/factory-store";
import { flashNotice, PlanTopNotices } from "./NoticeBar";

const plan = (mode: Partial<FactoryProject>) =>
  project({
    recipes: [recipe("mk", [["raw", 1]], [["x", 1]])],
    nodes: [node("m", "mk")],
    storages: [drawer("d1", "x"), drawer("d2", "x")],
    edges: [wire("w1", "m", "d1", "x", {}, { toDrawer: true })],
    extra: { planMode: false, ...mode },
  });

afterEach(cleanup);

describe.each([
  ["Build", { solveMode: false }],
  ["Solve", { solveMode: true }],
  ["Pool", { poolMode: true }],
] as const)("a %s plan", (_mode, mode) => {
  it("lands as a plan-mode board", () => {
    useFactoryStore.getState().setProject(plan(mode));
    expect(useFactoryStore.getState().project.planMode).toBe(true);
  });

  it("tells a refused power wire on that board", () => {
    useFactoryStore.getState().setProject(plan(mode));
    render(<PlanTopNotices />);
    act(() => flashNotice("info", POWER_WIRE_REFUSED));
    expect(screen.getAllByText(POWER_WIRE_REFUSED)).toHaveLength(1);
  });
});

describe.each([
  ["Build", { solveMode: false }],
  ["Solve", { solveMode: true }],
] as const)("on a %s plan", (_mode, mode) => {
  it("a second drawer wired to a port is refused, and the board says why", () => {
    useFactoryStore.getState().setProject(plan(mode));
    render(<PlanTopNotices />);
    act(() => {
      useFactoryStore.getState().connectNodes("m", "d2", {
        kind: "item",
        id: "x",
        sourceHandle: handle("output", "x"),
      });
    });
    expect(useFactoryStore.getState().project.edges.map((e) => e.id)).toEqual(["w1"]);
    expect(screen.getAllByText(PORT_HAS_DRAWER)).toHaveLength(1);
  });
});
