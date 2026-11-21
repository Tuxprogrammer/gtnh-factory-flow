import { copyFileSync, existsSync, mkdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

// The core module's resources are the source for the generated tables the
// website and the mod both read; the website copies under src/lib are built
// from them. Runs as predev and prebuild with --write, and as a test with
// --check, which fails when a copy has drifted. A checkout without the jvm/
// tree (a deploy clone of the website alone) keeps its committed copies.
//
// The generators (power-resource-map.mjs and the like) write the core file;
// run this afterwards to refresh the website copy.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

export const CORE_TABLES = [
  [
    "jvm/core/core-power/src/main/resources/assets/gtnhplanner/power/power-planner-data.json",
    "src/lib/power/data/power-planner-data.json",
  ],
  [
    "jvm/core/core-power/src/main/resources/assets/gtnhplanner/power/resource-map.json",
    "src/lib/power/data/resource-map.json",
  ],
  [
    "jvm/core/core-power/src/main/resources/assets/gtnhplanner/power/machine-icons.json",
    "src/lib/power/data/machine-icons.json",
  ],
  [
    "jvm/core/core-machines/src/main/resources/assets/gtnhplanner/machines/fusion-startups.json",
    "src/lib/machines/data/fusion-startups.json",
  ],
];

/** The pairs whose website copy differs from the core file. Empty when the core tree is absent. */
export function divergedTables() {
  const diverged = [];
  for (const [core, site] of CORE_TABLES) {
    if (!existsSync(path.join(root, core))) continue;
    const same =
      existsSync(path.join(root, site)) &&
      readFileSync(path.join(root, core)).equals(readFileSync(path.join(root, site)));
    if (!same) diverged.push({ core, site });
  }
  return diverged;
}

function main(mode) {
  const diverged = divergedTables();
  if (mode === "--check") {
    if (diverged.length > 0) {
      for (const { core, site } of diverged) console.error(`${site} differs from ${core}`);
      console.error("Run: node tools/sync-core-tables.mjs --write");
      process.exit(1);
    }
    return;
  }
  for (const { core, site } of diverged) {
    mkdirSync(path.dirname(path.join(root, site)), { recursive: true });
    copyFileSync(path.join(root, core), path.join(root, site));
    console.log(`copied ${core} -> ${site}`);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv[2] ?? "--write");
}
