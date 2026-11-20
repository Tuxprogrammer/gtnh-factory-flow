import type {
  EdgeThroughput,
  FactoryProject,
  NodeThroughputResult,
  Recipe,
  ResourceAmount,
  ResourceFlow,
  ThroughputResult,
} from "@/lib/model/types";
import {
  isOreDictionaryResource,
  isRecipeInputConsumed,
  makeResourceKey,
  resourceLabel,
} from "@/lib/model";
import { getPoolProject } from "@/lib/solver/pool-mode";
import { viewEnvelope } from "@/test-support/view-corpus";
import {
  buildRailPorts,
  deriveNodeVerdict,
  type NodeVerdict,
  type RailPort,
} from "../../node-verdict";
import { makeResourceHandleId, sectionHandleId } from "../../resource-handles";

/**
 * The card's rail rows and verdict as shared cases (`conformance/view/rails/cases.json`): what the website's
 * `deriveNodeVerdict` and `buildRailPorts` decide for one card of a solved plan, in the core's record shape
 * (`NodeRailsInput`, `NodeRailsView`; jvm/core/core-view `NodeRails`). The input is the facts those two read of the
 * board (the recipe's slots, the solve's flows, the wires on the card); the view is the part of their answer both hosts
 * draw: the verdict, the bare slots and each row's name, rates, tone and marks. What the tooltips read (the plug, what
 * a line wants or could carry, the fill bar, the supply hatch) needs the whole board and stays the website's.
 *
 * Node only (tests and the generator); never import this from app code.
 */

// region The wire records (jvm/core/core-view rails/NodeRailsInput and NodeRailsView)

export interface RailsSlotWire {
  key: string;
  kind: string;
  resourceId: string;
  name?: string;
  oreLabel?: string;
  handleId: string;
  consumed: boolean;
  chance?: number;
  consumeChance?: number;
  alternatives: number;
}

export interface RailsFlowWire {
  key: string;
  kind: string;
  resourceId: string;
  name?: string;
  oreLabel?: string;
  handleId: string;
  amountPerSecond: number;
}

export interface RailsSideWire {
  slots: RailsSlotWire[];
  flows: RailsFlowWire[];
  wires: Array<{ key: string; transferredPerSecond: number }>;
}

export interface NodeRailsInputWire {
  enabled: boolean;
  hasResult: boolean;
  missingRecipe: boolean;
  solveMode: boolean;
  hasSlots: boolean;
  utilization: number;
  euT: number;
  inputs: RailsSideWire;
  outputs: RailsSideWire;
}

export interface NodeRailsRowWire {
  key: string;
  handleId: string;
  kind: string;
  resourceId: string;
  name: string;
  slot?: number;
  connected: boolean;
  unsupplied: boolean;
  boundaryFree: boolean;
  currentPerSecond: number;
  nameplatePerSecond: number;
  tone: string;
  shortBadge?: number;
  showNameplate: boolean;
  energyPerUnit?: number;
  free: boolean;
  chance?: number;
  consumeChance?: number;
  alternatives: number;
}

export interface NodeRailsViewWire {
  verdict: string;
  pct: number;
  bare?: {
    inputs: Array<{ resourceKey: string; kind: string; displayName: string }>;
    outputs: Array<{ resourceKey: string; kind: string; displayName: string }>;
  };
  inputs: NodeRailsRowWire[];
  outputs: NodeRailsRowWire[];
}

export interface NodeRailsCase {
  name: string;
  input: NodeRailsInputWire;
  view: NodeRailsViewWire;
}

// endregion

/** A recipe slot as either end reads it: an input's keep and consume chance, an output's chance. */
export type RailsSlot = ResourceAmount & {
  consumed?: boolean;
  chance?: number;
  consumeChance?: number;
};

const clamp01 = (value: number | undefined, fallback: number): number =>
  value === undefined || !Number.isFinite(value) ? fallback : Math.min(Math.max(value, 0), 1);

