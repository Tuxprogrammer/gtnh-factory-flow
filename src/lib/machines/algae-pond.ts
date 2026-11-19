import type { MachineConfigControl, Recipe, RecipeInput } from "@/lib/model/types";
import { GT_VOLTAGE_TIERS } from "@/lib/model/tiers";

/**
 * The Algae Farm (GT5U MTEAlgaePond, "Algae Pond" map, the pack's craftable controller).
 *
 * Its one energy hatch picks the recipe: the pond runs the recipe whose special value is the hatch's tier, or one
 * tier higher when an input bus holds enough Heavy Compost (compostForTier(hatch tier) a run). It draws
 * V[hatch tier] x 9/10 EU/t, never overclocks and runs one parallel. The recipes are registered at 0 EU/t with no
 * inputs, which is what the dataset carries; the draw and the compost come from here. GT++'s
 * MTEAlgaePondBaseLegacy (casing tier, no power) is the old controller and is not modelled.
 */
export const ALGAE_POND_MACHINE_TYPE = "Algae Pond";
export const ALGAE_COMPOST = "algaeCompost";
export const HEAVY_COMPOST_ID = "miscutils:item.basicagrichemitem@8";
/** The highest energy hatch tier (MAX). Tier 15 algae is reachable only with compost on it. */
const TOP_HATCH_TIER = GT_VOLTAGE_TIERS.length - 1;

type AlgaeRecipe = Partial<Pick<Recipe, "machineType" | "specialValue" | "metadata">>;

export function isAlgaePondRecipe(recipe: Partial<Pick<Recipe, "machineType">>): boolean {
  return recipe.machineType === ALGAE_POND_MACHINE_TYPE;
}

/** The recipe's ALGAE_POND_TIER (its special value). */
export function algaePondRecipeTier(recipe: AlgaeRecipe): number | undefined {
  const value = recipe.specialValue ?? recipe.metadata?.specialValue;
  return typeof value === "number" && Number.isInteger(value) && value >= 0 ? value : undefined;
}

/** MTEAlgaePond.getRecipeEUt: V[tier] x 9 / 10 in long maths. */
export function algaePondEuPerTick(hatchTier: number): number {
  return Math.floor((GT_VOLTAGE_TIERS[hatchTier].maxEuT * 9) / 10);
}

/** MTEAlgaePond.compostForTier: Heavy Compost a run on a hatch of this tier. */
export function algaePondCompostPerRun(hatchTier: number): number {
  return hatchTier > 1 ? Math.min(64, 2 ** (hatchTier - 1)) : 1;
}

/** Compost is the default only where no hatch reaches the recipe's own tier (tier 15). */
function compostByDefault(tier: number): boolean {
  return tier > TOP_HATCH_TIER;
}

export function algaePondUsesCompost(
  recipe: AlgaeRecipe,
  settings: Record<string, string> | undefined,
): boolean {
  const tier = algaePondRecipeTier(recipe);
  if (tier === undefined || tier < 1) return false;
  const key = settings?.[ALGAE_COMPOST];
  return key === undefined ? compostByDefault(tier) : key === "compost";
}

/** The energy hatch tier that grows this recipe; above TOP_HATCH_TIER means no hatch can. */
export function algaePondHatchTier(
  recipe: AlgaeRecipe,
  settings: Record<string, string> | undefined,
): number | undefined {
  const tier = algaePondRecipeTier(recipe);
  if (tier === undefined) return undefined;
  return algaePondUsesCompost(recipe, settings) ? tier - 1 : tier;
}

/**
 * The dataset lists every pond recipe at ULV and 0 EU/t. The recipe instead carries the draw and the minimum tier
 * of the hatch that grows it by default, so its card and search show the power it needs.
 */
export function enrichAlgaePondRecipe(recipe: Recipe): Recipe {
  const hatch = algaePondHatchTier(recipe, undefined);
  if (hatch === undefined || hatch > TOP_HATCH_TIER) return recipe;
  return { ...recipe, minimumTier: GT_VOLTAGE_TIERS[hatch].tier, eut: algaePondEuPerTick(hatch) };
}

