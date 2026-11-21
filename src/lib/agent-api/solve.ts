import type { z } from "zod";
import { buildNotices } from "@/components/flow/clean/rail/notices";
import { parseResourceHandleId } from "@/components/flow/resource-handles";
import { parsePortKey } from "@/lib/solver/plan/port-key";
import { makeResourceKey } from "@/lib/model/resources";
import type { FactoryProject, ResourceFlow, ResourceKind, ThroughputResult } from "@/lib/model/types";
import { ensurePlanEngine, PlanEngineUnavailableError } from "@/lib/server/plan-engine";
import { calculateThroughput } from "@/lib/solver";
import { describeCard } from "./cards";
import { getDraft, type Draft } from "./drafts";
import { AgentApiError } from "./errors";
import { targetCards } from "./plans";
import { CARD_COLUMNS, type plansSolveRequest, type plansSolveResponse, type solveCardLine, type SOLVE_FIELDS } from "./plan-schemas";
import { nameOf } from "./format";
import { fitList } from "./size";

/**
 * plans/solve: the draft solved by the site's own plan solver on the compiled core, exactly as the community plan
 * routes solve an uploaded plan (`ensurePlanEngine`, then `calculateThroughput`). Targets are port rates on the cards
 * that make them; inputs no card in the draft supplies come in from outside and are the "unsupplied" list (the raws
 * still needed); outputs nothing takes are the byproducts with nowhere to go. Every rate is per second at the solved
 * machine counts.
 */

type SolveField = (typeof SOLVE_FIELDS)[number];
type SolveResponse = z.infer<typeof plansSolveResponse>;
type CardLine = z.infer<typeof solveCardLine>;

const MAX_WARNINGS = 12;
/** Below this a rate is solver dust (the board snaps balances at 1e-5 relative). */
const DUST = 1e-6;

/** Four significant figures: what a person reads off the board, and small. */
export function figure(value: number): number {
  if (!Number.isFinite(value) || value === 0) {
    return 0;
  }
  return Number(value.toPrecision(4));
}

function flowMap(flows: Record<string, ResourceFlow>): Record<string, number> {
  const out: Record<string, number> = {};
  const seen = new Map<string, number>();
  for (const flow of Object.values(flows)) {
    if (!(flow.amountPerSecond > DUST)) {
      continue;
    }
    const base = flow.displayName || flow.resourceId;
    const count = seen.get(base) ?? 0;
    seen.set(base, count + 1);
    const name = count === 0 ? base : `${base} (${flow.kind})`;
    out[name] = (out[name] ?? 0) + figure(flow.amountPerSecond);
  }
  return out;
}

function cardLines(draft: Draft, result: ThroughputResult): CardLine[] {
  const recipes = new Map(draft.project.recipes.map((recipe) => [recipe.id, recipe]));
  return draft.project.nodes.map((node) => {
    const card = describeCard(node, recipes.get(node.recipeId));
    const solved = result.nodes[node.id];
    const line: CardLine = [
      node.id,
      card.name,
      card.machine.name,
      card.tier,
      figure(solved?.theoreticalMachinesRequired ?? 0),
      solved ? flowMap(solved.inputs) : {},
      solved ? flowMap(solved.outputs) : {},
    ];
    return card.dials ? [...line, card.dials] : line;
  });
}

interface BoundaryFlow {
  side: "in" | "out";
  id: string;
  kind: ResourceKind;
  name: string;
  perSecond: number;
}

/**
 * What crosses the plan's edge, per resource: the open (unwired) ports the solve runs (`plan.terminals`) and the
 * material forced in or out at a wired port with no drawer (`plan.forced`). Inputs are what the draft still needs
 * from outside; outputs are what has nowhere to go. Largest first.
 */
function boundaryFlows(project: FactoryProject, result: ThroughputResult): BoundaryFlow[] {
  const names = new Map<string, string>();
  for (const recipe of project.recipes) {
    for (const slot of [...recipe.inputs, ...recipe.outputs]) {
      const key = makeResourceKey(slot.kind, slot.id);
      if (!names.has(key)) {
        names.set(key, nameOf(slot));
      }
    }
  }
  for (const node of project.nodes) {
    for (const slot of Object.values(node.recipeInputOverrides ?? {})) {
      names.set(makeResourceKey(slot.kind, slot.id), nameOf(slot));
    }
  }
  const totals = new Map<string, BoundaryFlow>();
  const add = (side: "in" | "out", kind: string, id: string, perSecond: number) => {
    if (!(perSecond > DUST) || (kind !== "item" && kind !== "fluid" && kind !== "aspect")) {
      return;
    }
    const key = `${side}|${makeResourceKey(kind, id)}`;
    const entry = totals.get(key);
    if (entry) {
      entry.perSecond += perSecond;
    } else {
      totals.set(key, { side, id, kind, name: names.get(makeResourceKey(kind, id)) ?? id, perSecond });
    }
  };
  const extras = result.plan;
  for (const flow of [...(extras?.terminals ?? []), ...(extras?.forced ?? [])]) {
    const port = parsePortKey(flow.portKey);
    const handle = parseResourceHandleId(port?.handle);
    if (handle) {
      add(flow.side, handle.kind, handle.resourceId, flow.perSecond);
    }
  }
  return [...totals.values()]
    .sort((a, b) => b.perSecond - a.perSecond)
    .map((flow) => ({ ...flow, perSecond: figure(flow.perSecond) }));
}

