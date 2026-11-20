import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { beforeAll, describe, expect, it, vi } from "vitest";

import { findClogLocks, attachClogLocks } from "@/components/flow/clog-lock";
import { findDeathSpirals } from "@/components/flow/death-spiral";
import { deriveNodeVerdict, type NodeVerdictKind } from "@/components/flow/node-verdict";
import { tourProject } from "@/components/flow/clean/tour/sandbox";
import { createNewProject } from "@/examples";
import { parseFactoryProjectJson } from "@/lib/import-export/factory-json";
import { normalizeLoadedProject } from "@/lib/model/project-normalize";
import type { FactoryProject } from "@/lib/model/types";
import { initLpEngine } from "@/lib/solver/lp-engine";
import { calculateThroughput } from "@/lib/solver/throughput";
import { useFactoryStore } from "@/store/factory-store";

/**
 * There is one board mode, so the old Build, Solve and Pool engines and the diagnosis built on them (`equilibrium.ts`,
 * `equations-core.ts` and its LP, `solve-mode.ts`, the death-spiral and clog-lock proofs, and every verdict below the
 * solve-mode line of `deriveNodeVerdict`) are reached by no plan board. This pins that, so the core ports only what a
 * board reaches and the old engines can be removed on evidence: every reference plan, opened as the store opens it,
 * solved and read by every card's verdict, the board's ring indexes and the solve Worker's clog-lock call, never calls
 * them. The one file that still builds a project outside the one mode is listed in `UNMODED`, and no player reaches it.
 */
const calls = { equilibrium: 0, rings: 0, equations: 0, solveMode: 0 };

vi.mock("@/lib/solver/equilibrium", async (original) => {
  const m = await original<typeof import("@/lib/solver/equilibrium")>();
  return {
    ...m,
    solveEquilibrium: (...a: Parameters<typeof m.solveEquilibrium>) => {
      calls.equilibrium += 1;
      return m.solveEquilibrium(...a);
    },
    stronglyConnectedComponents: (...a: Parameters<typeof m.stronglyConnectedComponents>) => {
      calls.rings += 1;
      return m.stronglyConnectedComponents(...a);
    },
  };
});
vi.mock("@/lib/solver/equations-core", async (original) => {
  const m = await original<typeof import("@/lib/solver/equations-core")>();
  return {
    ...m,
    solveEquationsCore: (...a: Parameters<typeof m.solveEquationsCore>) => {
      calls.equations += 1;
      return m.solveEquationsCore(...a);
    },
  };
});
vi.mock("@/lib/solver/solve-mode", async (original) => {
  const m = await original<typeof import("@/lib/solver/solve-mode")>();
  return {
    ...m,
    solveSolveMode: (...a: Parameters<typeof m.solveSolveMode>) => {
      calls.solveMode += 1;
      return m.solveSolveMode(...a);
    },
  };
});

const ROOT = process.cwd();
/** The plans the solve shadow and the card corpus read (the recorded cards are every recipe card of these plans). */
const DIRS = [
  "conformance/plans/website",
  "conformance/plans/mod",
  "conformance/plans/valid",
  "src/components/flow/__fixtures__",
  "jvm/mod/src/test/resources/factory-flow",
  "examples",
];

/** What a verdict can read on a plan board: everything above the solve-mode line of `deriveNodeVerdict`. */
const PLAN_KINDS: readonly NodeVerdictKind[] = [
  "off",
  "no-recipe",
  "unwired",
  "balanced",
  "demand-set",
];

/**
 * Non-test files that build a project with neither mode flag (`createEmptyProject`), each with why. The blueprint
 * stats solve a captured payload as a Build plan, but only the blueprint save dialog asks for them, and nothing opens
 * that dialog any more: no caller sets the blueprint store's `saveRequest` or `overwritePicking` since the shelf
 * buttons left the board.
 */
const UNMODED: Record<string, string> = {
  "src/examples/empty-project.ts": "createNewProject adds both flags to it",
  "src/lib/blueprints/io-stats.ts":
    "blueprint stats, reachable only from the save dialog nothing opens",
};

