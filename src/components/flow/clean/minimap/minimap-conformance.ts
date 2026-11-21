import type { FactoryPocket } from "@/lib/model/types";
import { viewEnvelope } from "@/test-support/view-corpus";
import { groupColorTag, groupColors } from "../group/group-model";
import { buildScene, type DrawerKind, type Scene, type SceneInput, type SceneNode } from "./minimap-scene";

/**
 * The minimap scene as shared cases (`conformance/view/minimap/cases.json`): the boxes and routes of a board as the
 * core's `MinimapBuilder` takes them (`MinimapInput`), and the scene the website's `buildScene` made of the same board.
 * The cases hold what both sides decide: which boxes are tiles, their tint and count, the wires kept and their colour,
 * the bounds. A tile's label, picture and rate text are the host's own (they come from its DOM), so they are not
 * in the view. No text goes through a measure, so the measure is `none`.
 */

export interface MinimapNodeWire {
  id: string;
  kind: string;
  x: number;
  y: number;
  w: number;
  h: number;
  hidden: boolean;
  selected: boolean;
  tag?: string;
  tint?: string;
  machines: number;
  pinned: boolean;
  role?: string;
}

export interface MinimapRouteWire {
  id: string;
  points: Array<{ x: number; y: number }>;
  width?: number;
  color?: string;
  flowing: boolean;
  hidden: boolean;
}

export interface MinimapInputWire {
  name: string;
  nodes: MinimapNodeWire[];
  routes: MinimapRouteWire[];
}

export interface MinimapViewWire {
  name: string;
  tiles: Array<{
    id: string;
    kind: string;
    x: number;
    y: number;
    w: number;
    h: number;
    tint: string;
    machines?: number;
    pinned?: boolean;
    zero?: boolean;
    drawer?: string;
    selected?: boolean;
  }>;
  wires: Array<{
    id: string;
    points: Array<{ x: number; y: number }>;
    width: number;
    color: string;
    flowing: boolean;
  }>;
  bounds?: { x0: number; y0: number; x1: number; y1: number };
}

export interface MinimapCase {
  name: string;
  input: MinimapInputWire;
  view: MinimapViewWire;
}

/** The same board as the website holds it: React Flow nodes by type, and edges with the routes the router made. */
export function sceneInputOf(input: MinimapInputWire): SceneInput {
  const nodes: SceneNode[] = input.nodes.map((n) => {
    const base = { id: n.id, x: n.x, y: n.y, w: n.w, h: n.h, hidden: n.hidden, selected: n.selected };
    switch (n.kind) {
      case "card":
        return {
          ...base,
          type: "recipeNode",
          data: {
            projectNode: {
              colorTag: n.tag,
              solvePin: n.pinned ? n.machines : undefined,
              solvePinExact: n.pinned || undefined,
            },
            result: { theoreticalMachinesRequired: n.pinned ? 0 : n.machines },
            recipe: { machineType: "" },
          },
        };
      case "drawer":
        return { ...base, type: "storageNode", data: {} };
      case "note":
        return { ...base, type: "annotationNode", data: { annotation: { kind: "text", colorTag: n.tag } } };
      case "board":
      case "group":
        return {
          ...base,
          type: n.kind === "board" ? "boardNode" : "pocketNode",
          data: { pocket: n.tint === undefined ? undefined : pocketOf(n.tint) },
        };
      default:
        return { ...base, type: "textNode", data: {} };
    }
  });
  const roles = new Map(input.nodes.map((n) => [n.id, n.role as DrawerKind | undefined]));
  return {
    name: input.name,
    nodes,
    edges: input.routes.map((r) => ({
      id: r.id,
      hidden: r.hidden,
      data: { color: r.color, flowRate: r.flowing ? {} : undefined },
    })),
    routes: input.routes.map((r) => ({ edgeId: r.id, points: r.points, width: r.width })),
    extras: (id) => {
      const drawer = roles.get(id);
      return drawer ? { drawer } : undefined;
    },
  };
}

/** A pocket that wears the group colour `tint` is (a tag whose swatch it is). */
function pocketOf(tint: string): FactoryPocket {
  const tag = GROUP_TINTS.find(([, c]) => c === tint)?.[0];
  return { id: "g", colorTag: tag } as unknown as FactoryPocket;
}

const GROUP_TAGS = ["azure", "emerald", "orange", "purple", "scarlet"] as const;
const GROUP_TINTS = GROUP_TAGS.map((tag) => [tag, groupColors(groupColorTag({ id: "g", colorTag: tag })).c] as const);
const tintOfGroup = (tag: (typeof GROUP_TAGS)[number]) => GROUP_TINTS.find(([t]) => t === tag)![1];

/** The view as the core writes it: a tile's host-only fields are left out, a missing optional is a missing key. */
export function viewOf(scene: Scene): MinimapViewWire {
  const view: MinimapViewWire = {
    name: scene.name,
    tiles: scene.tiles.map((t) => {
      const tile: MinimapViewWire["tiles"][number] = {
        id: t.id,
        kind: t.kind,
        x: t.x,
        y: t.y,
        w: t.w,
        h: t.h,
        tint: t.tint,
      };
      if (t.kind === "card") {
        tile.machines = Number((t.count ?? "x0").slice(1));
        tile.pinned = t.pinned === true;
        tile.zero = t.zero === true;
      }
      if (t.kind === "drawer") tile.drawer = t.drawer;
      tile.selected = t.selected === true;
      return tile;
    }),
    wires: scene.wires.map((w) => ({
      id: w.id,
      points: w.points,
      width: w.width,
      color: w.color,
      flowing: w.flowing,
    })),
  };
  if (scene.bounds) view.bounds = scene.bounds;
  return view;
}

