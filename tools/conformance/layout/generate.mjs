// Writes the wire router's conformance data (conformance/layout) from the website's TypeScript router:
//   router-tests.json: every router call the website's router tests make (grid-edge-router.test.ts,
//     incremental-router.test.ts), boards in and routes out, plus the cardRimDocks vectors they ask for;
//   boards.json.gz: the reference boards (boxes and wires, as the game's layout bench lays out its plans), each
//     routed fresh, dragged and dropped.
// The Java router replays both (jvm/core/core-layout, RouterConformanceTest) and must answer the same.
//
// Usage: node tools/conformance/layout/generate.mjs [--boards <dir>]
//   --boards: a directory of board inputs (the mod's RouterVersus bench writes them to
//   jvm/mod/build/bench/versus/boards); without it the inputs already in boards.json.gz are routed again.
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { gunzipSync, gzipSync } from "node:zlib";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../../..");
const out = join(root, "conformance/layout");
const args = process.argv.slice(2);
const boardsAt = args.indexOf("--boards");

let boards;
if (boardsAt >= 0) {
  const dir = resolve(args[boardsAt + 1]);
  boards = readdirSync(dir)
    .filter((n) => n.endsWith(".json"))
    .sort()
    .map((n) => JSON.parse(readFileSync(join(dir, n), "utf8")))
    .map(({ name, boxes, wires }) => ({ name, boxes, wires }));
} else {
  boards = JSON.parse(
    gunzipSync(readFileSync(join(out, "boards.json.gz"))).toString("utf8"),
  ).boards.map(({ name, boxes, wires }) => ({ name, boxes, wires }));
}

const work = mkdtempSync(join(tmpdir(), "layout-capture-"));
try {
  const boardsIn = join(work, "boards-in.json");
  const boardsOut = join(work, "boards-out.json");
  writeFileSync(boardsIn, JSON.stringify(boards));
  const run = spawnSync("npx", ["vitest", "run", "--config", join(here, "capture.config.ts")], {
    cwd: root,
    stdio: "inherit",
    env: {
      ...process.env,
      LAYOUT_CAPTURE_OUT: work,
      LAYOUT_BOARDS_IN: boardsIn,
      LAYOUT_BOARDS_OUT: boardsOut,
    },
  });
  if (run.status !== 0) throw new Error(`the capture run failed (${run.status})`);
  const read = (name) => JSON.parse(readFileSync(join(work, `${name}.json`), "utf8"));
  const grid = read("grid-edge-router");
  const incremental = read("incremental-router");
  const tests = {
    generator: "node tools/conformance/layout/generate.mjs",
    solves: [...grid.solves, ...incremental.solves],
    sequences: [...grid.sequences, ...incremental.sequences],
    rimDocks: [...grid.rimDocks, ...incremental.rimDocks],
  };
  writeFileSync(join(out, "router-tests.json"), `${JSON.stringify(tests)}\n`);
  const answered = JSON.parse(readFileSync(boardsOut, "utf8"));
  const file = { generator: "node tools/conformance/layout/generate.mjs", boards: answered };
  // gzipSync of a Buffer writes no name or time: the same bytes every run.
  writeFileSync(
    join(out, "boards.json.gz"),
    gzipSync(Buffer.from(`${JSON.stringify(file)}\n`), { level: 9 }),
  );
  console.log(
    `router-tests.json: ${tests.solves.length} solves, ${tests.sequences.length} sequences, ${tests.rimDocks.length} rim docks; boards.json.gz: ${answered.length} boards`,
  );
} finally {
  rmSync(work, { recursive: true, force: true });
}
