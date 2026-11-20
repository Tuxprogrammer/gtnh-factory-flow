/**
 * Boards with open groups (frames), routed by the website's router with its default dials, for the Java router's
 * conformance run (tools/conformance/layout/generate.mjs appends them to router-tests.json's sequences).
 *
 * A frame is an obstacle; a wire may cross the frames its ends sit in (`exemptObstacleIds`, paying
 * `costInsideExempt` per px inside them) and keeps to the frames both its ends share (`homeObstacleIds`, paying
 * `costOutsideHome` per px outside them), as FactoryFlow builds the requests (`throughBoardIds`, `homeBoardIds`).
 * Some wires are wide, so lanes between the groups fill up.
 * The steps carry no dials: each side routes them with its own default (router-tuning.ts `DEFAULT_ROUTER_TUNING`,
 * RouterTuning.DEFAULT), so those defaults are held equal. (The reference boards in boards.json.gz have no frames;
 * their routes already follow the other default costs, `costOverflow` among them.)
 *
 * Each board names the dials it pins: the capture routes it again with each one moved by half a unit and fails when
 * no route changes, so a wrong default on either side cannot pass unseen.
 */
import { writeFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  LANE_CAPACITY,
  cardRimDocks,
  type GridObstacle,
  type GridRouteRequest,
} from "@/components/flow/grid-edge-router";
import { IncrementalRouter, type IncrementalStats } from "@/components/flow/incremental-router";
import { DEFAULT_ROUTER_TUNING, type RouterTuning } from "@/components/flow/router-tuning";

interface Card {
  id: string;
  x: number;
  y: number;
  /** Size, px: a recipe card's 160 x 120 when absent. */
  w?: number;
  h?: number;
  /** The frames the card sits in, outermost first. */
  chain: string[];
}
interface Frame {
  id: string;
  left: number;
  top: number;
  right: number;
  bottom: number;
}
interface Wire {
  id: string;
  from: string;
  to: string;
  width: number;
}
interface FrameBoard {
  name: string;
  cards: Card[];
  frames: Frame[];
  wires: Wire[];
  /** The card a drag moves, a cell right per rough beat. */
  moving: string;
  /** The dials some route of this board must follow. */
  pins: ReadonlyArray<(typeof DIALS)[number]>;
}

/** The dials these boards exist to pin. */
const DIALS = ["costInsideExempt", "costOutsideHome"] as const;

const W = 160;
const H = 120;

/**
 * Two groups side by side, the left one with a group inside it, cards outside both, wires within a group, between
 * the groups, into a group from outside and past the groups; the thick wires share the lanes between the groups.
 */
const BOARDS: FrameBoard[] = [
  {
    name: "two groups, one nested",
    frames: [
      { id: "frame-a", left: 0, top: 0, right: 600, bottom: 440 },
      { id: "frame-a2", left: 20, top: 240, right: 580, bottom: 420 },
      { id: "frame-b", left: 800, top: 0, right: 1400, bottom: 440 },
    ],
    cards: [
      { id: "a1", x: 40, y: 60, chain: ["frame-a"] },
      { id: "a2", x: 360, y: 60, chain: ["frame-a"] },
      { id: "a3", x: 40, y: 280, chain: ["frame-a", "frame-a2"] },
      { id: "a4", x: 360, y: 280, chain: ["frame-a", "frame-a2"] },
      { id: "b1", x: 840, y: 60, chain: ["frame-b"] },
      { id: "b2", x: 1180, y: 60, chain: ["frame-b"] },
      { id: "b3", x: 840, y: 280, chain: ["frame-b"] },
      { id: "b4", x: 1180, y: 280, chain: ["frame-b"] },
      { id: "c1", x: 360, y: 600, chain: [] },
      { id: "c2", x: 1020, y: 600, chain: [] },
      { id: "c3", x: 1600, y: 200, chain: [] },
      { id: "c4", x: -360, y: 160, chain: [] },
    ],
    wires: [
      { id: "a1-a2", from: "a1", to: "a2", width: 4 },
      { id: "a1-a4", from: "a1", to: "a4", width: 6 },
      { id: "a3-a4", from: "a3", to: "a4", width: 4 },
      { id: "a2-b1", from: "a2", to: "b1", width: 12 },
      { id: "a2-b3", from: "a2", to: "b3", width: 10 },
      { id: "a4-b3", from: "a4", to: "b3", width: 16 },
      { id: "a4-b1", from: "a4", to: "b1", width: 8 },
      { id: "a3-b4", from: "a3", to: "b4", width: 8 },
      { id: "c1-a3", from: "c1", to: "a3", width: 6 },
      { id: "c4-a1", from: "c4", to: "a1", width: 12 },
      { id: "c4-a3", from: "c4", to: "a3", width: 4 },
      { id: "b1-b2", from: "b1", to: "b2", width: 4 },
      { id: "b3-b4", from: "b3", to: "b4", width: 5 },
      { id: "b1-b4", from: "b1", to: "b4", width: 4 },
      { id: "b2-c3", from: "b2", to: "c3", width: 10 },
      { id: "c2-b4", from: "c2", to: "b4", width: 8 },
      { id: "c1-c2", from: "c1", to: "c2", width: 12 },
      { id: "c4-c3", from: "c4", to: "c3", width: 6 },
    ],
    moving: "a2",
    pins: ["costInsideExempt"],
  },
  {
    // A group's own wire either goes round the wall inside the group or out over its top, which costs
    // `costOutsideHome` a px: at the default the two are close.
    name: "a wall inside a group",
    frames: [{ id: "frame-h", left: 0, top: -400, right: 600, bottom: 600 }],
    cards: [
      { id: "h1", x: 40, y: -340, chain: ["frame-h"] },
      { id: "h2", x: 400, y: -340, chain: ["frame-h"] },
      { id: "hw", x: 240, y: -400, w: 120, h: 480, chain: ["frame-h"] },
      { id: "hx", x: 800, y: 0, chain: [] },
    ],
    wires: [
      { id: "h1-h2", from: "h1", to: "h2", width: 6 },
      { id: "h2-hx", from: "h2", to: "hx", width: 4 },
    ],
    moving: "h1",
    pins: ["costOutsideHome"],
  },
];

