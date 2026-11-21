import type { z } from "zod";
import { makeResourceHandleId } from "@/components/flow/resource-handles";
import { makeResourceKey } from "@/lib/model/resources";
import type { FactoryNode, Recipe } from "@/lib/model/types";
import { describeCard, planCard, type PlannedCard } from "./cards";
import { agentDatasetVersion, loadRecipe, resolveResource, withDataset } from "./dataset";
import { createDraft, draftExpiresAt, getDraft, touchDraft, type Draft, type DraftTarget } from "./drafts";
import { AgentApiError } from "./errors";
import { draftCardCap, importedCarrier, importedPins } from "./imported";
import { arrangeDraft } from "./layout";
import { fitList, jsonBytes, RESPONSE_AIM_BYTES } from "./size";
import {
  type draftTarget,
  type plansAddCardsRequest,
  type plansAddCardsResponse,
  type plansConnectRequest,
  type plansConnectResponse,
  type plansCreateRequest,
  type plansCreateResponse,
  type plansDisconnectRequest,
  type plansDisconnectResponse,
  type plansGetRequest,
  type plansGetResponse,
  type plansRemoveCardsRequest,
  type plansRemoveCardsResponse,
  type plansSetTargetsRequest,
  type plansSetTargetsResponse,
} from "./plan-schemas";
import { TIER_NAMES } from "./schemas";
import { autoConnect, connect, describeWire, disconnect } from "./wiring";

/**
 * The draft endpoints other than solve. Each validates everything first and changes the draft only when the whole
 * request is good, so a refused batch leaves the draft as it was.
 */

/** The card that carries each target: the first card whose main (first) output it is, else the first that makes it. */
export function targetCards(draft: Draft): Map<string, string> {
  const recipes = new Map(draft.project.recipes.map((recipe) => [recipe.id, recipe]));
  const carriers = new Map<string, string>();
  for (const target of draft.targets) {
    const makes = (node: FactoryNode, mainOnly: boolean) => {
      const outputs = recipes.get(node.recipeId)?.outputs ?? [];
      return (mainOnly ? outputs.slice(0, 1) : outputs).some(
        (output) => output.kind === target.kind && output.id === target.id,
      );
    };
    const carrier =
      importedCarrier(draft, makeResourceKey(target.kind, target.id)) ??
      (draft.project.nodes.find((node) => makes(node, true)) ?? draft.project.nodes.find((node) => makes(node, false)))
        ?.id;
    if (carrier) {
      carriers.set(makeResourceKey(target.kind, target.id), carrier);
    }
  }
  return carriers;
}

/**
 * Writes the targets into the plan as plan mode's own target: a port rate on the carrying card's output row
 * (`FactoryNode.portRates`, keyed by the canonical handle), which pins that card's extent exactly. The same rule as
 * the plan board's conversion of an old plan's target ("the first card making the resource").
 */
export function applyTargets(draft: Draft): void {
  const carriers = targetCards(draft);
  const rates = new Map<string, Record<string, number>>();
  for (const target of draft.targets) {
    const cardId = carriers.get(makeResourceKey(target.kind, target.id));
    if (cardId) {
      rates.set(cardId, {
        ...rates.get(cardId),
        [makeResourceHandleId("output", { kind: target.kind, id: target.id })]: target.rate,
      });
    }
  }
  draft.project = {
    ...draft.project,
    nodes: draft.project.nodes.map((node) => {
      const pinned = importedPins(draft, node.id);
      const own = rates.get(node.id);
      const portRates = own || pinned ? { ...pinned, ...own } : undefined;
      if (portRates) {
        return { ...node, portRates };
      }
      if (node.portRates) {
        const { portRates: _dropped, ...rest } = node;
        return rest;
      }
      return node;
    }),
  };
}

function describeTargets(draft: Draft): z.infer<typeof draftTarget>[] {
  const carriers = targetCards(draft);
  return draft.targets.map((target) => {
    const cardId = carriers.get(makeResourceKey(target.kind, target.id));
    return { id: target.id, kind: target.kind, name: target.name, rate: target.rate, ...(cardId ? { cardId } : {}) };
  });
}

/** After any change to cards or wires: targets re-seated, cards arranged, the draft kept another 24 hours. */
function settle(draft: Draft): void {
  applyTargets(draft);
  draft.project = arrangeDraft(draft.project);
  touchDraft(draft);
}

function prefixed(index: number, error: unknown): unknown {
  return error instanceof AgentApiError ? new AgentApiError(error.code, `cards[${index}]: ${error.message}`) : error;
}

