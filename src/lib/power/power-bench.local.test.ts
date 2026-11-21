import { it } from "vitest";
import { clearAdoptOverrides, withAdopt } from "@/lib/core/adopt";
import "@/lib/core/power-adopt";
import { loadCore, resetCoreForTests, wasmGcSupported, type CoreTarget } from "@/lib/core/loader";
import { buildPowerRecipe, powerCardTier } from "./power-recipe";
import { POWER_SOURCES } from "./registry";
import { turbineCard } from "./turbine-flow";

/** Time per call of the power paths the board takes, TypeScript against the core on each target. */
function time(label: string, reps: number, fn: () => void): number {
  for (let i = 0; i < 3; i++) fn();
  const t0 = performance.now();
  for (let i = 0; i < reps; i++) fn();
  const ms = (performance.now() - t0) / reps;
  console.log(`${label.padEnd(44)} ${(ms * 1000).toFixed(1).padStart(9)} us per pass`);
  return ms;
}

function passes(): void {
  const recipes = POWER_SOURCES.map((s) => buildPowerRecipe(s.id, undefined, `r:${s.id}`)!);
  time(`build ${recipes.length} power recipes (edit/load)`, 20, () => {
    for (const s of POWER_SOURCES) buildPowerRecipe(s.id, undefined, `r:${s.id}`);
  });
  time(`tier for ${recipes.length} cards (render)`, 200, () => {
    for (const r of recipes) powerCardTier(r);
  });
  time(`turbineCard for ${recipes.length} cards (render)`, 200, () => {
    for (const r of recipes) turbineCard(r, undefined, 3.3, undefined);
  });
  // One turbine card: a repeat render (same inputs) and a first sight (a new running count each time).
  const one = recipes.find((r) => r.power?.sourceId.includes("turbine"))!;
  time("one turbine card, repeat render", 5000, () => {
    powerCardTier(one);
    turbineCard(one, undefined, 3.3, undefined);
  });
  let n = 0;
  time("one turbine card, new running count", 3000, () => {
    powerCardTier(one);
    turbineCard(one, undefined, 3 + ++n * 1e-4, undefined);
  });
}

it("measures", async () => {
  clearAdoptOverrides();
  console.log("--- TypeScript (area off)");
  withAdopt({ power: false }, passes);
  for (const target of (wasmGcSupported() ? ["wasm-gc", "js"] : ["js"]) as CoreTarget[]) {
    resetCoreForTests();
    await loadCore({ target });
    console.log(`--- core, ${target}`);
    withAdopt({ power: true }, passes);
  }
}, 600000);
