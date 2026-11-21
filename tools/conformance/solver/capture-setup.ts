/**
 * Records every solve input the website's tests hand the plan solver, for the solver's two-engine comparison
 * (conformance/solver/website, tools/conformance/solver/generate.mjs). Loaded as a vitest setup file by
 * capture.config.ts only. Every way a test reaches a plan solver is wrapped: the website's three entries (`solvePlan`,
 * `solveCore`, `solvePiece`), the engine the plan solve's seam hands out (`getSolveCore`, the compiled core where it is
 * loaded) and the tests' own door to the core (`coreSolve`); a call made from inside another (an island of a plan) is
 * the outer call's business and is not written. Each test file's inputs,
 * with the test that asked, go to $SOLVE_CAPTURE_OUT/<test file path with / as ~>.json. Answers are not kept here:
 * the export solves each input again through the public entry, as the Java kernel will.
 */
import { afterAll, beforeEach, expect, vi } from "vitest";
import { mkdirSync, writeFileSync } from "node:fs";
import { join, relative } from "node:path";

const OUT = process.env.SOLVE_CAPTURE_OUT;
const ROOT = join(__dirname, "../../..");

const seen = new Set<string>();
const inputs: Array<{ name: string; input: unknown; nonFinite?: true }> = [];
let depth = 0;
let count = 0;

const testName = () => expect.getState().currentTestName ?? "(outside a test)";

/** True when the value holds a number JSON cannot spell (it would come back as null). */
function hasNonFinite(value: unknown): boolean {
  if (typeof value === "number") return !Number.isFinite(value);
  if (Array.isArray(value)) return value.some(hasNonFinite);
  if (value !== null && typeof value === "object")
    return Object.values(value as Record<string, unknown>).some(hasNonFinite);
  return false;
}

function record(input: unknown): void {
  if (depth > 0) return;
  const text = JSON.stringify(input);
  if (seen.has(text)) return;
  seen.add(text);
  count += 1;
  inputs.push({
    name: `${testName()} #${count}`,
    input: JSON.parse(text),
    ...(hasNonFinite(input) ? { nonFinite: true as const } : {}),
  });
}

function wrap<A extends unknown[], R>(fn: (...args: A) => R): (...args: A) => R {
  return (...args: A) => {
    record(args[0]);
    depth += 1;
    try {
      return fn(...args);
    } finally {
      depth -= 1;
    }
  };
}

// Ids a test makes at random (crypto.randomUUID: new cards, pasted plans) are made in turn instead, so every capture
// run records the same inputs.
let made = 0;
beforeEach(() => {
  made = 0;
  vi.spyOn(globalThis.crypto, "randomUUID").mockImplementation(() => {
    made += 1;
    return `10000000-0000-4000-8000-${made.toString(16).padStart(12, "0")}` as ReturnType<
      typeof crypto.randomUUID
    >;
  });
});

vi.mock("@/lib/solver/plan/solve-plan", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/solver/plan/solve-plan")>();
  return { ...original, solvePlan: wrap(original.solvePlan) };
});

vi.mock("@/lib/solver/plan/adapter/engine", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/solver/plan/adapter/engine")>();
  return { ...original, getSolveCore: () => wrap(original.getSolveCore()) };
});

vi.mock("@/test-support/core-solve", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/test-support/core-solve")>();
  return { ...original, coreSolve: wrap(original.coreSolve) };
});

vi.mock("@/lib/solver/plan/solve-core", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/solver/plan/solve-core")>();
  return {
    ...original,
    solveCore: wrap(original.solveCore),
    solvePiece: wrap(original.solvePiece),
  };
});

afterAll(() => {
  if (!OUT || inputs.length === 0) return;
  const file = relative(ROOT, expect.getState().testPath ?? "unknown").replaceAll("/", "~");
  mkdirSync(OUT, { recursive: true });
  writeFileSync(join(OUT, `${file}.json`), JSON.stringify({ file, inputs }));
});
