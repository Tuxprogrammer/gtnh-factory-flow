import { readFileSync } from "node:fs";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";

/**
 * The plan routes solve on the compiled core and on nothing else (`plan-engine.ts`): with no core they refuse (503)
 * rather than hold the request thread on the website's stand-in, and a production build cannot be made without one.
 */
afterEach(() => {
  vi.doUnmock("@/lib/core/solve-adopt");
  vi.resetModules();
});

async function engine(loads: boolean) {
  vi.resetModules();
  const warm = vi.fn(async () => loads);
  vi.doMock("@/lib/core/solve-adopt", () => ({ warmSolveCore: warm }));
  return { warm, ...(await import("./plan-engine")) };
}

describe("the plan routes' engine", () => {
  it("loads the core for a plan", async () => {
    const { warm, ensurePlanEngine } = await engine(true);
    await expect(ensurePlanEngine({ planMode: true })).resolves.toBeUndefined();
    expect(warm).toHaveBeenCalledTimes(1);
  });

  it("refuses a plan when the core does not load", async () => {
    const { ensurePlanEngine, PlanEngineUnavailableError } = await engine(false);
    vi.spyOn(console, "error").mockImplementation(() => {});
    await expect(ensurePlanEngine({ planMode: true })).rejects.toBeInstanceOf(
      PlanEngineUnavailableError,
    );
  });

  it("a production build requires the core", () => {
    const pkg = JSON.parse(readFileSync(path.join(process.cwd(), "package.json"), "utf8")) as {
      scripts: Record<string, string>;
    };
    expect(pkg.scripts.prebuild).toContain("node tools/copy-core.mjs --required");
  });
});
