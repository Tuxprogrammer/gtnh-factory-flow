// @vitest-environment jsdom
/**
 * Ctrl+A on the real board selects every card, drawer, group, frame and note on it, as the controls list says. It
 * never takes the key from text the player is typing or selecting, from a dialog or menu, or from a board that
 * something covers.
 */
import { act, cleanup, render } from "@testing-library/react";
import { createElement } from "react";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { createNewProject } from "@/examples";
import { leaveLibrary, openLibrary } from "@/lib/library/library-tab";
import type { FactoryEdge, FactoryNode, FactoryProject, Recipe } from "@/lib/model/types";
import { leaveWelcomeTab } from "@/lib/welcome/welcome-tab";
import { useFactoryStore } from "@/store/factory-store";

vi.mock("@/lib/compact-view", () => ({
  isCompactViewport: () => false,
  useIsCompactViewport: () => false,
}));

import { FactoryFlow } from "./FactoryFlow";

beforeAll(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
  window.matchMedia ??= ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener() {},
    removeListener() {},
    addEventListener() {},
    removeEventListener() {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
  (globalThis as unknown as { DOMMatrixReadOnly: unknown }).DOMMatrixReadOnly ??= class {
    m22 = 1;
  };
});

function recipe(id: string, input: string | undefined, output: string): Recipe {
  return {
    id,
    name: `Recipe ${id}`,
    machineType: "Macerator",
    minimumTier: "LV",
    durationTicks: 20,
    eut: 2,
    inputs: input ? [{ kind: "item", id: input, amount: 1, displayName: input }] : [],
    outputs: [{ kind: "item", id: output, amount: 1, displayName: output }],
  };
}

function node(id: string, recipeId: string, x: number): FactoryNode {
  return {
    id,
    recipeId,
    machineCount: 1,
    parallel: 1,
    overclockTier: "LV",
    enabled: true,
    position: { x, y: 0 },
  };
}

function wire(id: string, source: string, target: string, resource: string): FactoryEdge {
  return {
    id,
    source,
    target,
    sourceHandle: `output:item:${resource}:0`,
    targetHandle: `input:item:${resource}:0`,
    resourceKind: "item",
    resourceId: resource,
  } as FactoryEdge;
}

/** Six machines in a chain, m0 the oldest. */
function chainPlan(): FactoryProject {
  const steps = [0, 1, 2, 3, 4, 5];
  return {
    ...createNewProject(),
    id: "delete-key",
    name: "Delete key",
    recipes: steps.map((i) => recipe(`r${i}`, i === 0 ? undefined : `res${i - 1}`, `res${i}`)),
    nodes: steps.map((i) => node(`m${i}`, `r${i}`, i * 400)),
    edges: steps.slice(1).map((i) => wire(`w${i}`, `m${i - 1}`, `m${i}`, `res${i - 1}`)),
  };
}

const ALL = ["m0", "m1", "m2", "m3", "m4", "m5"];
const cards = () => useFactoryStore.getState().project.nodes.map((entry) => entry.id);
const selected = () =>
  [...document.querySelectorAll(".react-flow__node.selected")].map((el) =>
    el.getAttribute("data-id"),
  );

/** Returns whether the page's default (selecting its text) was left alone. */
function pressCtrlA(target: Element = document.body, init: KeyboardEventInit = {}) {
  const event = new KeyboardEvent("keydown", {
    key: "a",
    ctrlKey: true,
    bubbles: true,
    cancelable: true,
    ...init,
  });
  act(() => {
    target.dispatchEvent(event);
  });
  return !event.defaultPrevented;
}

function press(key: string) {
  act(() => {
    document.body.dispatchEvent(
      new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true }),
    );
  });
}

function boardWithPlan() {
  render(createElement(FactoryFlow));
  act(() => useFactoryStore.getState().markHydratedProject(chainPlan()));
  expect(cards()).toEqual(ALL);
}

beforeEach(() => {
  leaveWelcomeTab();
  leaveLibrary();
});
afterEach(() => {
  cleanup();
  document.body.innerHTML = "";
  act(() => useFactoryStore.getState().clearResourceBrowser());
  leaveLibrary();
});

describe("the board's Ctrl+A", () => {
  it("selects every card and keeps the browser from selecting the page", () => {
    boardWithPlan();
    expect(pressCtrlA()).toBe(false);
    expect(selected().sort()).toEqual(ALL);
    // The selection is the board's: Delete takes all of it, wires with it.
    press("Delete");
    expect(cards()).toEqual([]);
    expect(useFactoryStore.getState().project.edges).toEqual([]);
  });

  it("also answers the Command key", () => {
    boardWithPlan();
    expect(pressCtrlA(document.body, { ctrlKey: false, metaKey: true })).toBe(false);
    expect(selected()).toHaveLength(6);
  });

  it("leaves Ctrl+A to a text field, a textarea and an editable note", () => {
    boardWithPlan();
    for (const tag of ["input", "textarea"]) {
      const field = document.body.appendChild(document.createElement(tag));
      expect(pressCtrlA(field)).toBe(true);
      field.remove();
    }
    const editable = document.body.appendChild(document.createElement("div"));
    editable.setAttribute("contenteditable", "true");
    expect(pressCtrlA(editable)).toBe(true);
    expect(selected()).toEqual([]);
  });

  it("leaves Ctrl+A to a dialog or menu that has the keyboard", () => {
    boardWithPlan();
    for (const role of ["dialog", "menu"]) {
      const panel = document.body.appendChild(document.createElement("div"));
      panel.setAttribute("role", role);
      const inner = panel.appendChild(document.createElement("button"));
      expect(pressCtrlA(inner)).toBe(true);
    }
    expect(selected()).toEqual([]);
  });

  it("is not Ctrl+Shift+A or Ctrl+Alt+A", () => {
    boardWithPlan();
    expect(pressCtrlA(document.body, { shiftKey: true })).toBe(true);
    expect(pressCtrlA(document.body, { altKey: true })).toBe(true);
    expect(selected()).toEqual([]);
  });

  it("leaves the page alone while the Library or the recipe search covers the board", () => {
    boardWithPlan();
    act(() => openLibrary());
    expect(pressCtrlA()).toBe(true);
    act(() => leaveLibrary());
    act(() => {
      useFactoryStore.getState().browseResource({ kind: "item", id: "res3" } as never, "recipes");
    });
    expect(pressCtrlA()).toBe(true);
    expect(selected()).toEqual([]);
  });
});
