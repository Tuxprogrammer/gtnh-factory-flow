// Writes conformance/machines/website-test-inputs.jsonl.gz: every (recipe, node, gameRates) the website's machine tests
// hand the machine maths (getOverclockedRecipeStats, getNodePowerReport, getNodeSteamReport), deduplicated, in a stable
// order. The shadow test (src/lib/core/machines-shadow.test.ts) runs each through the TypeScript and the compiled core.
//   node tools/audits/machine-test-inputs/record.mjs [--out <file.jsonl.gz>]
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";

const root = fileURLToPath(new URL("../../../", import.meta.url));
const at = process.argv.indexOf("--out");
const out = path.resolve(
  at > 0
    ? process.argv[at + 1]
    : path.join(root, "conformance/machines/website-test-inputs.jsonl.gz"),
);
const dir = mkdtempSync(path.join(os.tmpdir(), "machine-inputs-"));
const raw = path.join(dir, "lines.jsonl");
writeFileSync(raw, "");
try {
  execFileSync(
    process.execPath,
    [
      path.join(root, "node_modules/vitest/vitest.mjs"),
      "run",
      "--config",
      path.join(root, "tools/audits/machine-test-inputs/vitest.config.ts"),
    ],
    { cwd: root, stdio: "inherit", env: { ...process.env, MACHINE_INPUTS_OUT: raw } },
  );
  const lines = [...new Set(readFileSync(raw, "utf8").split("\n").filter(Boolean))].sort();
  // gzip -n semantics: no name or time in the header, so the same lines give the same bytes.
  writeFileSync(out, gzipSync(lines.join("\n") + "\n", { level: 9 }));
  console.log(`${lines.length} inputs -> ${path.relative(root, out)}`);
} finally {
  rmSync(dir, { recursive: true, force: true });
}