/**
 * The facts the core's rows and verdict read, taken from the board as the website holds it. `displayRecipe` is the
 * recipe whose slots the card draws (a shared machine's section draws its own); `handleSection` is that section's
 * number.
 */
export function railsInputOf(
  rawProject: FactoryProject,
  result: ThroughputResult | undefined,
  nodeId: string,
  displayRecipe: { inputs: RailsSlot[]; outputs: RailsSlot[] },
  handleSection = 0,
): NodeRailsInputWire {
  const project = getPoolProject(rawProject);
  const node = project.nodes.find((entry) => entry.id === nodeId);
  const nodeResult = result?.nodes[nodeId];
  const recipe = node ? project.recipes.find((entry) => entry.id === node.recipeId) : undefined;
  const side = (input: boolean): RailsSideWire => {
    const resources = input ? displayRecipe.inputs : displayRecipe.outputs;
    const flows = input ? nodeResult?.inputs : nodeResult?.outputs;
    const edges = project.edges.filter((edge) => (input ? edge.target : edge.source) === nodeId);
    const handle = (resource: { kind: ResourceAmount["kind"]; id: string }) =>
      sectionHandleId(handleSection, makeResourceHandleId(input ? "input" : "output", resource));
    const labelOf = (
      resource: Pick<ResourceAmount, "id" | "displayName"> &
        Partial<Pick<ResourceAmount, "alternatives">>,
    ) => (isOreDictionaryResource(resource) ? resourceLabel(resource) : undefined);
    return {
      slots: resources.map((r) => ({
        key: makeResourceKey(r.kind, r.id),
        kind: r.kind,
        resourceId: r.id,
        name: r.displayName,
        oreLabel: labelOf(r),
        handleId: handle(r),
        consumed: isRecipeInputConsumed(r),
        chance: r.chance,
        consumeChance: r.consumeChance,
        alternatives: r.alternatives?.length ?? 0,
      })),
      flows: Object.entries(flows ?? {}).map(([key, flow]: [string, ResourceFlow]) => ({
        key,
        kind: flow.kind,
        resourceId: flow.resourceId,
        name: flow.displayName,
        oreLabel: labelOf({ id: flow.resourceId, displayName: undefined as unknown as string }),
        handleId: handle({ kind: flow.kind, id: flow.resourceId }),
        amountPerSecond: flow.amountPerSecond,
      })),
      wires: edges.map((edge) => ({
        key: makeResourceKey(edge.resourceKind, edge.resourceId),
        transferredPerSecond: result?.edges[edge.id]?.transferredPerSecond ?? 0,
      })),
    };
  };
  return {
    enabled: Boolean(node) && node?.enabled !== false,
    hasResult: Boolean(nodeResult),
    missingRecipe: nodeResult?.status === "missing-recipe",
    solveMode: project.solveMode === true,
    hasSlots: (recipe?.inputs.length ?? 0) > 0 || (recipe?.outputs.length ?? 0) > 0,
    utilization: clamp01(nodeResult?.utilization, 0),
    euT: nodeResult?.euT ?? 0,
    inputs: side(true),
    outputs: side(false),
  };
}

const VERDICTS = new Set(["off", "no-recipe", "unwired", "balanced", "demand-set"]);

