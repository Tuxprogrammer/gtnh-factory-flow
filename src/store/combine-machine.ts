import { getFusionMachine } from "@/lib/machines/fusion";
import { getSharedMachineHandlers, listNodeSections } from "@/lib/model/shared-machine";
import {
  applyMachineHandlerToRecipe,
  getRecipeCoilTierControl,
  getRecipeMachineConfigTierControls,
  getSelectedMachineHandler,
  type MachineConfigTierControl,
} from "@/lib/model/recipe-rules";
import { getVoltageTierIndex, getVoltageTierMaxEuT, isVoltageTierName } from "@/lib/model/tiers";
import type { FactoryNode, MachineHandler, MachineTier, Recipe } from "@/lib/model/types";
import { carryMachineVoltage } from "@/lib/solver/hatch-input";
import { getNodeRunTier, getPowerPoolEuT, isMultiblockRecipe } from "@/lib/solver/power";
import { getNodePowerReport } from "@/lib/solver/power-report";

type Lookup = (id: string) => Recipe | undefined;
type VoltageTier = Exclude<MachineTier, "DEMO">;

/**
 * The machines that run every recipe on every card, in one order whatever
 * order the cards come in: by their summed position in the cards' own lists
 * (the dataset's order when the lists agree), then by id.
 */
