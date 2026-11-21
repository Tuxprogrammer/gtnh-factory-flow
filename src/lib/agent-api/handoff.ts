import { randomUUID } from "node:crypto";
import type { z } from "zod";
import { makeResourceHandleId, parseResourceHandleId } from "@/components/flow/resource-handles";
import { decodePlanCode, encodePlanCode, planCodeLink } from "@/lib/import-export/plan-code";
import { normalizeProjectFuelProfiles } from "@/lib/model/fuels";
import { makeResourceKey } from "@/lib/model/resources";
import { factoryProjectSchema } from "@/lib/model/schemas";
import type {
  FactoryEdge,
  FactoryNode,
  FactoryPocket,
  FactoryProject,
  FactoryStorage,
  Recipe,
  ResourceKind,
} from "@/lib/model/types";
import { agentDatasetVersion, loadRecipe, resolveResource, withDataset } from "./dataset";
import { storeDelivery } from "./deliveries";
import type {
  plansDeliverRequest,
  plansDeliverResponse,
  plansDiffRequest,
  plansDiffResponse,
  plansImportRequest,
  plansImportResponse,
  ProposalPayload,
} from "./delivery-schemas";
import { createDraft, draftExpiresAt, draftNow, getDraft, touchDraft, type DraftTarget } from "./drafts";
import { AgentApiError } from "./errors";
import { importedBase, MAX_IMPORTED_CARDS, setImportedBase } from "./imported";
import { applyTargets } from "./plans";
import { toSlimSnapshot, type SlimSnapshot } from "./slim";

/**
 * Handing plans over: `plans/deliver` (a draft as a new plan the player opens), `plans/import` (the player's open
 * plan as a draft, from its slim snapshot) and `plans/diff` (what an imported draft adds, as a proposal the player
 * applies). Nothing here reads or writes accounts, sessions, library designs or community posts.
 */

const MAX_WARNINGS = 20;

/** The draft as a complete plan in the site's own format, under a new id: what the planner opens. */
export function deliveredPlan(project: FactoryProject, name: string, now: number): FactoryProject {
  const stamp = new Date(now).toISOString();
  const plan = {
    ...structuredClone(project),
    id: randomUUID(),
    name,
    metadata: { source: "agent-api", createdAt: stamp, updatedAt: stamp },
  };
  return factoryProjectSchema.parse(normalizeProjectFuelProfiles(plan)) as FactoryProject;
}

export async function deliverPlan(
  body: z.infer<typeof plansDeliverRequest>,
): Promise<z.infer<typeof plansDeliverResponse>> {
  const draft = getDraft(body.planId);
  if (!draft.project.nodes.length) {
    throw new AgentApiError("bad_request", "This draft has no cards: add some before delivering it.");
  }
  const code = await encodePlanCode(deliveredPlan(draft.project, draft.name, draftNow()));
  // Stored as the site reads the code back (its loader fills derived fields), so the id and the code open one plan.
  const plan = await decodePlanCode(code);
  const stored = await storeDelivery({ kind: "delivery", name: draft.name, payload: plan, player: body.player });
  // The site opens a plan code from a link (`/#p=<code>`) without any post; only when the site knows its address.
  const site = process.env.NEXT_PUBLIC_SITE_URL?.trim().replace(/\/+$/, "");
  return {
    name: stored.name,
    id: stored.id,
    code,
    ...(site ? { url: planCodeLink(code, site) } : {}),
    expiresAt: stored.expiresAt,
    cardCount: plan.nodes.length,
  };
}

function bad(message: string): never {
  throw new AgentApiError("bad_request", message);
}

/** The resource a port pins when it is a card's own main output slot written the way the draft's targets write it. */
function canonicalTarget(port: string): { kind: ResourceKind; id: string } | undefined {
  let parsed: ReturnType<typeof parseResourceHandleId>;
  try {
    parsed = parseResourceHandleId(port);
  } catch {
    return undefined;
  }
  if (!parsed || parsed.side !== "output" || parsed.section !== 0) {
    return undefined;
  }
  const resource = { kind: parsed.kind, id: parsed.resourceId };
  return makeResourceHandleId("output", resource) === port ? resource : undefined;
}

function nextNumber(ids: string[], prefix: string): number {
  const pattern = new RegExp(`^${prefix}([1-9][0-9]{0,5})$`);
  return ids.reduce((next, id) => Math.max(next, Number(pattern.exec(id)?.[1] ?? 0) + 1), 1);
}