/** What the core's view holds of the website's answer: the verdict, and rows only where the core answers (a solved plan). */
export function railsViewOf(
  verdict: NodeVerdict,
  rails: { inputs: RailPort[]; outputs: RailPort[] },
  displayRecipe: { inputs: RailsSlot[]; outputs: RailsSlot[] },
  solveMode: boolean,
): NodeRailsViewWire {
  // Past the three readings that need no solve, only a solved plan is the core's: the host's ladder answers the rest.
  if (
    !VERDICTS.has(verdict.kind) ||
    (!solveMode && !["off", "no-recipe", "unwired"].includes(verdict.kind))
  ) {
    return { verdict: "legacy", pct: verdict.pct, inputs: [], outputs: [] };
  }
  const row = (p: RailPort, resources: RailsSlot[]): NodeRailsRowWire => {
    const at = resources.findIndex((r) => r.kind === p.kind && r.id === p.resourceId);
    return {
      key: p.key,
      handleId: p.handleId,
      kind: p.kind,
      resourceId: p.resourceId,
      name: p.displayName,
      slot: at < 0 ? undefined : at,
      connected: p.connected,
      unsupplied: p.unsupplied,
      boundaryFree: p.boundaryFree,
      currentPerSecond: p.currentPerSecond,
      nameplatePerSecond: p.nameplatePerSecond,
      tone: p.tone,
      shortBadge: p.badge?.kind === "short" ? p.badge.perSecond : undefined,
      showNameplate: p.showNameplate === true,
      energyPerUnit: p.energyPerUnit,
      free: p.free === true,
      chance: (p.resource as RailsSlot | undefined)?.chance,
      consumeChance: (p.resource as RailsSlot | undefined)?.consumeChance,
      alternatives: p.resource?.alternatives?.length ?? 0,
    };
  };
  if (verdict.kind === "off") return { verdict: "off", pct: 0, inputs: [], outputs: [] };
  // The record's order: the bare slots sit between the percentage and the rows.
  return {
    verdict: verdict.kind,
    pct: verdict.pct,
    bare:
      verdict.kind === "unwired" && verdict.bare
        ? { inputs: verdict.bare.inputs, outputs: verdict.bare.outputs }
        : undefined,
    inputs: rails.inputs.map((p) => row(p, displayRecipe.inputs)),
    outputs: rails.outputs.map((p) => row(p, displayRecipe.outputs)),
  };
}

// region The cases

const LAVA = "gregtech:gt.metaitem.02@32765";

const res = (
  kind: "item" | "fluid",
  id: string,
  amount: number,
  extra: Partial<RailsSlot> = {},
): RailsSlot => ({ kind, id, amount, displayName: id, ...extra }) as RailsSlot;

const flow = (
  kind: "item" | "fluid" | "power",
  resourceId: string,
  amountPerSecond: number,
  displayName: string | undefined = resourceId,
): ResourceFlow =>
  ({
    key: `${kind}:${resourceId}`,
    kind,
    resourceId,
    displayName,
    amountPerSecond,
  }) as ResourceFlow;

const flowsOf = (...list: ResourceFlow[]): Record<string, ResourceFlow> =>
  Object.fromEntries(list.map((f) => [`${f.kind}:${f.resourceId}`, f]));

const nodeResult = (partial: Partial<NodeThroughputResult>): NodeThroughputResult =>
  ({
    nodeId: "N",
    recipeId: "r",
    recipeName: "r",
    inputs: {},
    outputs: {},
    euT: 0,
    requiredRatePerSecond: 0,
    maxRatePerSecond: 1,
    utilization: 1,
    theoreticalMachinesRequired: 1,
    status: "balanced",
    warnings: [],
    ...partial,
  }) as NodeThroughputResult;

const wire = (
  id: string,
  source: string,
  target: string,
  resourceId: string,
  kind: "item" | "fluid" = "item",
) => ({
  id,
  source,
  target,
  resourceKind: kind,
  resourceId,
});

interface Spec {
  name: string;
  recipe: { inputs: RailsSlot[]; outputs: RailsSlot[] };
  edges?: ReturnType<typeof wire>[];
  /** The card's own result; undefined is a solve with no entry for it. */
  node?: NodeThroughputResult;
  rates?: Record<string, number>;
  solveMode?: boolean;
  enabled?: boolean;
  section?: number;
}

