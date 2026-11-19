/**
 * Records what the website's router tests ask the router and what it answers, for the Java router's conformance
 * run (conformance/layout). Loaded as a vitest setup file by capture.config.ts only: every solveGridRoutes call
 * (from a test or from inside IncrementalRouter), every IncrementalRouter step and every cardRimDocks call is written,
 * inputs as they were before the call and answers as they came back, to $LAYOUT_CAPTURE_OUT/<test file>.json. The
 * same for every arrangeBoard and arrangeBoardColumns call the website's arrange tests make (none of them has a
 * judge; the judged arrange is captured on the reference boards by arrange.capture.ts).
 */
import { afterAll, expect, vi } from "vitest";
import { mkdirSync, writeFileSync } from "node:fs";
import { basename, join } from "node:path";

type Json = unknown;
const clone = (value: Json): Json =>
  value === undefined ? undefined : JSON.parse(JSON.stringify(value));
const testName = () => expect.getState().currentTestName ?? "(outside a test)";
/** The reference boards write their own, smaller answers (boards.capture.ts, arrange.capture.ts). */
const recording = () =>
  !testName().startsWith("reference boards") && !testName().startsWith("arrange boards");

const solves: Json[] = [];
const sequences = new Map<number, { name: string; steps: Json[] }>();
const rimDocks: Json[] = [];
const arranges: Json[] = [];
const optimizes: Json[] = [];
const frees: Json[] = [];
const proxies: Json[] = [];
const freeSeen = new Set<string>();
/** What each air term was made from, so a recorded call can name the term it was handed. */
const airInputs = new WeakMap<object, Json>();

vi.mock("@/lib/board-arrange-air", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/board-arrange-air")>();
  return {
    ...original,
    makeAirTerm: (...args: Parameters<typeof original.makeAirTerm>) => {
      const term = original.makeAirTerm(...args);
      const [cards, wires, perPixel] = args;
      airInputs.set(term, {
        cards: cards.map((c) => ({ id: c.id, width: c.width, height: c.height, role: c.role })),
        wires: wires.map((w) => ({ source: w.source, target: w.target })),
        perPixel,
      });
      return term;
    },
  };
});

// The optimiser's search and the free placement as the column pass and the arrange call them: inputs and answers,
// for testing their translation on its own. Only calls whose judge is the optimiser's own stand-in are kept (a host
// judge's answers are not data); the free placement takes no judge; scoreLayoutProxy's air is kept as the value it
// gave at those positions.
vi.mock("@/lib/board-arrange-optimize", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/board-arrange-optimize")>();
  return {
    ...original,
    optimizeIslandLayout: (...args: Parameters<typeof original.optimizeIslandLayout>) => {
      const [cards, wires, options = {}] = args;
      const { judge, air, onProgress, ...rest } = options;
      void onProgress;
      const before = { cards: clone(cards), wires: clone(wires), options: clone(rest) };
      const result = original.optimizeIslandLayout(...args);
      if (recording() && judge === undefined)
        optimizes.push({
          name: testName(),
          ...before,
          air: air ? (airInputs.get(air) ?? "unknown") : null,
          result: clone(result),
        });
      return result;
    },
    scoreLayoutProxy: (...args: Parameters<typeof original.scoreLayoutProxy>) => {
      const [cards, wires, positions, prices, air] = args;
      const input = {
        cards: cards.map((c) => ({ id: c.id, width: c.width, height: c.height })),
        wires: clone(wires),
        positions: clone([...positions]),
        prices: clone(prices),
      };
      const score = original.scoreLayoutProxy(...args);
      if (recording()) proxies.push({ name: testName(), ...input, airValue: air ? air(positions) : null, score });
      return score;
    },
  };
});

vi.mock("@/lib/board-arrange-free", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/board-arrange-free")>();
  return {
    ...original,
    arrangeFree: (...args: Parameters<typeof original.arrangeFree>) => {
      const [cards, wires, options] = args;
      const { onProgress, ...rest } = options;
      void onProgress;
      const input = { cards: clone(cards), wires: clone(wires), options: clone(rest) };
      const result = original.arrangeFree(...args);
      const key = JSON.stringify(input);
      if (!freeSeen.has(key)) {
        freeSeen.add(key);
        frees.push({
          name: testName(),
          ...input,
          result: { positions: clone([...result.positions]), islands: clone(result.islands) },
        });
      }
      return result;
    },
  };
});
let routerCount = 0;