export async function createPlan(body: z.infer<typeof plansCreateRequest>): Promise<z.infer<typeof plansCreateResponse>> {
  const cap = body.constraints?.tierCap;
  if (body.cardTier && cap && TIER_NAMES.indexOf(body.cardTier) > TIER_NAMES.indexOf(cap)) {
    throw new AgentApiError("bad_request", `cardTier ${body.cardTier} is above the tierCap ${cap}.`);
  }
  const dataset = await withDataset(() => agentDatasetVersion());
  const draft = createDraft({ name: body.name, dataset, constraints: body.constraints, cardTier: body.cardTier });
  return {
    dataset,
    planId: draft.id,
    name: draft.name,
    expiresAt: draftExpiresAt(draft),
    ...(draft.constraints ? { constraints: draft.constraints } : {}),
    ...(draft.cardTier ? { cardTier: draft.cardTier } : {}),
  };
}

export async function addCards(
  body: z.infer<typeof plansAddCardsRequest>,
): Promise<z.infer<typeof plansAddCardsResponse>> {
  const draft = getDraft(body.planId);
  const tooBig = () => {
    if (draft.project.nodes.length + body.cards.length > draftCardCap(draft)) {
      throw new AgentApiError(
        "plan_too_big",
        `A draft holds at most ${draftCardCap(draft)} cards; this one has ${draft.project.nodes.length}.`,
      );
    }
  };
  tooBig();
  const planned: PlannedCard[] = [];
  const loaded = new Map<string, Recipe>();
  for (const [index, request] of body.cards.entries()) {
    try {
      const recipe =
        loaded.get(request.recipeId) ?? (await withDataset(() => loadRecipe(draft.dataset, request.recipeId)));
      loaded.set(recipe.id, recipe);
      planned.push(planCard(recipe, request, draft.constraints, draft.cardTier));
    } catch (error) {
      throw prefixed(index, error);
    }
  }
  // The recipes loaded while other calls could run: check the draft again before changing it.
  getDraft(body.planId);
  tooBig();

  const known = new Set(draft.project.recipes.map((recipe) => recipe.id));
  const recipes = [...draft.project.recipes];
  const nodes = [...draft.project.nodes];
  const newIds: string[] = [];
  for (const card of planned) {
    if (!known.has(card.recipe.id)) {
      known.add(card.recipe.id);
      recipes.push(card.recipe);
    }
    const id = `c${draft.nextCard}`;
    draft.nextCard += 1;
    newIds.push(id);
    nodes.push({ ...card.node, id, position: { x: 0, y: 0 } });
  }
  draft.project = { ...draft.project, recipes, nodes };
  const wires = autoConnect(draft, newIds);
  settle(draft);

  const byId = new Map(draft.project.recipes.map((recipe) => [recipe.id, recipe]));
  return {
    planId: draft.id,
    cards: newIds.map((id) => {
      const node = draft.project.nodes.find((entry) => entry.id === id)!;
      return describeCard(node, byId.get(node.recipeId));
    }),
    wires: wires.map((edge) => describeWire(draft, edge)),
    cardCount: draft.project.nodes.length,
  };
}

export async function removeCards(
  body: z.infer<typeof plansRemoveCardsRequest>,
): Promise<z.infer<typeof plansRemoveCardsResponse>> {
  const draft = getDraft(body.planId);
  const ids = [...new Set(body.cardIds)];
  const missing = ids.filter((id) => !draft.project.nodes.some((node) => node.id === id));
  if (missing.length) {
    throw new AgentApiError("bad_request", `No card ${missing.join(", ")} in this draft.`);
  }
  const gone = new Set(ids);
  const nodes = draft.project.nodes.filter((node) => !gone.has(node.id));
  const edges = draft.project.edges.filter((edge) => !gone.has(edge.source) && !gone.has(edge.target));
  const removedWires = draft.project.edges.filter((edge) => !edges.includes(edge));
  for (const edge of removedWires) {
    draft.manual.delete(edge.id);
  }
  for (const key of [...draft.cut]) {
    const [source, target] = key.split(">");
    if (gone.has(source!) || gone.has(target!)) {
      draft.cut.delete(key);
    }
  }
  const used = new Set(nodes.map((node) => node.recipeId));
  draft.project = {
    ...draft.project,
    nodes,
    edges,
    recipes: draft.project.recipes.filter((recipe) => used.has(recipe.id)),
  };
  settle(draft);
  return {
    planId: draft.id,
    removed: ids,
    wiresRemoved: removedWires.length,
    cardCount: nodes.length,
    targets: describeTargets(draft),
  };
}

