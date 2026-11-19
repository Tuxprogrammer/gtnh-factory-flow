/**
 * The solver's input and answer as the conformance tree holds them, read through the Zod schemas that
 * conformance/schema/solve-input.schema.json and solve-digest.schema.json are generated from
 * (src/lib/solver/plan/schemas.ts). The mod's SolverSchemaTest checks the same files against the JSON Schema.
 */
import { describe, expect, it } from "vitest";

import { solveDigestSchema, solveInputSchema } from "@/lib/solver/plan/schemas";

import { listConformance, readConformanceJson } from "./conformance";

describe("solver corpus inputs are SolveInputs", () => {
  const files = listConformance("solver", "corpus", "inputs");
  it("has the corpus", () => expect(files.length).toBeGreaterThan(20));
  for (const f of files) {
    it(f, () => {
      const r = solveInputSchema.safeParse(readConformanceJson("solver", "corpus", "inputs", f));
      expect(r.success ? "ok" : JSON.stringify(r.error.issues.slice(0, 3))).toBe("ok");
    });
  }
});

describe("Up to plans carry a SolveInput and the digest of the answer the website gave it", () => {
  const files = listConformance("solver", "up-to");
  it("has the plans", () => expect(files.length).toBeGreaterThan(20));
  for (const f of files) {
    it(f, () => {
      const plan = readConformanceJson("solver", "up-to", f) as { input: unknown; answer: unknown };
      const input = solveInputSchema.safeParse(plan.input);
      expect(input.success ? "ok" : JSON.stringify(input.error.issues.slice(0, 3))).toBe("ok");
      const answer = solveDigestSchema.safeParse(plan.answer);
      expect(answer.success ? "ok" : JSON.stringify(answer.error.issues.slice(0, 3))).toBe("ok");
    });
  }
});

describe("goldens are digests", () => {
  const files = listConformance("solver", "corpus", "goldens");
  it("has the goldens", () => expect(files.length).toBeGreaterThan(20));
  for (const f of files) {
    it(f, () => {
      const r = solveDigestSchema.safeParse(readConformanceJson("solver", "corpus", "goldens", f));
      expect(r.success ? "ok" : JSON.stringify(r.error.issues.slice(0, 3))).toBe("ok");
    });
  }
});

describe("the board scenarios' inputs are SolveInputs", () => {
  const all = readConformanceJson("board", "mod-answers.json") as Record<string, { input: unknown }>;
  for (const [name, one] of Object.entries(all)) {
    it(name, () => {
      const r = solveInputSchema.safeParse(one.input);
      expect(r.success ? "ok" : JSON.stringify(r.error.issues.slice(0, 3))).toBe("ok");
    });
  }
});
