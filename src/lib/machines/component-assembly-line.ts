import type { MachineConfigControl } from "@/lib/model/types";

/**
 * MTEComponentAssemblyLine's casing: Component Assembly Line Casing metas 0
 * (LV) through 13 (MAX) set `casingTier`. A recipe's special value is the
 * casing it needs counted from LV = 1 (validateRecipe rejects
 * `mSpecialValue > casingTier + 1`), so the ladder starts at that casing.
 */
export const COAL_CASING = "componentAssemblyLineCasing";

const CASING_TIERS = [
  "LV",
  "MV",
  "HV",
  "EV",
  "IV",
  "LuV",
  "ZPM",
  "UV",
  "UHV",
  "UEV",
  "UIV",
  "UMV",
  "UXV",
  "MAX",
];

export const COAL_CASING_CONTROL: MachineConfigControl = {
  id: COAL_CASING,
  label: "Casing",
  minimumKey: "lv",
  defaultKey: "lv",
  minimumFromSpecialValue: true,
  tiers: CASING_TIERS.map((tier, index) => ({
    key: tier.toLowerCase(),
    label: tier,
    resource: {
      kind: "item" as const,
      id: `goodgenerator:componentassemblylinecasing${index ? `@${index}` : ""}`,
      amount: 1,
      consumed: false,
      displayName: `Component Assembly Line Casing (${tier})`,
      tooltip: ["Halves recipe time per casing tier above the recipe's own."],
    },
  })),
};

/**
 * `createOverclockCalculator` sets the duration modifier to
 * 2^-(casingTier + 1 - mSpecialValue): each casing tier above the recipe's
 * halves its time. `tier` is the casing's position on the full LV..MAX ladder.
 */
export function componentAssemblyLineSpeed(casingTier: number, specialValue = 1): number {
  return 2 ** (casingTier + 1 - specialValue);
}