export function sharedCombineHandlers(cards: FactoryNode[], lookup: Lookup): MachineHandler[] {
  const lists = cards.map((card) => getSharedMachineHandlers(card, lookup));
  const rank = (id: string) =>
    lists.reduce((sum, list) => sum + list.findIndex((handler) => handler.id === id), 0);
  return (lists[0] ?? [])
    .filter((handler) => lists.every((list) => list.some((entry) => entry.id === handler.id)))
    .sort((a, b) => rank(a.id) - rank(b.id) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

/**
 * Which selected card hosts a Combine, and which machine it runs. A card
 * that names a machine every recipe shares is the player's choice of
 * machine (a Mega Chemical Reactor among Large Chemical Reactor cards); one
 * naming the default machine counts after one naming another. When cards
 * name different machines, the one most cards name wins, ties to the shared
 * list's order. The host is the first card naming that machine, else the
 * first card; it keeps its place and id, while the machine's settings come
 * from every card (`combinedMachineSettings`).
 */
export function pickCombineHost(
  cards: FactoryNode[],
  handlers: MachineHandler[],
): { host: FactoryNode; handler: MachineHandler } {
  const fallback = handlers[0]!;
  const votes = new Map<string, number>();
  for (const card of cards) {
    if (card.machineHandlerId !== fallback.id && handlers.some((entry) => entry.id === card.machineHandlerId)) {
      votes.set(card.machineHandlerId!, (votes.get(card.machineHandlerId!) ?? 0) + 1);
    }
  }
  let handler = fallback;
  let best = 0;
  for (const entry of handlers) {
    const count = votes.get(entry.id) ?? 0;
    if (count > best) {
      best = count;
      handler = entry;
    }
  }
  const host = cards.find((card) => card.machineHandlerId === handler.id) ?? cards[0]!;
  return { host, handler };
}

const higherTier = (a: VoltageTier, b: VoltageTier): VoltageTier =>
  getVoltageTierIndex(b) > getVoltageTierIndex(a) ? b : a;

/** A config control's setting as a number that grows with the option. */
function settingRank(control: MachineConfigTierControl): number {
  if (control.numeric) return Number(control.current.key);
  return control.minimumIndex + control.tiers.findIndex((tier) => tier.key === control.current.key);
}

const NUMBER = /^-?\d+(\.\d+)?$/;

/**
 * Two stored settings no control reads, highest last: numbers by value (10 above 9), voltage tier names by tier (IV
 * above EV), anything else by its text. Numbers rank below tier names, and those below the rest, so the order is the
 * same whatever pairs are compared and the pick never follows card order.
 */
export function compareStoredSetting(a: string, b: string): number {
  const kind = (key: string) => (NUMBER.test(key) ? 0 : isVoltageTierName(key) ? 1 : 2);
  const ka = kind(a);
  const kb = kind(b);
  if (ka !== kb) return ka - kb;
  if (ka === 0) return Number(a) - Number(b) || (a < b ? -1 : a > b ? 1 : 0);
  if (ka === 1) return getVoltageTierIndex(a as VoltageTier) - getVoltageTierIndex(b as VoltageTier);
  return a < b ? -1 : a > b ? 1 : 0;
}

/**
 * The combined machine's settings, taken from every card and never from
 * whichever card hosts, so the same cards combine to the same machine in any
 * order: the highest tier and the largest supply any card ran on, and the
 * highest coil and machine setting any card ran each recipe at. Settings the
 * cards agree on come through as they are. `fitCombinedMachine` then raises
 * tier and amps until every recipe starts and gets one parallel.
 */
export function combinedMachineSettings(
  cards: FactoryNode[],
  handler: MachineHandler,
  lookup: Lookup,
): Partial<FactoryNode> {
  // Each card as it would run on the chosen machine.
  const onMachine = cards.flatMap((card) => {
    const primary = lookup(card.recipeId);
    if (!primary) return [];
    let node: FactoryNode = { ...card, machineHandlerId: handler.id };
    if (getSelectedMachineHandler(primary, card).id !== handler.id) {
      node = {
        ...node,
        ...carryMachineVoltage(
          { recipe: primary, node: card },
          { recipe: primary, machineHandlerId: handler.id },
        ),
      };
    }
    const sections = listNodeSections(node).flatMap(({ node: view }) => {
      const recipe = lookup(view.recipeId);
      return recipe ? [applyMachineHandlerToRecipe(recipe, view)] : [];
    });
    return [{ card, node, effective: applyMachineHandlerToRecipe(primary, node), sections }];
  });
  const first = onMachine[0];
  if (!first) return {};
  const settings: Partial<FactoryNode> = { machineHandlerId: handler.id };

  if (!getFusionMachine(first.effective.machineType)) {
    let tier = getNodeRunTier(first.effective, first.node);
    for (const entry of onMachine) tier = higherTier(tier, getNodeRunTier(entry.effective, entry.node));
    settings.overclockTier = tier;
    if (isMultiblockRecipe(first.effective) && onMachine.every((entry) => isMultiblockRecipe(entry.effective))) {
      let supply = 0;
      for (const entry of onMachine) {
        supply = Math.max(
          supply,
          getPowerPoolEuT(entry.effective, entry.node, getNodeRunTier(entry.effective, entry.node)),
        );
      }
      const voltage = getVoltageTierMaxEuT(tier);
      const typed = onMachine.every((entry) => entry.node.powerInputMode === "eut");
      const amps = typed ? supply / voltage : Math.max(1, Math.ceil(supply / voltage));
      settings.hatchVoltageTier = tier;
      settings.hatchAmps = amps;
      settings.powerEuT = voltage * amps;
      settings.powerInputMode = typed
        ? "eut"
        : onMachine.some((entry) => entry.node.powerInputMode !== undefined)
          ? "amps"
          : undefined;
      const hatchTypes = new Set(onMachine.map((entry) => entry.node.energyHatchType));
      settings.energyHatchType = hatchTypes.size === 1 ? first.node.energyHatchType : undefined;
    }
  }

  // Coils and machine settings: the highest any card ran a recipe at, which
  // is at least every recipe's own minimum.
  let coil: { key: string; rank: number } | undefined;
  const config = new Map<string, { key: string; rank: number }>();
  for (const entry of onMachine) {
    for (const recipe of entry.sections) {
      const control = getRecipeCoilTierControl(recipe, entry.node);
      if (control) {
        const rank = settingRank(control);
        if (!coil || rank > coil.rank) coil = { key: control.current.key, rank };
      }
      for (const option of getRecipeMachineConfigTierControls(recipe, entry.node)) {
        const rank = settingRank(option);
        const held = config.get(option.id);
        if (!held || rank > held.rank) config.set(option.id, { key: option.current.key, rank });
      }
    }
  }
  settings.coilTier = coil && cards.some((card) => card.coilTier !== undefined) ? coil.key : undefined;
  const stored = new Map<string, string>();
  for (const card of cards) {
    for (const [id, key] of Object.entries(card.machineConfigTiers ?? {})) {
      // A setting no control on this machine reads: the highest key, so
      // the pick does not follow card order either.
      const held = stored.get(id);
      if (held === undefined || compareStoredSetting(key, held) > 0) stored.set(id, key);
    }
  }
  const machineConfigTiers: Record<string, string> = {};
  for (const [id, key] of stored) machineConfigTiers[id] = config.get(id)?.key ?? key;
  settings.machineConfigTiers = stored.size > 0 ? machineConfigTiers : undefined;
  return settings;
}

/** The host with the combined settings; a setting left undefined is removed. */
export function withCombinedSettings(node: FactoryNode, settings: Partial<FactoryNode>): FactoryNode {
  const next: FactoryNode = { ...node, ...settings };
  for (const key of Object.keys(settings) as Array<keyof FactoryNode>) {
    if (settings[key] === undefined) delete next[key];
  }
  return next;
}

/**
 * The combined card as a player could build it: the chosen machine, stored
 * only when the dataset names it (a map with no exported machines runs its
 * made-up default, which a load would drop), whole hatch amps, and a tier
 * and supply every section's recipe starts on. Only raises, never lowers.
 */
export function fitCombinedMachine(
  card: FactoryNode,
  handler: MachineHandler,
  lookup: Lookup,
): FactoryNode {
  const primary = lookup(card.recipeId);
  if (!primary) {
    return card;
  }
  let node: FactoryNode = { ...card };
  // Moving onto another machine keeps the voltage the host ran at.
  if (getSelectedMachineHandler(primary, card).id !== handler.id) {
    node = {
      ...node,
      ...carryMachineVoltage(
        { recipe: primary, node: card },
        { recipe: primary, machineHandlerId: handler.id },
      ),
    };
  }
  if ((primary.machineHandlers ?? []).some((entry) => entry.id === handler.id)) {
    node.machineHandlerId = handler.id;
  } else {
    delete node.machineHandlerId;
  }
  const recipes = listNodeSections(node)
    .map(({ node: view }) => lookup(view.recipeId))
    .filter((recipe): recipe is Recipe => recipe !== undefined);
  const effective = applyMachineHandlerToRecipe(primary, node);
  if (!isMultiblockRecipe(effective)) {
    // Each section runs at the card's tier floored at its own minimum; the
    // card names the machine that runs them all.
    let top = getNodeRunTier(effective, node);
    for (const recipe of recipes) {
      const tier = getNodeRunTier(applyMachineHandlerToRecipe(recipe, node), node);
      if (getVoltageTierIndex(tier) > getVoltageTierIndex(top)) top = tier;
    }
    return top === node.overclockTier ? node : { ...node, overclockTier: top };
  }
  if (
    node.hatchVoltageTier === undefined ||
    node.hatchAmps === undefined ||
    node.powerInputMode === "eut"
  ) {
    return node;
  }
  let tier = node.hatchVoltageTier;
  let amps = Math.max(1, Math.ceil(node.hatchAmps));
  const reports = () =>
    recipes.map((recipe) =>
      getNodePowerReport(recipe, { ...node, hatchVoltageTier: tier, hatchAmps: amps }),
    );
  // A recipe the hatches' voltage cannot start (more than one tier above
  // them, or a structural minimum) needs higher hatches; amps cannot buy it.
  for (const report of reports()) {
    if (report.state === "over-tier" && !report.recipeGateReason) {
      if (getVoltageTierIndex(report.minimumTier) > getVoltageTierIndex(tier))
        tier = report.minimumTier;
    }
  }
  // Then enough whole amps for one parallel of every recipe.
  for (const report of reports()) {
    if (report.state === "under-powered") {
      amps = Math.max(amps, Math.ceil(report.singleDrawEuT / getVoltageTierMaxEuT(tier)));
    }
  }
  return {
    ...node,
    hatchVoltageTier: tier,
    hatchAmps: amps,
    powerEuT: getVoltageTierMaxEuT(tier) * amps,
  };
}
