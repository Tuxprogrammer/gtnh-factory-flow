/**
 * Writes (or checks) the JSON Schema files in `conformance/schema/`.
 *
 *   npx tsx tools/conformance/schema/write.ts            write them, then rewrite the manifest
 *   npx tsx tools/conformance/schema/write.ts --check    exit 1 and say which file differs
 *
 * The same builder runs in `src/test-support/schema-drift.test.ts`, so the suite fails when a Zod schema changed and
 * the checked-in files did not.
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

import { buildAll } from "./build";

const root = fileURLToPath(new URL("../../../", import.meta.url));
const dir = path.join(root, "conformance", "schema");
const check = process.argv.includes("--check");

const wanted = buildAll();
let bad = 0;
mkdirSync(dir, { recursive: true });
for (const [file, text] of wanted) {
  const target = path.join(dir, file);
  let have: string | null = null;
  try {
    have = readFileSync(target, "utf8");
  } catch {
    have = null;
  }
  if (have === text) continue;
  if (check) {
    console.error(`${file}: ${have === null ? "missing" : "differs from what the Zod schemas generate"}`);
    bad++;
  } else {
    writeFileSync(target, text);
    console.log(`wrote conformance/schema/${file}`);
  }
}
if (check) {
  if (bad > 0) {
    console.error("run: npx tsx tools/conformance/schema/write.ts");
    process.exit(1);
  }
  console.log("conformance/schema matches the Zod schemas");
} else {
  const r = spawnSync(process.execPath, [path.join(root, "tools/conformance/manifest.mjs"), "--write"], { stdio: "inherit" });
  process.exit(r.status ?? 1);
}
