import { warmSolveCore } from "@/lib/core/solve-adopt";
import type { FactoryProject } from "@/lib/model/types";

/** The compiled core could not be loaded on this server: a plan cannot be solved here. */
export class PlanEngineUnavailableError extends Error {
  constructor() {
    super("Plan solving is unavailable on this server right now. Try again later.");
    this.name = "PlanEngineUnavailableError";
  }
}

/**
 * Stats are derived on the server from the uploaded plan (`calculateThroughput`), and a plan solves on the compiled
 * core (src/lib/core/solve-adopt.ts): the routes load its solver module first (a no-op after the first load). A
 * production build always carries the core (`prebuild` runs tools/copy-core.mjs --required). Where it still cannot
 * load, the route answers 503 rather than solve on the website's stand-in engine, which held the request thread for
 * minutes on a big plan; the stats refresh then keeps the saved card.
 */
export async function ensurePlanEngine(project: Pick<FactoryProject, "planMode">): Promise<void> {
  if (project.planMode && !(await warmSolveCore())) {
    console.error("[plan-engine] the compiled core did not load: plan routes answer 503 (public/core, tools/copy-core.mjs)");
    throw new PlanEngineUnavailableError();
  }
}
