import { calculateThroughput } from "@/lib/solver";
import { initLpEngine } from "@/lib/solver/lp-engine";
import { warmSolveCore } from "@/lib/core/solve-adopt";
import { attachClogLocks } from "@/components/flow/clog-lock";
import type { FactoryProject } from "@/lib/model/types";
import { setActiveGameRates, type ActiveGameRates } from "@/lib/model/game-rates";

// HiGHS loads once at worker startup, and every solve waits for the load to
// settle: a board routed here is slow by definition, so a one-time wasm
// fetch is always the better trade than a minutes-long homegrown walk. A
// failed load resolves too - solves then run on the homegrown simplex.
// The route serves them from the package in any start mode; public/ (copied by predev and prebuild) is the fallback.
const engineReady = initLpEngine({
  glueUrl: "/api/highs/highs.js",
  wasmUrl: "/api/highs/highs.wasm",
  fallback: { glueUrl: "/highs.js", wasmUrl: "/highs.wasm" },
});

/**
 * A HiGHS request that never answers (a stalled fetch) would hold every solve forever. Past this wait the solve runs
 * as after a failed load, on the homegrown simplex; HiGHS still takes over for later solves if it arrives.
 */
const ENGINE_WAIT_MS = 20_000;
const engineSettled = Promise.race([
  // The compiled core's solver loads beside HiGHS (src/lib/core/solve-adopt.ts; nothing when the solve area is off).
  // HiGHS stays: the clog-lock proof below and every solve the core does not take run on it. The first solve waits
  // for both, so the first answer comes from the engine every later one does.
  Promise.all([engineReady, warmSolveCore()]),
  new Promise<boolean>((resolve) => setTimeout(() => resolve(false), ENGINE_WAIT_MS)),
]);

/**
 * The solver, off the main thread. One message in (a plan, the dataset's rates and the content key
 * that names it), one message out (the same key and the finished books). The
 * scheduling - coalescing rapid edits, dropping superseded solves, deciding
 * which result is still current - all lives on the store side in
 * `solve-books.ts`; this file stays a dumb calculator on purpose.
 */
self.onmessage = async (
  event: MessageEvent<{ key: string; project: FactoryProject; rates?: ActiveGameRates }>,
) => {
  const { key, project, rates } = event.data;
  try {
    await engineSettled;
    // The worker is its own module scope: the dataset's rate factors reach it with each plan, set after the wait so
    // a later message cannot swap them under this solve.
    setActiveGameRates(rates);
    const started = performance.now();
    const result = calculateThroughput(project);
    // The clog-lock proof is a second LP over the same board, and on a plan
    // with many stopped machines a harder one than the books: it runs here,
    // on HiGHS, and rides back on the books so the main thread never runs it.
    attachClogLocks(project, result);
    // The solve's own cost rides back so the router can learn whether this
    // board is one that must stay off the main thread - the diagnosis
    // counts, since a board slow only in its proof must stay here too.
    self.postMessage({ key, result, solveMs: performance.now() - started });
  } catch (error) {
    self.postMessage({ key, error: error instanceof Error ? error.message : String(error) });
  }
};