function caseOf(spec: Spec): NodeRailsCase {
  const recipe = {
    id: "r",
    name: "r",
    machineType: "M",
    minimumTier: "LV",
    durationTicks: 20,
    eut: 1,
    ...spec.recipe,
  };
  const project = {
    schemaVersion: 1,
    id: "rails",
    name: "rails",
    solveMode: spec.solveMode ?? true,
    fuelProfiles: [],
    storages: [],
    recipes: [recipe],
    nodes: [
      {
        id: "N",
        recipeId: "r",
        machineCount: 1,
        parallel: 1,
        overclockTier: "LV",
        enabled: spec.enabled ?? true,
        position: { x: 0, y: 0 },
      },
    ],
    edges: spec.edges ?? [],
  } as unknown as FactoryProject;
  const edges: Record<string, EdgeThroughput> = {};
  for (const [id, rate] of Object.entries(spec.rates ?? {})) {
    edges[id] = {
      transferredPerSecond: rate,
      demandPerSecond: rate,
      isLimited: false,
      constraint: "full",
    } as EdgeThroughput;
  }
  const result = {
    nodes: spec.node ? { N: spec.node } : {},
    edges,
    storages: {},
    resources: {},
  } as unknown as ThroughputResult;
  const verdict = deriveNodeVerdict(project, result, "N");
  const rails = buildRailPorts(project, result, "N", spec.recipe, verdict, {
    handleSection: spec.section,
  });
  return {
    name: spec.name,
    input: railsInputOf(project, result, "N", spec.recipe, spec.section ?? 0),
    view: JSON.parse(
      JSON.stringify(railsViewOf(verdict, rails, spec.recipe, project.solveMode === true)),
    ) as NodeRailsViewWire,
  };
}

const PLATE = res("item", "gt:plate", 2);
const DUST = res("item", "gt:dust", 1);
const GEAR = res("item", "gt:gear", 1);
const WATER = res("fluid", "water", 500);

