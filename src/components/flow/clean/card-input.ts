/**
 * The core's card input (`CardInput`, jvm/core/core-view) from the website's own card facts: what `cleanCardView`
 * makes a card's view from, written as the record the compiled core's `ViewExports` reads. The core's `CardViews.card`
 * over this input is held to `cleanCardView` over the same facts by card-shadow.test.ts and card-view.test.ts; the
 * board's switch (card-view.ts) writes it for every card it draws through the core. Pure: no store, no DOM.
 */
import type {
  CardInput,
  CardInputDials,
  CardInputMenu,
  CardInputPort,
} from "@/lib/core/records.generated";
import { getActivePowerDisplayUnit, getActiveRateUnit } from "@/lib/format/rate-unit";
import { isCustomRateRecipe } from "@/lib/model/custom-rate";
import { isFluidTankRecipe } from "@/lib/model/fluid-tank";
import { ownsItsRecipe } from "@/lib/model/owns-recipe";
import { isCropFarmRecipe } from "@/lib/model/passive-production";
import { pinRuleOf } from "@/lib/model/pin-rule";
import { isSharedMachineNode } from "@/lib/model/shared-machine";
import type { FactoryNode, Recipe } from "@/lib/model/types";
import { powerCardTier } from "@/lib/power/power-recipe";
import type { RailPort } from "../node-verdict";
import { circuitNumber, type CleanCardFacts } from "./clean-model";
import { glanceProduct } from "./glance";
import { cardLoop } from "./loops";
import { ROW_TYPE, mcTextWidth, measureClean, measureRowName } from "./text-measure";

const RULE: Record<ReturnType<typeof pinRuleOf>, CardInput["count"]["rule"]> = {
  any: "ANY",
  upTo: "UP_TO",
  exact: "EXACT",
};

const RATE: Record<ReturnType<typeof getActiveRateUnit>, CardInputDials["rate"]> = {
  tick: "TICK",
  second: "SECOND",
  minute: "MINUTE",
  hour: "HOUR",
  eu: "EU",
};

/** The menu with every recipe row allowed and no task boards. */
export const OPEN_MENU: CardInputMenu = {
  calm: false,
  locked: false,
  ownsRecipe: false,
  shared: false,
  holder: false,
  emptyCropFarm: false,
  tasks: false,
  readOnly: false,
};

/** The board's dials as the card reads them now: the rate unit, the power key, average or peak draw. */
export function cardDials(averageDraw: boolean): CardInputDials {
  return { rate: RATE[getActiveRateUnit()], power: getActivePowerDisplayUnit(), averageDraw };
}

/** The menu's facts for a card on an open board (`cardRecipeRows`' tests, by the record's fields). */
export function cardMenuFacts(
  recipe: Recipe,
  node: FactoryNode,
  board: { calm: boolean; locked: boolean; tasks: boolean },
): CardInputMenu {
  return {
    calm: board.calm,
    locked: board.locked,
    ownsRecipe: ownsItsRecipe(recipe),
    shared: isSharedMachineNode(node),
    holder: isCustomRateRecipe(recipe) || isFluidTankRecipe(recipe),
    emptyCropFarm: isCropFarmRecipe(recipe) && recipe.outputs.length === 0,
    tasks: board.tasks,
    // Only the mod has cards it shows but cannot edit (a website card it cannot run); every website card is editable.
    readOnly: false,
  };
}

function optional<T>(value: T | undefined | null): T | undefined {
  return value === null ? undefined : value;
}

/** Drops undefined keys: the wire has no nulls, a missing optional is a missing key. Runs per card per render. */
function clean<T extends object>(value: T): T {
  const out: Record<string, unknown> = {};
  for (const k in value) if (value[k] !== undefined) out[k] = value[k];
  return out as T;
}

function port(p: RailPort, node: Pick<FactoryNode, "portRates">): CardInputPort {
  const resource = p.resource as
    | { chance?: number; consumeChance?: number; alternatives?: unknown[] }
    | undefined;
  return clean({
    key: p.key,
    handleId: p.handleId,
    output: p.side === "output",
    name: p.displayName,
    kind: p.kind,
    resourceId: p.resourceId,
    perSecond: p.currentPerSecond,
    energyPerUnit: p.energyPerUnit,
    wired: p.connected,
    chance: resource?.chance,
    consumeChance: resource?.consumeChance,
    alternatives: resource?.alternatives?.length ?? 0,
    free: p.free === true,
    setRate: node.portRates?.[p.handleId],
  });
}

/** A shared machine's section carries `#rN` on its id; the card is the id before it (idle-trade.ts). */
const cardOf = (nodeId: string | undefined) => nodeId?.split("#r")[0];

