import { describe, expect, it } from "vitest";
import { readViewCases } from "@/test-support/view-corpus";
import { buildScene } from "./minimap-scene";
import { minimapCases, sceneInputOf, viewOf, type MinimapCase } from "./minimap-conformance";

/**
 * conformance/view/minimap holds the boxes and routes of a board and the scene made of them. The website's
 * `buildScene` must give each stored scene (the core's `MinimapBuilder` checks the same file from Java; the mod's map
 * draws from it). That the file is what the code makes now is `view-corpus.test.ts`'s check.
 */
const file = readViewCases<MinimapCase["input"], MinimapCase["view"]>("minimap", "cases");

describe("the minimap scene's cases", () => {
  it("takes no measure", () => {
    expect(file.measure).toBe("none");
  });

  it.each(file.cases.map((c) => [c.name, c] as const))("%s: the website gives the stored scene", (_n, c) => {
    expect(JSON.parse(JSON.stringify(viewOf(buildScene(sceneInputOf(c.input)))))).toEqual(c.view);
  });

  it("holds every case the module makes, in order", () => {
    expect(file.cases.map((c) => c.name)).toEqual(minimapCases().map((c) => c.name));
  });

  it("covers every kind of tile and a wire of each look", () => {
    const kinds = new Set(file.cases.flatMap((c) => c.view.tiles.map((t) => t.kind)));
    expect([...kinds].sort()).toEqual(["board", "card", "drawer", "group", "note"]);
    expect(file.cases.some((c) => c.view.wires.some((w) => !w.flowing))).toBe(true);
    expect(file.cases.some((c) => c.view.bounds === undefined)).toBe(true);
  });
});
