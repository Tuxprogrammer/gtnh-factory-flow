import { listNodeSections } from "@/lib/model/shared-machine";
import { applyMachineHandlerToRecipe, getSelectedMachineHandler } from "@/lib/model/recipe-rules";
import { getVoltageTierIndex, getVoltageTierMaxEuT } from "@/lib/model/tiers";
import type { FactoryNode, MachineHandler, Recipe } from "@/lib/model/types";
import { carryMachineVoltage } from "@/lib/solver/hatch-input";
import { getNodeRunTier, isMultiblockRecipe } from "@/lib/solver/power";
import { getNodePowerReport } from "@/lib/solver/power-report";

type Lookup = (id: string) => Recipe | undefined;

/**
 * Which selected card hosts a Combine, and which machine it runs. A card
 * that names a machine every recipe shares is the player's choice of
 * machine (a Mega Chemical Reactor among Large Chemical Reactor cards), so
 * it hosts and its settings come along; one naming the default machine
 * counts after one naming another. With no such card, the first card hosts
 * the first shared machine.
 */
export function pickCombineHost(
  cards: FactoryNode[],
  handlers: MachineHandler[],
): { host: FactoryNode; handler: MachineHandler } {
  const shared = (card: FactoryNode) =>
    handlers.find((handler) => handler.id === card.machineHandlerId);
  const chosen =
    cards.find((card) => shared(card) && card.machineHandlerId !== handlers[0]!.id) ??
    cards.find((card) => shared(card));
  if (chosen) {
    return { host: chosen, handler: shared(chosen)! };
  }
  const host = cards[0]!;
  return { host, handler: handlers[0]! };
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
