import { warmSolveCore } from "@/lib/core/solve-adopt";
import { initLpEngine } from "@/lib/solver/lp-engine";
import type { FactoryProject } from "@/lib/model/types";

/**
 * Stats are derived on the server from the uploaded plan (`calculateThroughput`).
 * A plan-mode plan solves integer programs, which only HiGHS can do, so the
 * routes load it first (a no-op for every other plan and after the first
 * load). If it cannot load the solve fails honestly and the stats are the
 * failed plan's, never an error page. The compiled core's solver loads beside it and solves when the solve area is
 * adopted (src/lib/core/solve-adopt.ts); without it HiGHS solves.
 */
export async function ensurePlanEngine(project: Pick<FactoryProject, "planMode">): Promise<void> {
  if (project.planMode) {
    await Promise.all([initLpEngine(), warmSolveCore()]);
  }
}
