import { describe, expect, it } from "vitest";
import { readViewCases } from "@/test-support/view-corpus";
import { groupCases, groupMembersOf, type GroupCase } from "./group-conformance";
import { sumGroup } from "./group-sum";

/**
 * conformance/view/group holds what a collapsed group's summary says for its members. The website's own `sumGroup` must
 * give each stored view for its members (the core view module checks the same file from Java). That the file is what
 * the code makes now is `view-corpus.test.ts`'s check.
 */
const file = readViewCases<GroupCase["input"], GroupCase["view"]>("group", "cases");

describe("the group summary's cases", () => {
  it("takes no measure", () => {
    expect(file.measure).toBe("none");
  });

  it.each(file.cases.map((c) => [c.name, c] as const))("%s: the website sums to the stored view", (_n, c) => {
    expect(JSON.parse(JSON.stringify(sumGroup(groupMembersOf(c.input))))).toEqual(c.view);
  });

  it("holds every case the module makes, in order", () => {
    expect(file.cases.map((c) => c.name)).toEqual(groupCases().map((c) => c.name));
  });

  it("covers the empty group, a shared host, a balance and a longer outputs list", () => {
    expect(file.cases.some((c) => c.input.members.length === 0)).toBe(true);
    expect(file.cases.some((c) => c.input.members.filter((m) => m.host === "host").length > 1)).toBe(true);
    expect(file.cases.some((c) => c.view.outputs.length > c.view.inputs.length)).toBe(true);
    expect(JSON.stringify(file.cases)).not.toContain("null");
  });
});
