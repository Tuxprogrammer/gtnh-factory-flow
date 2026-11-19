// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { setActivePowerDisplayUnit } from "@/lib/model/rate-unit";
import { buildPowerRecipe } from "@/lib/power/power-recipe";
import { turbineFlowCheck } from "@/lib/power/turbine-flow";
import { buildFootView, type TurbineFoot } from "./clean-model";
import { footTip } from "./menus/card-tips";

afterEach(() => setActivePowerDisplayUnit("eu"));

const HP = {
  rotor: "Ichorium",
  size: "Large",
  grade: "SH Steam",
  flowMode: "optimal",
  fitting: "tight",
};
const hp = buildPowerRecipe("xl-turbo-hp-steam-turbine", HP, "hp")!;
const ports = { inputs: [], outputs: [] };
const check = (running: number, turbines: number): TurbineFoot => ({
  ...turbineFlowCheck(hp, HP, running, turbines)!,
  tier: "UV",
});

describe("a turbine card fed off its set flow says so in its foot (fork issue 64)", () => {
  it("one turbine at 57% of optimal: the bar says the share and what it makes", () => {
    const foot = buildFootView(
      { rule: "any" },
      undefined,
      ports,
      undefined,
      undefined,
      undefined,
      check(264_900 / 460_800, 1),
    )!;
    expect(foot).toMatchObject({ tone: "starved", word: "FLOW", frac: "", fill: 0 });
    expect(foot.say).toBe("57% of optimal, makes 342.6k EU/t");
    expect(foot.facts.reason).toBe("FLOW");

    const tip = footTip("n", foot);
    expect(tip.title).toBe("Below optimal flow");
    expect(tip.rows).toEqual([
      { label: "It gets", value: "264,900 L/t of 460,800 (57%)", tone: "warn" },
      { label: "Makes", value: "342.6k EU/t", tone: "warn" },
      { label: "The plan counts", value: "596k EU/t" },
    ]);
    expect(tip.notes?.[0]?.text).toBe(
      "A turbine loses efficiency the further its flow is from optimal. Set its flow to Custom 264,900 L/t to plan it exactly.",
    );
  });

  it("x2.4 is three turbines at 80% each, and the tip says so", () => {
    const foot = buildFootView(
      { rule: "any" },
      undefined,
      ports,
      undefined,
      undefined,
      undefined,
      check(2.4, 3),
    )!;
    expect(foot.say).toBe("3 at 80% of optimal, make 1.99M EU/t");
    const tip = footTip("n", foot);
    expect(tip.rows).toEqual([
      { label: "Each turbine gets", value: "368,640 L/t of 460,800 (80%)", tone: "warn" },
      { label: "Turbines", value: "3" },
      { label: "Makes", value: "1.99M EU/t", tone: "warn" },
      { label: "The plan counts", value: "2.49M EU/t" },
    ]);
    expect(tip.notes?.[0]?.text).toBe(
      "A turbine loses efficiency the further its flow is from optimal. Set its flow to Custom 368,640 L/t to plan it exactly.",
    );
  });

  it("a turbine fed above optimal (a Custom flow over it) is titled above optimal flow", () => {
    const over = { ...HP, flowMode: "custom", customFlow: "576000" };
    const recipe = buildPowerRecipe("xl-turbo-hp-steam-turbine", over, "over")!;
    const foot = buildFootView({ rule: "any" }, undefined, ports, undefined, undefined, undefined, {
      ...turbineFlowCheck(recipe, over, 0.9, 1)!,
    })!;
    // 0.9 x 576,000 = 518,400 L/t: above the 460,800 optimal.
    expect(foot.say.startsWith("113% of optimal")).toBe(true);
    expect(footTip("n", foot).title).toBe("Above optimal flow");
  });

  it("follows the Amps dial in the card's own tier", () => {
    setActivePowerDisplayUnit("amps");
    const foot = buildFootView(
      { rule: "any" },
      undefined,
      ports,
      undefined,
      undefined,
      undefined,
      check(264_900 / 460_800, 1),
    )!;
    expect(footTip("n", foot).rows?.[1]?.value).toMatch(/ A UV$/);
  });

  it("a ring's bar comes first", () => {
    const foot = buildFootView(
      { rule: "any" },
      undefined,
      ports,
      undefined,
      "dead",
      undefined,
      check(0.5, 1),
    );
    expect(foot?.facts.reason).not.toBe("FLOW");
  });
});
