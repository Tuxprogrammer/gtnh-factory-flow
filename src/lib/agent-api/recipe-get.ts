import type { z } from "zod";
import { getMachineBehaviour, resolveCoefficient, type MachineContext } from "@/lib/machines/machine-table";
import {
  applyMachineHandlerToRecipe,
  getRecipeCoilTierControl,
  getRecipeMachineConfigTierControls,
  getRecipeSpecialValue,
  type MachineConfigTierControl,
} from "@/lib/model/recipe-rules";
import { getVoltageTierIndex } from "@/lib/model/tiers";
import type { MachineHandler, MachineTier, Recipe } from "@/lib/model/types";
import { agentDatasetVersion, loadRecipe, withDataset } from "./dataset";
import {
  formatInput,
  formatOutput,
  isHeatMachine,
  machinesOf,
  recipeHeat,
  recipeMapOf,
  secondsOf,
} from "./format";
import type { machineDial, machineOption, recipesGetRequest, recipesGetResponse } from "./schemas";

type Dial = z.infer<typeof machineDial>;

function formatDial(control: MachineConfigTierControl): Dial {
  const dial: Dial = {
    id: control.id,
    label: control.label,
    min: control.minimum.key,
    default: control.current.key,
  };
  if (control.numeric) {
    dial.numeric = control.numeric;
  } else {
    dial.options = control.tiers.map((tier) => ({
      key: tier.key,
      label: tier.label,
      ...(tier.heat !== undefined ? { heat: tier.heat } : {}),
      ...(tier.parallelMultiplier !== undefined ? { parallels: tier.parallelMultiplier } : {}),
    }));
  }
  return dial;
}

/**
 * The machine's parallels at its minimum tier with every dial at its minimum, from the curated machine table, else the
 * handler's own figure. Undefined when neither states one (one recipe at a time).
 */
function parallelsAtMinimum(applied: Recipe, handler: MachineHandler, dials: MachineConfigTierControl[]): number | undefined {
  const behaviour = getMachineBehaviour(applied.machineType);
  if (behaviour?.parallels !== undefined) {
    const context: MachineContext = {
      tier: () => 0,
      value: (controlId) => {
        const dial = dials.find((entry) => entry.id === controlId);
        const value = Number(dial?.minimum.key);
        return Number.isFinite(value) ? value : (dial?.numeric?.min ?? 0);
      },
      voltageTier: getVoltageTierIndex(String(applied.minimumTier) as Exclude<MachineTier, "DEMO">),
      recipeSpecialValue: getRecipeSpecialValue(applied),
      recipeMap: recipeMapOf(applied),
      recipe: applied,
      settings: {},
    };
    try {
      const parallels = resolveCoefficient(behaviour.parallels, context, 1);
      return Number.isFinite(parallels) && parallels > 0 ? parallels : undefined;
    } catch {
      return undefined;
    }
  }
  return handler.maxParallel;
}

function formatMachine(recipe: Recipe, handler: MachineHandler): z.infer<typeof machineOption> {
  const applied = applyMachineHandlerToRecipe(recipe, { machineHandlerId: handler.id });
  const coil = getRecipeCoilTierControl(applied, {});
  const controls = getRecipeMachineConfigTierControls(applied, {});
  const dials = coil ? [coil, ...controls] : controls;
  const parallels = parallelsAtMinimum(applied, handler, dials);
  const profile = applied.machineProfile;
  return {
    id: handler.id,
    name: handler.label,
    kind: handler.kind ?? "single",
    minTier: String(applied.minimumTier),
    ...(applied.maximumTier ? { maxTier: String(applied.maximumTier) } : {}),
    ...(applied.availableTiers?.length ? { tiers: applied.availableTiers } : {}),
    euT: applied.eut,
    seconds: secondsOf(applied.durationTicks),
    ...(parallels !== undefined ? { parallels } : {}),
    ...(profile?.perfectOverclock ? { perfectOverclock: true } : {}),
    ...(isHeatMachine(applied.machineType) ? { heatOverclock: true } : {}),
    dials: dials.map(formatDial),
  };
}

export async function getRecipe(
  body: z.infer<typeof recipesGetRequest>,
): Promise<z.infer<typeof recipesGetResponse>> {
  return withDataset(async () => {
    const versionId = await agentDatasetVersion();
    const recipe = await loadRecipe(versionId, body.recipeId);
    const heat = recipeHeat(recipe);
    const circuitInput = recipe.programmedCircuit
      ? recipe.inputs.find((input) => input.id === recipe.programmedCircuit)
      : undefined;
    const circuit = circuitInput?.displayName ?? recipe.programmedCircuit;
    return {
      dataset: versionId,
      recipe: {
        recipeId: recipe.id,
        name: recipe.name,
        map: recipeMapOf(recipe),
        minTier: String(recipe.minimumTier),
        euT: recipe.eut,
        seconds: secondsOf(recipe.durationTicks),
        ...(heat !== undefined ? { heat } : {}),
        ...(circuit ? { circuit } : {}),
        inputs: recipe.inputs.map((input) => formatInput(input, true)),
        outputs: recipe.outputs.map(formatOutput),
        machines: machinesOf(recipe).map((handler) => formatMachine(recipe, handler)),
      },
    };
  });
}
