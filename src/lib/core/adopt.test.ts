import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ADOPT, clearAdoptOverrides, coreFor, isAdopted, setAdoptOverride, withAdopt } from "./adopt";
import { coreState, loadCore, resetCoreForTests } from "./loader";
import { existsSync } from "node:fs";

const present = existsSync(path.join(process.cwd(), "public", "core", "manifest.json"));

afterEach(() => {
  clearAdoptOverrides();
  resetCoreForTests();
});

describe("core adoption flags", () => {
  it("has every area on its committed value until something overrides it", () => {
    for (const [area, flag] of Object.entries(ADOPT))
      expect(isAdopted(area as keyof typeof ADOPT)).toBe(flag);
  });

  it("an override wins, and undefined puts the committed flag back", () => {
    setAdoptOverride("solve", true);
    expect(isAdopted("solve")).toBe(true);
    setAdoptOverride("solve", false);
    expect(isAdopted("solve")).toBe(false);
    setAdoptOverride("solve", undefined);
    expect(isAdopted("solve")).toBe(ADOPT.solve);
  });

  it("withAdopt restores the overrides after a return, a throw and a rejection", async () => {
    setAdoptOverride("power", false);
    expect(withAdopt({ power: true, layout: true }, () => [isAdopted("power"), isAdopted("layout")])).toEqual([
      true,
      true,
    ]);
    expect(isAdopted("power")).toBe(false);
    expect(isAdopted("layout")).toBe(ADOPT.layout);
    expect(() =>
      withAdopt({ power: true }, () => {
        throw new Error("x");
      }),
    ).toThrow("x");
    expect(isAdopted("power")).toBe(false);
    await expect(withAdopt({ power: true }, () => Promise.reject(new Error("y")))).rejects.toThrow("y");
    expect(isAdopted("power")).toBe(false);
  });

  it("is never persisted: an override touches no storage", () => {
    const calls: string[] = [];
    const spy = new Proxy({}, { get: (_t, k) => (calls.push(String(k)), () => null) });
    vi.stubGlobal("localStorage", spy);
    vi.stubGlobal("sessionStorage", spy);
    try {
      setAdoptOverride("machines", true);
      isAdopted("machines");
      withAdopt({ power: true }, () => isAdopted("power"));
      clearAdoptOverrides();
    } finally {
      vi.unstubAllGlobals();
    }
    expect(calls).toEqual([]);
  });

  it("gives no core before the load, when the area is off, or when the load failed", async () => {
    resetCoreForTests();
    expect(coreFor("machines")).toBeNull();
    expect(coreState()).toBe("idle");
    await expect(loadCore({ dir: path.join(process.cwd(), "no-such-core-dir") })).rejects.toThrow();
    setAdoptOverride("machines", true);
    expect(coreFor("machines")).toBeNull();
  });

  it.skipIf(!present)("the first call for an area that is on starts the load, and a later one gets the core", async () => {
    resetCoreForTests();
    setAdoptOverride("machines", true);
    expect(coreFor("machines")).toBeNull();
    expect(coreState()).toBe("loading");
    await loadCore();
    expect(coreFor("machines")).not.toBeNull();
  });

  it.skipIf(!present)("gives the loaded core only to an area that is on", async () => {
    await loadCore();
    setAdoptOverride("machines", false);
    expect(coreFor("machines")).toBeNull();
    setAdoptOverride("machines", true);
    expect(coreFor("machines")).not.toBeNull();
    expect(typeof coreFor("machines")?.BaseExports.numberTextOf).toBe("function");
  });
});