/** The table's EU multiplier: the chosen hatch's draw over the draw the recipe carries. */
export function algaePondPowerMultiplier(
  recipe: AlgaeRecipe | undefined,
  settings: Record<string, string> | undefined,
): number {
  if (!recipe) return 1;
  const base = algaePondHatchTier(recipe, undefined);
  const hatch = algaePondHatchTier(recipe, settings);
  if (
    base === undefined ||
    hatch === undefined ||
    base > TOP_HATCH_TIER ||
    hatch > TOP_HATCH_TIER ||
    hatch < 0
  ) {
    return 1;
  }
  return algaePondEuPerTick(hatch) / algaePondEuPerTick(base);
}

/** Why this card's hatch does not grow its recipe, or undefined when it does. */
export function getAlgaePondGateReason(
  recipe: AlgaeRecipe | undefined,
  settings: Record<string, string> | undefined,
  hatchTier: number,
): string | undefined {
  if (!recipe) return undefined;
  const hatch = algaePondHatchTier(recipe, settings);
  if (hatch === undefined) return undefined;
  if (hatch > TOP_HATCH_TIER) {
    return `No energy hatch reaches tier ${hatch}. This recipe grows only with compost on a ${GT_VOLTAGE_TIERS[TOP_HATCH_TIER].tier} hatch.`;
  }
  if (hatchTier === hatch) return undefined;
  return algaePondUsesCompost(recipe, settings)
    ? `Needs one ${GT_VOLTAGE_TIERS[hatch].tier} energy hatch. With compost the pond grows the recipe one tier above its hatch.`
    : `Needs one ${GT_VOLTAGE_TIERS[hatch].tier} energy hatch. The pond grows the recipe of its hatch's tier.`;
}

/** The compost knob, on every recipe a lower hatch can reach with compost (tier 1 and up). */
export function getAlgaePondControls(recipe: AlgaeRecipe): MachineConfigControl[] {
  const tier = algaePondRecipeTier(recipe);
  if (tier === undefined || tier < 1) return [];
  const hatch = tier - 1;
  const perRun = algaePondCompostPerRun(hatch);
  const option = (key: string, label: string, detail: string, id: string) => ({
    key,
    label,
    resource: {
      kind: "item" as const,
      id,
      amount: 1,
      displayName: label,
      tooltip: [detail],
      consumed: false,
    },
  });
  return [
    {
      id: ALGAE_COMPOST,
      label: "Compost",
      minimumKey: "none",
      defaultKey: compostByDefault(tier) ? "compost" : "none",
      tiers: [
        option(
          "none",
          "No compost",
          `Grows this tier on one ${GT_VOLTAGE_TIERS[Math.min(tier, TOP_HATCH_TIER)].tier} energy hatch.`,
          `factoryflow:machine_config/${ALGAE_COMPOST}_none`,
        ),
        option(
          "compost",
          `Compost ${perRun}x`,
          `${perRun} Heavy Compost a run grows this tier on one ${GT_VOLTAGE_TIERS[hatch].tier} energy hatch.`,
          HEAVY_COMPOST_ID,
        ),
      ],
    },
  ];
}

/** The Heavy Compost a composted run uses up, added as an input. */
export function applyAlgaePondInputs(
  recipe: Recipe,
  settings: Record<string, string> | undefined,
): Recipe {
  if (!algaePondUsesCompost(recipe, settings)) return recipe;
  if (recipe.inputs.some((input) => input.id === HEAVY_COMPOST_ID)) return recipe;
  const hatch = algaePondHatchTier(recipe, settings)!;
  const compost: RecipeInput = {
    kind: "item",
    id: HEAVY_COMPOST_ID,
    amount: algaePondCompostPerRun(hatch),
    displayName: "Heavy Compost",
  };
  return { ...recipe, inputs: [...recipe.inputs, compost] };
}