/** Every reference in the snapshot names something in it; ids are unique. */
function checkSnapshot(snapshot: SlimSnapshot): void {
  if (snapshot.cards.length > MAX_IMPORTED_CARDS) {
    throw new AgentApiError(
      "plan_too_big",
      `An imported plan holds at most ${MAX_IMPORTED_CARDS} cards; this one has ${snapshot.cards.length}.`,
    );
  }
  const things = new Set<string>();
  const cards = new Set<string>();
  for (const [index, card] of snapshot.cards.entries()) {
    if (things.has(card.id)) bad(`snapshot.cards[${index}]: the id "${card.id}" is used twice.`);
    things.add(card.id);
    cards.add(card.id);
  }
  for (const [index, drawer] of snapshot.drawers.entries()) {
    if (things.has(drawer.id)) bad(`snapshot.drawers[${index}]: the id "${drawer.id}" is used twice.`);
    things.add(drawer.id);
  }
  const boards = new Set<string>();
  for (const [index, board] of (snapshot.boards ?? []).entries()) {
    if (boards.has(board.id)) bad(`snapshot.boards[${index}]: the id "${board.id}" is used twice.`);
    boards.add(board.id);
  }
  for (const [index, board] of (snapshot.boards ?? []).entries()) {
    if (board.parent && !boards.has(board.parent)) bad(`snapshot.boards[${index}]: no board "${board.parent}".`);
  }
  for (const [list, entries] of [
    ["cards", snapshot.cards],
    ["drawers", snapshot.drawers],
  ] as const) {
    for (const [index, entry] of entries.entries()) {
      if (entry.board && !boards.has(entry.board)) bad(`snapshot.${list}[${index}]: no board "${entry.board}".`);
    }
  }
  const wires = new Set<string>();
  for (const [index, wire] of snapshot.wires.entries()) {
    if (wires.has(wire.id)) bad(`snapshot.wires[${index}]: the id "${wire.id}" is used twice.`);
    wires.add(wire.id);
    for (const end of [wire.from, wire.to]) {
      if (!things.has(end)) bad(`snapshot.wires[${index}]: no card or drawer "${end}".`);
    }
  }
  const pinned = new Set<string>();
  for (const [index, target] of snapshot.targets.entries()) {
    if (!cards.has(target.card)) bad(`snapshot.targets[${index}]: no card "${target.card}".`);
    const key = `${target.card}>${target.port}`;
    if (pinned.has(key)) bad(`snapshot.targets[${index}]: card "${target.card}" pins "${target.port}" twice.`);
    pinned.add(key);
  }
}