const box = (
  id: string,
  kind: string,
  x: number,
  extra: Partial<MinimapNodeWire> = {},
): MinimapNodeWire => ({
  id,
  kind,
  x,
  y: 0,
  w: kind === "drawer" ? 136 : 320,
  h: kind === "drawer" ? 60 : 200,
  hidden: false,
  selected: false,
  machines: 0,
  pinned: false,
  ...extra,
});

const run = (id: string, color?: string, extra: Partial<MinimapRouteWire> = {}): MinimapRouteWire => ({
  id,
  points: [
    { x: 0, y: 10 },
    { x: 100, y: 10 },
    { x: 100, y: 80 },
  ],
  color,
  flowing: true,
  hidden: false,
  ...extra,
});

const SPECS: Array<{ name: string; nodes: MinimapNodeWire[]; routes?: MinimapRouteWire[] }> = [
  { name: "an empty plan has no tiles and no bounds", nodes: [] },
  {
    name: "a card carries its tag's colour and the count it shows",
    nodes: [
      box("a", "card", 0, { tag: "orange", machines: 6, pinned: true }),
      box("b", "card", 400, { machines: 0 }),
      box("c", "card", 800, { tag: "no-such-colour", machines: 2.5 }),
    ],
  },
  {
    name: "a count reads to two places below 100 and whole from 100",
    nodes: [
      box("a", "card", 0, { tag: "lime", machines: 0.333333 }),
      box("b", "card", 400, { tag: "lime", machines: 99.999 }),
      box("c", "card", 800, { tag: "lime", machines: 100.4 }),
      box("d", "card", 1200, { tag: "lime", machines: 250.6 }),
      box("e", "card", 1600, { tag: "lime", machines: 1.005 }),
    ],
  },
  {
    name: "a drawer takes its role's colour, and a plain one is a product",
    nodes: [
      box("s", "drawer", 0, { role: "source" }),
      box("p", "drawer", 200, { role: "product" }),
      box("t", "drawer", 400, { role: "trash" }),
      box("b", "drawer", 600, { role: "buffer" }),
      box("d", "drawer", 800),
    ],
  },
  {
    name: "a note is yellow without a tag, and grey for a tag nobody knows",
    nodes: [
      box("a", "note", 0, { w: 180, h: 120 }),
      box("b", "note", 300, { w: 180, h: 120, tag: "pink" }),
      box("c", "note", 600, { w: 180, h: 120, tag: "no-such-colour" }),
    ],
  },
  {
    name: "an open group and a collapsed one wear their colour",
    nodes: [
      box("g1", "board", 0, { tint: tintOfGroup("azure"), w: 900, h: 500 }),
      box("g2", "group", 1000, { tint: tintOfGroup("emerald"), w: 320, h: 120 }),
      box("g3", "group", 1400, { w: 320, h: 120 }),
    ],
  },
  {
    name: "a hidden box and one with no size are no tiles, and a selected one says so",
    nodes: [
      box("a", "card", 0, { machines: 1 }),
      box("b", "card", 400, { hidden: true, machines: 1 }),
      box("c", "card", 800, { w: 0, machines: 1 }),
      box("d", "drawer", 1200, { h: 0 }),
      box("e", "card", 1600, { selected: true, machines: 3 }),
    ],
  },
  {
    name: "the bounds hold every tile and nothing hidden",
    nodes: [
      { ...box("a", "card", -200, { machines: 1 }), y: -50 },
      { ...box("b", "drawer", 700), y: 400 },
      box("z", "card", 5000, { hidden: true, machines: 1 }),
    ],
  },
  {
    name: "a wire is lifted a fifth towards white and keeps what it has",
    nodes: [box("a", "card", 0, { machines: 1 })],
    routes: [
      run("w1", "#102030"),
      run("w2", "#ffffff"),
      run("w3", "#000000", { flowing: false, width: 5 }),
      run("w4", "#F59A5C", { width: 2.5 }),
    ],
  },
  {
    name: "a wire with no colour or a bad one is grey; a hidden one and a short one are left out",
    nodes: [box("a", "card", 0, { machines: 1 })],
    routes: [
      run("w1"),
      run("w2", "red"),
      run("w3", "#12345", { flowing: false }),
      run("w4", "#102030", { hidden: true }),
      run("w5", "#102030", { points: [{ x: 5, y: 5 }] }),
    ],
  },
];

export function minimapCases(): MinimapCase[] {
  return SPECS.map((s) => {
    const input: MinimapInputWire = { name: "Plan", nodes: s.nodes, routes: s.routes ?? [] };
    return { name: s.name, input, view: viewOf(buildScene(sceneInputOf(input))) };
  });
}

export function viewFiles(): Record<string, string> {
  return { "minimap/cases.json": viewEnvelope("minimap", "cases", "none", { cases: minimapCases() }) };
}
