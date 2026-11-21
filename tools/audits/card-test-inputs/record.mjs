// Writes conformance/view-inputs/card-website-test-inputs.jsonl.gz: every card's facts the website's own tests hand
// `cleanCardView` (setup.ts), with the board's dials, deduplicated, in a stable order. The card shadow
// (src/components/flow/clean/card-shadow.test.ts) replays each through the website's builders and the compiled core.
// Beside it special-website-test-inputs.jsonl.gz: each special card's facts and its kind's, for the special cards'
// shadow (src/components/flow/clean/special/special-view.test.ts).
//   node tools/audits/card-test-inputs/record.mjs [--out <file.jsonl.gz>] [--special-out <file.jsonl.gz>]
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";

const root = fileURLToPath(new URL("../../../", import.meta.url));
const at = process.argv.indexOf("--out");
const out = path.resolve(
  at > 0
    ? process.argv[at + 1]
    : path.join(root, "conformance/view-inputs/card-website-test-inputs.jsonl.gz"),
);
const sat = process.argv.indexOf("--special-out");
const specialOut = path.resolve(
  sat > 0
    ? process.argv[sat + 1]
    : path.join(root, "conformance/view-inputs/special-website-test-inputs.jsonl.gz"),
);
const dir = mkdtempSync(path.join(os.tmpdir(), "card-inputs-"));
const raw = path.join(dir, "lines.jsonl");
const specialRaw = path.join(dir, "special.jsonl");
writeFileSync(raw, "");
writeFileSync(specialRaw, "");
try {
  execFileSync(
    process.execPath,
    [
      path.join(root, "node_modules/vitest/vitest.mjs"),
      "run",
      "--config",
      path.join(root, "tools/audits/card-test-inputs/vitest.config.ts"),
    ],
    { cwd: root, stdio: "inherit", env: { ...process.env, CARD_INPUTS_OUT: raw, SPECIAL_INPUTS_OUT: specialRaw } },
  );
  for (const [from, to] of [
    [raw, out],
    [specialRaw, specialOut],
  ]) {
    const lines = [...new Set(readFileSync(from, "utf8").split("\n").filter(Boolean))].sort();
    mkdirSync(path.dirname(to), { recursive: true });
    // gzip -n semantics: no name or time in the header, so the same lines give the same bytes.
    writeFileSync(to, gzipSync(lines.join("\n") + "\n", { level: 9 }));
    console.log(`${lines.length} inputs -> ${path.relative(root, to)}`);
  }
} finally {
  rmSync(dir, { recursive: true, force: true });
}
