import { IslandCache, type PieceCache } from "../cache";
import { planExtensions, solvePlan } from "../solve-plan";
import type { Answer, SolveInput } from "../types";

/**
 * The plan solver: one `SolveInput` in, one `Answer` out, synchronous and pure (`solve-plan.ts`: islands, drawer
 * relaxation, alternatives, spares). Tests may stand in their own with `setSolveCore`; `setSolveCore(undefined)` puts
 * the real one back.
 */
export type SolveCore = (input: SolveInput) => Answer;

let override: SolveCore | undefined;

/**
 * Islands solved lately, by value (spec-solver 4.4): an edit re-solves only the islands it touched, and undo finds the
 * older answer. One per thread (the worker has its own); it never changes an answer, only what is solved again.
 */
export const islandCache: PieceCache = new IslandCache(64);

const cached: SolveCore = (input) => solvePlan(input, planExtensions, islandCache);

/** The website's own engine (TypeScript on its homegrown simplex), whatever else is installed. */
export const typescriptSolveCore: SolveCore = cached;

/**
 * The compiled core's engine, asked on every solve (`src/lib/core/solve-adopt.ts` installs it in the threads that may
 * load the solver: the solve Worker and the server's plan routes). It answers null while the core is off, loading,
 * missing or on a target that is not chosen to solve, and the website's own engine solves. Plan code may not reach the
 * loader, so this is the whole seam.
 */
let coreBridge: (() => SolveCore | null) | undefined;

export function setSolveCoreBridge(next: (() => SolveCore | null) | undefined): void {
  coreBridge = next;
}

export function setSolveCore(next: SolveCore | undefined): void {
  override = next;
}

export function getSolveCore(): SolveCore {
  return override ?? coreBridge?.() ?? cached;
}
