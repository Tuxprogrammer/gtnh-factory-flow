import { getVoltageTierIndex } from "@/lib/model/tiers";
import type { MachineTier, Recipe, RecipeOutput } from "@/lib/model/types";

type VoltageTier = Exclude<MachineTier, "DEMO">;

/**
 * Item output slots of the single-block machines whose recipe maps carry more
 * item outputs than the machine has slots. `MTEBasicMachine.checkRecipe` rolls
 * output i only for i < mOutputItems.length (one entry per output slot), and
 * `canOutput` checks only those slots, so the outputs past them are never made
 * and never block the machine. The steam macerators go further and roll output
 * 0 alone (`MTESteamMacerator*.checkRecipe`).
 *
 * Counts are `setSlotsCount` in GT5-Unofficial's `LoaderMetaTileEntities` and
 * the steam constructors. Each step is [first tier, slots from that tier up].
 * Families whose slots cover every recipe on their map are left out.
 */
const ITEM_OUTPUT_SLOTS: Record<string, Array<[VoltageTier, number]>> = {
  Macerator: [
    ["LV", 1],
    ["HV", 2],
    ["EV", 3],
    ["IV", 4],
  ],
  "Steam Macerator": [["ULV", 1]],
  "High Pressure Steam Macerator": [["ULV", 1]],
  "Arc Furnace": [
    ["LV", 4],
    ["EV", 9],
  ],
  "Cutting Machine": [
    ["LV", 2],
    ["HV", 4],
  ],
  Mixer: [
    ["LV", 1],
    ["HV", 4],
  ],
  "Chemical Bath": [
    ["LV", 3],
    ["HV", 6],
  ],
  Autoclave: [
    ["LV", 2],
    ["HV", 3],
    ["EV", 4],
  ],
  Centrifuge: [["LV", 6]],
  "Forge Hammer": [["LV", 1]],
  "Steam Forge Hammer": [["ULV", 1]],
  "High Pressure Steam Forge Hammer": [["ULV", 1]],
  "Precision Laser Engraver": [["LV", 1]],
};

type SlotRecipe = Partial<Pick<Recipe, "machineType" | "machineProfile">>;

/** The machine's item output slots at this tier, or undefined when it has room for every recipe. */
export function singleblockItemOutputSlots(
  recipe: SlotRecipe,
  tier: VoltageTier,
): number | undefined {
  if (recipe.machineProfile?.kind === "multiblock") {
    return undefined;
  }
  const steps = Object.hasOwn(ITEM_OUTPUT_SLOTS, recipe.machineType ?? "")
    ? ITEM_OUTPUT_SLOTS[recipe.machineType ?? ""]
    : undefined;
  if (!steps) {
    return undefined;
  }
  const index = getVoltageTierIndex(tier);
  let slots = steps[0][1];
  for (const [from, count] of steps) {
    if (getVoltageTierIndex(from) <= index) {
      slots = count;
    }
  }
  return slots;
}

/**
 * Whether this output has a slot to land in. `outputs` is the list the output
 * came from, in the game's order: items first, then fluids, as exported.
 */
export function hasItemOutputSlot(
  recipe: SlotRecipe & { outputs?: readonly RecipeOutput[] },
  output: RecipeOutput,
  tier: VoltageTier,
): boolean {
  if (output.kind !== "item") {
    return true;
  }
  const slots = singleblockItemOutputSlots(recipe, tier);
  if (slots === undefined) {
    return true;
  }
  const itemIndex = recipe.outputs?.filter((entry) => entry.kind === "item").indexOf(output);
  return itemIndex === undefined || itemIndex < slots;
}

/** The outputs with the ones past the machine's item slots set to zero; ports stay, amounts go. */
export function zeroUnslottedOutputs(
  recipe: SlotRecipe,
  outputs: RecipeOutput[],
  tier: VoltageTier,
): RecipeOutput[] {
  const slots = singleblockItemOutputSlots(recipe, tier);
  if (slots === undefined) {
    return outputs;
  }
  let itemIndex = 0;
  return outputs.map((output) =>
    output.kind === "item" && itemIndex++ >= slots ? { ...output, amount: 0 } : output,
  );
}
