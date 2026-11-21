import { isCustomRateRecipe } from "@/lib/model/custom-rate";
import { isFluidTankRecipe } from "@/lib/model/fluid-tank";
import {
  buildMachineList,
  machinesFromUnits,
  type MachineListEntry,
} from "@/lib/model/machine-list";
import { pinRuleOf, type PinRule } from "@/lib/model/pin-rule";
import type { PlanUpTo } from "@/lib/solver/plan/types";
import { powerCardTier } from "@/lib/power/power-recipe";
import { getCategoryPresentation } from "@/lib/model/category-presentation";
import { makeResourceKey, resourceLabel } from "@/lib/model/resources";
import { getStorageRoles, type StorageRole } from "@/lib/model/storage-role";
import { hasStorageTarget, storageTargetMode, type TargetMode } from "@/lib/model/storage-target";
import type {
  FactoryProject,
  ResourceAmount,
  ResourceKind,
  ThroughputResult,
} from "@/lib/model/types";
import {
  railFromInput,
  RAIL_CORE_EPS,
  keptAmount,
  type CoreLine,
  type RailInDrawer,
  type RailInFlow,
  type RailInPort,
  type RailInput,
} from "./rail-core";
import {
  canonicalizeResourceHandleId,
  makeResourceHandleId,
  parseResourceHandleId,
} from "../../resource-handles";

/**
 * The overview's numbers, worked out once per (project, solve): what the plan
 * brings in, sends out and keeps inside, what sets each resource (drawers,
 * ports set to a rate on their cards, open ports) and the machines to build.
 * A port of the mod's `BoardSession.buildTotals` and `OverviewRail.index`.
 * Pure: no store, no DOM.
 */

/** Below this a rate (per second) is solver noise, not a flow worth a line. */
export const RAIL_EPS = RAIL_CORE_EPS;

export type RailSide = "inputs" | "outputs" | "internal";

export type RailResource = Pick<
  ResourceAmount,
  "kind" | "id" | "displayName" | "iconPath" | "iconAtlas" | "dominantColor"
>;

/** A drawer that takes a rule, as one setter of a line. */
export interface RailDrawer {
  type: "drawer";
  id: string;
  source: boolean;
  role: StorageRole;
  rule: TargetMode;
  /** Magnitude, per second; 0 when none is set. */
  ratePerSecond: number;
  /** A rule or a rate is in force (the box shows the number, not "Set"). */
  set: boolean;
  unmet: boolean;
}

/** A port set to a rate on its card with nothing wired to it. */
export interface RailPin {
  type: "port";
  nodeId: string;
  handle: string;
  output: boolean;
  ratePerSecond: number;
  machine: string;
}

/** A port of a line's resource with nothing wired to it and no drawer. */
export interface RailOpenPort {
  nodeId: string;
  handle: string;
  output: boolean;
  perSecond: number;
  machine: string;
}

export interface RailLine {
  /** The canonical resource key (`item:iron_ingot`). */
  key: string;
  kind: ResourceKind;
  resourceId: string;
  label: string;
  /** Units a second (EU a second for power: the rail shows it per tick). */
  amount: number;
  energy: boolean;
  resource: RailResource;
  drawers: RailDrawer[];
  pins: RailPin[];
  open: RailOpenPort[];
}

export interface RailMachine extends MachineListEntry {
  /** A count is set (Up to or Exactly). */
  pinned: boolean;
  /** The count rule as the card shows it: Any, Up to (with or without a count) or Exactly. */
  rule: PinRule;
  /** The set count when pinned. */
  pin?: number;
  /** The solver's read-out for an Up to card, when it gave one. */
  upTo?: PlanUpTo;
  /** A generator's tier for its made EU under the Amps dial, the one its card's EU row counts in. */
  madeTier?: string;
}

export interface RailModel {
  inputs: RailLine[];
  outputs: RailLine[];
  internal: RailLine[];
  machines: RailMachine[];
  /** Whole machines to build (each card rounded up). */
  wholeMachines: number;
  power: {
    peak: { used: number; made: number; steam: number };
    average: { used: number; made: number; steam: number };
    hasMade: boolean;
  };
  /** Canonical resource key by what a port asks for (two keys a wire joins are one resource). */
  aliases: ReadonlyMap<string, string>;
}

export const EMPTY_RAIL: RailModel = {
  inputs: [],
  outputs: [],
  internal: [],
  machines: [],
  wholeMachines: 0,
  power: {
    peak: { used: 0, made: 0, steam: 0 },
    average: { used: 0, made: 0, steam: 0 },
    hasMade: false,
  },
  aliases: new Map(),
};

