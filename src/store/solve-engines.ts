/**
 * What a solve in the solve Worker waits for before it runs.
 *
 * Where the compiled core solves (the solve area on: `src/lib/core/solve-adopt.ts`; WebAssembly GC or the JavaScript
 * build), every solve waits for it, so a plan never shows HiGHS's numbers first and the core's after. HiGHS is fetched
 * only when the core will not solve here: the area off, a failed load, or a load still going after `coreWaitMs` (the
 * core still takes over once it arrives). Every wait is bounded, so a fetch that
 * never answers cannot hold a solve: past both waits the website's homegrown simplex answers.
 */
export const CORE_WAIT_MS = 10_000;
export const HIGHS_WAIT_MS = 20_000;

export type SettledEngine = "core" | "highs" | "simplex";

export interface EngineLoaders {
  /** Resolves true when the core will solve here (`warmSolveCore`), false when it will not; never rejects. */
  core: () => Promise<boolean>;
  /** Loads HiGHS; resolves true when it loaded (`initLpEngine`). */
  highs: () => Promise<boolean>;
  coreWaitMs?: number;
  highsWaitMs?: number;
}

const within = <T>(promise: Promise<T>, ms: number, otherwise: T): Promise<T> =>
  Promise.race([promise, new Promise<T>((resolve) => setTimeout(() => resolve(otherwise), ms))]);

export async function settleSolveEngines(loaders: EngineLoaders): Promise<SettledEngine> {
  const core = await within(
    loaders.core().catch(() => false),
    loaders.coreWaitMs ?? CORE_WAIT_MS,
    false,
  );
  if (core) return "core";
  const highs = await within(
    loaders.highs().catch(() => false),
    loaders.highsWaitMs ?? HIGHS_WAIT_MS,
    false,
  );
  return highs ? "highs" : "simplex";
}
