import { describe, expect, it } from "vitest";
import { readViewCases } from "@/test-support/view-corpus";
import type { FactoryNodeColorTag } from "@/lib/model/types";
import { notePalette } from "./note-model";
import { noteLookCases, type NoteLookCase } from "./note-conformance";

/**
 * conformance/view/note holds the sticky note's colours per colour tag. The website's `notePalette` must give each
 * stored palette (the core view module checks the same file from Java, the mod's note card draws from it). That the file
 * is what the code makes now is `view-corpus.test.ts`'s check.
 */
const file = readViewCases<NoteLookCase["input"], NoteLookCase["view"]>("note", "look");

describe("the sticky note's colour cases", () => {
  it("takes no measure", () => {
    expect(file.measure).toBe("none");
  });

  it.each(file.cases.map((c) => [c.name, c] as const))("%s: the website gives the stored palette", (_n, c) => {
    expect(notePalette(c.input.tag as FactoryNodeColorTag | undefined)).toEqual(c.view);
  });

  it("holds every case the module makes, in order", () => {
    expect(file.cases.map((c) => c.name)).toEqual(noteLookCases().map((c) => c.name));
  });

  it("reads no tag and an unknown tag as yellow, and has dark paper that takes light ink", () => {
    const named = (name: string) => file.cases.find((c) => c.name === name)!.view;
    expect(named("no tag")).toEqual(named("yellow"));
    expect(named("no-such-colour")).toEqual(named("yellow"));
    expect(file.cases.some((c) => c.view.ink === "#F2F3F7")).toBe(true);
    expect(file.cases.some((c) => c.view.ink === "#1E1E22")).toBe(true);
  });
});
