import { createHash } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { getPowerSource, POWER_SOURCES } from "@/lib/power/registry";
import { buildPowerSettingsReader } from "@/lib/power/types";
import { CORE_EXPORT_NAMES, CORE_MODULES } from "./exports.generated";
import {
  CoreNotLoadedError,
  CoreUnavailableError,
  core,
  coreIfLoaded,
  coreInfo,
  coreState,
  loadCore,
  resetCoreForTests,
  wasmGcSupported,
  type CoreTarget,
} from "./loader";

/**
 * The compiled core (jvm/web) through the loader, on both targets. The core is built by tools/copy-core.mjs; a checkout
 * with only Node has none, and the tests that need it skip. CI sets GTNH_CORE_REQUIRED=1, which turns a missing core
 * into a failure instead of a skip.
 */
const present = existsSync(path.join(process.cwd(), "public", "core", "manifest.json"));
const required = process.env.GTNH_CORE_REQUIRED === "1";

describe("core loader without a core", () => {
  it("is built when CI requires it", () => {
    if (required) expect(present).toBe(true);
  });

  it("says unavailable, and core() throws until something loaded", async () => {
    resetCoreForTests();
    const empty = mkdtempSync(path.join(os.tmpdir(), "no-core-"));
    try {
      await expect(loadCore({ dir: empty })).rejects.toBeInstanceOf(CoreUnavailableError);
      expect(coreState()).toBe("unavailable");
      expect(coreIfLoaded()).toBeNull();
      expect(() => core()).toThrow(CoreNotLoadedError);
    } finally {
      rmSync(empty, { recursive: true, force: true });
      resetCoreForTests();
    }
  });
});

describe("core loader and the manifest", () => {
  it("refuses a manifest of another format and names the fix", async () => {
    resetCoreForTests();
    const dir = mkdtempSync(path.join(os.tmpdir(), "old-core-"));
    try {
      writeFileSync(
        path.join(dir, "manifest.json"),
        JSON.stringify({ format: 1, version: "x", files: [] }),
      );
      await expect(loadCore({ dir })).rejects.toThrow(/rebuild/);
      expect(coreState()).toBe("unavailable");
    } finally {
      rmSync(dir, { recursive: true, force: true });
      resetCoreForTests();
    }
  });

  it.skipIf(!present)("names every file by its content hash, and every module has both targets", () => {
    const manifest = JSON.parse(
      readFileSync(path.join(process.cwd(), "public", "core", "manifest.json"), "utf8"),
    ) as {
      format: number;
      runtime: string;
      modules: Record<string, { wasm: string; js: string }>;
      files: { path: string }[];
    };
    expect(manifest.format).toBe(2);
    const listed = new Set(manifest.files.map((f) => f.path));
    for (const f of [
      manifest.runtime,
      ...Object.values(manifest.modules).flatMap((m) => [m.wasm, m.js]),
    ]) {
      expect(f).toMatch(/\.[0-9a-f]{8}\./);
      expect(listed.has(f), f).toBe(true);
      expect(existsSync(path.join(process.cwd(), "public", "core", f)), f).toBe(true);
    }
    expect(Object.keys(manifest.modules)).toEqual(Object.keys(CORE_MODULES));
  });
});

/** JSON the core wrote against what the website's own TypeScript made: numbers to 1e-9 relative, "Infinity" strings read back. */
function same(want: unknown, got: unknown, where: string): string | null {
  const num = (v: unknown) =>
    v === "Infinity" ? Infinity : v === "-Infinity" ? -Infinity : v === "NaN" ? NaN : v;
  const w = num(want);
  const g = num(got);
  if (typeof w === "number" && typeof g === "number") {
    if (Object.is(w, g) || w === g || (Number.isNaN(w) && Number.isNaN(g))) return null;
    return Math.abs(w - g) <= 1e-9 * Math.max(Math.abs(w), Math.abs(g))
      ? null
      : `${where}: ${w} vs ${g}`;
  }
  if (Array.isArray(w) && Array.isArray(g)) {
    if (w.length !== g.length) return `${where}: ${w.length} items vs ${g.length}`;
    for (let i = 0; i < w.length; i++) {
      const d = same(w[i], g[i], `${where}[${i}]`);
      if (d) return d;
    }
    return null;
  }
  if (w && g && typeof w === "object" && typeof g === "object") {
    const keys = new Set([...Object.keys(w), ...Object.keys(g)]);
    for (const k of keys) {
      const d = same(
        (w as Record<string, unknown>)[k],
        (g as Record<string, unknown>)[k],
        `${where}.${k}`,
      );
      if (d) return d;
    }
    return null;
  }
  return w === g ? null : `${where}: ${JSON.stringify(w)} vs ${JSON.stringify(g)}`;
}