function plans(): { name: string; raw: FactoryProject }[] {
  const out: { name: string; raw: FactoryProject }[] = [];
  for (const dir of DIRS)
    for (const f of readdirSync(path.join(ROOT, dir))
      .filter((f) => f.endsWith(".json"))
      .sort()) {
      try {
        out.push({
          name: `${dir}/${f}`,
          raw: parseFactoryProjectJson(readFileSync(path.join(ROOT, dir, f), "utf8")),
        });
      } catch {
        // Not a plan this build reads.
      }
    }
  return out;
}

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) return e.name === "__fixtures__" ? [] : sourceFiles(full);
    return /\.tsx?$/.test(e.name) && !/\.test\.tsx?$/.test(e.name) && !/\.d\.ts$/.test(e.name)
      ? [full]
      : [];
  });
}

describe("no plan board reaches the old engines or the verdicts below the solve-mode line", () => {
  beforeAll(async () => {
    await initLpEngine();
  });

  const all = plans();

  it("reads the stored old Build, Solve and Pool plans among them", () => {
    const legacy = all.filter((p) => !p.raw.planMode).map((p) => p.name);
    for (const name of ["old-build.json", "old-solve.json", "old-pool.json"])
      expect(
        legacy.some((l) => l.endsWith(name)),
        name,
      ).toBe(true);
    expect(all.length).toBeGreaterThanOrEqual(30);
  });

  it.each(all.map((p) => [p.name, p] as const))("%s", (_name, { raw }) => {
    const before = { ...calls };
    // As the store lands every arriving plan (`normalizeLoadedProject` ends in `openAsPlan`).
    const project = normalizeLoadedProject(raw);
    expect(project.planMode).toBe(true);
    expect(project.solveMode).toBe(true);
    expect(project.poolMode).toBeFalsy();
    const result = calculateThroughput(project);
    for (const node of project.nodes)
      expect(PLAN_KINDS, node.id).toContain(deriveNodeVerdict(project, result, node.id).kind);
    expect(findDeathSpirals(project, result).spirals).toEqual([]);
    expect(findClogLocks(project, result).locks).toEqual([]);
    // The solve Worker runs this on every answer.
    attachClogLocks(project, result);
    expect(result.clogLocks?.locks).toEqual([]);
    expect(calls).toEqual(before);
  });

  it("a new design, the tour's plan and the store's mode switches stay in the one mode", () => {
    for (const p of [createNewProject(), tourProject()]) {
      expect(p.planMode).toBe(true);
      expect(normalizeLoadedProject(p).solveMode).toBe(true);
    }
    useFactoryStore.setState({ project: createNewProject() });
    useFactoryStore.getState().setSolveMode(false);
    useFactoryStore.getState().setPoolMode(true);
    const after = useFactoryStore.getState().project;
    expect([after.planMode, after.solveMode, after.poolMode ?? false]).toEqual([true, true, false]);
  });

  it("only the listed files build a project outside the one mode", () => {
    const found = sourceFiles(path.join(ROOT, "src"))
      .filter((f) => /\bcreateEmptyProject\(\)/.test(readFileSync(f, "utf8")))
      .map((f) => path.relative(ROOT, f).split(path.sep).join("/"))
      .sort();
    expect(found).toEqual(Object.keys(UNMODED).sort());
  });

  it("the counters see the old engines when a plan does reach them", () => {
    // The same old Build plan kept in its saved mode, as only the old engines' own tests load one.
    const build = all.find((p) => p.name.endsWith("old-build.json"))!.raw;
    expect(build.planMode).toBeFalsy();
    const before = { ...calls };
    calculateThroughput(build);
    expect(calls.equilibrium).toBeGreaterThan(before.equilibrium);
    expect(calls.equations).toBeGreaterThan(before.equations);
    const solve = all.find((p) => p.name.endsWith("old-solve.json"))!.raw;
    calculateThroughput(solve);
    expect(calls.solveMode).toBeGreaterThan(before.solveMode);
  });
});
