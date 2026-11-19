import { isCustomRateRecipe } from "@/lib/model/custom-rate";
import { pinRuleOf } from "@/lib/model/pin-rule";
import { isFluidTankRecipe } from "@/lib/model/fluid-tank";
import { buildMachineList, machinesFromUnits } from "@/lib/model/machine-list";
import type {
  FactoryPocket,
  FactoryProject,
  ResourceKind,
  ThroughputResult,
} from "@/lib/model/types";
import { sumGroup, type GroupMember, type GroupSumFlow } from "./group-sum";
import { buildResourceLookup, splitResultNodeId, type RailResource } from "../rail/rail-model";

/**
 * What a collapsed group's summary card says (docs/groups.md, "What the summary says"): from the plan-wide solve,
 * never a solve of the group alone, over its members and every group nested in it.
 *
 * - machines: each machine card's build count (solved count rounded up, a pinned count as pinned); a shared machine
 *   once; drawers, notes and planning cards are not machines;
 * - EU/t: the members' power as the overview counts it (peak or average, its dial);
 * - in and out: per resource, the members' solved consumption minus their solved production (the card's own
 *   numbers: count times per-machine rate), busiest first.
 */

const EPS = 1e-6;

export interface GroupFlow {
  key: string;
  kind: ResourceKind;
  resourceId: string;
  resource: RailResource;
  /** Units a second (EU a second for power). */
  perSecond: number;
}

export interface GroupSummary {
  machines: number;
  euT: number;
  inputs: GroupFlow[];
  outputs: GroupFlow[];
}

/** Every group nested under `pocketId`, itself included. */
export function groupFamily(pockets: readonly FactoryPocket[], pocketId: string): Set<string> {
  const family = new Set<string>([pocketId]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const pocket of pockets) {
      if (
        pocket.parentPocketId !== undefined &&
        family.has(pocket.parentPocketId) &&
        !family.has(pocket.id)
      ) {
        family.add(pocket.id);
        grew = true;
      }
    }
  }
  return family;
}

export function computeGroupSummaries(
  project: FactoryProject,
  result: ThroughputResult | undefined,
  pocketIds: readonly string[],
  peak: boolean,
): Map<string, GroupSummary> {
  const out = new Map<string, GroupSummary>();
  if (pocketIds.length === 0) return out;
  const pockets = project.pockets ?? [];
  const recipes = new Map(project.recipes.map((recipe) => [recipe.id, recipe]));
  const nodesById = new Map(project.nodes.map((node) => [node.id, node]));
  const lookup = buildResourceLookup(project);
  const entries = result ? buildMachineList(project, result) : [];
  // A failed solve leaves each card's nominal single craft in its result: not flows of the plan.
  const answered = result !== undefined && result.plan?.failure === undefined;

  for (const pocketId of pocketIds) {
    const family = groupFamily(pockets, pocketId);
    const inside = (cardId: string) => {
      const owner = nodesById.get(cardId)?.pocketId;
      return owner !== undefined && family.has(owner);
    };

    const members: GroupMember[] = [];
    for (const entry of entries) {
      if (!inside(entry.nodeId)) continue;
      const node = nodesById.get(entry.nodeId);
      if (isFluidTankRecipe(recipes.get(node?.recipeId ?? ""))) continue;
      const pin = node?.solvePin;
      const rule = node ? pinRuleOf(node) : "any";
      const pinned = rule !== "any" && pin !== undefined && pin > 0;
      // An Up to card builds what runs (the solver's read-out), an Exactly card its set count.
      const running = rule === "upTo" ? result?.plan?.upTo?.[entry.nodeId]?.reached : undefined;
      if (!pinned && !(entry.count > 1e-9)) continue;
      // A crop card's solved and set counts are seeds: the group builds the harvesters they fill.
      const seeds = running ?? (pinned ? pin : undefined);
      members.push({
        host: entry.nodeId,
        builds: seeds !== undefined ? machinesFromUnits(entry, seeds) : entry.count,
        euT: (peak ? entry.euT : entry.avgEuT) ?? 0,
        flows: [],
      });
    }

    // What each flow is, as the first card to name it gave it.
    const meta = new Map<
      string,
      { kind: ResourceKind; resourceId: string; resource: RailResource; name: string }
    >();
    if (answered) {
      for (const node of Object.values(result!.nodes)) {
        if (!node.enabled) continue;
        const { cardId } = splitResultNodeId(node.nodeId);
        if (!inside(cardId)) continue;
        if (isCustomRateRecipe(recipes.get(node.recipeId))) continue;
        // A machine at zero keeps its nameplate rates (its port rows read them) but moves nothing.
        if (!((node.theoreticalMachinesRequired ?? 0) > 1e-9)) continue;
        const running = Math.min(1, Math.max(0, node.utilization ?? 1));
        const flows: GroupMember["flows"] = [];
        for (const [list, sign] of [
          [node.inputs, 1],
          [node.outputs, -1],
        ] as const) {
          for (const flow of Object.values(list)) {
            if (!meta.has(flow.key)) {
              const known = lookup.get(flow.key);
              const name = known?.displayName ?? flow.displayName ?? flow.resourceId;
              meta.set(flow.key, {
                kind: flow.kind,
                resourceId: flow.resourceId,
                resource: { ...(known ?? { kind: flow.kind, id: flow.resourceId }), displayName: name },
                name,
              });
            }
            flows.push({
              key: flow.key,
              name: meta.get(flow.key)!.name,
              perSecond: sign * flow.amountPerSecond * running,
            });
          }
        }
        members.push({ host: cardId, builds: null, euT: 0, flows });
      }
    }

    const sum = sumGroup(members);
    const asFlow = (flow: GroupSumFlow): GroupFlow => {
      const known = meta.get(flow.key)!;
      return {
        key: flow.key,
        kind: known.kind,
        resourceId: known.resourceId,
        resource: known.resource,
        perSecond: flow.perSecond,
      };
    };
    const machines = sum.machines;
    const euT = sum.euT;
    const inputs = sum.inputs.map(asFlow);
    const outputs = sum.outputs.map(asFlow);
    out.set(pocketId, { machines, euT, inputs, outputs });
  }
  return out;
}

/** Rows the summary card draws: the longer list (at least one: the "Nothing goes in or out." line). */
export function summaryRows(summary: GroupSummary | undefined): number {
  if (!summary) return 1;
  return Math.max(1, summary.inputs.length, summary.outputs.length);
}