/** A shared machine's section result id (`card#r2`) split into the card and the handle prefix (`r2:`). */
export function splitResultNodeId(id: string): { cardId: string; prefix: string } {
  const at = id.indexOf("#r");
  if (at < 0) return { cardId: id, prefix: "" };
  return { cardId: id.slice(0, at), prefix: `r${id.slice(at + 2)}:` };
}

/** The canonical handle id of a result node's flow (`output:item:foo`, `r2:output:item:foo`). */
export function flowHandle(
  resultNodeId: string,
  side: "input" | "output",
  kind: ResourceKind,
  resourceId: string,
): string {
  const { prefix } = splitResultNodeId(resultNodeId);
  return `${prefix}${makeResourceHandleId(side, { kind, id: resourceId })}`;
}

/** An ore dictionary slot as the card names it ("Any 1x Copper Wire"), from its members, never its raw tag. */
export function oredictName(
  project: FactoryProject,
  kind: ResourceKind,
  id: string,
): string | undefined {
  const category = getCategoryPresentation(project.recipes, kind, id);
  return category ? resourceLabel(category) : undefined;
}

export function buildResourceLookup(project: FactoryProject): Map<string, RailResource> {
  const resources = new Map<string, RailResource>();
  const add = (resource: RailResource) => {
    const key = `${resource.kind}:${resource.id}`;
    const existing = resources.get(key);
    if (!existing || (!existing.iconPath && resource.iconPath)) resources.set(key, resource);
  };
  for (const recipe of project.recipes) {
    for (const resource of [...recipe.inputs, ...recipe.outputs]) add(resource);
  }
  for (const storage of project.storages ?? []) {
    add({
      kind: storage.kind,
      id: storage.resourceId,
      displayName: storage.displayName,
      iconPath: storage.iconPath,
      iconAtlas: storage.iconAtlas,
      dominantColor: storage.dominantColor,
    });
  }
  return resources;
}

/** Keys a wire joins to another: what an input asks for, to what is wired into it. */
function buildAliases(project: FactoryProject): Map<string, string> {
  const same = new Map<string, string>();
  for (const edge of project.edges) {
    const made = makeResourceKey(edge.resourceKind, edge.resourceId);
    const target = parseResourceHandleId(edge.targetHandle);
    if (!target) continue;
    const used = makeResourceKey(target.kind, target.resourceId);
    if (used !== made && !same.has(used)) same.set(used, made);
  }
  return same;
}

export function canonicalOf(aliases: ReadonlyMap<string, string>, key: string): string {
  let at = key;
  for (let i = 0; i < 16; i += 1) {
    const next = aliases.get(at);
    if (next === undefined) break;
    at = next;
  }
  return at;
}

function wiredPorts(project: FactoryProject): Set<string> {
  const wired = new Set<string>();
  for (const edge of project.edges) {
    const out =
      canonicalizeResourceHandleId(edge.sourceHandle) ??
      makeResourceHandleId("output", { kind: edge.resourceKind, id: edge.resourceId });
    const inn =
      canonicalizeResourceHandleId(edge.targetHandle) ??
      makeResourceHandleId("input", { kind: edge.resourceKind, id: edge.resourceId });
    wired.add(`${edge.source}|${out}`);
    wired.add(`${edge.target}|${inn}`);
  }
  return wired;
}

/** What the rail is worked out from: the plan and its answer as plain records (`rail-core.ts`). */
export interface RailSource {
  input: RailInput;
  /** The cards' machines, in the order the input lists them (a fluid tank left out). */
  machines: RailMachine[];
  lookup: Map<string, RailResource>;
  aliases: Map<string, string>;
}