export async function importPlan(
  body: z.infer<typeof plansImportRequest>,
): Promise<z.infer<typeof plansImportResponse>> {
  const snapshot = body.snapshot;
  checkSnapshot(snapshot);
  const dataset = await withDataset(() => agentDatasetVersion());

  // Recipes by id from the dataset; a card whose recipe (or a section's) is not there is kept opaque.
  const wanted = new Set(snapshot.cards.flatMap((card) => [card.recipeId, ...(card.sections ?? [])]));
  const recipes = new Map<string, Recipe>();
  for (const recipeId of wanted) {
    try {
      recipes.set(recipeId, await withDataset(() => loadRecipe(dataset, recipeId)));
    } catch (error) {
      if (!(error instanceof AgentApiError && error.code === "unknown_recipe")) {
        throw error;
      }
    }
  }
  const opaque = new Set<string>();
  const warnings: string[] = [];
  for (const card of snapshot.cards) {
    const missing = [card.recipeId, ...(card.sections ?? [])].filter((id) => !recipes.has(id));
    if (missing.length) {
      opaque.add(card.id);
      warnings.push(
        `Card ${card.id}: recipe ${missing.join(", ")} is not in dataset ${dataset}; the card is kept as it is and cannot be changed.`,
      );
    }
  }

  // Targets: each resource's first canonical main-output pin becomes a draft target on that card; every other pin
  // is kept on its card as it is.
  const carriers = new Map<string, string>();
  const pins = new Map<string, Record<string, number>>();
  const targets: DraftTarget[] = [];
  for (const target of snapshot.targets) {
    const resource = canonicalTarget(target.port);
    const key = resource ? makeResourceKey(resource.kind, resource.id) : undefined;
    if (resource && key && !carriers.has(key)) {
      carriers.set(key, target.card);
      let name = resource.id;
      try {
        if (resource.kind !== "power") {
          name = (await withDataset(() => resolveResource(dataset, { id: resource.id, kind: resource.kind as "item" }))).name;
        }
      } catch {
        // A resource the dataset does not name keeps its id as its name.
      }
      targets.push({ ...resource, name, rate: target.rate });
    } else {
      pins.set(target.card, { ...pins.get(target.card), [target.port]: target.rate });
    }
  }

  const nodes: FactoryNode[] = snapshot.cards.map((card) => {
    const { heatingCoil, ...tiers } = card.dials ?? {};
    return {
      id: card.id,
      recipeId: card.recipeId,
      machineCount: 1,
      parallel: 1,
      ...(card.machine ? { machineHandlerId: card.machine } : {}),
      overclockTier: card.tier,
      ...(heatingCoil ? { coilTier: heatingCoil } : {}),
      ...(Object.keys(tiers).length ? { machineConfigTiers: tiers } : {}),
      ...(card.count !== undefined ? { solvePin: card.count } : {}),
      ...(card.count !== undefined && card.exact ? { solvePinExact: true } : {}),
      ...(card.sections ? { extraRecipes: card.sections.map((recipeId) => ({ recipeId })) } : {}),
      enabled: true,
      ...(card.board ? { pocketId: card.board } : {}),
      position: { x: card.position.x, y: card.position.y },
    };
  });
  const storages: FactoryStorage[] = snapshot.drawers.map((drawer) => ({
    id: drawer.id,
    kind: drawer.resource.kind,
    resourceId: drawer.resource.id,
    ...(drawer.mode ? { drainMode: drawer.mode } : {}),
    ...(drawer.board ? { pocketId: drawer.board } : {}),
    position: { x: drawer.position.x, y: drawer.position.y },
  }));
  const edges: FactoryEdge[] = snapshot.wires.map((wire) => ({
    id: wire.id,
    source: wire.from,
    target: wire.to,
    resourceKind: wire.resource.kind,
    resourceId: wire.resource.id,
    ...(wire.fromPort ? { sourceHandle: wire.fromPort } : {}),
    ...(wire.toPort ? { targetHandle: wire.toPort } : {}),
  }));
  const pockets: FactoryPocket[] = (snapshot.boards ?? []).map((board) => ({
    id: board.id,
    name: board.name,
    ...(board.parent ? { parentPocketId: board.parent } : {}),
    position: { x: board.position.x, y: board.position.y },
  }));

  const name = snapshot.plan.name.trim().slice(0, 80) || "Imported plan";
  const draft = createDraft({ name, dataset });
  const used = new Set(nodes.flatMap((node) => [node.recipeId, ...(node.extraRecipes ?? []).map((s) => s.recipeId)]));
  draft.project = {
    ...draft.project,
    recipes: [...recipes.values()].filter((recipe) => used.has(recipe.id)),
    nodes,
    storages,
    edges,
    ...(pockets.length ? { pockets } : {}),
  };
  draft.targets = targets;
  draft.nextCard = nextNumber([...nodes.map((node) => node.id), ...storages.map((storage) => storage.id)], "c");
  draft.nextWire = nextNumber(edges.map((edge) => edge.id), "w");
  setImportedBase(draft, { basePlanId: snapshot.plan.id, base: snapshot, opaque, pins, carriers });
  applyTargets(draft);
  touchDraft(draft);
  // What diff compares against is the draft as imported, read back the same way it will be read then.
  setImportedBase(draft, {
    basePlanId: snapshot.plan.id,
    base: toSlimSnapshot(draft.project),
    opaque,
    pins,
    carriers,
  });

  const shown = warnings.slice(0, MAX_WARNINGS);
  if (warnings.length > shown.length) {
    shown.push(`And ${warnings.length - shown.length} more cards with recipes not in this dataset.`);
  }
  return {
    dataset,
    planId: draft.id,
    name: draft.name,
    expiresAt: draftExpiresAt(draft),
    cardCount: nodes.length,
    wireCount: edges.length,
    warnings: shown,
  };
}

type WithPosition = { position: { x: number; y: number } };

function withoutPosition<T extends WithPosition>(value: T): string {
  const { position: _position, ...rest } = value;
  return JSON.stringify(rest);
}

function listed(items: string[], limit = 8): string {
  const shown = items.slice(0, limit);
  return items.length > limit ? `${shown.join(", ")} and ${items.length - limit} more` : shown.join(", ");
}

/** "2 cards" / "1 card". */
function count(n: number, word: string): string {
  return `${n} ${word}${n === 1 ? "" : "s"}`;
}

