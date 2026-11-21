import type { z } from "zod";
import {
  applyMachineHandlerToRecipe,
  getRecipeCoilTierControl,
  getRecipeMachineConfigTierControls,
  type MachineConfigTierControl,
} from "@/lib/model/recipe-rules";
import { getVoltageTierIndex, isVoltageTierName } from "@/lib/model/tiers";
import type { FactoryNode, MachineHandler, Recipe } from "@/lib/model/types";
import { bannedSet } from "./constraints";
import { steamAllowed, unpoweredKind, type PowerSource } from "./power-source";
import { AgentApiError } from "./errors";
import { machinesOf, recipeHeat } from "./format";
import type { CardRequest, draftCard } from "./plan-schemas";
import { TIER_NAMES, type AgentConstraints } from "./schemas";

/**
 * Turning a card request into a card the planner would make: the recipe's machine (its handler), the tier the card
 * runs at and its dials, checked against what recipes/get offers for that recipe.
 *
 * Defaults: the machine is the recipe's first multiblock that runs it at or below the draft's `tierCap`, else its
 * first single block that does, never a steam or primitive machine unless the constraints allow them
 * (`steamAllowed`) or the request names one. The tier is the highest the machine is built at that is at or below the
 * draft's card tier (`cardTier`, else `tierCap`) and not below the recipe's minimum; with neither, or for a machine
 * that draws no EU on the recipe, the lowest. A
 * recipe nothing can run at or below the cap is tier_too_low. A recipe whose tier is not a voltage tier (a crafting
 * table, a furnace's "NONE") keeps its own tier and is never capped. Steam and primitive machines count as ULV for
 * every cap whatever their tier reads, and keep that tier: they do not overclock.
 */

const CAP_INDEX_NONE = TIER_NAMES.length - 1;

interface MachineFit {
  handler: MachineHandler;
  applied: Recipe;
  /** The lowest tier the machine runs the recipe at, at or below the cap; undefined when none. */
  lowest?: string;
  /** It burns steam or is primitive. */
  unpowered?: PowerSource;
}

function tierIndex(tier: string): number {
  return getVoltageTierIndex(tier as Parameters<typeof getVoltageTierIndex>[0]);
}

/** The tiers the machine can be built at for this recipe, lowest first (voltage tiers only). */
function allowedTiers(applied: Recipe): string[] {
  const min = String(applied.minimumTier);
  if (!isVoltageTierName(min)) {
    return [min];
  }
  const minIndex = tierIndex(min);
  if (applied.availableTiers?.length) {
    return applied.availableTiers.filter((tier) => isVoltageTierName(tier) && tierIndex(tier) >= minIndex);
  }
  const max = applied.maximumTier && isVoltageTierName(applied.maximumTier) ? tierIndex(applied.maximumTier) : CAP_INDEX_NONE;
  return TIER_NAMES.slice(minIndex, max + 1);
}

function fit(recipe: Recipe, handler: MachineHandler, capIndex: number): MachineFit {
  const handled = applyMachineHandlerToRecipe(recipe, { machineHandlerId: handler.id });
  // A handler that states no minimum runs the recipe at the recipe's own.
  const applied = handled.minimumTier ? handled : { ...handled, minimumTier: recipe.minimumTier };
  const unpowered = unpoweredKind(handler, recipe);
  if (unpowered) {
    return { handler, applied, lowest: String(applied.minimumTier), unpowered };
  }
  const lowest = allowedTiers(applied).find((tier) => !isVoltageTierName(tier) || tierIndex(tier) <= capIndex);
  return { handler, applied, ...(lowest ? { lowest } : {}) };
}

function key(value: string): string {
  return value.trim().toLowerCase();
}

function handlerMatches(handler: MachineHandler, wanted: string): boolean {
  const want = key(wanted);
  return key(handler.id) === want || key(handler.label) === want;
}

function isBanned(handler: MachineHandler, banned: Set<string>): boolean {
  return banned.has(key(handler.id)) || banned.has(key(handler.label)) || banned.has(key(handler.machineType));
}