/** Reads a plan and its answer into the records the rail is worked out from. The view settings are the caller's. */
export function railSource(
  project: FactoryProject,
  result: ThroughputResult,
  view: RailInput["view"] = { filter: "", peak: true, power: "eu" },
): RailSource {
  const recipes = new Map(project.recipes.map((recipe) => [recipe.id, recipe]));
  const nodesById = new Map(project.nodes.map((node) => [node.id, node]));
  const lookup = buildResourceLookup(project);
  const aliases = buildAliases(project);
  const wired = wiredPorts(project);
  const roles = getStorageRoles(project);

  const machineEntries = buildMachineList(project, result);
  const isTank = (entry: MachineListEntry) =>
    // A fluid tank is free and instant: no machine to build (the mod lists none for it).
    isFluidTankRecipe(recipes.get(nodesById.get(entry.nodeId)?.recipeId ?? ""));
  const machineOf = (entry: MachineListEntry): RailMachine => {
    const node = nodesById.get(entry.nodeId);
    const rule = node ? pinRuleOf(node) : "any";
    const pin = node?.solvePin;
    const pinned = rule !== "any" && pin !== undefined && pin > 0;
    const upTo = rule === "upTo" ? result.plan?.upTo?.[entry.nodeId] : undefined;
    // An Up to card lists what runs, not what is set.
    // A crop card's solved units are seeds: the line counts the harvesters they fill.
    const count = upTo ? machinesFromUnits(entry, Math.max(0, upTo.reached)) : entry.count;
    return {
      ...entry,
      count,
      pinned,
      rule,
      ...(pinned ? { pin: machinesFromUnits(entry, pin) } : {}),
      ...(upTo ? { upTo } : {}),
      madeTier: powerCardTier(recipes.get(node?.recipeId ?? "")),
    };
  };
  const all = machineEntries.map((entry) => ({
    entry,
    tank: isTank(entry),
    rail: machineOf(entry),
  }));
  const machines = all.filter((m) => !m.tank).map((m) => m.rail);
  const machineName = new Map(machineEntries.map((entry) => [entry.nodeId, entry.label]));
  const nameOf = (cardId: string): string => {
    const known = machineName.get(cardId);
    if (known) return known;
    const recipe = recipes.get(nodesById.get(cardId)?.recipeId ?? "");
    return recipe?.machineType ?? cardId;
  };

  // A failed solve (nothing set, nothing to calculate) leaves each card's nominal single craft in its result: those
  // are not flows of the plan, so nothing is counted until the plan has an answer.
  const answered = result.plan?.failure === undefined;
  const flows: RailInFlow[] = [];
  const names: Record<string, string> = {};
  const named = (kind: ResourceKind, id: string) => {
    const name = oredictName(project, kind, id);
    if (name !== undefined) names[`${kind}:${id}`] = name;
  };
  for (const node of Object.values(result.nodes)) {
    if (!node.enabled) continue;
    const { cardId } = splitResultNodeId(node.nodeId);
    const projectNode = nodesById.get(cardId);
    const recipe = recipes.get(node.recipeId);
    if (!projectNode || isCustomRateRecipe(recipe)) continue;
    const ports: RailInPort[] = [];
    for (const side of ["output", "input"] as const) {
      const sideFlows = side === "output" ? node.outputs : node.inputs;
      for (const flow of Object.values(sideFlows)) {
        const handle = flowHandle(node.nodeId, side, flow.kind, flow.resourceId);
        const set = projectNode.portRates?.[handle];
        named(flow.kind, flow.resourceId);
        ports.push({
          output: side === "output",
          key: flow.key,
          kind: flow.kind,
          id: flow.resourceId,
          label: flow.displayName ?? lookup.get(flow.key)?.displayName ?? flow.resourceId,
          perSecond: flow.amountPerSecond,
          handle,
          wired: wired.has(`${cardId}|${handle}`),
          set: set !== undefined && set > 0 ? set : 0,
        });
      }
    }
    flows.push({
      card: cardId,
      machine: nameOf(cardId),
      ...(node.theoreticalMachinesRequired !== undefined
        ? { machines: node.theoreticalMachinesRequired }
        : {}),
      ...(node.utilization !== undefined ? { utilization: node.utilization } : {}),
      ports,
    });
  }

  const drawers: RailInDrawer[] = [];
  const unmetIds = result.plan?.unmetDrawers ?? [];
  for (const storage of project.storages ?? []) {
    const role = roles.get(storage.id) ?? "idle";
    if (role !== "trash" && role !== "source" && role !== "product" && role !== "byproduct")
      continue;
    // Nothing typed and no rule picked: Any (the legacy default for an input would read "exactly").
    const rule: TargetMode =
      role === "byproduct" ||
      (storage.targetMode === undefined && storage.targetPerSecond === undefined)
        ? "ignore"
        : storageTargetMode(storage, role);
    const set = role !== "byproduct" && hasStorageTarget(storage, role);
    named(storage.kind, storage.resourceId);
    drawers.push({
      id: storage.id,
      kind: storage.kind,
      resourceId: storage.resourceId,
      label:
        storage.displayName ??
        lookup.get(`${storage.kind}:${storage.resourceId}`)?.displayName ??
        storage.resourceId,
      role,
      rule,
      set,
      rate: set ? Math.abs(storage.targetPerSecond ?? 0) : 0,
      unmet: unmetIds.includes(storage.id),
      eaten: result.storages[storage.id]?.producedPerSecond ?? 0,
    });
  }

  const input: RailInput = {
    answered,
    aliases: Object.fromEntries(aliases),
    flows,
    drawers,
    machines: all.map(({ entry, tank, rail }) => ({
      nodeId: entry.nodeId,
      label: entry.label,
      count: rail.count,
      tank,
      pinned: rail.pinned,
      rule: rail.rule,
      ...(rail.pin !== undefined ? { pin: rail.pin } : {}),
      ...(rail.upTo
        ? {
            upTo: {
              ...(rail.upTo.reason ? { reason: rail.upTo.reason } : {}),
              reached: rail.upTo.reached,
              capacity: rail.upTo.capacity,
            },
          }
        : {}),
      ...(entry.tier !== undefined ? { tier: entry.tier } : {}),
      ...(entry.amps !== undefined ? { amps: entry.amps } : {}),
      multiblock: entry.isMultiblock,
      ...(rail.madeTier !== undefined ? { madeTier: rail.madeTier } : {}),
      state: entry.state,
      ...(entry.euT !== undefined ? { euT: entry.euT } : {}),
      ...(entry.avgEuT !== undefined ? { avgEuT: entry.avgEuT } : {}),
      ...(entry.steamLs !== undefined ? { steamLs: entry.steamLs } : {}),
      ...(entry.avgSteamLs !== undefined ? { avgSteamLs: entry.avgSteamLs } : {}),
      ...(entry.madeEuT !== undefined ? { madeEuT: entry.madeEuT } : {}),
      ...(entry.avgMadeEuT !== undefined ? { avgMadeEuT: entry.avgMadeEuT } : {}),
    })),
    names,
    spares: [],
    cards: project.nodes.map((node) => node.id),
    wires: project.edges.map((edge) => ({ from: edge.source, to: edge.target })),
    view,
  };
  return { input, machines, lookup, aliases };
}

