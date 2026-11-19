import { describe, expect, it } from "vitest";
import { buildPowerRecipe } from "./power-recipe";
import { mayTurbineFlow, turbineFlowCheck, turbinesFor } from "./turbine-flow";

const f32 = Math.fround;

// Fork issue 64's chain: an XL Turbo HP Steam Turbine and an XL Turbo Steam Turbine, large Ichorium rotors, fed
// 264,900 L/t of their 460,800 L/t optimal by six Solar Towers through a Whakawhiti Wera XL.
const HP = {
  rotor: "Ichorium",
  size: "Large",
  grade: "SH Steam",
  flowMode: "optimal",
  fitting: "tight",
};
const LP = { rotor: "Ichorium", size: "Large" };
const SHARE = 264_900 / 460_800;

/** MTEXLTurbineHPSteam / MTEXLTurbineSteam.fluidIntoPower off optimal, in the Java's float steps. */
function javaXlSteam(flow: number, opt: number, eff: number, perLitre: 1 | 0.5): number {
  let tEU = perLitre === 1 ? flow : Math.trunc(f32(flow * 0.5));
  const efficiency = f32(1 - Math.abs(f32(f32(flow - opt) / opt)));
  tEU = Math.trunc(f32(tEU * efficiency));
  return Math.max(1, Math.trunc(f32(tEU * f32(eff))));
}

describe("a turbine fed off its set flow (fork issue 64)", () => {
  it("one XL HP steam turbine at 57% of optimal makes 342,636 EU/t, not the 596,025 the plan counts", () => {
    const recipe = buildPowerRecipe("xl-turbo-hp-steam-turbine", HP, "hp")!;
    const check = turbineFlowCheck(recipe, HP, SHARE, 1)!;
    expect(check).toMatchObject({
      turbines: 1,
      flow: 264_900,
      setFlow: 460_800,
      optimal: 460_800,
      unit: "L/t",
    });
    expect(check.euMade).toBe(342_636);
    expect(check.euMade).toBe(javaXlSteam(264_900, 460_800, 2.25, 1));
    expect(Math.round(check.euCounted)).toBe(596_025);
  });

  it("its exhaust in the XL steam turbine makes 171,317 EU/t, not 298,012", () => {
    const recipe = buildPowerRecipe("xl-turbo-steam-turbine", LP, "lp")!;
    const check = turbineFlowCheck(recipe, LP, SHARE, 1)!;
    expect(check.euMade).toBe(171_317);
    expect(check.euMade).toBe(javaXlSteam(264_900, 460_800, 2.25, 0.5));
    expect(Math.round(check.euCounted)).toBe(298_013);
  });

  it("shares a fractional count over whole turbines: x2.4 is three turbines at 80% each", () => {
    const recipe = buildPowerRecipe("xl-turbo-hp-steam-turbine", HP, "hp")!;
    expect(turbinesFor(2.4)).toBe(3);
    const check = turbineFlowCheck(recipe, HP, 2.4, turbinesFor(2.4))!;
    expect(check.turbines).toBe(3);
    expect(check.flow).toBe(368_640);
    expect(check.euMade).toBe(3 * javaXlSteam(368_640, 460_800, 2.25, 1));
    expect(check.euMade).toBeLessThan(check.euCounted);
  });

  it("an Exactly card shares what runs over its count", () => {
    expect(turbinesFor(2, 3)).toBe(3);
    const recipe = buildPowerRecipe("xl-turbo-hp-steam-turbine", HP, "hp")!;
    expect(turbineFlowCheck(recipe, HP, 2, 3)!.flow).toBe(307_200);
  });

  it("says nothing when each turbine gets its set flow", () => {
    const recipe = buildPowerRecipe("xl-turbo-hp-steam-turbine", HP, "hp")!;
    expect(turbineFlowCheck(recipe, HP, 1, 1)).toBeUndefined();
    expect(turbineFlowCheck(recipe, HP, 3, 3)).toBeUndefined();
    expect(turbineFlowCheck(recipe, HP, 0, 1)).toBeUndefined();
    expect(mayTurbineFlow(recipe, 2, 2)).toBe(false);
    expect(mayTurbineFlow(recipe, 2.4, 3)).toBe(true);
  });

  it("measures a Custom flow above optimal the same way: closer to optimal it makes more than the plan counts", () => {
    // Set to 1.25 x optimal (the cap), each turbine fed 0.8 of that is fed exactly optimal.
    const over = { ...HP, flowMode: "custom", customFlow: "576000" };
    const recipe = buildPowerRecipe("xl-turbo-hp-steam-turbine", over, "hp")!;
    const check = turbineFlowCheck(recipe, over, 0.8, 1)!;
    expect(check.flow).toBe(460_800);
    expect(check.euMade).toBe(1_036_800);
    expect(check.euMade).toBeGreaterThan(check.euCounted);
  });

  it("follows the game's off-optimal loss for gas and plasma turbines too", () => {
    for (const [id, settings] of [
      ["xl-turbo-gas-turbine", { rotor: "Ichorium", size: "Large" }],
      ["large-gas-turbine", { rotor: "Ichorium", size: "Large" }],
      ["xl-turbo-plasma-turbine", { rotor: "Ichorium", size: "Large" }],
      ["large-plasma-generator", { rotor: "Ichorium", size: "Large" }],
    ] as const) {
      const recipe = buildPowerRecipe(id, settings, id)!;
      const full = recipe.power!.euPerTick;
      const check = turbineFlowCheck(recipe, settings, 0.5, 1)!;
      expect(check, id).toBeDefined();
      // fluidIntoPower: fuel EU of the flow x (1 - |flow - opt| / opt) x rotor efficiency, so half the flow makes a
      // quarter (within the Java's integer truncation).
      const share = check.flow / check.optimal;
      expect(check.euMade / full, id).toBeCloseTo(share * share, 2);
      expect(check.euCounted, id).toBeCloseTo(full * 0.5, 0);
    }
  });
});
