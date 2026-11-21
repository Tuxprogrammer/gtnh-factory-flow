/**
 * Writes conformance/solver/website/cases.json.gz from what capture.config.ts recorded (tools/conformance/solver/
 * generate.mjs): every distinct solve input the website's tests and the reference plans handed the plan solver, as
 * both engines read it (ids made UUIDs and renamed in their order, the mod's dump format, a chance rounded to the Java
 * float it is there), with the compiled core's answer to it (`src/test-support/core-solve.ts`, the path the solve
 * Worker takes) as the golden digest. Inputs already in
 * the corpus or the Up to plans are left out (those are checked there), and so are inputs a JSON file cannot hold
 * (a non-finite number). Run only by export.config.ts.
 */
import { readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { gzipSync } from "node:zlib";

import { beforeAll, describe, expect, it } from "vitest";

import { coreCaseAnswer, corePresent, useCoreSolver } from "@/test-support/core-solve";
import { toDump, withOrderedIds, withUuids } from "@/lib/solver/plan/__fixtures__/dump";
import { caseNames, loadInput, parseSolveInput } from "@/lib/solver/plan/__fixtures__/load";
import type { SolveInput } from "@/lib/solver/plan/types";
import { conformancePath } from "@/test-support/conformance";

const IN = process.env.SOLVE_CAPTURE_OUT!;
const OUT = process.env.SOLVE_CASES_OUT ?? conformancePath("solver", "website", "cases.json.gz");

interface Captured {
  file: string;
  inputs: Array<{ name: string; input: SolveInput; nonFinite?: true }>;
}

/**
 * An input as both engines read it: ids made UUIDs and renamed in their own order (a test that makes random ids writes
 * the same case every run), the dump format, the chance a Java float as `load.ts` reads every dump.
 */
function asCase(input: SolveInput): { read: SolveInput; text: string } {
  const read = parseSolveInput(toDump(withOrderedIds(withUuids(input))));
  return { read, text: JSON.stringify(toDump(read)) };
}

describe("website solver cases", () => {
  beforeAll(async () => {
    if (!corePresent) throw new Error("the answers are the compiled core's: build it first (tools/copy-core.mjs)");
    await useCoreSolver();
  }, 120_000);

  it("writes every recorded input with the core's answer", () => {
    const known = new Set<string>();
    for (const name of caseNames()) known.add(asCase(loadInput(name)).text);
    const upTo = conformancePath("solver", "up-to");
    for (const f of readdirSync(upTo).filter((n) => n.endsWith(".json")))
      known.add(
        asCase(parseSolveInput(JSON.parse(readFileSync(join(upTo, f), "utf8")).input)).text,
      );

    const files = readdirSync(IN)
      .filter((f) => f.endsWith(".json"))
      .sort()
      .map((f) => JSON.parse(readFileSync(join(IN, f), "utf8")) as Captured);
    const seen = new Set<string>();
    const cases: Array<{ name: string; input: unknown; answer: unknown }> = [];
    let skippedNonFinite = 0;
    let skippedKnown = 0;
    for (const captured of files) {
      for (const { name, input, nonFinite } of captured.inputs) {
        if (nonFinite) {
          skippedNonFinite += 1;
          continue;
        }
        const { read, text } = asCase(input);
        if (known.has(text)) {
          skippedKnown += 1;
          continue;
        }
        if (seen.has(text)) continue;
        seen.add(text);
        cases.push({
          name: `${captured.file.replaceAll("~", "/")} > ${name}`,
          input: JSON.parse(text),
          answer: coreCaseAnswer(read),
        });
      }
    }
    cases.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
    const file = { generator: "node tools/conformance/solver/generate.mjs", cases };
    // gzipSync of a Buffer writes no name or time: the same bytes every run.
    writeFileSync(OUT, gzipSync(Buffer.from(`${JSON.stringify(file)}\n`), { level: 9 }));
    console.log(
      `[website cases] ${cases.length} cases written to ${OUT}; left out: ${skippedKnown} already in the corpus or the Up to plans, ${skippedNonFinite} with a non-finite number`,
    );
    expect(cases.length).toBeGreaterThan(0);
  }, 3_600_000);
});
