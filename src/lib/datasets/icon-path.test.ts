import { describe, expect, it } from "vitest";
import { servableIconPath } from "./icon-path";

describe("servableIconPath", () => {
  it("drops an asset note and keeps every loadable path", () => {
    expect(servableIconPath("asset:gregtech:textures/aspects/AEQUALITAS.png")).toBeUndefined();
    expect(servableIconPath(undefined)).toBeUndefined();
    expect(servableIconPath("/nei/thaumcraft/aspects/aer.png")).toBe("/nei/thaumcraft/aspects/aer.png");
    expect(servableIconPath("/datasets/gtnh/v/textures/aspect/a.png")).toBe("/datasets/gtnh/v/textures/aspect/a.png");
  });
});
