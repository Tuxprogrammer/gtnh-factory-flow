/**
 * Writes conformance/view/: the overview rail's and the notices bar's cases (input records and the view the website
 * makes of them) and the arithmetic vectors. The core view module (jvm/core/core-view) must give the same views.
 *
 *   npx tsx tools/conformance/view/generate.ts
 *   node tools/conformance/manifest.mjs --write
 *
 * Deterministic: the same code gives the same bytes. src/components/flow/clean/rail/rail-conformance.test.ts fails
 * when a file differs from what the code makes now.
 */
import fs from "node:fs";
import { fileURLToPath } from "node:url";
import { viewFiles } from "../../../src/components/flow/clean/rail/rail-conformance";

const out = fileURLToPath(new URL("../../../conformance/view", import.meta.url));
fs.mkdirSync(out, { recursive: true });
for (const [name, text] of Object.entries(viewFiles())) {
  fs.writeFileSync(`${out}/${name}`, text);
  console.log(`${name}: ${text.length} bytes`);
}
