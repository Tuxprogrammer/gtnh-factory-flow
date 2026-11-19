/**
 * Writes conformance/solver/website/cases.json.gz from what capture.config.ts recorded (tools/conformance/solver/
 * generate.mjs): every distinct solve input the website's tests and the reference plans handed the plan solver, as
 * both engines read it (ids made UUIDs, the mod's dump format, a chance rounded to the Java float it is there), with
 * the website's answer to it through the public entry (`solvePlan`, no cache) as the golden digest. Inputs already in
 * the corpus or the Up to plans are left out (those are checked there), and so are inputs a JSON file cannot hold
 * (a non-finite number). Run only by export.config.ts.
 */
import { readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { gzipSync } from "node:zlib";

import { beforeAll, describe, expect, it } from "vitest";

import { initLpEngine } from "@/lib/solver/lp-engine";
import { websiteCaseAnswer } from "@/lib/solver/plan/__fixtures__/website-cases";
import { toDump, withUuids } from "@/lib/solver/plan/__fixtures__/dump";
import { caseNames, loadInput, parseSolveInput } from "@/lib/solver/plan/__fixtures__/load";
import type { SolveInput } from "@/lib/solver/plan/types";
import { conformancePath } from "@/test-support/conformance";

const IN = process.env.SOLVE_CAPTURE_OUT!;
const OUT = process.env.SOLVE_CASES_OUT ?? conformancePath("solver", "website", "cases.json.gz");

interface Captured {
  file: string;
  inputs: Array<{ name: string; input: SolveInput; nonFinite?: true }>;
}

describe("website solver cases", () => {
  beforeAll(async () => {
    await initLpEngine();
  });

  it("writes every recorded input with the website's answer", () => {
    const known = new Set<string>();
    for (const name of caseNames()) known.add(JSON.stringify(toDump(loadInput(name))));
    const upTo = conformancePath("solver", "up-to");
    for (const f of readdirSync(upTo).filter((n) => n.endsWith(".json")))
      known.add(
        JSON.stringify(
          toDump(parseSolveInput(JSON.parse(readFileSync(join(upTo, f), "utf8")).input)),
        ),
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
        const dump = toDump(withUuids(input));
        // The input as both engines read it: the chance a Java float, as `load.ts` reads every dump.
        const read = parseSolveInput(dump);
        const text = JSON.stringify(toDump(read));
        if (known.has(text)) {
          skippedKnown += 1;
          continue;
        }
        if (seen.has(text)) continue;
        seen.add(text);
        cases.push({
          name: `${captured.file.replaceAll("~", "/")} > ${name}`,
          input: JSON.parse(text),
          answer: websiteCaseAnswer(read),
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