const SPECS: Spec[] = [
  {
    name: "a disabled card is off and draws no rows",
    recipe: { inputs: [PLATE], outputs: [GEAR] },
    enabled: false,
  },
  {
    name: "a card the solve has no entry for is no recipe, and its rows come from the recipe at nothing",
    recipe: { inputs: [PLATE, WATER], outputs: [GEAR] },
    edges: [wire("e1", "S", "N", "gt:plate")],
  },
  {
    name: "a card the solve could not find a recipe for is no recipe",
    recipe: { inputs: [PLATE], outputs: [GEAR] },
    node: nodeResult({ status: "missing-recipe", utilization: 0 }),
  },
  {
    name: "a flowing input with no wire is a bare slot, marked short at its nameplate, and so is a product with no taker",
    recipe: { inputs: [PLATE, DUST], outputs: [GEAR] },
    edges: [wire("e1", "S", "N", "gt:plate")],
    rates: { e1: 4 },
    node: nodeResult({
      utilization: 0.5,
      euT: 30,
      inputs: flowsOf(flow("item", "gt:plate", 4), flow("item", "gt:dust", 2)),
      outputs: flowsOf(flow("item", "gt:gear", 2)),
    }),
  },
  {
    name: "a slotted card with no wires and no flows is unwired with nothing bare",
    recipe: { inputs: [PLATE], outputs: [GEAR] },
    node: nodeResult({ utilization: 0 }),
  },
  {
    name: "a card with no slots has nothing to wire and runs",
    recipe: { inputs: [], outputs: [] },
    node: nodeResult({ utilization: 1 }),
  },
  {
    name: "a wired card that runs is balanced and each row reads its wire's rate",
    recipe: { inputs: [PLATE, WATER], outputs: [GEAR] },
    edges: [
      wire("e1", "S", "N", "gt:plate"),
      wire("e2", "S2", "N", "water", "fluid"),
      wire("e3", "N", "D", "gt:gear"),
    ],
    rates: { e1: 3.5, e2: 250, e3: 1.75 },
    node: nodeResult({
      utilization: 0.4375,
      euT: 120,
      inputs: flowsOf(flow("item", "gt:plate", 8), flow("fluid", "water", 570, "Water")),
      outputs: flowsOf(flow("item", "gt:gear", 4, "Gear")),
    }),
  },
  {
    name: "two wires into one slot are one row that adds them",
    recipe: { inputs: [PLATE], outputs: [GEAR] },
    edges: [
      wire("e1", "S", "N", "gt:plate"),
      wire("e2", "S2", "N", "gt:plate"),
      wire("e3", "N", "D", "gt:gear"),
    ],
    rates: { e1: 1.25, e2: 2.5, e3: 1 },
    node: nodeResult({
      utilization: 0.5,
      inputs: flowsOf(flow("item", "gt:plate", 7.5)),
      outputs: flowsOf(flow("item", "gt:gear", 2)),
    }),
  },
  {
    name: "a card the plan does not need is demand set, its wired rows calm, an idle row with no wire left out",
    recipe: { inputs: [PLATE, DUST], outputs: [GEAR] },
    edges: [wire("e1", "S", "N", "gt:plate"), wire("e3", "N", "D", "gt:gear")],
    rates: { e1: 0, e3: 0 },
    node: nodeResult({
      utilization: 0,
      inputs: flowsOf(flow("item", "gt:plate", 0), flow("item", "gt:dust", 0)),
      outputs: flowsOf(flow("item", "gt:gear", 0)),
    }),
  },
  {
    name: "a card at half a percent is demand set and above it balanced",
    recipe: { inputs: [PLATE], outputs: [GEAR] },
    edges: [wire("e1", "S", "N", "gt:plate"), wire("e3", "N", "D", "gt:gear")],
    rates: { e1: 0.01, e3: 0.01 },
    node: nodeResult({
      utilization: 0.005,
      inputs: flowsOf(flow("item", "gt:plate", 0.01)),
      outputs: flowsOf(flow("item", "gt:gear", 0.01)),
    }),
  },
  {
    name: "the percentage is the utilisation to one place",
    recipe: { inputs: [PLATE], outputs: [GEAR] },
    edges: [wire("e1", "S", "N", "gt:plate"), wire("e3", "N", "D", "gt:gear")],
    rates: { e1: 1, e3: 1 },
    node: nodeResult({
      utilization: 0.12345,
      inputs: flowsOf(flow("item", "gt:plate", 1)),
      outputs: flowsOf(flow("item", "gt:gear", 1)),
    }),
  },
  {
    name: "an input the recipe keeps is no row, and the lava the game gives follows as an inert row",
    recipe: {
      inputs: [
        res("item", "gt:circuit", 1, { consumed: false }),
        res("item", LAVA, 1, { displayName: "IT'S FREE! Place Lava on Side" }),
        PLATE,
      ],
      outputs: [GEAR],
    },
    edges: [
      wire("e0", "S0", "N", "gt:circuit"),
      wire("e1", "S", "N", "gt:plate"),
      wire("e3", "N", "D", "gt:gear"),
    ],
    rates: { e0: 1, e1: 2, e3: 1 },
    node: nodeResult({
      utilization: 1,
      inputs: flowsOf(flow("item", "gt:circuit", 1), flow("item", "gt:plate", 2)),
      outputs: flowsOf(flow("item", "gt:gear", 1)),
    }),
  },
  {
    name: "a spawner's mob and a bee's species follow as inert rows",
    recipe: {
      inputs: [
        res("item", "factoryflow:eec_mob:zombie", 1),
        res("item", "factoryflow:bee_species:forest", 1, { consumed: false }),
      ],
      outputs: [GEAR],
    },
    edges: [wire("e3", "N", "D", "gt:gear")],
    rates: { e3: 1 },
    node: nodeResult({ utilization: 1, outputs: flowsOf(flow("item", "gt:gear", 1)) }),
  },
  {
    name: "before the first solve a kept input and a free input are both no row",
    recipe: {
      inputs: [res("item", LAVA, 1), res("item", "gt:circuit", 1, { consumed: false }), PLATE],
      outputs: [GEAR],
    },
  },
  {
    name: "an ore dictionary slot reads its group's name, whatever the solve called it",
    recipe: {
      inputs: [res("item", "oredict:plateIron", 1, { displayName: "Ore Dictionary: plateIron" })],
      outputs: [GEAR],
    },
    edges: [wire("e1", "S", "N", "oredict:plateIron"), wire("e3", "N", "D", "gt:gear")],
    rates: { e1: 1, e3: 1 },
    node: nodeResult({
      utilization: 1,
      inputs: flowsOf(flow("item", "oredict:plateIron", 1, "Ore Dictionary: plateIron")),
      outputs: flowsOf(flow("item", "gt:gear", 1)),
    }),
  },
  {
    name: "an EU output with no wire is not bare and carries no energy per unit",
    recipe: { inputs: [res("fluid", "steam", 100)], outputs: [res("fluid", "water", 1), GEAR] },
    edges: [
      wire("e1", "S", "N", "steam", "fluid"),
      wire("e3", "N", "D", "gt:gear"),
      wire("e4", "N", "T", "water", "fluid"),
    ],
    rates: { e1: 100, e3: 1, e4: 1 },
    node: nodeResult({
      utilization: 1,
      euT: 32,
      inputs: flowsOf(flow("fluid", "steam", 100)),
      outputs: flowsOf(
        flow("power", "eu", 640),
        flow("item", "gt:gear", 1),
        flow("fluid", "water", 1),
      ),
    }),
  },
  {
    name: "a recipe slot's chance and choice count ride its row",
    recipe: {
      inputs: [
        res("item", "gt:plate", 1, {
          consumeChance: 0.5,
          alternatives: [res("item", "gt:a", 1), res("item", "gt:b", 1)],
        }),
      ],
      outputs: [res("item", "gt:gear", 1, { chance: 0.25 })],
    },
    edges: [wire("e1", "S", "N", "gt:plate"), wire("e3", "N", "D", "gt:gear")],
    rates: { e1: 1, e3: 1 },
    node: nodeResult({
      utilization: 1,
      inputs: flowsOf(flow("item", "gt:plate", 1)),
      outputs: flowsOf(flow("item", "gt:gear", 1)),
    }),
  },
  {
    name: "a shared machine's second section carries its section on every handle",
    recipe: { inputs: [PLATE], outputs: [GEAR] },
    edges: [wire("e1", "S", "N", "gt:plate"), wire("e3", "N", "D", "gt:gear")],
    rates: { e1: 1, e3: 1 },
    node: nodeResult({
      utilization: 1,
      inputs: flowsOf(flow("item", "gt:plate", 1)),
      outputs: flowsOf(flow("item", "gt:gear", 1)),
    }),
    section: 2,
  },
  {
    name: "a plan that is not solved answers legacy with no rows, but a bare slot is still unwired",
    recipe: { inputs: [PLATE], outputs: [GEAR] },
    edges: [wire("e1", "S", "N", "gt:plate"), wire("e3", "N", "D", "gt:gear")],
    rates: { e1: 1, e3: 1 },
    solveMode: false,
    node: nodeResult({
      utilization: 1,
      inputs: flowsOf(flow("item", "gt:plate", 1)),
      outputs: flowsOf(flow("item", "gt:gear", 1)),
    }),
  },
  {
    name: "a bare slot is unwired also when the plan is not solved",
    recipe: { inputs: [PLATE], outputs: [GEAR] },
    edges: [wire("e3", "N", "D", "gt:gear")],
    rates: { e3: 1 },
    solveMode: false,
    node: nodeResult({
      utilization: 1,
      inputs: flowsOf(flow("item", "gt:plate", 1)),
      outputs: flowsOf(flow("item", "gt:gear", 1)),
    }),
  },
];

export function railsCases(): NodeRailsCase[] {
  return SPECS.map(caseOf);
}

export function viewFiles(): Record<string, string> {
  return { "rails/cases.json": viewEnvelope("rails", "cases", "none", { cases: railsCases() }) };
}