/** A pure line as the board's own: its resource (icon, colour) looked up again, its setters typed. */
function lineOf(
  core: CoreLine,
  names: Record<string, string>,
  lookup: Map<string, RailResource>,
): RailLine {
  const kind = core.kind as ResourceKind;
  const resource = lookup.get(`${kind}:${core.id}`) ?? {
    kind,
    id: core.id,
    displayName: core.label,
  };
  return {
    key: core.key,
    kind,
    resourceId: core.id,
    label: core.label,
    amount: core.amount,
    energy: core.energy,
    resource: {
      ...resource,
      displayName: names[`${kind}:${core.id}`] ?? resource.displayName ?? core.label,
    },
    drawers: core.drawers.map(
      (d): RailDrawer => ({
        type: "drawer",
        id: d.id,
        source: d.source,
        role: d.role as StorageRole,
        rule: d.rule as TargetMode,
        ratePerSecond: d.rate,
        set: d.set,
        unmet: d.unmet,
      }),
    ),
    pins: core.pins.map(
      (p): RailPin => ({
        type: "port",
        nodeId: p.nodeId,
        handle: p.handle,
        output: p.output,
        ratePerSecond: p.rate,
        machine: p.machine,
      }),
    ),
    open: core.open.map((o) => ({ ...o })),
  };
}

export function buildRailModel(
  project: FactoryProject,
  result: ThroughputResult | undefined,
): RailModel {
  if (!result) return EMPTY_RAIL;
  const { input, machines, lookup, aliases } = railSource(project, result);
  const core = railFromInput(input);
  return {
    inputs: core.inputs.map((l) => lineOf(l, input.names, lookup)),
    outputs: core.outputs.map((l) => lineOf(l, input.names, lookup)),
    internal: core.internal.map((l) => lineOf(l, input.names, lookup)),
    machines,
    wholeMachines: core.wholeMachines,
    power: { peak: core.peak, average: core.average, hasMade: core.hasMade },
    aliases,
  };
}

/** A section's lines: what the plan's spares carry is theirs, so a line left with nothing but that is not listed. */
export function visibleLines(
  lines: readonly RailLine[],
  side: RailSide,
  spared: ReadonlyMap<string, number>,
): Array<{ line: RailLine; amount: number }> {
  const out: Array<{ line: RailLine; amount: number }> = [];
  for (const line of lines) {
    const amount = keptAmount(line, side === "internal", spared);
    if (amount !== undefined) out.push({ line, amount });
  }
  return out;
}
