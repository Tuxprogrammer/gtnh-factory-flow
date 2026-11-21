// Writes conformance/solver/website/cases.json.gz: every distinct solve input the website's own tests (the solver's
// test scenarios, the board parity scenarios, every other test that solves a plan) and the reference plans hand the
// plan solver, with the compiled core's answer, for the Java kernel to match (core-solver WebsiteCasesTest) and the website
// to keep (src/lib/solver/plan/__fixtures__/website-cases.test.ts).
//
// Usage: node tools/conformance/solver/generate.mjs
//   1. capture.config.ts runs the whole website suite and reference-plans.capture.ts with capture-setup.ts recording
//      every input handed to solvePlan, solveCore or solvePiece (not the islands of one call);
//   2. export.config.ts dedupes them, makes them what both engines read (export.capture.ts), solves each on the compiled
//      core (needs public/core: tools/copy-core.mjs) and writes the file;
//   3. the conformance manifest is rewritten.
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../../..");
const work = mkdtempSync(join(tmpdir(), "solver-capture-"));
const run = (config) => {
  const r = spawnSync("npx", ["vitest", "run", "--config", join(here, config)], {
    cwd: root,
    stdio: "inherit",
    env: { ...process.env, SOLVE_CAPTURE_OUT: work },
  });
  return r.status;
};
try {
  // A failing website test still records what it solved; the export below is what must succeed.
  const captured = run("capture.config.ts");
  if (captured !== 0)
    console.error(
      `[website cases] the capture run ended ${captured}; its recorded inputs are used`,
    );
  if (run("export.config.ts") !== 0) throw new Error("the export failed");
  const manifest = spawnSync("node", [join(root, "tools/conformance/manifest.mjs"), "--write"], {
    stdio: "inherit",
  });
  if (manifest.status !== 0) throw new Error("the manifest could not be written");
} finally {
  rmSync(work, { recursive: true, force: true });
}
