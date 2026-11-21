/**
 * Writes the power goldens (conformance/power/power-goldens.jsonl.gz) for the core power module and the website to
 * check against each other. What each record holds is documented in src/lib/power/golden-records.ts.
 *
 *   npx tsx tools/audits/export-mod-power-goldens.ts --out conformance/power/power-goldens.jsonl.gz
 *   node tools/conformance/manifest.mjs --write
 *
 * FORMAT: gzip, UTF-8, one JSON object per line.
 */
import fs from "node:fs";
import zlib from "node:zlib";
import { powerGoldenLines } from "../../src/lib/power/golden-records";

function arg(name: string): string | undefined {
  const at = process.argv.indexOf(`--${name}`);
  return at > 0 ? process.argv[at + 1] : undefined;
}

function main() {
  const out = arg("out");
  if (!out) throw new Error("Expected --out <file.jsonl.gz>");
  const { lines, sources, cases } = powerGoldenLines();
  // Node writes no timestamp into the gzip header, so the same records give the same bytes.
  fs.writeFileSync(out, zlib.gzipSync(`${lines.join("\n")}\n`, { level: 9 }));
  console.log(`${sources} sources, ${cases} cases, ${lines.length} records written to ${out}`);
}

main();
