import type { FactoryNode, Recipe } from "@/lib/model/types";
import {
  applyMachineHandlerToRecipe,
  getRecipeCoilTierControl,
  getRecipeMachineConfigTierControls,
} from "@/lib/model/recipe-rules";
import { applyRecipeInputOverrides } from "@/lib/model/recipe-input-overrides";
import { getOverclockedRecipeStats } from "@/lib/solver/overclock";
import {
  getMachineDurationMultiplier,
  getMachineEutMultiplier,
  getMachineOutputMultiplier,
  getMachineParallelMultiplier,
  getMachineStructuralParallels,
} from "@/lib/solver/machine-effects";
import {
  getRuntimeCalculationOutputs,
  selectRuntimeCalculationVariant,
} from "@/lib/solver/runtime-calculation";
import {
  getEffectiveVoltageOrdinal,
  getNodePowerAmps,
  getNodeRunTier,
  isMultiblockRecipe,
} from "@/lib/solver/power";
import { getHeatDiscountMultiplier } from "@/lib/solver/heat";
import { getNodePowerReport, getNodeSteamReport, hasPowerReport } from "@/lib/solver/power-report";

/** A card as the goldens record it: any node fields, with the three every card has. */
export type ReadoutNode = Partial<FactoryNode> & {
  machineCount: number;
  parallel: number;
  overclockTier: string;
};

/**
 * What MachineGoldensTest reads, computed as the website computes a card: the TypeScript twin of the core's
 * MachineReadout (jvm/core/core-machines). The machine golden generator (tools/audits/export-mod-machine-goldens.ts)
 * writes it under "expected", and the shadow test (src/lib/core/machines-shadow.test.ts) holds the compiled core to it.
 */
export function machineReadout(recipe: Recipe, node: ReadoutNode) {
  const card = node as FactoryNode;
  const stats = getOverclockedRecipeStats(recipe, card);
  const effective = applyMachineHandlerToRecipe(recipe, card);
  const runtime = selectRuntimeCalculationVariant(effective, card);
  const multipliers = effective.outputs.map((output) =>
    getMachineOutputMultiplier(effective, card, output, stats.tier),
  );
  const runtimeOutputs = getRuntimeCalculationOutputs(effective, card);
  const runTier = getNodeRunTier(effective, card);
  const voltageOrdinal = getEffectiveVoltageOrdinal(effective, card, runTier);
  const expected: Record<string, unknown> = {
    tier: stats.tier,
    minimumTier: stats.minimumTier,
    overclockSteps: stats.overclockSteps,
    perfectOverclockSteps: stats.perfectOverclockSteps,
    perfectSpeedFactor: stats.perfectSpeedFactor,
    perfectEuFactor: stats.perfectEuFactor,
    durationTicks: stats.durationTicks,
    eut: stats.eut,
    parallels: runtime?.parallel ?? getMachineParallelMultiplier(effective, card),
    structuralParallels: getMachineStructuralParallels(effective, card),
    durationMultiplier: getMachineDurationMultiplier(effective, card),
    eutMultiplier: getMachineEutMultiplier(effective, card),
    outputMultipliers: multipliers,
    chances: effective.outputs.map((output) => output.chance ?? 1),
    ...(runtimeOutputs
      ? {
          runtimeOutputs: runtimeOutputs.map(({ kind, id, amount, chance }) => ({
            kind,
            id,
            amount,
            chance,
          })),
        }
      : {}),
    outputAmounts: runtimeOutputs
      ? runtimeOutputs.map((output) => output.amount)
      : effective.outputs.map((output, index) => output.amount * multipliers[index]),
    inputAmounts: applyRecipeInputOverrides(recipe, card).inputs.map((input) => ({
      amount: input.amount,
      consumed: input.consumed !== false,
    })),
    machineType: effective.machineType,
    multiblock: isMultiblockRecipe(effective),
    runTier,
    amps: getNodePowerAmps(effective, card),
    voltageOrdinal,
    heatDiscount: getHeatDiscountMultiplier(effective, card, runTier, voltageOrdinal),
  };
  if (hasPowerReport(recipe, card)) {
    const report = getNodePowerReport(recipe, card);
    expected.powerState = report.state;
    if (report.recipeGateReason !== undefined) expected.recipeGateReason = report.recipeGateReason;
    expected.poolEuT = report.poolEuT;
    expected.singleDrawEuT = report.singleDrawEuT;
  }
  const steam = getNodeSteamReport(recipe, card);
  if (steam) {
    expected.steam = {
      perTick: steam.drawSteamPerTick,
      singlePerTick: steam.singleDrawSteamPerTick,
      parallels: steam.parallels,
      multiblock: steam.isMultiblock,
      highPressure: steam.highPressure,
    };
  }
  const coil = getRecipeCoilTierControl(effective, card);
  expected.controls = [
    ...getRecipeMachineConfigTierControls(effective, card),
    ...(coil ? [{ ...coil, coil: true }] : []),
  ].map((control) => ({
    id: control.id,
    label: control.label,
    minimumKey: control.minimum.key,
    currentKey: control.current.key,
    minimumIndex: control.minimumIndex,
    tiers: control.tiers.map((option) => option.key),
    ...(control.numeric ? { numeric: control.numeric } : {}),
    ...("coil" in control ? { coil: true } : {}),
  }));
  return { expected, runtimeVariant: runtime !== undefined };
}
