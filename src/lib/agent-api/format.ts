import { getMachineBehaviour, HEAT_OVERCLOCK } from "@/lib/machines/machine-table";
import { getRecipeMachineHandlers, getRecipeSpecialValue } from "@/lib/model/recipe-rules";
import type { Recipe, RecipeInput, RecipeOutput } from "@/lib/model/types";
import type { RecipeSummary } from "@/lib/datasets/types";
import type { compactRecipe, recipeInput, recipeOutput } from "./schemas";
import type { z } from "zod";

/** At most this many alternatives are listed for an "any of" input in a detailed answer. */
const MAX_ALTERNATIVES = 5;

type RecipeLike = RecipeSummary | Recipe;

/** Rounds away float noise from ticks/20 and amount products without hiding real fractions. */
export function round(value: number): number {
  return Number.isFinite(value) ? Number(value.toFixed(6)) : value;
}

export function secondsOf(durationTicks: number): number {
  return round(durationTicks / 20);
}

export function nameOf(resource: { id: string; displayName?: string }): string {
  return resource.displayName || resource.id;
}

export function formatInput(input: RecipeInput, detail: boolean): z.infer<typeof recipeInput> {
  const out: z.infer<typeof recipeInput> = {
    id: input.id,
    kind: input.kind,
    name: nameOf(input),
    amount: round(input.amount),
    consumed: input.consumed !== false,
  };
  if (detail && input.alternatives?.length) {
    out.alternatives = input.alternatives.slice(0, MAX_ALTERNATIVES).map((alternative) => ({
      id: alternative.id,
      kind: alternative.kind,
      name: nameOf(alternative),
    }));
    if (input.alternatives.length > MAX_ALTERNATIVES) {
      out.moreAlternatives = input.alternatives.length - MAX_ALTERNATIVES;
    }
  }
  return out;
}

export function formatOutput(output: RecipeOutput): z.infer<typeof recipeOutput> {
  const out: z.infer<typeof recipeOutput> = {
    id: output.id,
    kind: output.kind,
    name: nameOf(output),
    amount: round(output.amount),
  };
  if (typeof output.chance === "number" && output.chance > 0 && output.chance < 1) {
    out.chance = round(output.chance);
  }
  return out;
}

/** True when the machine's overclocks read the recipe's special value as the heat it needs (EBF and kin). */
export function isHeatMachine(machineType: string | undefined): boolean {
  return getMachineBehaviour(machineType)?.overclock === HEAT_OVERCLOCK;
}

/**
 * The heat in K a recipe needs, when it runs on a heat machine (its default machine or any other that can run it):
 * the recipe's special value. Undefined for every other recipe.
 */
export function recipeHeat(recipe: RecipeLike): number | undefined {
  const heatMachine =
    isHeatMachine(recipe.machineType) ||
    (recipe.machineHandlers ?? []).some((handler) => isHeatMachine(handler.machineType));
  if (!heatMachine) {
    return undefined;
  }
  const special = getRecipeSpecialValue(recipe) ?? recipe.specialValue;
  return typeof special === "number" && special > 0 ? special : undefined;
}

export function recipeMapOf(recipe: RecipeLike): string {
  return ("recipeMap" in recipe && recipe.recipeMap) || recipe.source?.recipeMap || recipe.machineType;
}

/** The machines a recipe can run on, default first, as the planner offers them. */
export function machinesOf(recipe: RecipeLike) {
  return getRecipeMachineHandlers(recipe as Recipe);
}

function circuitName(recipe: RecipeLike): string | undefined {
  const circuit = recipe.programmedCircuit;
  if (!circuit) {
    return undefined;
  }
  const input = recipe.inputs.find((entry) => entry.id === circuit);
  return input ? nameOf(input) : circuit;
}

export function formatCompactRecipe(
  recipe: RecipeLike,
  machineName: string,
  detail: boolean,
): z.infer<typeof compactRecipe> {
  const heat = recipeHeat(recipe);
  const circuit = circuitName(recipe);
  const out: z.infer<typeof compactRecipe> = {
    recipeId: recipe.id,
    name: recipe.name,
    machine: machineName,
    minTier: String(recipe.minimumTier),
    euT: recipe.eut,
    seconds: secondsOf(recipe.durationTicks),
    ...(heat !== undefined ? { heat } : {}),
    ...(circuit ? { circuit } : {}),
    inputs: recipe.inputs.map((input) => formatInput(input, detail)),
    outputs: recipe.outputs.map(formatOutput),
  };
  if (detail) {
    out.map = recipeMapOf(recipe);
    out.machines = machinesOf(recipe).map((handler) => ({
      id: handler.id,
      name: handler.label,
      kind: handler.kind ?? "single",
    }));
  }
  return out;
}
