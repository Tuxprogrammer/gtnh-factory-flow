import type { FactoryProject, ThroughputResult } from "@/lib/model/types";
import { parsePortKey, splitLeftoverKey } from "@/lib/solver/plan/port-key";
import type { PlanLeftover } from "@/lib/solver/plan/types";
import { parseResourceHandleId } from "../../resource-handles";
import {
  depthOfCards,
  effectiveSharesOf,
  sparesFromInput,
  type RailInBearer,
  type RailInSpare,
} from "./rail-core";
import {
  buildResourceLookup,
  oredictName,
  splitResultNodeId,
  type RailModel,
  type RailResource,
} from "./rail-model";

/**
 * A spare (the solver's leftover): flow sent out of the plan, or brought into
 * it, at ports with no drawer, with more than one place that could bear it.
 * The mod's `BoardSession.SpareLine`/`SpareBearer`. Pure.
 */

export interface SpareBearerView {
  /** The place's PortKey, also its key in `spareShares`. */
  key: string;
  /** The card the place is on (a shared section's `#rN` dropped). */
  nodeId: string;
  /** The place's port handle on that card. */
  handle: string;
  output: boolean;
  /** The resource's name. */
  name: string;
  /** The machine's name. */
  machine: string;
  /** The canonical resource key. */
  resourceKey: string;
  resource: RailResource;
  /** What crosses at this place when it bears the whole leftover, a second. */
  max: number;
  solverPick: boolean;
  energy: boolean;
  /** The ports the leftover crosses at this place (its own port when it bears none now). */
  ports: Array<{ nodeId: string; handle: string }>;
}

export interface SpareView {
  key: string;
  importing: boolean;
  /** A stored split is in force. */
  custom: boolean;
  bearers: SpareBearerView[];
  /** The solver's own shares. */
  solved: number[];
  /** The shares in force: the stored split first, else the solver's. */
  shares: number[];
}

/** The shares in force for a leftover: the stored split (normalised), else the solver's. */
export const effectiveShares = effectiveSharesOf;

/** Reads the solver's leftovers into the records the spares are worked out from (`rail-core.ts`). */
export function spareInputs(
  project: FactoryProject,
  result: ThroughputResult | undefined,
  rail: Pick<RailModel, "machines">,
): { spares: RailInSpare[]; resources: Map<string, RailResource> } {
  const leftovers: PlanLeftover[] = result?.plan?.leftovers ?? [];
  const spares: RailInSpare[] = [];
  const resources = new Map<string, RailResource>();
  if (!result || leftovers.length === 0) return { spares, resources };
  const lookup = buildResourceLookup(project);
  const recipes = new Map(project.recipes.map((recipe) => [recipe.id, recipe]));
  const nodesById = new Map(project.nodes.map((node) => [node.id, node]));
  const machineName = new Map(rail.machines.map((m) => [m.nodeId, m.label]));
  for (const leftover of leftovers) {
    const bearers: RailInBearer[] = [];
    const found = new Map<string, RailResource>();
    let ok = true;
    for (const b of leftover.bearers) {
      const parsed = parsePortKey(b.portKey);
      const handle = parsed ? parseResourceHandleId(parsed.handle) : undefined;
      if (!parsed || !handle) {
        ok = false;
        break;
      }
      const { cardId } = splitResultNodeId(parsed.nodeId);
      const key = `${handle.kind}:${handle.resourceId}`;
      const resource = lookup.get(key) ?? { kind: handle.kind, id: handle.resourceId };
      const node = nodesById.get(cardId);
      found.set(b.portKey, resource);
      bearers.push({
        key: b.portKey,
        nodeId: cardId,
        handle: parsed.handle,
        output: parsed.side === "o",
        name:
          oredictName(project, handle.kind, handle.resourceId) ??
          resource.displayName ??
          handle.resourceId,
        machine:
          machineName.get(cardId) ?? recipes.get(node?.recipeId ?? "")?.machineType ?? cardId,
        resource: key,
        max: b.maxPerSecond,
        solverPick: b.solverPick,
        energy: handle.kind === "power",
        share: b.share,
        ports: (b.flows.length > 0 ? b.flows.map((f) => f.portKey) : [b.portKey]).flatMap(
          (portKey) => {
            const p = parsePortKey(portKey);
            return p ? [{ nodeId: splitResultNodeId(p.nodeId).cardId, handle: p.handle }] : [];
          },
        ),
      });
    }
    if (!ok || bearers.length === 0) continue;
    const stored = project.spareShares?.[leftover.key];
    spares.push({
      key: leftover.key,
      importing: leftover.importing,
      custom: leftover.custom,
      ...(stored !== undefined ? { stored } : {}),
      bearers,
    });
    for (const [portKey, resource] of found) resources.set(`${leftover.key}|${portKey}`, resource);
  }
  return { spares, resources };
}

export function buildSpares(
  project: FactoryProject,
  result: ThroughputResult | undefined,
  rail: Pick<RailModel, "aliases" | "machines">,
): SpareView[] {
  const { spares, resources } = spareInputs(project, result, rail);
  if (spares.length === 0) return [];
  const core = sparesFromInput({
    aliases: Object.fromEntries(rail.aliases),
    spares,
    cards: project.nodes.map((node) => node.id),
    wires: project.edges.map((edge) => ({ from: edge.source, to: edge.target })),
  });
  return core.map((spare) => ({
    ...spare,
    bearers: spare.bearers.map(({ resourceKey, ...b }) => ({
      ...b,
      resourceKey,
      resource: resources.get(`${spare.key}|${b.key}`)!,
    })),
  }));
}

/** How far each card is from where the plan starts along its wires (the nearest way, so a loop does not run on). */
export function cardDepths(project: FactoryProject): Map<string, number> {
  // Only wires between cards count: a drawer feeding a card does not make it later in the plan.
  return depthOfCards(
    project.nodes.map((n) => n.id),
    project.edges.map((edge) => ({ from: edge.source, to: edge.target })),
  );
}

/** How much of each resource the spares with a choice of place carry in (`importing`) or out, by canonical key. */
export function sparedByResource(
  spares: readonly SpareView[],
  importing: boolean,
): Map<string, number> {
  const out = new Map<string, number>();
  for (const spare of spares) {
    if (spare.importing !== importing || spare.bearers.length < 2) continue;
    spare.bearers.forEach((b, i) => {
      out.set(b.resourceKey, (out.get(b.resourceKey) ?? 0) + spare.shares[i]! * b.max);
    });
  }
  return out;
}

/** Whether a leftover key names these bearers (kept for symmetry with `makeLeftoverKey`). */
export function keyHasBearers(key: string, bearerKeys: readonly string[]): boolean {
  const parts = splitLeftoverKey(key);
  return parts.length === bearerKeys.length && parts.every((p) => bearerKeys.includes(p));
}
