import { calculateThroughput } from "@/lib/solver";
import { initLpEngine } from "@/lib/solver/lp-engine";
import { warmSolveCore } from "@/lib/core/solve-adopt";
import { settleSolveEngines } from "./solve-engines";
import { attachClogLocks } from "@/components/flow/clog-lock";
import type { FactoryProject } from "@/lib/model/types";
import { setActiveGameRates, type ActiveGameRates } from "@/lib/model/game-rates";

// The engines settle once, at Worker startup (solve-engines.ts): where the compiled core solves (WebAssembly GC), every
// solve waits for it and HiGHS is never fetched; HiGHS loads only where the core will not solve (no WebAssembly GC, a
// failed or hung load), and a solve never waits past the bounded waits there. The route serves HiGHS from the package in
// any start mode; public/ (copied by predev and prebuild) is the fallback.
const engineSettled = settleSolveEngines({
  core: warmSolveCore,
  highs: () =>
    initLpEngine({
      glueUrl: "/api/highs/highs.js",
      wasmUrl: "/api/highs/highs.wasm",
      fallback: { glueUrl: "/highs.js", wasmUrl: "/highs.wasm" },
    }),
});

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
    // The clog-lock proof is a second LP over the same board (old Build boards only: a plan reads none, see
    // plan/one-mode-reach.test.ts): it runs here and rides back on the books so the main thread never runs it.
    attachClogLocks(project, result);
    // The solve's own cost rides back so the router can learn whether this
    // board is one that must stay off the main thread - the diagnosis
    // counts, since a board slow only in its proof must stay here too.
    self.postMessage({ key, result, solveMs: performance.now() - started });
  } catch (error) {
    self.postMessage({ key, error: error instanceof Error ? error.message : String(error) });
  }
};
