/**
 * The Up to parity export (docs/up-to.md): every hand-built Up to plan of `upto.test.ts` and every corpus plan with
 * each exact count turned Up to, written with the compiled core's answer (the website's engine) for the mod's
 * `UpToParityTest`, which solves each input with the Java kernel and compares the two answers by the golden rules (`compare.ts`, the mod's
 * `SolverCorpus.differences`). Skipped unless `UPTO_EXPORT_DIR` names where to write (`conformance/solver/up-to/`,
 * which both suites read; run `node tools/conformance/manifest.mjs --write` after it so the manifest holds the new hashes):
 *
 *     UPTO_EXPORT_DIR=$PWD/conformance/solver/up-to npx vitest run up-to-export
 *
 * Inputs are in the mod's dump format (`SolveInputJson`, what `__fixtures__/load.ts` reads); a hand-built plan's ids
 * become UUIDs first (`upto:<id>` hashed as Java's `UUID.nameUUIDFromBytes`), so both engines solve the very same input.
 * Answers are the golden digest (`__fixtures__/digest.ts`), with `upTo` and `drawers.banked`.
 */
import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";

import { beforeAll, describe, expect, it } from "vitest";

import { coreCaseAnswer, coreSolve, corePresent, useCoreSolver } from "@/test-support/core-solve";
import { toDump, withUuids } from "../__fixtures__/dump";
import { caseNames, loadInput } from "../__fixtures__/load";
import {
  budgetPlan,
  chain,
  CORPUS_UP_TO,
  everyCountUpTo,
  FIXTURES,
  pinDiagnosis,
  withAlternatives,
} from "../__fixtures__/up-to/plans";
import type { MachineIn, SolveInput } from "../types";

const OUT = process.env.UPTO_EXPORT_DIR;

const lone = (id: string): MachineIn => ({
  id,
  name: id,
  durationTicks: 20,
  energyPerTick: 0,
  throughputFactor: 1,
  inputs: [{ amount: 1, chance: 1, multiplier: 1, resource: 1, name: "ore", free: false }],
  outputs: [{ amount: 1, chance: 1, multiplier: 1, resource: 2, name: "gear", free: false }],
  countFixed: false,
  machineCount: 1,
  targetOutputRates: {},
  targetInputRates: {},
});

const idleNoise = (): SolveInput =>
  JSON.parse(
    readFileSync(path.join(__dirname, "..", "__fixtures__", "up-to", "idle-noise.json"), "utf8"),
  ) as SolveInput;

/** Every case: its name and its input, ids already UUIDs. */
function cases(): Array<[string, () => SolveInput]> {
  const out: Array<[string, () => SolveInput]> = [];
  for (const [name, make] of Object.entries(FIXTURES)) out.push([name, () => withUuids(make())]);
  out.push(["alternatives, chosen", chosen]);
  out.push(["no Up to", () => withUuids(exactly(chain(2, 1)))]);
  out.push(["pin diagnosis", () => withUuids(pinDiagnosis())]);
  out.push(["read-out budget", () => withUuids(budgetPlan())]);
  out.push(["idle noise", () => withUuids(idleNoise())]);
  out.push([
    "idle noise beside a lone card",
    () => {
      const plan = idleNoise();
      return withUuids({
        ...plan,
        machines: [...plan.machines, lone("zz-lone")],
        pools: [...plan.pools, { machines: ["zz-lone"], capacity: 2, exact: false, upTo: true }],
      });
    },
  ]);
  const corpus = new Set<string>(CORPUS_UP_TO);
  for (const name of caseNames()) {
    if (name === "platline-x10" && !corpus.has(name) && !process.env.CORPUS_BENCH) continue;
    out.push([`${name}, every count Up to`, () => everyCountUpTo(loadInput(name))]);
  }
  return out;
}

/** The pools of a plan made Exactly: no Up to pool left. */
function exactly(i: SolveInput): SolveInput {
  return { ...i, pools: i.pools.map((p) => ({ ...p, exact: true, upTo: false })) };
}

/** The alternatives plan with its first other place stored as the choice. */
function chosen(): SolveInput {
  const base = withUuids(withAlternatives());
  const first = coreSolve(base);
  if (first.kind !== "solved") throw new Error("the alternatives plan failed");
  const other = first.alternatives.options.find((o) => o.rank !== "DEFAULT");
  if (!other) throw new Error("the alternatives plan offers no other place");
  return { ...base, choice: other.key.gateAnchors };
}

/** A file name for a case. */
function slug(name: string): string {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "");
}

if (!OUT) {
  describe.skip("Up to parity export (set UPTO_EXPORT_DIR to write it)", () => {
    it("writes every Up to plan and its answer", () => {});
  });
} else {
  describe("Up to parity export", () => {
    beforeAll(async () => {
      if (!corePresent) throw new Error("the answers are the compiled core's: build it first (tools/copy-core.mjs)");
      await useCoreSolver();
      mkdirSync(OUT, { recursive: true });
      for (const f of readdirSync(OUT)) if (f.endsWith(".json")) rmSync(path.join(OUT, f));
    }, 120_000);

    it.each(cases())(
      "%s",
      (name, make) => {
        const input = make();
        const answer = coreCaseAnswer(input);
        const file = path.join(OUT, `${slug(name)}.json`);
        writeFileSync(file, `${JSON.stringify({ name, input: toDump(input), answer }, null, 1)}\n`);
        expect(answer).not.toHaveProperty("failed");
      },
      120_000,
    );
  });
}