export async function setTargets(
  body: z.infer<typeof plansSetTargetsRequest>,
): Promise<z.infer<typeof plansSetTargetsResponse>> {
  const draft = getDraft(body.planId);
  const targets: DraftTarget[] = [];
  for (const [index, entry] of body.targets.entries()) {
    const resource = await withDataset(() => resolveResource(draft.dataset, entry.resource));
    if (targets.some((target) => target.kind === resource.kind && target.id === resource.id)) {
      throw new AgentApiError("bad_request", `targets[${index}]: ${resource.name} is listed twice.`);
    }
    targets.push({ ...resource, rate: entry.rate });
  }
  getDraft(body.planId);
  const previous = draft.targets;
  draft.targets = targets;
  const carriers = targetCards(draft);
  const homeless = targets.find((target) => !carriers.has(makeResourceKey(target.kind, target.id)));
  if (homeless) {
    draft.targets = previous;
    throw new AgentApiError(
      "no_recipe",
      `No card in this draft makes ${homeless.name}: add a recipe that makes it first (recipes/search with makes).`,
    );
  }
  applyTargets(draft);
  touchDraft(draft);
  return { planId: draft.id, targets: describeTargets(draft) };
}

export async function connectCards(
  body: z.infer<typeof plansConnectRequest>,
): Promise<z.infer<typeof plansConnectResponse>> {
  const draft = getDraft(body.planId);
  const from = await withDataset(() => resolveResource(draft.dataset, body.from.resource));
  const to = body.to.resource ? await withDataset(() => resolveResource(draft.dataset, body.to.resource!)) : undefined;
  getDraft(body.planId);
  const { edge, created } = connect(draft, { fromCard: body.from.cardId, from, toCard: body.to.cardId, to });
  if (created) {
    settle(draft);
  }
  return { planId: draft.id, wire: describeWire(draft, edge), created };
}

export async function disconnectWire(
  body: z.infer<typeof plansDisconnectRequest>,
): Promise<z.infer<typeof plansDisconnectResponse>> {
  const draft = getDraft(body.planId);
  disconnect(draft, body.wireId);
  settle(draft);
  return { planId: draft.id, removed: body.wireId };
}

/**
 * The draft, compact. `fields` picks the parts and `cardIds` narrows it to some cards and the wires that touch them.
 * An answer over the 12 KB aim drops wires from its end, then cards, and says `truncated: true`; ask again with
 * `cardIds` or `fields` for the rest.
 */
export async function getPlan(body: z.infer<typeof plansGetRequest>): Promise<z.infer<typeof plansGetResponse>> {
  const draft = getDraft(body.planId);
  const fields = new Set(body.fields ?? ["cards", "wires", "targets"]);
  const only = body.cardIds ? new Set(body.cardIds) : undefined;
  const missing = body.cardIds?.filter((id) => !draft.project.nodes.some((node) => node.id === id)) ?? [];
  if (missing.length) {
    throw new AgentApiError("bad_request", `No card ${missing.join(", ")} in this draft.`);
  }
  const recipes = new Map(draft.project.recipes.map((recipe) => [recipe.id, recipe]));
  const cards = fields.has("cards")
    ? draft.project.nodes
        .filter((node) => !only || only.has(node.id))
        .map((node) => describeCard(node, recipes.get(node.recipeId)))
    : undefined;
  const wires = fields.has("wires")
    ? draft.project.edges
        .filter((edge) => !only || only.has(edge.source) || only.has(edge.target))
        .map((edge) => describeWire(draft, edge))
    : undefined;
  const head = {
    dataset: draft.dataset,
    planId: draft.id,
    name: draft.name,
    expiresAt: draftExpiresAt(draft),
    ...(draft.constraints ? { constraints: draft.constraints } : {}),
    ...(draft.cardTier ? { cardTier: draft.cardTier } : {}),
    cardCount: draft.project.nodes.length,
    wireCount: draft.project.edges.length,
    ...(fields.has("targets") ? { targets: describeTargets(draft) } : {}),
  };
  const whole = { ...head, ...(cards ? { cards } : {}), ...(wires ? { wires } : {}) };
  if (!wires || jsonBytes(whole) <= RESPONSE_AIM_BYTES) {
    return cards && jsonBytes(whole) > RESPONSE_AIM_BYTES
      ? fitList(cards, (kept, truncated) => ({ ...head, cards: kept, ...(truncated ? { truncated: true } : {}) }))
      : whole;
  }
  const withCards = { ...head, ...(cards ? { cards } : {}) };
  if (cards && jsonBytes({ ...withCards, wires: [] }) > RESPONSE_AIM_BYTES) {
    return fitList(cards, (kept) => ({ ...head, cards: kept, wires: [], truncated: true }));
  }
  return fitList(wires, (kept, truncated) => ({ ...withCards, wires: kept, ...(truncated ? { truncated: true } : {}) }));
}
