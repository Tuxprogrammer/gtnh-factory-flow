// @vitest-environment jsdom
import { act, cleanup, render } from "@testing-library/react";
import { ReactFlowProvider } from "@xyflow/react";
import { useRef } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { boardVirtualizer, type VRect } from "./card-virtualizer";
import { FAR_ATTRIBUTE } from "./VirtualCard";
import { VirtualizationController } from "./VirtualizationController";

/**
 * Drawers and special cards (generators, shared machines, crop cards, holders) are hidden by the controller when
 * far, not by a shell of their own. Hidden, a card is never measured, so it must never stay hidden without a box.
 */

let frames: FrameRequestCallback[] = [];
function flushFrames() {
  for (let round = 0; round < 20 && frames.length > 0; round += 1) {
    const due = frames;
    frames = [];
    for (const callback of due) callback(performance.now());
  }
}

let nodesLayer: HTMLElement;
function Harness() {
  const boardRef = useRef<HTMLDivElement>(null);
  return (
    <div ref={boardRef}>
      <div
        className="react-flow__nodes"
        ref={(element) => {
          if (element) nodesLayer = element;
        }}
      />
      <VirtualizationController boardRef={boardRef} />
    </div>
  );
}

/** What React Flow does when a card mounts: a wrapper under the nodes layer. */
function addWrapper(id: string) {
  const wrapper = document.createElement("div");
  wrapper.className = "react-flow__node react-flow__node-storageNode";
  wrapper.setAttribute("data-id", id);
  nodesLayer.appendChild(wrapper);
  return wrapper;
}

function wrapperOf(id: string) {
  return nodesLayer.querySelector<HTMLElement>(`[data-id="${id}"]`)!;
}

const box = (x: number): VRect => ({ x, y: 0, w: 120, h: 80 });
const view = { x: 0, y: 0, w: 1920, h: 1080 };

beforeEach(() => {
  frames = [];
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => {
    frames.push(callback);
    return frames.length;
  });
  vi.stubGlobal("cancelAnimationFrame", () => {});
  // jsdom has no CSS.escape; the ids here need none.
  vi.stubGlobal("CSS", { escape: (value: string) => value });
});

afterEach(() => {
  cleanup();
  boardVirtualizer.useBoard(undefined);
  vi.unstubAllGlobals();
});

/** Plan A: drawer "near" on screen, drawer "far" well off it, with the camera settled. */
function openPlanA() {
  boardVirtualizer.useBoard("plan-a");
  boardVirtualizer.setGeometry([
    ["near", box(0)],
    ["far", box(90_000)],
  ]);
  render(
    <ReactFlowProvider>
      <Harness />
    </ReactFlowProvider>,
  );
  addWrapper("near");
  addWrapper("far");
  act(() => {
    boardVirtualizer.setView(view, false);
    flushFrames();
  });
  expect(wrapperOf("far").hasAttribute(FAR_ATTRIBUTE)).toBe(true);
  expect(wrapperOf("near").hasAttribute(FAR_ATTRIBUTE)).toBe(false);
}

describe("a drawer or special card hidden as far", () => {
  it("is not hidden again when its plan's tab comes back, before it has been measured", async () => {
    openPlanA();
    // Another plan's tab: plan A's wrappers go, plan B's come.
    wrapperOf("near").remove();
    wrapperOf("far").remove();
    boardVirtualizer.useBoard("plan-b");
    boardVirtualizer.setGeometry([["other", box(0)]]);
    addWrapper("other");
    await act(async () => {
      await Promise.resolve();
      flushFrames();
    });
    // Back to plan A: its cards mount again and have no box until React Flow measures them.
    wrapperOf("other").remove();
    boardVirtualizer.useBoard("plan-a");
    boardVirtualizer.setGeometry([]);
    addWrapper("near");
    addWrapper("far");
    await act(async () => {
      await Promise.resolve();
      flushFrames();
    });
    expect(wrapperOf("far").hasAttribute(FAR_ATTRIBUTE)).toBe(false);
    expect(wrapperOf("near").hasAttribute(FAR_ATTRIBUTE)).toBe(false);
  });

  it("is shown when it loses its box, so that it can be measured and found again", () => {
    openPlanA();
    act(() => {
      boardVirtualizer.setGeometry([["near", box(0)]]);
      flushFrames();
    });
    expect(wrapperOf("far").hasAttribute(FAR_ATTRIBUTE)).toBe(false);
  });

  it("still comes back when the camera reaches it", () => {
    openPlanA();
    act(() => {
      boardVirtualizer.setView({ ...view, x: 89_000 }, false);
      flushFrames();
    });
    expect(wrapperOf("far").hasAttribute(FAR_ATTRIBUTE)).toBe(false);
    expect(wrapperOf("near").hasAttribute(FAR_ATTRIBUTE)).toBe(true);
  });
});
