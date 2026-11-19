/**
 * The website solver cases (`conformance/solver/website/cases.json.gz`, tools/conformance/solver/generate.mjs): every
 * distinct solve input the website's tests and the reference plans hand the plan solver, with the answer the website
 * gave, which the Java kernel must give too (core-solver `WebsiteCasesTest`). One answer form for the writer and both
 * readers. Node-only; never import this from app code.
 */
import { readFileSync } from "node:fs";
import { gunzipSync } from "node:zlib";

import { conformancePath } from "@/test-support/conformance";

import { solvePlan } from "../solve-plan";
import type { Answer, SolveInput } from "../types";
import { digest } from "./digest";

export interface WebsiteCase {
  name: string;
  /** The mod's dump format, as `load.ts` `parseSolveInput` reads it. */
  input: unknown;
  /** The golden digest of a solved answer, or `{failed: message}`. */
  answer: unknown;
}

/** A solved answer's digest; a failed one as its failure's message, as the Up to export writes it. */
export function answerDigest(answer: Answer): unknown {
  if (answer.kind === "failed") return { failed: answer.failure.message };
  return digest(answer);
}

/** The website's answer to a case: the public entry, no cache. */
export function websiteCaseAnswer(input: SolveInput): unknown {
  return answerDigest(solvePlan(input));
}

export function loadWebsiteCases(): WebsiteCase[] {
  const bytes = readFileSync(conformancePath("solver", "website", "cases.json.gz"));
  return (JSON.parse(gunzipSync(bytes).toString("utf8")) as { cases: WebsiteCase[] }).cases;
}
