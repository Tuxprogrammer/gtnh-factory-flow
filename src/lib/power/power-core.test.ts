import { existsSync } from "node:fs";
import path from "node:path";
import { gunzipSync } from "node:zlib";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { clearAdoptOverrides, isAdopted, withAdopt } from "@/lib/core/adopt";
import {
  core,
  loadCore,
  resetCoreForTests,
  wasmGcSupported,
  type CoreTarget,
} from "@/lib/core/loader";
import { readConformanceBytes } from "@/test-support/conformance";
import "@/lib/core/power-adopt";
import { corePowerCard, corePowerCardTier } from "./power-core";
import { buildPowerRecipe, powerCardTier } from "./power-recipe";
import { getPowerSource, POWER_SOURCES } from "./registry";
import { turbineCard } from "./turbine-flow";
import { buildPowerSettingsReader, type PowerFlowLine } from "./types";

/**
 * The power sources through the compiled core against the website's TypeScript, on both targets. Three inputs:
 *   - the power goldens (conformance/power/power-goldens.jsonl.gz): every source at its defaults and with each setting
 *     moved alone, the turbine flow check at several shares, the tier boundaries;
 *   - the inputs the website's own power tests build (conformance/power/website-test-inputs.jsonl.gz, written by
 *     power-inputs.local.test.ts): each is a model, a recipe, a tier and a turbine card to agree on;
 *   - the switch itself: a power card built with the power area on equals the one built with it off, whole.
 *
 * Numbers agree to 1e-9 relative, as the loader's own check and the core's PowerGoldensTest hold them.
 * The core is built by tools/copy-core.mjs; a checkout with only Node has none and these tests skip. CI sets
 * GTNH_CORE_REQUIRED=1, which turns a missing core into a failure.
 */
const present = existsSync(path.join(process.cwd(), "public", "core", "manifest.json"));
const required = process.env.GTNH_CORE_REQUIRED === "1";