function chooseMachine(recipe: Recipe, request: CardRequest, constraints: AgentConstraints | undefined): MachineFit {
  const capIndex = constraints?.tierCap ? tierIndex(constraints.tierCap) : CAP_INDEX_NONE;
  const banned = bannedSet(constraints);
  const handlers = machinesOf(recipe);
  if (request.machine) {
    const handler = handlers.find((entry) => handlerMatches(entry, request.machine!));
    if (!handler) {
      throw new AgentApiError(
        "bad_request",
        `"${request.machine}" cannot run this recipe. Machines: ${handlers.map((entry) => `${entry.label} (${entry.id})`).join(", ")}.`,
      );
    }
    if (isBanned(handler, banned)) {
      throw new AgentApiError("bad_request", `${handler.label} is banned by the draft's constraints.`);
    }
    return fit(recipe, handler, capIndex);
  }
  const open = handlers.filter((handler) => !isBanned(handler, banned));
  if (open.length === 0) {
    throw new AgentApiError("bad_request", "Every machine that runs this recipe is banned by the draft's constraints.");
  }
  const all = open.map((handler) => fit(recipe, handler, capIndex));
  const fits = steamAllowed(constraints) ? all : all.filter((entry) => !entry.unpowered);
  if (fits.length === 0) {
    throw new AgentApiError(
      "bad_request",
      `Only steam or primitive machines run this recipe (${all.map((entry) => entry.handler.label).join(", ")}): name one in machine, or set constraints.steam.`,
    );
  }
  const chosen =
    fits.find((entry) => entry.lowest && entry.handler.kind === "multiblock") ??
    fits.find((entry) => entry.lowest && (entry.handler.kind ?? "single") === "single") ??
    fits.find((entry) => entry.lowest);
  if (!chosen) {
    const lowest = fits.map((entry) => String(entry.applied.minimumTier)).sort((a, b) => tierIndex(a) - tierIndex(b))[0];
    throw new AgentApiError(
      "tier_too_low",
      `Nothing runs this recipe at or below ${constraints?.tierCap}: it needs ${lowest} at least.`,
    );
  }
  return chosen;
}

/** The tier a card defaults to: the highest the machine is built at, at or below the card tier, else the lowest. */
function defaultTier(machine: MachineFit, constraints: AgentConstraints | undefined, cardTier: string | undefined): string {
  const lowest = machine.lowest!;
  const cap = constraints?.tierCap;
  const wanted = cardTier && cap ? (tierIndex(cardTier) <= tierIndex(cap) ? cardTier : cap) : (cardTier ?? cap);
  // A machine that draws no EU (a cauldron, a generator's fuel page) gains nothing from a higher tier.
  if (!wanted || machine.unpowered || !isVoltageTierName(lowest) || !(machine.applied.eut > 0)) {
    return lowest;
  }
  const below = allowedTiers(machine.applied).filter((tier) => isVoltageTierName(tier) && tierIndex(tier) <= tierIndex(wanted));
  // A recipe whose minimum is above the card tier but within the cap runs at its minimum.
  return below.at(-1) ?? lowest;
}

function chooseTier(
  machine: MachineFit,
  request: CardRequest,
  constraints: AgentConstraints | undefined,
  cardTier: string | undefined,
): string {
  const { applied, handler } = machine;
  const min = String(applied.minimumTier);
  if (!request.tier) {
    if (!machine.lowest) {
      throw new AgentApiError(
        "tier_too_low",
        `${handler.label} needs ${min} for this recipe, above the draft's tierCap ${constraints?.tierCap}.`,
      );
    }
    return defaultTier(machine, constraints, cardTier);
  }
  const tier = request.tier;
  if (machine.unpowered && tier !== min) {
    throw new AgentApiError(
      "bad_request",
      `${handler.label} ${machine.unpowered === "steam" ? "burns steam" : "is primitive"} and has no voltage tier (it runs as ${min}).`,
    );
  }
  if (machine.unpowered) {
    return tier;
  }
  if (!isVoltageTierName(min)) {
    throw new AgentApiError("bad_request", `${handler.label} has no voltage tier for this recipe (it runs at ${min}).`);
  }
  if (tierIndex(tier) < tierIndex(min)) {
    throw new AgentApiError("tier_too_low", `${handler.label} needs ${min} at least for this recipe, not ${tier}.`);
  }
  if (constraints?.tierCap && tierIndex(tier) > tierIndex(constraints.tierCap)) {
    throw new AgentApiError("bad_request", `${tier} is above the draft's tierCap ${constraints.tierCap}.`);
  }
  const allowed = allowedTiers(applied);
  if (!allowed.includes(tier)) {
    throw new AgentApiError("bad_request", `${handler.label} is built at ${allowed.join(", ")} only, not ${tier}.`);
  }
  return tier;
}