/** The drag: rough beats with the moving card a cell further each, then the drop. */
const BEATS = 2;

function obstaclesOf(board: FrameBoard, shift: number): GridObstacle[] {
  const cards = board.cards.map((c) => {
    const x = c.id === board.moving ? c.x + shift : c.x;
    return { id: c.id, left: x, top: c.y, right: x + (c.w ?? W), bottom: c.y + (c.h ?? H) };
  });
  return [...cards, ...board.frames.map((f) => ({ ...f }))];
}

/** As FactoryFlow: a wire crosses the frames of either end and keeps to the ones both ends share. */
function requestsOf(board: FrameBoard, obstacles: GridObstacle[]): GridRouteRequest[] {
  const byId = new Map(obstacles.map((o) => [o.id, o]));
  const cardById = new Map(board.cards.map((c) => [c.id, c]));
  return board.wires.map((w, order) => {
    const source = cardById.get(w.from)!;
    const target = cardById.get(w.to)!;
    const through = [...new Set([...source.chain, ...target.chain])];
    const home = source.chain.filter((id) => target.chain.includes(id));
    return {
      edgeId: w.id,
      order,
      sources: cardRimDocks(byId.get(w.from)!),
      targets: cardRimDocks(byId.get(w.to)!),
      sourceCardId: w.from,
      targetCardId: w.to,
      strokeWidth: Math.min(w.width, LANE_CAPACITY),
      exemptObstacleIds: through.length > 0 ? through : undefined,
      homeObstacleIds: home.length > 0 ? home : undefined,
    };
  });
}

interface Step {
  obstacles: GridObstacle[];
  requests: GridRouteRequest[];
  thorough: boolean;
  routes: unknown[];
  stats: Pick<IncrementalStats, "fresh" | "routed" | "kept" | "crossings" | "pops">;
}

function drag(board: FrameBoard, tuning: RouterTuning): Step[] {
  const router = new IncrementalRouter();
  const steps: Step[] = [];
  for (let beat = 0; beat <= BEATS + 1; beat += 1) {
    const shift = Math.min(beat, BEATS) * 20;
    const thorough = beat === 0 || beat === BEATS + 1;
    const obstacles = obstaclesOf(board, shift);
    const requests = requestsOf(board, obstacles);
    const stats = {} as IncrementalStats;
    const routes = [...router.solve(obstacles, requests, tuning, { thorough, stats }).values()];
    steps.push({
      obstacles,
      requests: JSON.parse(JSON.stringify(requests)) as GridRouteRequest[],
      thorough,
      routes: JSON.parse(JSON.stringify(routes)) as unknown[],
      stats: { fresh: stats.fresh, routed: stats.routed, kept: stats.kept, crossings: stats.crossings, pops: stats.pops },
    });
  }
  return steps;
}

const output = process.env.LAYOUT_FRAMES_OUT;
const sequences: unknown[] = [];

describe("frame boards", () => {
  for (const board of BOARDS) {
    it(`frame board ${board.name}`, () => {
      const steps = drag(board, DEFAULT_ROUTER_TUNING);
      const routes = JSON.stringify(steps.map((s) => s.routes));
      // Each pinned dial moved by half a unit must move a route.
      for (const dial of board.pins) {
        const moved = drag(board, { ...DEFAULT_ROUTER_TUNING, [dial]: DEFAULT_ROUTER_TUNING[dial] + 0.5 });
        expect(JSON.stringify(moved.map((s) => s.routes)), `${board.name}: no route depends on ${dial}`).not.toBe(
          routes,
        );
      }
      sequences.push({ name: `frame board ${board.name}`, steps });
    });
  }
  it("writes the answers", () => {
    if (output) writeFileSync(output, JSON.stringify(sequences));
  });
});