describe("the power shadow needs the core in CI", () => {
  it("is built when CI requires it", () => {
    if (required) expect(present).toBe(true);
  });
});

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
    for (const k of new Set([...Object.keys(w), ...Object.keys(g)])) {
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

function lines(...parts: string[]): Record<string, unknown>[] {
  return gunzipSync(readConformanceBytes(...parts))
    .toString("utf8")
    .split("\n")
    .filter((line) => line.length > 0)
    .map((line) => JSON.parse(line) as Record<string, unknown>);
}

const goldens = lines("power", "power-goldens.jsonl.gz");
const inputs = lines("power", "website-test-inputs.jsonl.gz") as unknown as Array<{
  source: string;
  settings: Record<string, string>;
}>;

/** A flow as the goldens write it: an empty alternatives list is kept. */
function flowRecord(line: PowerFlowLine) {
  return {
    name: line.name,
    perSecond: line.perSecond,
    unit: line.unit,
    alternatives: line.alternatives ?? [],
  };
}

function modelRecord(model: ReturnType<typeof tsModel>) {
  return {
    euPerTick: model.euPerTick,
    inputs: model.inputs.map(flowRecord),
    outputs: model.outputs.map(flowRecord),
    stats: model.stats.map((s) => ({ label: s.label, value: s.value })),
    warnings: model.warnings ?? [],
  };
}

function tsModel(sourceId: string, settings: Record<string, string>) {
  const source = getPowerSource(sourceId)!;
  return source.compute(buildPowerSettingsReader(source, settings));
}

/** Shares a turbine card is checked at, as [running turbines' worth, whole turbines pinned]. */
const SHARES: Array<[number, number | undefined]> = [
  [1, undefined],
  [0.575, undefined],
  [2.4, undefined],
  [2, 3],
  [0, undefined],
];

const targets: CoreTarget[] = wasmGcSupported() ? ["wasm-gc", "js"] : ["js"];

describe("the TypeScript answers until the core is loaded", () => {
  it("builds the same recipe with the area off, and with it on but no core loaded", () => {
    resetCoreForTests();
    clearAdoptOverrides();
    const off = withAdopt({ power: false }, () =>
      POWER_SOURCES.map((s) => buildPowerRecipe(s.id, undefined, `r:${s.id}`)),
    );
    const onWithoutCore = withAdopt({ power: true }, () =>
      POWER_SOURCES.map((s) => buildPowerRecipe(s.id, undefined, `r:${s.id}`)),
    );
    expect(onWithoutCore).toEqual(off);
    expect(
      withAdopt({ power: true }, () => corePowerCard("large-steam-turbine", {})),
    ).toBeUndefined();
    expect(withAdopt({ power: true }, () => corePowerCardTier(512))).toBeUndefined();
  });

  it("is adopted by default, and says so through isAdopted", () => {
    clearAdoptOverrides();
    expect(isAdopted("power")).toBe(true);
  });
});

const suite = describe.skipIf(!present);
for (const target of targets) {
  suite(`power through the core on the ${target} target`, () => {
    beforeAll(async () => {
      resetCoreForTests();
      await loadCore({ target });
    });
    afterAll(() => {
      resetCoreForTests();
      clearAdoptOverrides();
    });

    it("leaves the core alone while the area is off", () => {
      withAdopt({ power: false }, () => {
        expect(corePowerCard("large-steam-turbine", {})).toBeUndefined();
        expect(corePowerCardTier(512)).toBeUndefined();
      });
    });

    it("does not answer for a source it does not know", () => {
      withAdopt({ power: true }, () => expect(corePowerCard("no-such-source", {})).toBeUndefined());
    });

    it("reproduces every golden case: model, tier, turbine check", () => {
      const wrong: string[] = [];
      let cases = 0;
      let checks = 0;
      let tiers = 0;
      withAdopt({ power: true }, () => {
        for (const record of goldens) {
          if (record.kind === "turbineFlow") {
            checks++;
            const card = corePowerCard(
              record.source as string,
              record.settings as Record<string, string>,
              record.running as number,
              (record.pin as number | null) ?? undefined,
            );
            if (!card) {
              wrong.push(`${record.source} turbine: no card`);
              continue;
            }
            // A card asks for the check only when `may` holds, so the check is compared there.
            const got = {
              turbines: card.turbines,
              may: card.may,
              check: card.may ? (card.check ?? null) : null,
            };
            const want = {
              turbines: record.turbines,
              may: record.may,
              check: record.may ? record.check : null,
            };
            const d = same(want, got, `${record.source}`);
            if (d) wrong.push(`turbine ${JSON.stringify(record.settings)} ${record.running}: ${d}`);
          } else if (record.kind === "cardTier") {
            tiers++;
            const eu = Number(record.euPerTick);
            const tier = corePowerCardTier(eu);
            if ((tier ?? null) !== record.tier) wrong.push(`tier ${eu}: ${tier} vs ${record.tier}`);
          } else if ("case" in record) {
            cases++;
            const card = corePowerCard(
              record.source as string,
              record.settings as Record<string, string>,
            );
            if (!card) {
              wrong.push(`${record.source} case ${record.case}: no card`);
              continue;
            }
            const want = record.model as Record<string, unknown>;
            const d = same(
              { ...want, cardTier: undefined },
              { ...modelRecord(card.model), cardTier: undefined },
              `${record.source} case ${record.case}`,
            );
            if (d) wrong.push(d);
            if ((card.cardTier ?? null) !== want.cardTier)
              wrong.push(
                `${record.source} case ${record.case} tier ${card.cardTier} vs ${want.cardTier}`,
              );
          }
        }
      });
      expect(cases).toBeGreaterThan(3000);
      expect(checks).toBeGreaterThan(14000);
      expect(tiers).toBeGreaterThan(30);
      expect(wrong.slice(0, 10)).toEqual([]);
    }, 60_000);

    // The Large Rocket Engine's output falls off by cube roots (Engines.java rocketFalloff), which the 1e-9
    // comparisons above cannot see to the last bit. The file holds the JVM's answers to the bit (core-power
    // RocketExactTest keeps it honest); the engine's cube root is core-base ExactMath.cbrt (fdlibm), the same bits on
    // the JVM and on both web targets.
    it(
      "gives the Large Rocket Engine's cube-root falloff to the last bit, as the JVM does",
      () => {
        const rows = JSON.parse(readConformanceBytes("power", "rocket-exact.json").toString("utf8"))
          .rows as Array<[string, number, boolean, string, string]>;
        const view = new DataView(new ArrayBuffer(8));
        const bits = (x: number) => {
          view.setFloat64(0, x);
          return view.getBigUint64(0).toString(16);
        };
        const wrong: string[] = [];
        for (const [fuel, throttle, boost, eu, air] of rows) {
          const settings = { fuel, throttle: String(throttle), boost: String(boost) };
          const got = JSON.parse(
            core().PowerExports.powerCompute("large-rocket-engine", JSON.stringify(settings))!,
          );
          const gotAir = got.inputs.find((flow: { name: string }) => flow.name === "Air").perSecond;
          if (bits(got.euPerTick) !== eu || bits(gotAir) !== air)
            wrong.push(`${fuel} ${throttle} ${boost}`);
        }
        expect(rows.length).toBeGreaterThanOrEqual(200);
        expect(wrong).toEqual([]);
      },
      60_000,
    );

    it("agrees on every input the website's own power tests build", () => {
      expect(inputs.length).toBeGreaterThan(150);
      const wrong: string[] = [];
      withAdopt({ power: true }, () => {
        for (const { source, settings } of inputs) {
          const card = corePowerCard(source, settings);
          if (!card) {
            wrong.push(`${source} ${JSON.stringify(settings)}: no card`);
            continue;
          }
          const d = same(
            modelRecord(tsModel(source, settings)),
            modelRecord(card.model),
            `${source} ${JSON.stringify(settings)}`,
          );
          if (d) wrong.push(d);
        }
      });
      expect(wrong.slice(0, 10)).toEqual([]);
    }, 60_000);

    it("builds the same recipe, tier and turbine card through the core as through the TypeScript", () => {
      const wrong: string[] = [];
      const cases = [
        ...POWER_SOURCES.map((s) => ({ source: s.id, settings: {} as Record<string, string> })),
        ...inputs,
      ];
      const build = ({ source, settings }: (typeof cases)[number]) => {
        const recipe = buildPowerRecipe(source, settings, `r:${source}`);
        // Some TypeScript sources write "no warnings" as [] and some leave it out; the panel reads both as none.
        if (recipe?.power && !recipe.power.warnings?.length) recipe.power.warnings = undefined;
        const tier = powerCardTier(recipe);
        // Only a turbine card has a flow to check; any other asks once, at one running share.
        const shares = getPowerSource(source)?.settings.some((x) => x.id === "flowMode")
          ? SHARES
          : SHARES.slice(0, 1);
        const turbines = recipe
          ? shares.map(([running, pin]) => turbineCard(recipe, settings, running, pin))
          : [];
        return { recipe, tier, turbines };
      };
      for (const input of cases) {
        const ts = withAdopt({ power: false }, () => build(input));
        const viaCore = withAdopt({ power: true }, () => build(input));
        const d = same(ts, viaCore, `${input.source} ${JSON.stringify(input.settings)}`);
        if (d) wrong.push(d);
      }
      expect(wrong.slice(0, 10)).toEqual([]);
    }, 60_000);
  });
}
