/**
 * The one entry point that writes conformance/view/: every record family's golden files (the input records and the
 * view the website's own code makes of them, plus arithmetic vectors). The core view module (jvm/core/core-view) must
 * give the same views. The families are listed in src/test-support/view-families.ts.
 *
 *   npx tsx tools/conformance/view/generate.ts            write the files (and remove any the families no longer make)
 *   npx tsx tools/conformance/view/generate.ts --check    fail when a file differs, is missing or is not made any more
 *   node tools/conformance/manifest.mjs --write           then refresh the manifest's hashes
 *
 * It also runs tools/web-core/gen-records.mjs for conformance/view/records.json (the shape table of the Java records).
 *
 * Deterministic: the same code gives the same bytes. src/test-support/view-corpus.test.ts runs the same check.
 */
import "./css-stub";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { VIEW_TOOL_FILES } from "../../../src/test-support/view-corpus";
import { allViewFiles } from "../../../src/test-support/view-families";

const out = fileURLToPath(new URL("../../../conformance/view", import.meta.url));
const made = allViewFiles();
const check = process.argv.includes("--check");

function existing(dir: string): string[] {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) return existing(full);
    return e.name === "README.md" ? [] : [path.relative(out, full).split(path.sep).join("/")];
  });
}

const problems: string[] = [];
for (const [name, text] of Object.entries(made)) {
  const file = path.join(out, name);
  if (check) {
    if (!fs.existsSync(file)) problems.push(`${name}: missing`);
    else if (fs.readFileSync(file, "utf8") !== text) problems.push(`${name}: differs`);
    continue;
  }
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, text);
  console.log(`${name}: ${text.length} bytes`);
}
for (const name of existing(out)) {
  if (name in made || VIEW_TOOL_FILES.includes(name)) continue;
  if (check) problems.push(`${name}: no family makes it`);
  else {
    fs.rmSync(path.join(out, name));
    console.log(`${name}: removed`);
  }
}
const records = fileURLToPath(new URL("../../web-core/gen-records.mjs", import.meta.url));
try {
  execFileSync("node", [records, check ? "--check" : "--write-shapes"], { stdio: "inherit" });
} catch {
  problems.push("records.json: differs from the Java records");
}
if (check && problems.length > 0) {
  console.error(`conformance/view is stale:\n  ${problems.join("\n  ")}`);
  process.exit(1);
}