/** The card's facts as the core's input record. */
export function cardInputOf(facts: CleanCardFacts, menu: CardInputMenu = OPEN_MENU): CardInput {
  const { projectNode: node, recipe, result, plan, rails, powerReport, steamReport } = facts;
  const id = node.id;
  const upTo = plan?.upTo?.[id];
  // Only the leftovers with a place on this card: the idle trade looks at no other (idle-trade.ts), and a card's input
  // must not grow with the plan.
  const leftovers = (plan?.leftovers ?? [])
    .filter((l) => l.bearers.some((b) => cardOf(b.nodeId) === id))
    .map((l) => ({
      bearers: l.bearers.map((b) =>
        clean({ nodeId: b.nodeId, side: b.side, share: b.share, resourceKey: b.resourceKey }),
      ),
    }));
  // Names of the cards the read-outs mention: the full cards upstream and the makers a leftover's places sit on.
  const names: Record<string, string> = {};
  const name = (cardId: string | undefined) => {
    if (cardId === undefined || cardId in names) return;
    const found = facts.cardName(cardId);
    if (found !== undefined) names[cardId] = found;
  };
  for (const up of upTo?.upstream ?? []) name(up);
  for (const l of leftovers) for (const b of l.bearers) name(cardOf(b.nodeId));
  const loop = cardLoop(plan?.loops, id);
  const product = glanceProduct(recipe.outputs);
  const forced = [
    ...(plan?.forced ?? [])
      .filter((f) => f.nodeId === id)
      .map((f) => ({ portKey: f.portKey, perSecond: f.perSecond })),
    ...(plan?.spills ?? [])
      .filter((s) => s.nodeId === id)
      .map((s) => ({ portKey: s.portKey, perSecond: s.perSecond, drawerId: s.drawerId })),
  ];
  return clean({
    nodeId: id,
    name: facts.name,
    hasPicker: facts.hasPicker,
    tier: facts.tierControl?.current,
    tierFixed: facts.tierControl?.fixed === true,
    hatchControl: facts.showHatchControl,
    power: powerReport
      ? clean({
          ok: powerReport.state === "ok",
          tier: powerReport.tier,
          amps: powerReport.amps,
          drawEuT: powerReport.drawEuT,
          parallels: powerReport.parallels,
          gateReason: powerReport.recipeGateReason,
          multiblock: powerReport.isMultiblock === true,
          hatchChip: powerReport.hatchChip,
        })
      : undefined,
    steam: steamReport
      ? {
          drawSteamPerTick: steamReport.drawSteamPerTick,
          highPressure: steamReport.highPressure === true,
          parallels: steamReport.parallels,
        }
      : undefined,
    parallel: facts.parallel,
    structuralParallel: facts.structuralParallel,
    pinned: facts.pinnedChips.map((c) =>
      clean({ id: c.id, label: c.label, value: c.value, icon: c.icon, warn: c.warn === true }),
    ),
    art: facts.art,
    glanceArt: facts.glanceArt,
    product: product
      ? { icon: product.image.replace(/^url\((.*)\)$/, "$1"), fluid: product.fluid }
      : undefined,
    circuit: facts.circuit
      ? clean({ icon: facts.circuit.resource?.iconPath, number: circuitNumber(facts.circuit) })
      : undefined,
    inputs: rails.inputs.map((p) => port(p, node)),
    outputs: rails.outputs.map((p) => port(p, node)),
    count: clean({
      rule: RULE[pinRuleOf(node, recipe)],
      pin: node.solvePin,
      needed: result?.theoreticalMachinesRequired ?? 0,
      utilization: result?.utilization,
      nodeParallel: node.parallel,
      customRate: isCustomRateRecipe(recipe as Recipe),
      unset:
        plan !== undefined &&
        pinRuleOf(node, recipe) === "any" &&
        ((plan.unanchored ?? []).includes(id) || (plan.unneeded ?? []).includes(id)),
    }),
    powerTier: powerCardTier(recipe as Recipe),
    upTo: upTo
      ? clean({
          capacity: upTo.capacity,
          reached: upTo.reached,
          reason: optional(upTo.reason),
          portKey: upTo.portKey,
          resourceKey: upTo.resourceKey,
          tied: upTo.tied ?? [],
          wants: upTo.wants,
          upstream: upTo.upstream ?? [],
        })
      : undefined,
    forced: forced.map((f) => clean(f)),
    loop: loop === "dead" ? "DEAD_LOOP" : loop === "clog" ? "CLOG_LOCK" : undefined,
    leftovers,
    turbine: facts.turbineCheck
      ? {
          turbines: facts.turbineCheck.turbines,
          flow: facts.turbineCheck.flow,
          setFlow: facts.turbineCheck.setFlow,
          optimal: facts.turbineCheck.optimal,
          unit: facts.turbineCheck.unit,
          euMade: facts.turbineCheck.euMade,
          euCounted: facts.turbineCheck.euCounted,
        }
      : undefined,
    cardNames: names,
    selected: facts.selected,
    menu,
  }) as CardInput;
}

/**
 * The website's text widths as the core asks them, by `TextStyle` name (jvm/core/core-view): the clean card's face
 * through text-measure.ts, the same calls `cleanCardView` makes, and Minecraft's glyph table for GAME.
 */
export const WEB_CARD_MEASURE = {
  width(style: string, text: string): number {
    const drawn = (type: { size: number; spacing: number }) =>
      text ? measureClean(text, type.size) + type.spacing * [...text].length : 0;
    switch (style) {
      case "LABEL":
        return measureClean(text, 8);
      case "ROW_FIGURE":
        return drawn(ROW_TYPE.figure);
      case "ROW_UNIT":
        return drawn(ROW_TYPE.unit);
      case "ROW_NAME":
        return measureRowName(text, false);
      case "ROW_NAME_SMALL":
        return measureRowName(text, true);
      case "GAME":
        return mcTextWidth(text);
      case "NAME_LARGE":
        return measureClean(text, 10);
      case "NAME_MEDIUM":
        return measureClean(text, 9);
      case "NAME_SMALL":
        return measureClean(text, 8);
      default:
        throw new Error(`no width for the text style ${style}`);
    }
  },
};
