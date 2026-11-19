import { describe, expect, it } from "vitest";
import { readViewCases } from "@/test-support/view-corpus";
import { drawerCaseNamed, drawerCases, type DrawerCase } from "./drawer-conformance";

/**
 * conformance/view/drawer holds what the drawer shows for plans, as the record the core takes and the view it makes. The
 * website's own `buildDrawerView` must give each stored view for its drawer (the core view module checks the same file
 * from Java, with the same six-pixel measure). That the file is what the code makes now is `view-corpus.test.ts`'s check.
 */
const file = readViewCases<DrawerCase["input"], DrawerCase["view"]>("drawer", "cases");

describe("the drawer's cases", () => {
  it("names the fixed measure", () => {
    expect(file.measure).toBe("six-px");
  });

  it.each(file.cases.map((c) => [c.name, c] as const))("%s: the website gives the stored input and view", (_n, c) => {
    const made = drawerCaseNamed(c.name);
    expect(JSON.parse(JSON.stringify(made.input))).toEqual(c.input);
    expect(JSON.parse(JSON.stringify(made.view))).toEqual(c.view);
  });

  it("holds every case the module makes, in order", () => {
    expect(file.cases.map((c) => c.name)).toEqual(drawerCases().map((c) => c.name));
  });

  it("covers every kind, every rule, a spill and a power drawer", () => {
    const seen = (pick: (c: DrawerCase) => string) => new Set(file.cases.map(pick));
    expect(seen((c) => c.input.kind)).toEqual(new Set(["source", "product", "trash", "buffer"]));
    expect(seen((c) => c.view.rule)).toEqual(new Set(["ignore", "at-least", "exact", "at-most"]));
    expect(file.cases.some((c) => c.view.spill)).toBe(true);
    expect(file.cases.some((c) => c.input.resource === "power")).toBe(true);
  });

  it("has a buffer with a rule and a buffer with a spill, and no null", () => {
    expect(file.cases.some((c) => c.input.kind === "buffer" && c.view.hasRule && c.view.rule !== "ignore")).toBe(true);
    expect(JSON.stringify(file.cases)).not.toContain("null");
  });
});
