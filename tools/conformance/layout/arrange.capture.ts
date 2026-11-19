/**
 * Arranges the reference boards (the inputs of conformance/layout/boards.json.gz) with the website's Arrange and its
 * router judge, and writes what came back for the Java Arrange to match (tools/conformance/layout/generate.mjs): each
 * stage on its own (the column candidates, the free placement) and the whole arrange, every candidate's verdict and
 * the final one. Each run gets its own judge, as each of the website's workers builds one.
 *
 * Inputs are built here from the boxes and wires and written into the data: cards (a square box is storage), wires
 * with ids, widths, weights and port rows (spread over the card's rows in wire order), the spacing (compact and normal
 * by turns), and the judge's obstacles and requests (each box's rim docks on the sides a wire may use, as
 * boards.capture.ts). The polish's eight-second allowance is a wall clock; here the clock stands still, so the polish
 * stops on its count alone and the answer is the same on any machine (the Java runner does the same).
 */
import { readFileSync, writeFileSync } from "node:fs";
import { afterAll, describe, it, vi } from "vitest";
import {
  LANE_CAPACITY,
  cardRimDocks,
  type GridEndpoint,
  type GridRouteRequest,
} from "@/components/flow/grid-edge-router";
import { DEFAULT_ROUTER_TUNING } from "@/components/flow/router-tuning";
import {
  arrangeBoard,
  arrangeColumnStage,
  arrangeFreeStage,
  type ArrangeCard,
  type ArrangeResult,
  type ArrangeWire,
} from "@/lib/board-arrange";
import { makeRouteJudge } from "@/lib/route-judge";

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
  return boxes.map((b) => ({ id: b.key, left: b.x, top: b.y, right: b.x + b.w, bottom: b.y + b.h }));
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

/** A port row's middle on a box: drawers have one, a card's rows are 40 px from 30 px down. */
function portY(box: Box, k: number): number {
  if (box.square) return box.h / 2;
  const rows = Math.max(1, Math.floor((box.h - 20) / 40));
  return 30 + 40 * (k % rows);
}

function arrangeInput(board: Board, index: number) {
  const cards: ArrangeCard[] = board.boxes.map((b) => ({
    id: b.key,
    x: b.x,
    y: b.y,
    width: b.w,
    height: b.h,
    ...(b.square ? { role: "storage" as const } : {}),
  }));
  const outs = board.boxes.map(() => 0);
  const ins = board.boxes.map(() => 0);
  const wires: ArrangeWire[] = board.wires.map((w, i) => ({
    id: w.key,
    source: board.boxes[w.from].key,
    target: board.boxes[w.to].key,
    sourcePortY: portY(board.boxes[w.from], outs[w.from]++),
    targetPortY: portY(board.boxes[w.to], ins[w.to]++),
    weight: 1 + (i % 4),
    width: w.width,
  }));
  return {
    cards,
    wires,
    taste: { spacing: index % 2 === 0 ? ("compact" as const) : ("normal" as const) },
    judgeInput: {
      obstacles: obstacles(board.boxes),
      requests: requests(board.boxes, board.wires),
      tuning: DEFAULT_ROUTER_TUNING,
    },
  };
}

const MAX_WIRES = Number(process.env.LAYOUT_ARRANGE_MAX_WIRES ?? 80);
const input = process.env.LAYOUT_BOARDS_IN;
const output = process.env.LAYOUT_ARRANGE_OUT;
const boards: Board[] = input ? JSON.parse(readFileSync(input, "utf8")) : [];
const results: unknown[] = [];

describe("arrange boards", () => {
  boards.forEach((board, index) => {
    if (board.wires.length > MAX_WIRES || board.boxes.length < 2) return;
    it(`arrange ${board.name}`, () => {
      const clock = vi.spyOn(performance, "now").mockReturnValue(0);
      try {
        const { judgeInput, ...arrange } = arrangeInput(board, index);
        const judge = () =>
          makeRouteJudge(judgeInput.obstacles, judgeInput.requests, judgeInput.tuning);
        const run = { ...arrange, tuning: judgeInput.tuning };
        const verdictOf = (result: ArrangeResult) => {
          const v = judge()(new Map(result.moves.map((m) => [m.id, m.position])));
          return { crossings: v.crossings, length: v.length, points: v.points };
        };
        const columns = arrangeColumnStage({ ...run, judge: judge() });
        const free = arrangeFreeStage({ ...run, judge: judge() });
        const final = arrangeBoard({ ...run, judge: judge() });
        results.push({
          name: board.name,
          ...arrange,
          judgeInput,
          plain: columns.plain,
          challenger: columns.challenger,
          free,
          result: final,
          verdicts: {
            plain: verdictOf(columns.plain),
            challenger: verdictOf(columns.challenger),
            free: verdictOf(free),
            result: verdictOf(final),
          },
        });
      } finally {
        clock.mockRestore();
      }
    });
  });
});

afterAll(() => {
  if (output) writeFileSync(output, JSON.stringify(results));
});
