/**
 * Routes the reference boards (conformance/layout/boards.json inputs: boxes and wires, as the game's bench lays out
 * the plans it measures) with the website's router, fresh and then with the busiest box dragged, and writes what
 * came back for the Java router to match (tools/conformance/layout/generate.mjs). Requests are built here the way
 * the Java BoxRouter builds them: each box's rim docks, filtered to the sides a wire may use.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { describe, it } from "vitest";
import {
  LANE_CAPACITY,
  cardRimDocks,
  type GridEndpoint,
  type GridRouteRequest,
} from "@/components/flow/grid-edge-router";
import { IncrementalRouter, type IncrementalStats } from "@/components/flow/incremental-router";
import { DEFAULT_ROUTER_TUNING } from "@/components/flow/router-tuning";
import { measureWireRoutes, routePoints } from "@/lib/route-metrics";

interface Box {
  key: string;
  x: number;
  y: number;
  w: number;
  h: number;
  square?: boolean;
}
interface Wire {
  key: string;
  from: number;
  to: number;
  fromSides: number;
  toSides: number;
  width: number;
}
interface Board {
  name: string;
  boxes: Box[];
  wires: Wire[];
}

const SIDE_BITS: Record<string, number> = { right: 1, bottom: 2, left: 4, top: 8 };

function sides(rim: GridEndpoint[], allowed: number): GridEndpoint[] {
  if ((allowed & 15) === 15 || (allowed & 15) === 0) return rim;
  return rim.filter((e) => (allowed & SIDE_BITS[e.side]) !== 0);
}

function obstacles(boxes: Box[]) {
  return boxes.map((b) => ({
    id: b.key,
    left: b.x,
    top: b.y,
    right: b.x + b.w,
    bottom: b.y + b.h,
  }));
}

function requests(boxes: Box[], wires: Wire[]): GridRouteRequest[] {
  const rims = obstacles(boxes).map((o) => cardRimDocks(o));
  return wires.map((w, i) => ({
    edgeId: w.key,
    order: i,
    sources: sides(rims[w.from], w.fromSides),
    targets: sides(rims[w.to], w.toSides),
    sourceCardId: boxes[w.from].key,
    targetCardId: boxes[w.to].key,
    sourceSquare: boxes[w.from].square === true,
    targetSquare: boxes[w.to].square === true,
    strokeWidth: Math.min(w.width, LANE_CAPACITY),
  }));
}

/** The box with the most wire ends (the first such). */
function busiest(board: Board): number {
  const count = board.boxes.map(() => 0);
  for (const w of board.wires) {
    count[w.from] += 1;
    count[w.to] += 1;
  }
  let best = 0;
  for (let i = 1; i < count.length; i += 1) if (count[i] > count[best]) best = i;
  return best;
}

/** The drag: the busiest box moved a cell right per beat, rough, then dropped (thorough). */
export const DRAG_BEATS = 3;

const input = process.env.LAYOUT_BOARDS_IN;
const output = process.env.LAYOUT_BOARDS_OUT;
const boards: Board[] = input ? JSON.parse(readFileSync(input, "utf8")) : [];
const results: unknown[] = [];

describe("reference boards", () => {
  for (const board of boards) {
    it(`board ${board.name}`, () => {
      const router = new IncrementalRouter();
      const moving = busiest(board);
      const steps: unknown[] = [];
      for (let beat = 0; beat <= DRAG_BEATS + 1; beat += 1) {
        const shift = Math.min(beat, DRAG_BEATS) * 20;
        const boxes = board.boxes.map((b, i) => (i === moving ? { ...b, x: b.x + shift } : b));
        const thorough = beat === 0 || beat === DRAG_BEATS + 1;
        const stats = {} as IncrementalStats;
        const routes = [
          ...router
            .solve(obstacles(boxes), requests(boxes, board.wires), DEFAULT_ROUTER_TUNING, {
              thorough,
              stats,
            })
            .values(),
        ];
        const measure = measureWireRoutes(routes);
        steps.push({
          shift,
          thorough,
          routes,
          stats: {
            fresh: stats.fresh,
            routed: stats.routed,
            kept: stats.kept,
            crossings: stats.crossings,
            rerouted: stats.rerouted,
            fallbacks: stats.fallbacks,
            pops: stats.pops,
            priced: stats.priced,
            searches: stats.searches,
          },
          measure: {
            crossings: measure.crossings,
            length: measure.length,
            points: routePoints(measure, DEFAULT_ROUTER_TUNING),
          },
        });
      }
      results.push({ ...board, moving, steps });
    });
  }
  it("writes the answers", () => {
    if (output) writeFileSync(output, JSON.stringify(results));
  });
});
