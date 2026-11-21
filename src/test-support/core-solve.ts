/**
 * The plan solve on the compiled core, for tests and the recorders (node only; never import from app code). The core
 * answers every plan the website solves (`src/lib/core/solve-adopt.ts`), so a test that checks what a plan solves to
 * checks it here, and every recorded answer under `conformance/solver/` is the core's.
 *
 *     beforeAll(() => useCoreSolver(), CORE_LOAD_MS);   // the solve seam answers through the core in this file
 *     const answer = coreSolve(input);          // one input, straight through the core
 *
 * Every plan solves on the core, so a test that solves a plan needs it: without one `useCoreSolver` fails with what to
 * do (build it, `tools/copy-core.mjs --build`, or fetch one, `box.sh <stream> core-pull`), never a skip or a quiet
 * answer from the website's stand-in engine.
 */
import { existsSync } from "node:fs";
import path from "node:path";

import { setAdoptOverride } from "@/lib/core/adopt";
import { core, loadCore, type CoreTarget } from "@/lib/core/loader";
import { solveEngine, solveThroughCore } from "@/lib/core/solve-adopt";
import { answerDigest } from "@/lib/solver/plan/__fixtures__/website-cases";
import type { Answer, SolveInput } from "@/lib/solver/plan/types";

export const corePresent = existsSync(path.join(process.cwd(), "public", "core", "manifest.json"));

/** How long a test file may take to load the solver module (a cold JavaScript build on a busy machine). */
export const CORE_LOAD_MS = 120_000;


/**
 * Loads the solver module (on `target` when given) and turns the solve area on: from here on the plan solve's seam
 * answers through the core.
 */
export async function useCoreSolver(target?: CoreTarget): Promise<void> {
  if (!corePresent)
    throw new Error(
      "this test solves plans, which runs on the compiled core, and public/core has none: build it (node tools/copy-core.mjs --build, JDK 25) or copy one in (GTNH_CORE_DIR)",
    );
  setAdoptOverride("solve", true);
  await loadCore({ modules: ["solver"], ...(target ? { target } : {}) });
  if (solveEngine() !== "core") throw new Error("the core is loaded but the plan solve does not use it");
}

/** One input through the core, the way the solve Worker hands it over. */
export function coreSolve(input: SolveInput): Answer {
  const answer = solveThroughCore(core(), input);
  if (answer === null) throw new Error("the core cannot be given this input");
  return answer;
}

/** The core's answer to a recorded case, as the recordings hold it: the golden digest, or `{failed: message}`. */
export function coreCaseAnswer(input: SolveInput): unknown {
  return answerDigest(coreSolve(input));
}
