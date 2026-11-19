import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { conformancePath, listConformance, readConformanceText } from "@/test-support/conformance";
import { readViewCases, readViewVectors } from "@/test-support/view-corpus";
import { parseCountText, wheelCount, wheelDraft } from "./clean-model";
import {
  cardViewJson,
  countVectors,
  viewFiles,
  websiteCardView,
  type CardCaseInput,
} from "./card-conformance";

/**
 * conformance/view/card/ holds what a recipe card shows for a card's facts, as the core's input records and views. The
 * website's own builders must give each stored view from its stored input (the core's CardViews checks the same files
 * from Java), and the files must be what the code makes now. Regenerate with
 * `GTNH_WRITE_VIEW=1 npx vitest run src/components/flow/clean/card-conformance.test.ts`, then
 * `node tools/conformance/manifest.mjs --write`.
 */
const made = viewFiles();

if (process.env.GTNH_WRITE_VIEW === "1") {
  for (const [name, text] of Object.entries(made)) {
    const file = path.join(conformancePath("view"), name);
    mkdirSync(path.dirname(file), { recursive: true });
    writeFileSync(file, text);
  }
}

describe("the card's conformance files", () => {
  it("are what the code makes now", () => {
    expect(listConformance("view", "card")).toEqual(
      Object.keys(made)
        .map((name) => name.slice("card/".length))
        .sort(),
    );
    for (const [name, text] of Object.entries(made)) {
      expect(readConformanceText("view", name) === text, `${name} differs: regenerate it`).toBe(true);
    }
  });
});

describe("the website's card builders over the shared cases", () => {
  const { measure, cases } = readViewCases<CardCaseInput, unknown>("card", "cases");
  it("are measured six px a character", () => expect(measure).toBe("six-px"));
  it("has the cases the builder tests cover", () => expect(cases.length).toBeGreaterThanOrEqual(99));
  it.each(cases.map((c) => [c.name, c] as const))("%s", (_name, c) => {
    expect(cardViewJson(websiteCardView(c.input), c.input)).toEqual(c.view);
  });
});

describe("the count's arithmetic", () => {
  const { vectors } = readViewVectors<ReturnType<typeof countVectors>>("card", "count");
  const rule = (r: string) => (r === "ANY" ? "any" : r === "UP_TO" ? "upTo" : "exact") as never;
  it("steps the wheel", () => {
    for (const v of vectors.wheel) {
      expect(wheelCount({ rule: rule(v.rule), set: v.set, value: v.value }, v.direction)).toBe(v.out);
    }
  });
  it("reads a typed count", () => {
    for (const v of vectors.parse) expect(parseCountText(v.text)).toBe(v.out);
  });
  it("steps an open box", () => {
    for (const v of vectors.draft) expect(wheelDraft(v.draft, { value: v.value }, v.direction)).toBe(v.out);
  });
});