function websiteModel(sourceId: string, settings: Record<string, string>) {
  const source = getPowerSource(sourceId)!;
  const r = source.compute(buildPowerSettingsReader(source, settings));
  const flow = (l: (typeof r.inputs)[number]) => ({
    name: l.name,
    perSecond: l.perSecond,
    unit: l.unit,
    alternatives: l.alternatives ?? [],
  });
  return {
    euPerTick: r.euPerTick,
    inputs: r.inputs.map(flow),
    outputs: r.outputs.map(flow),
    stats: r.stats.map((s) => ({ label: s.label, value: s.value })),
    warnings: r.warnings ?? [],
  };
}

const targets: CoreTarget[] = wasmGcSupported() ? ["wasm-gc", "js"] : ["js"];

const suite = describe.skipIf(!present);
for (const target of targets) {
  suite(`core on the ${target} target`, () => {
    beforeAll(async () => {
      resetCoreForTests();
      await loadCore({ target });
    });
    afterAll(() => resetCoreForTests());

    it("loads once, reports itself, and is synchronous afterwards", async () => {
      const info = coreInfo()!;
      expect(info.target).toBe(target);
      expect(info.version).toMatch(/^[0-9a-f]{16}$/);
      expect(info.loadMs).toBeGreaterThan(0);
      expect(coreState()).toBe("ready");
      expect(await loadCore({ target })).toBe(core());
    });

    it("spells numbers as JavaScript does (core-base NumberText)", () => {
      const n = core().BaseExports;
      expect(n.numberTextOf(0.1)).toBe("0.1");
      expect(n.numberTextOf(1e21)).toBe("1e+21");
      expect(n.numberTextOf(123456789012345680000)).toBe("123456789012345680000");
      expect(n.numberTextOf(5e-324)).toBe("5e-324");
      expect(n.numberTextOf(-1.5e-7)).toBe("-1.5e-7");
      expect(n.numberTextFixed(1.005, 2)).toBe((1.005).toFixed(2));
      expect(n.numberTextFixed(1234.5678, 1)).toBe((1234.5678).toFixed(1));
      expect(n.numberTextParse("1e3")).toBe(1000);
      expect(n.numberTextParse("  -0.25 ")).toBe(Number("  -0.25 "));
      expect(n.jsMathRound(-2.5)).toBe(Math.round(-2.5));
      expect(n.float32Round(0.1)).toBe(Math.fround(0.1));
      expect(n.float32Round(16777217)).toBe(Math.fround(16777217));
    });

    it("passes text through the boundary unchanged, in both directions", () => {
      const text = `plain, ${"\u00e9\u4e2d\ud83d\ude00"} and a lone surrogate \ud800, ${"x".repeat(100_000)}`;
      expect(core().webCoreEcho(text)).toBe(text);
      expect(core().webCoreEcho("")).toBe("");
      expect(core().webCoreLength(text)).toBe(text.length);
      expect(core().webCoreRepeat("ab\u00e9", 3)).toBe("ab\u00e9ab\u00e9ab\u00e9");
      expect(core().webCoreRepeat("x", 0)).toBe("");
    });

    it("makes ids as java.util.UUID does (core-base Uuid)", () => {
      const b = core().BaseExports;
      const msb = "0123456789abcdef";
      const lsb = "fedcba9876543210";
      expect(b.uuidToString(msb, lsb)).toBe("01234567-89ab-cdef-fedc-ba9876543210");
      const hilo = BigInt.asIntN(64, BigInt(`0x${msb}`) ^ BigInt(`0x${lsb}`));
      expect(b.uuidHash(msb, lsb)).toBe(
        Number(BigInt.asIntN(32, hilo >> 32n)) ^ Number(BigInt.asIntN(32, hilo)),
      );
      expect(b.uuidCompare(msb, lsb, msb, lsb)).toBe(0);
      expect(b.uuidCompare("0000000000000001", lsb, msb, lsb)).toBe(-1);
      // nameUUIDFromBytes: MD5 of the UTF-8 text with the version (3) and variant bits set.
      const md5 = createHash("md5").update("minecraft:iron_ingot", "utf8").digest();
      md5[6] = (md5[6] & 0x0f) | 0x30;
      md5[8] = (md5[8] & 0x3f) | 0x80;
      const hex = md5.toString("hex");
      expect(b.uuidName("minecraft:iron_ingot")).toBe(
        `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`,
      );
    });

    it("gives every power source the numbers the website's TypeScript gives, at its defaults", () => {
      const power = core().PowerExports;
      const ids = JSON.parse(power.powerSourceIds()) as string[];
      expect(ids.sort()).toEqual(POWER_SOURCES.map((s) => s.id).sort());
      const wrong: string[] = [];
      for (const id of ids) {
        const got = JSON.parse(power.powerCompute(id, "{}")!);
        const d = same(websiteModel(id, {}), { ...got, cardTier: undefined }, id);
        if (d) wrong.push(d);
      }
      expect(wrong).toEqual([]);
    });

    it("gives a turbine at a custom flow the website's numbers (the float steps)", () => {
      const power = core().PowerExports;
      for (const settings of [
        { flowMode: "custom", customFlow: "123" },
        { flowMode: "custom", customFlow: "4567" },
      ]) {
        const got = JSON.parse(
          power.powerCompute("large-steam-turbine", JSON.stringify(settings))!,
        );
        expect(
          same(
            websiteModel("large-steam-turbine", settings),
            { ...got, cardTier: undefined },
            "large-steam-turbine",
          ),
        ).toBeNull();
      }
      expect(power.powerCompute("no-such-source", "{}")).toBeNull();
    });

    it("exports exactly the functions the Java sources declare, module by module", async () => {
      const modules = CORE_MODULES as Record<
        string,
        { namespaces: readonly string[]; top: readonly string[] }
      >;
      if (Object.keys(modules).length === 0) return; // no jvm tree in this checkout: nothing declared
      const exp = core() as unknown as Record<string, unknown>;
      // What is on the object: data properties only, so a namespace of a module not loaded (a throwing getter) is not read.
      const present = () => {
        const out: Record<string, unknown> = {};
        for (const k of Object.getOwnPropertyNames(exp)) {
          const d = Object.getOwnPropertyDescriptor(exp, k);
          if (d && "value" in d) out[k] = d.value;
        }
        return out;
      };
      const check = (loaded: string[]) => {
        const have = present();
        const topWanted = new Set<string>();
        for (const name of loaded) {
          for (const ns of modules[name].namespaces) {
            const actual = Object.keys(have[ns] as object)
              .filter((k) => typeof (have[ns] as Record<string, unknown>)[k] === "function")
              .sort();
            const declared = (CORE_EXPORT_NAMES.namespaces as Record<string, readonly string[]>)[ns];
            expect(actual, ns).toEqual([...declared].sort());
          }
          for (const t of modules[name].top) topWanted.add(t);
        }
        const namespaces = Object.values(modules).flatMap((m) => m.namespaces);
        const top = Object.keys(have)
          .filter((k) => typeof have[k] === "function" && !namespaces.includes(k) && k !== "main")
          .sort();
        expect(top).toEqual([...topWanted].sort());
        // A module that is not loaded names itself in the error instead of answering undefined.
        for (const name of Object.keys(modules).filter((n) => !loaded.includes(n)))
          for (const ns of modules[name].namespaces) {
            expect(() => exp[ns], ns).toThrow(CoreNotLoadedError);
            expect(() => exp[ns], ns).toThrow(new RegExp(name));
          }
        expect(Object.keys(have).filter((k) => namespaces.includes(k)).length).toBe(
          loaded.reduce((n, name) => n + modules[name].namespaces.length, 0),
        );
      };
      check(["core"]);
      // The solver module loads on its own call and joins the same object; the core's functions stay.
      const before = core();
      await loadCore({ target, modules: ["solver"] });
      expect(core()).toBe(before);
      expect(coreInfo()!.modules.solver?.target).toBe(target);
      check(Object.keys(modules));
    });
  });
}