vi.mock("@/lib/board-arrange", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/board-arrange")>();
  const recorded =
    (name: "arrangeBoard" | "arrangeBoardColumns") =>
    (input: Parameters<typeof original.arrangeBoard>[0]) => {
      const { judge, onProgress, ...rest } = input;
      const before = clone(rest);
      const result = original[name](input);
      if (recording() && !judge) {
        void onProgress;
        arranges.push({ name: testName(), call: name, input: before, result: clone(result) });
      }
      return result;
    };
  return {
    ...original,
    arrangeBoard: recorded("arrangeBoard"),
    arrangeBoardColumns: recorded("arrangeBoardColumns"),
  };
});

vi.mock("@/components/flow/grid-edge-router", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/components/flow/grid-edge-router")>();
  const { measureWireRoutes, routePoints } = await import("@/lib/route-metrics");
  const { DEFAULT_ROUTER_TUNING } = await import("@/components/flow/router-tuning");
  return {
    ...original,
    solveGridRoutes: (...args: Parameters<typeof original.solveGridRoutes>) => {
      const [obstacles, requests, stats, tuning = DEFAULT_ROUTER_TUNING, pinned = []] = args;
      const input = {
        obstacles: clone(obstacles),
        requests: clone(requests),
        tuning: clone(tuning),
        pinned: clone(pinned),
      };
      const ownStats = stats ?? { crossings: 0, rerouted: 0, fallbacks: 0 };
      if (!recording()) return original.solveGridRoutes(...args);
      const routes = original.solveGridRoutes(obstacles, requests, ownStats, tuning, pinned);
      const list = [...routes.values()];
      const measure = measureWireRoutes(list);
      const { perEdge, firstPassMs, negotiationMs, ...work } = ownStats;
      void perEdge;
      void firstPassMs;
      void negotiationMs;
      solves.push({
        name: testName(),
        ...input,
        routes: clone(list),
        stats: clone(work),
        measure: {
          crossings: measure.crossings,
          length: measure.length,
          bends45: measure.bends45,
          bends90: measure.bends90,
          bendsSharp: measure.bendsSharp,
          weightedLength: measure.weightedLength,
          weightedCrossings: measure.weightedCrossings,
          points: routePoints(measure, tuning),
        },
      });
      return routes;
    },
    cardRimDocks: (rect: Parameters<typeof original.cardRimDocks>[0]) => {
      const docks = original.cardRimDocks(rect);
      if (recording())
        rimDocks.push({
          rect: { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom },
          docks: clone(docks),
        });
      return docks;
    },
  };
});

vi.mock("@/components/flow/incremental-router", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/components/flow/incremental-router")>();
  class RecordingRouter extends original.IncrementalRouter {
    private readonly id = (routerCount += 1);
    private steps(): Json[] {
      let sequence = sequences.get(this.id);
      if (!sequence) {
        sequence = { name: `${testName()} #${this.id}`, steps: [] };
        sequences.set(this.id, sequence);
      }
      return sequence.steps;
    }
    restore(snapshot: Parameters<InstanceType<typeof original.IncrementalRouter>["restore"]>[0]) {
      this.steps().push({ restore: clone(snapshot) });
      super.restore(snapshot);
    }
    reset() {
      this.steps().push({ reset: true });
      super.reset();
    }
    solve(...args: Parameters<InstanceType<typeof original.IncrementalRouter>["solve"]>) {
      const [obstacles, requests, tuning, options] = args;
      if (!recording()) return super.solve(...args);
      const input = {
        obstacles: clone(obstacles),
        requests: clone(requests),
        tuning: clone(tuning),
        thorough: options.thorough,
      };
      const stats = options.stats ?? ({} as NonNullable<typeof options.stats>);
      const routes = super.solve(obstacles, requests, tuning, { ...options, stats });
      this.steps().push({
        ...input,
        routes: clone([...routes.values()]),
        stats: {
          fresh: stats.fresh,
          routed: stats.routed,
          kept: stats.kept,
          crossings: stats.crossings,
          pops: stats.pops,
        },
      });
      return routes;
    }
  }
  return { ...original, IncrementalRouter: RecordingRouter };
});

afterAll(() => {
  const out = process.env.LAYOUT_CAPTURE_OUT;
  if (!out) return;
  mkdirSync(out, { recursive: true });
  const file = basename(expect.getState().testPath ?? "unknown").replace(/\.test\.ts$/, "");
  writeFileSync(
    join(out, `${file}.json`),
    JSON.stringify({ solves, sequences: [...sequences.values()], rimDocks, arranges, optimizes, frees, proxies }),
  );
});
