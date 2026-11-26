// @vitest-environment jsdom
/**
 * A wire goes to React Flow only once both its cards have handles: a card first shown as a shell is measured with
 * none, and React Flow fails such a wire on every change of its store (a console warning each time in development)
 * until the card is drawn. The probe that found it: a 54-card plan opened at the glance step logged about 2,700.
 */
import type { Edge } from "@xyflow/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { boardEdges } from "./board-edges";
import { boardVirtualizer } from "./card-virtualizer";

const wire = (id: string, source: string, target: string): Edge => ({ id, source, target });
let handles: Record<string, number | undefined>;
let release: Array<() => void>;

beforeEach(() => {
  boardVirtualizer.useBoard(`board-edges-${Math.random()}`);
  boardVirtualizer.setGeometry([
    ["a", { x: 0, y: 0, w: 320, h: 200 }],
    ["b", { x: 400, y: 0, w: 320, h: 200 }],
    ["drawer", { x: 800, y: 0, w: 200, h: 100 }],
  ]);
  boardVirtualizer.setView({ x: -100, y: -100, w: 1920, h: 1080 }, true);
  // Recipe cards are the virtualizer's; a drawer keeps its contents and is not.
  release = [boardVirtualizer.register("a"), boardVirtualizer.register("b")];
  handles = { a: 0, b: 0, drawer: 2 };
  boardEdges.handleCountOf = (id) => handles[id];
});

afterEach(() => {
  boardEdges.handleCountOf = undefined;
  for (const r of release) r();
  boardEdges.setAll([]);
});

const shownIds = () => boardEdges.shown.map((edge) => edge.id).sort();

describe("wires handed to React Flow", () => {
  it("wait for a shell measured with no handles, and arrive when it is drawn", () => {
    boardEdges.setAll([wire("ab", "a", "b"), wire("bd", "b", "drawer")]);
    expect(shownIds()).toEqual([]);
    // Drawn in the background pass: React Flow holds its handles now.
    handles.a = 3;
    boardVirtualizer.markDrawn("a");
    expect(boardEdges.refresh()).toBeUndefined(); // b still has none
    handles.b = 2;
    boardVirtualizer.markDrawn("b");
    expect(boardEdges.refresh()?.map((edge) => edge.id).sort()).toEqual(["ab", "bd"]);
  });

  it("wait for a card React Flow has not measured yet", () => {
    handles = { a: undefined, b: 2, drawer: 2 };
    boardEdges.setAll([wire("ab", "a", "b")]);
    expect(shownIds()).toEqual([]);
  });

  it("keep a drawn card's wires while it is away, whatever React Flow reads off its shell", () => {
    boardVirtualizer.markDrawn("a");
    boardVirtualizer.markDrawn("b");
    handles = { a: 0, b: 0, drawer: 2 };
    boardEdges.setAll([wire("ab", "a", "b")]);
    expect(shownIds()).toEqual(["ab"]);
  });

  it("are every wire while the board is held for a photograph", () => {
    const unhold = boardVirtualizer.hold();
    try {
      boardEdges.setAll([wire("ab", "a", "b")]);
      expect(shownIds()).toEqual(["ab"]);
    } finally {
      unhold();
    }
  });
});