export async function diffPlan(body: z.infer<typeof plansDiffRequest>): Promise<z.infer<typeof plansDiffResponse>> {
  const draft = getDraft(body.planId);
  const imported = importedBase(draft);
  if (!imported) {
    bad("This draft was not made by plans/import, so there is no plan to compare it with.");
  }
  const base = imported.base;
  const now = toSlimSnapshot(draft.project);

  // Add-only: anything of the imported plan that is gone or different refuses the whole proposal.
  const problems: string[] = [];
  const compare = <T extends { id: string }>(word: string, before: T[], after: T[], key: (value: T) => string) => {
    const current = new Map(after.map((entry) => [entry.id, entry]));
    for (const entry of before) {
      const found = current.get(entry.id);
      if (!found) {
        problems.push(`removed ${word} ${entry.id}`);
      } else if (key(found) !== key(entry)) {
        problems.push(`changed ${word} ${entry.id}`);
      }
    }
  };
  compare("card", base.cards, now.cards, withoutPosition);
  compare("drawer", base.drawers, now.drawers, withoutPosition);
  compare("wire", base.wires, now.wires, (wire) => JSON.stringify(wire));
  compare("board", base.boards ?? [], now.boards ?? [], withoutPosition);
  const pinKey = (target: { card: string; port: string }) => `${target.card}>${target.port}`;
  const nowPins = new Map(now.targets.map((target) => [pinKey(target), target]));
  for (const target of base.targets) {
    if (!nowPins.has(pinKey(target))) {
      problems.push(`removed the rate pinned on card ${target.card} (${target.port})`);
    }
  }
  if (problems.length) {
    bad(
      `A proposal can only add to the player's plan, and this draft ${listed(problems, 6)}. Tell the player that change in words instead, or put it back and diff again.`,
    );
  }

  const baseIds = new Set([...base.cards, ...base.drawers].map((entry) => entry.id));
  const baseWires = new Set(base.wires.map((wire) => wire.id));
  const basePins = new Map(base.targets.map((target) => [pinKey(target), target.rate]));
  const addedNodes = draft.project.nodes.filter((node) => !baseIds.has(node.id));
  const addedStorages = (draft.project.storages ?? []).filter((storage) => !baseIds.has(storage.id));
  const addedEdges = draft.project.edges.filter((edge) => !baseWires.has(edge.id));
  const targets = now.targets.filter((target) => basePins.get(pinKey(target)) !== target.rate);
  if (!addedNodes.length && !addedStorages.length && !addedEdges.length && !targets.length) {
    bad("This draft adds nothing to the imported plan: add cards or set a target first.");
  }

  // New things go to the right of the player's plan, keeping the draft's layout among themselves.
  const basePositions = [...base.cards, ...base.drawers].map((entry) => entry.position);
  const added = [...addedNodes, ...addedStorages];
  let dx = 0;
  let dy = 0;
  if (basePositions.length && added.length) {
    dx = Math.max(...basePositions.map((p) => p.x)) + 600 - Math.min(...added.map((entry) => entry.position.x));
    dy = Math.min(...basePositions.map((p) => p.y)) - Math.min(...added.map((entry) => entry.position.y));
  }
  const moved = <T extends WithPosition>(entry: T): T => ({
    ...structuredClone(entry),
    position: { x: entry.position.x + dx, y: entry.position.y + dy },
  });
  const recipeIds = new Set(
    addedNodes.flatMap((node) => [node.recipeId, ...(node.extraRecipes ?? []).map((section) => section.recipeId)]),
  );
  const summary = {
    addedCards: addedNodes.length,
    addedWires: addedEdges.length,
    addedDrawers: addedStorages.length,
    changedTargets: targets.length,
  };
  const payload: ProposalPayload = {
    version: 1,
    basePlanId: imported.basePlanId,
    summary,
    recipes: structuredClone(draft.project.recipes.filter((recipe) => recipeIds.has(recipe.id))) as never,
    cards: addedNodes.map(moved) as never,
    drawers: addedStorages.map(moved) as never,
    wires: structuredClone(addedEdges) as never,
    targets,
  };

  const recipeNames = new Map(draft.project.recipes.map((recipe) => [recipe.id, recipe.name]));
  const parts: string[] = [];
  if (addedNodes.length) {
    parts.push(
      `${count(addedNodes.length, "card")} (${listed(addedNodes.map((node) => `${recipeNames.get(node.recipeId) ?? node.recipeId} ${node.id}`))})`,
    );
  }
  if (addedEdges.length) parts.push(count(addedEdges.length, "wire"));
  if (addedStorages.length) parts.push(count(addedStorages.length, "drawer"));
  const text = [
    parts.length ? `Adds ${parts.join(", ")}.` : "",
    targets.length
      ? `Sets ${count(targets.length, "rate")}: ${listed(targets.map((target) => `${target.rate}/s on ${target.card}`))}.`
      : "",
  ]
    .filter(Boolean)
    .join(" ");

  const stored = await storeDelivery({
    kind: "proposal",
    name: draft.name,
    payload,
    basePlanId: imported.basePlanId,
    player: body.player,
  });
  return { proposalId: stored.id, basePlanId: imported.basePlanId, expiresAt: stored.expiresAt, summary, text };
}