function solveProject(project: FactoryProject): Promise<ThroughputResult> {
  return ensurePlanEngine(project).then(
    () => calculateThroughput(project),
    (error: unknown) => {
      if (error instanceof PlanEngineUnavailableError) {
        throw new AgentApiError("internal", error.message);
      }
      throw error;
    },
  );
}

export async function solveDraft(body: z.infer<typeof plansSolveRequest>): Promise<SolveResponse> {
  const draft = getDraft(body.planId);
  const project = draft.project;
  const fields = new Set<SolveField>(body.fields ?? ["targets", "unsupplied", "byproducts", "power", "warnings", "cards"]);
  const result = await solveProject(project);
  const failure = result.plan?.failure;
  const solved = Boolean(result.plan) && !failure;

  const carriers = targetCards(draft);
  const targetKeys = new Set(draft.targets.map((target) => makeResourceKey(target.kind, target.id)));
  const targets = draft.targets.map((target) => {
    const cardId = carriers.get(makeResourceKey(target.kind, target.id));
    const key = makeResourceKey(target.kind, target.id);
    const made = cardId ? (result.nodes[cardId]?.outputs[key]?.amountPerSecond ?? 0) : 0;
    return {
      id: target.id,
      kind: target.kind,
      name: target.name,
      rate: target.rate,
      made: figure(made),
      met: solved && made >= target.rate * (1 - 1e-6),
      ...(cardId ? { cardId } : {}),
    };
  });

  const free = new Set(draft.constraints?.free ?? []);
  const boundary = boundaryFlows(project, result);
  const unsupplied = boundary
    .filter((flow) => flow.side === "in")
    .map(({ side: _side, ...flow }) => ({ ...flow, ...(free.has(flow.id) ? { free: true } : {}) }));
  const byproducts = boundary
    .filter((flow) => flow.side === "out" && !targetKeys.has(makeResourceKey(flow.kind, flow.id)))
    .map(({ side: _side, ...flow }) => flow);

  const warnings: string[] = [];
  if (draft.targets.length === 0) {
    warnings.push("No target is set, so nothing runs: set one with plans/set-targets.");
  }
  for (const target of targets) {
    if (!target.cardId) {
      warnings.push(`No card in the draft makes ${target.name}, so its target does nothing.`);
    } else if (solved && !target.met) {
      warnings.push(`${target.name}: the plan makes ${target.made}/s of the ${target.rate}/s asked.`);
    }
  }
  for (const notice of buildNotices(project, result)) {
    if (notice.id !== "no-pin") {
      warnings.push(notice.focus.length ? `${notice.text} (${notice.focus.join(", ")})` : notice.text);
    }
  }
  if (solved) {
    const idle = (result.plan?.unneeded ?? []).filter((id) => project.nodes.some((node) => node.id === id));
    if (idle.length) {
      warnings.push(`Not needed by any target, so they run at 0: ${idle.join(", ")}.`);
    }
  }
  const shownWarnings =
    warnings.length > MAX_WARNINGS
      ? [...warnings.slice(0, MAX_WARNINGS - 1), `and ${warnings.length - MAX_WARNINGS + 1} more`]
      : warnings;

  const head: SolveResponse = {
    dataset: draft.dataset,
    planId: draft.id,
    solved,
    targetsMet: targets.length > 0 && targets.every((target) => target.met),
    ...(fields.has("targets") ? { targets } : {}),
    ...(fields.has("unsupplied") ? { unsupplied } : {}),
    ...(fields.has("byproducts") ? { byproducts } : {}),
    ...(fields.has("power") ? { euT: figure(result.totalEuT) } : {}),
    ...(fields.has("warnings") ? { warnings: shownWarnings } : {}),
  };
  if (!fields.has("cards")) {
    return head;
  }
  return fitList(cardLines(draft, result), (cards, truncated) => ({
    ...head,
    cardColumns: [...CARD_COLUMNS],
    cards,
    ...(truncated ? { truncated: true } : {}),
  }));
}
