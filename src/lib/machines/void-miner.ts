import type { MachineConfigControl, MachineHandler, Recipe, RecipeInput } from "@/lib/model/types";
import { GT_VOLTAGE_TIERS } from "@/lib/model/tiers";

/**
 * The Void Miners (GT5U bwcrossmod.galacticgreg.MTEVoidMinerBase, tiers I to III in MTEVoidMiners).
 *
 * The "Void Miner" map has one NEI recipe per dimension: the planet marker (not consumed) in, the dimension's ore pool
 * out with each ore's share as its chance, registered at 1 tick and 0 EU (VoidMinerRecipes). The machine ignores both
 * numbers. setElectricityStats runs a fixed RECIPE_DURATION_TICKS = 10 cycle at VP[tier + MIN_TIER_OFFSET 5] EU/t, and
 * each cycle outputs ONE ore drawn by weight from the pool, a stack of TIER_MULTIPLIER (1, 2, 3) ores, times the noble
 * gas boost when a hatch holds one (Neon 4, Krypton 8, Xenon 16, Oganesson 64, VoidMinerUtility.NOBLE_GASSES_MULTIPLIER),
 * which costs 1 L a cycle (consumeNobleGas). Batch mode runs 16 cycles' worth in one 160-tick cycle: the same rates.
 * No overclocks and no parallels: a stronger hatch changes nothing. The rates mod does not touch this machine.
 * A filtered ore is voided, not rerolled, so each ore's rate is its share of the pool whatever the filter.
 */
export const VOID_MINER_MAP = "Void Miner";
export const VOID_MINER_CYCLE_TICKS = 10;
export const VOID_MINER_NOBLE_GAS = "voidMinerNobleGas";
/** MTEVoidMinerBase.MIN_TIER_OFFSET. */
const MIN_TIER_OFFSET = 5;

/** TIER_MULTIPLIER of each controller, by its handler's machine type. */
export const VOID_MINER_TIERS: Record<string, number> = {
  "Void Miner I": 1,
  "Void Miner II": 2,
  "Void Miner III": 3,
};

/** The noble gases in the game's order, with their fluid ids and output boosts. */
export const VOID_MINER_GASES = [
  { key: "neon", label: "Neon", boost: 4 },
  { key: "krypton", label: "Krypton", boost: 8 },
  { key: "xenon", label: "Xenon", boost: 16 },
  { key: "oganesson", label: "Oganesson", boost: 64 },
] as const;

export function isVoidMinerRecipe(recipe: Partial<Pick<Recipe, "machineType" | "source">>): boolean {
  return (
    recipe.machineType === VOID_MINER_MAP ||
    recipe.source?.recipeMap === VOID_MINER_MAP ||
    (recipe.machineType !== undefined && VOID_MINER_TIERS[recipe.machineType] !== undefined)
  );
}

/** VP[tier + 5]: GTValues.VP is V x 30 / 32. */
export function voidMinerEuPerTick(tier: number): number {
  return Math.floor((GT_VOLTAGE_TIERS[tier + MIN_TIER_OFFSET].maxEuT * 30) / 32);
}

/** The energy hatch tier the controller needs (gt.mbtt.structure.any_leg_casing_min_tier, VN[getMinTier()]). */
export function voidMinerMinimumTier(tier: number) {
  return GT_VOLTAGE_TIERS[tier + MIN_TIER_OFFSET].tier;
}

function handlerTier(handler: Pick<MachineHandler, "machineType" | "label">): number | undefined {
  return VOID_MINER_TIERS[handler.machineType ?? ""] ?? VOID_MINER_TIERS[handler.label];
}

/**
 * The dataset's Void Miner recipe as the machine runs it: each controller's draw, minimum hatch and 10-tick cycle on
 * its handler, the recipe itself at Void Miner I's (handlers ordered I, II, III so a new card starts on the cheapest),
 * and the chances scaled to sum to one, since every cycle draws exactly one ore and NEI rounds each share to 0.01%.
 * The exported runtime ladder is the overclock calculator on 0 EU and 1 tick, so it goes.
 */
export function enrichVoidMinerRecipe(recipe: Recipe): Recipe {
  const handlers = (recipe.machineHandlers ?? [])
    .map((handler) => {
      const tier = handlerTier(handler);
      if (tier === undefined) return handler;
      const minimumTier = voidMinerMinimumTier(tier);
      return {
        ...handler,
        minimumTier,
        durationTicks: VOID_MINER_CYCLE_TICKS,
        eut: voidMinerEuPerTick(tier),
      };
    })
    .sort((a, b) => (handlerTier(a) ?? 99) - (handlerTier(b) ?? 99));
  const sum = recipe.outputs.reduce((total, output) => total + (output.chance ?? 1), 0);
  const outputs =
    sum > 0 && Math.abs(sum - 1) > 1e-9
      ? recipe.outputs.map((output) => ({ ...output, chance: (output.chance ?? 1) / sum }))
      : recipe.outputs;
  const { runtimeCalculation: _runtime, ...rest } = recipe;
  return {
    ...rest,
    minimumTier: voidMinerMinimumTier(1),
    durationTicks: VOID_MINER_CYCLE_TICKS,
    eut: voidMinerEuPerTick(1),
    outputs,
    ...(handlers.length ? { machineHandlers: handlers } : {}),
  };
}

/** The noble gas knob: each option multiplies the ores a cycle by the controller's tier and the gas's boost. */
export function getVoidMinerControls(tier: number): MachineConfigControl[] {
  const option = (key: string, label: string, multiplier: number, id: string, detail: string) => ({
    key,
    label,
    outputMultiplier: multiplier,
    resource: {
      kind: key === "none" ? ("item" as const) : ("fluid" as const),
      id,
      amount: 1,
      displayName: label,
      tooltip: [detail],
      consumed: false,
    },
  });
  return [
    {
      id: VOID_MINER_NOBLE_GAS,
      label: "Noble gas",
      minimumKey: "none",
      defaultKey: "none",
      tiers: [
        option(
          "none",
          "No gas",
          tier,
          `factoryflow:machine_config/${VOID_MINER_NOBLE_GAS}_none`,
          `${tier} ores a cycle.`,
        ),
        ...VOID_MINER_GASES.map((gas) =>
          option(
            gas.key,
            gas.label,
            tier * gas.boost,
            gas.key,
            `${gas.boost}x ores for 1 L of ${gas.label} a cycle: ${tier * gas.boost} ores a cycle.`,
          ),
        ),
      ],
    },
  ];
}

/** The noble gas a boosted cycle uses up, 1 L, added as an input. */
export function applyVoidMinerInputs(recipe: Recipe, settings: Record<string, string> | undefined): Recipe {
  const gas = VOID_MINER_GASES.find((entry) => entry.key === settings?.[VOID_MINER_NOBLE_GAS]);
  if (!gas) return recipe;
  if (recipe.inputs.some((input) => input.kind === "fluid" && input.id === gas.key)) return recipe;
  const input: RecipeInput = { kind: "fluid", id: gas.key, amount: 1, displayName: gas.label };
  return { ...recipe, inputs: [...recipe.inputs, input] };
}
