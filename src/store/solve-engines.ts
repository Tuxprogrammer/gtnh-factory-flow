/**
 * What a solve in the solve Worker waits for before it runs.
 *
 * Every solve waits for the compiled core (`src/lib/core/solve-adopt.ts`; WebAssembly GC or its JavaScript build), so a
 * plan never shows the stand-in's numbers first and the core's after. The wait is bounded: a load that fails, or is
 * still going after `coreWaitMs`, leaves the website's own engine (its homegrown simplex) to answer, and the core still
 * takes over once it arrives. While the stand-in answers, the page says solving is degraded (`solve-health.ts`).
 */
export const CORE_WAIT_MS = 10_000;

export type SettledEngine = "core" | "simplex";

export interface EngineLoaders {
  /** Resolves true when the core will solve here (`warmSolveCore`), false when it will not; never rejects. */
  core: () => Promise<boolean>;
  coreWaitMs?: number;
}

const within = <T>(promise: Promise<T>, ms: number, otherwise: T): Promise<T> =>
  Promise.race([promise, new Promise<T>((resolve) => setTimeout(() => resolve(otherwise), ms))]);

export async function settleSolveEngines(loaders: EngineLoaders): Promise<SettledEngine> {
  const core = await within(
    loaders.core().catch(() => false),
    loaders.coreWaitMs ?? CORE_WAIT_MS,
    false,
  );
  return core ? "core" : "simplex";
}