interface DialChoice {
  coilTier?: string;
  machineConfigTiers?: Record<string, string>;
}

function dialValueOk(control: MachineConfigTierControl, value: string | number): string {
  if (control.numeric) {
    const number = typeof value === "number" ? value : Number(value);
    const { min, max } = control.numeric;
    if (!Number.isFinite(number) || number < min || (max !== undefined && number > max)) {
      throw new AgentApiError(
        "bad_request",
        `Dial ${control.id} takes a number from ${min}${max !== undefined ? ` to ${max}` : " up"}.`,
      );
    }
    return String(number);
  }
  const index = control.tiers.findIndex((option) => option.key === String(value));
  if (index === -1) {
    throw new AgentApiError(
      "bad_request",
      `Dial ${control.id} takes one of: ${control.tiers.map((option) => option.key).join(", ")}.`,
    );
  }
  if (index < control.minimumIndex) {
    throw new AgentApiError("bad_request", `Dial ${control.id} is ${control.minimum.key} at least for this recipe.`);
  }
  return String(value);
}

function chooseDials(applied: Recipe, request: CardRequest): DialChoice {
  const wanted = Object.entries(request.dials ?? {});
  if (wanted.length === 0) {
    return {};
  }
  const coil = getRecipeCoilTierControl(applied, {});
  const controls = getRecipeMachineConfigTierControls(applied, {});
  const all = coil ? [coil, ...controls] : controls;
  const choice: DialChoice = {};
  for (const [id, value] of wanted) {
    const control = all.find((entry) => entry.id === id);
    if (!control) {
      throw new AgentApiError(
        "bad_request",
        all.length
          ? `No dial "${id}" on this machine. Dials: ${all.map((entry) => entry.id).join(", ")}.`
          : `This machine has no dials.`,
      );
    }
    const stored = dialValueOk(control, value);
    if (control === coil) {
      choice.coilTier = stored;
    } else {
      choice.machineConfigTiers = { ...choice.machineConfigTiers, [id]: stored };
    }
  }
  return choice;
}

export interface PlannedCard {
  recipe: Recipe;
  node: Omit<FactoryNode, "id" | "position">;
  handler: MachineHandler;
}

/**
 * The card a request makes, or the error that says why it cannot be made. Pure: the draft is not touched. `cardTier`
 * is the draft's (plans/create); without it a card defaults to the highest tier at or below `tierCap`.
 */
export function planCard(
  recipe: Recipe,
  request: CardRequest,
  constraints: AgentConstraints | undefined,
  cardTier?: string,
): PlannedCard {
  const machine = chooseMachine(recipe, request, constraints);
  const tier = chooseTier(machine, request, constraints, cardTier);
  const dials = chooseDials(machine.applied, request);
  const isDefault = machinesOf(recipe)[0]?.id === machine.handler.id;
  return {
    recipe,
    handler: machine.handler,
    node: {
      recipeId: recipe.id,
      machineCount: 1,
      parallel: 1,
      ...(isDefault ? {} : { machineHandlerId: machine.handler.id }),
      overclockTier: tier,
      ...dials,
      enabled: true,
    },
  };
}

/** The card as the API shows it. */
export function describeCard(node: FactoryNode, recipe: Recipe | undefined): z.infer<typeof draftCard> {
  const handlers = recipe ? machinesOf(recipe) : [];
  const handler = handlers.find((entry) => entry.id === node.machineHandlerId) ?? handlers[0];
  const dials: Record<string, string> = { ...node.machineConfigTiers };
  if (node.coilTier) {
    dials.heatingCoil = node.coilTier;
  }
  const heat = recipe ? recipeHeat(recipe) : undefined;
  return {
    cardId: node.id,
    recipeId: node.recipeId,
    name: recipe?.name ?? node.recipeId,
    machine: { id: handler?.id ?? "", name: handler?.label ?? recipe?.machineType ?? "" },
    tier: String(node.overclockTier),
    ...(Object.keys(dials).length ? { dials } : {}),
    ...(heat !== undefined ? { heat } : {}),
  };
}
