/**
 * The overview rail worked out from plain records: the website's side of the view the Java core computes
 * (`core-view` `RailBuilder`, `RailMath`). The input is what a host reads off its plan and solve (flows, drawers,
 * machines, spares, the player's view); the output is what the rail prints. Both sides run the cases in
 * `conformance/view/` and must give the same view. Pure: no store, no DOM, no project.
 */

import { GT_VOLTAGE_TIERS, getVoltageTierMaxEuT } from "@/lib/model/tiers";
import type { MachineTier } from "@/lib/model/types";

/** Below this a rate (per second) is solver noise, not a flow worth a line. */
export const RAIL_CORE_EPS = 1e-6;

export interface RailInPort {
  output: boolean;
  /** The resource key the port asks for or makes (`item:iron_ingot`). */
  key: string;
  kind: string;
  id: string;
  label: string;
  /** What it moves at the card's nameplate; the card's utilization scales it. */
  perSecond: number;
  handle: string;
  wired: boolean;
  /** The rate the player pinned on this port, 0 when none. */
  set: number;
}

export interface RailInFlow {
  card: string;
  machine: string;
  machines?: number;
  utilization?: number;
  /** Outputs first, then inputs. */
  ports: RailInPort[];
}

export interface RailInDrawer {
  id: string;
  kind: string;
  resourceId: string;
  label: string;
  /** source, product, byproduct or trash; anything else is left out by the host. */
  role: string;
  rule: string;
  set: boolean;
  rate: number;
  unmet: boolean;
  eaten: number;
}

export interface RailInUpTo {
  reason?: string;
  reached: number;
  capacity: number;
}

export interface RailInMachine {
  nodeId: string;
  label: string;
  count: number;
  tank: boolean;
  pinned: boolean;
  rule: string;
  pin?: number;
  upTo?: RailInUpTo;
  tier?: string;
  amps?: number;
  multiblock: boolean;
  madeTier?: string;
  state: string;
  euT?: number;
  avgEuT?: number;
  steamLs?: number;
  avgSteamLs?: number;
  madeEuT?: number;
  avgMadeEuT?: number;
}

export interface RailInBearer {
  key: string;
  nodeId: string;
  handle: string;
  output: boolean;
  name: string;
  machine: string;
  /** `kind:id` */
  resource: string;
  max: number;
  solverPick: boolean;
  energy: boolean;
  /** The solver's share. */
  share: number;
  ports: Array<{ nodeId: string; handle: string }>;
}

export interface RailInSpare {
  key: string;
  importing: boolean;
  custom: boolean;
  stored?: Record<string, number>;
  bearers: RailInBearer[];
}

export interface RailInput {
  answered: boolean;
  /** Canonical resource key by what a port asks for. */
  aliases: Record<string, string>;
  flows: RailInFlow[];
  drawers: RailInDrawer[];
  machines: RailInMachine[];
  /** Ore dictionary slot names by `kind:id`. */
  names: Record<string, string>;
  spares: RailInSpare[];
  cards: string[];
  wires: Array<{ from: string; to: string }>;
  view: { filter: string; peak: boolean; power: string; keepOpen?: boolean };
}

// region Lines

export interface CoreDrawer {
  id: string;
  source: boolean;
  role: string;
  rule: string;
  rate: number;
  set: boolean;
  unmet: boolean;
}

export interface CorePin {
  nodeId: string;
  handle: string;
  output: boolean;
  rate: number;
  machine: string;
}

export interface CoreOpen {
  nodeId: string;
  handle: string;
  output: boolean;
  perSecond: number;
  machine: string;
}

export interface CoreLine {
  key: string;
  kind: string;
  id: string;
  label: string;
  amount: number;
  energy: boolean;
  drawers: CoreDrawer[];
  pins: CorePin[];
  open: CoreOpen[];
}

export interface CorePower {
  used: number;
  made: number;
  steam: number;
}

export interface CoreModel {
  inputs: CoreLine[];
  outputs: CoreLine[];
  internal: CoreLine[];
  machines: RailInMachine[];
  wholeMachines: number;
  peak: CorePower;
  average: CorePower;
  hasMade: boolean;
}

export function canonicalKey(aliases: Readonly<Record<string, string>>, key: string): string {
  let at = key;
  for (let i = 0; i < 16; i += 1) {
    const next = Object.prototype.hasOwnProperty.call(aliases, at) ? aliases[at] : undefined;
    if (next === undefined) break;
    at = next;
  }
  return at;
}

function addTo<T>(map: Map<string, T[]>, key: string, value: T): void {
  const list = map.get(key);
  if (list) list.push(value);
  else map.set(key, [value]);
}

interface Look {
  kind: string;
  id: string;
  label: string;
}

/** The lines and machines before the filter, the peak choice and the dial. */
export function railFromInput(input: RailInput): CoreModel {
  const made = new Map<string, number>();
  const used = new Map<string, number>();
  const looks = new Map<string, Look>();
  const pinsByLine = new Map<string, CorePin[]>();
  const openByLine = new Map<string, CoreOpen[]>();
  for (const flow of input.flows) {
    const flowing = input.answered && (flow.machines ?? 0) > 1e-9;
    const running = flowing ? Math.min(1, Math.max(0, flow.utilization ?? 1)) : 0;
    for (const port of flow.ports) {
      const key = canonicalKey(input.aliases, port.key);
      const bucket = port.output ? made : used;
      bucket.set(key, (bucket.get(key) ?? 0) + port.perSecond * running);
      if (!looks.has(key) || (port.output && port.key === key)) {
        looks.set(key, { kind: port.kind, id: port.id, label: port.label });
      }
      if (port.wired) continue;
      const lineKey = `${port.output ? "o" : "i"}|${key}`;
      if (port.set > 0) {
        addTo(pinsByLine, lineKey, {
          nodeId: flow.card,
          handle: port.handle,
          output: port.output,
          rate: port.set,
          machine: flow.machine,
        });
      }
      addTo(openByLine, lineKey, {
        nodeId: flow.card,
        handle: port.handle,
        output: port.output,
        perSecond: port.perSecond * running,
        machine: flow.machine,
      });
    }
  }

  // What trash voids leaves the plan without being one of its outputs.
  const trashed = new Map<string, number>();
  const sourceDrawers = new Map<string, CoreDrawer[]>();
  const productDrawers = new Map<string, CoreDrawer[]>();
  for (const d of input.drawers) {
    const key = canonicalKey(input.aliases, `${d.kind}:${d.resourceId}`);
    if (d.role === "trash") {
      trashed.set(key, (trashed.get(key) ?? 0) + Math.max(0, d.eaten));
      continue;
    }
    const source = d.role === "source";
    if (!source && d.role !== "product" && d.role !== "byproduct") continue;
    addTo(source ? sourceDrawers : productDrawers, key, {
      id: d.id,
      source,
      role: d.role,
      rule: d.rule,
      rate: d.rate,
      set: d.set,
      unmet: d.unmet,
    });
    if (!looks.has(key)) looks.set(key, { kind: d.kind, id: d.resourceId, label: d.label });
  }

  const line = (key: string, amount: number, side: "i" | "o" | "x"): CoreLine => {
    const colon = key.indexOf(":");
    const look: Look = looks.get(key) ?? {
      kind: colon < 0 ? key : key.slice(0, colon),
      id: colon < 0 ? "" : key.slice(colon + 1),
      label: key,
    };
    const named = input.names[`${look.kind}:${look.id}`];
    return {
      key,
      kind: look.kind,
      id: look.id,
      label: named ?? look.label,
      amount,
      energy: look.kind === "power",
      drawers:
        side === "i"
          ? (sourceDrawers.get(key) ?? [])
          : side === "o"
            ? (productDrawers.get(key) ?? [])
            : [],
      pins: side === "x" ? [] : (pinsByLine.get(`${side}|${key}`) ?? []),
      open: side === "x" ? [] : (openByLine.get(`${side}|${key}`) ?? []),
    };
  };

  const inputs: CoreLine[] = [];
  const outputs: CoreLine[] = [];
  const internal: CoreLine[] = [];
  for (const key of new Set([...made.keys(), ...used.keys()])) {
    const m = made.get(key) ?? 0;
    const u = used.get(key) ?? 0;
    const leaves = m - u - (trashed.get(key) ?? 0);
    if (u - m > RAIL_CORE_EPS) inputs.push(line(key, u - m, "i"));
    if (leaves > RAIL_CORE_EPS) outputs.push(line(key, leaves, "o"));
    if (Math.min(m, u) > RAIL_CORE_EPS) internal.push(line(key, Math.min(m, u), "x"));
  }
  // Biggest first; equal amounts keep the order the plan met them in (the sort is stable), as the mod lists them.
  const byAmount = (a: CoreLine, b: CoreLine) => b.amount - a.amount;
  inputs.sort(byAmount);
  outputs.sort(byAmount);
  internal.sort(byAmount);
  // A resource a card's unwired port takes or makes stays listed at 0, so it can be set from here.
  for (const [lineKey] of openByLine) {
    const key = lineKey.slice(2);
    const list = lineKey.startsWith("o|") ? outputs : inputs;
    if (!list.some((l) => l.key === key))
      list.push(line(key, 0, lineKey.startsWith("o|") ? "o" : "i"));
  }
  // A resource with a drawer stays listed at 0, so its rule and rate can be set from here.
  for (const key of sourceDrawers.keys()) {
    if (!inputs.some((l) => l.key === key)) inputs.push(line(key, 0, "i"));
  }
  for (const key of productDrawers.keys()) {
    if (!outputs.some((l) => l.key === key)) outputs.push(line(key, 0, "o"));
  }

  const sum = (pick: (entry: RailInMachine) => number | undefined) =>
    input.machines.reduce((total, entry) => total + (pick(entry) ?? 0), 0);
  const machines = input.machines.filter((entry) => !entry.tank);
  return {
    inputs,
    outputs,
    internal,
    machines,
    wholeMachines: machines.reduce((total, e) => total + Math.ceil(e.count - 1e-9), 0),
    peak: { used: sum((e) => e.euT), made: sum((e) => e.madeEuT), steam: sum((e) => e.steamLs) },
    average: {
      used: sum((e) => e.avgEuT),
      made: sum((e) => e.avgMadeEuT),
      steam: sum((e) => e.avgSteamLs),
    },
    hasMade: input.machines.some((e) => e.madeEuT !== undefined),
  };
}

// endregion

// region Spares

export interface CoreBearer {
  key: string;
  nodeId: string;
  handle: string;
  output: boolean;
  name: string;
  machine: string;
  resourceKey: string;
  max: number;
  solverPick: boolean;
  energy: boolean;
  ports: Array<{ nodeId: string; handle: string }>;
}

export interface CoreSpare {
  key: string;
  importing: boolean;
  custom: boolean;
  bearers: CoreBearer[];
  solved: number[];
  shares: number[];
}

/** How far each card is from where the plan starts along its wires (the nearest way, so a loop does not run on). */
export function depthOfCards(
  cards: readonly string[],
  wires: ReadonlyArray<{ from: string; to: string }>,
): Map<string, number> {
  const known = new Set(cards);
  const feeds = new Map<string, string[]>();
  const fed = new Set<string>();
  for (const w of wires) {
    if (!known.has(w.from) || !known.has(w.to)) continue;
    addTo(feeds, w.from, w.to);
    fed.add(w.to);
  }
  const depth = new Map<string, number>();
  const queue: string[] = [];
  for (const id of cards) {
    if (!fed.has(id)) {
      depth.set(id, 0);
      queue.push(id);
    }
  }
  for (let head = 0; head < queue.length; head += 1) {
    const at = queue[head]!;
    for (const to of feeds.get(at) ?? []) {
      if (!depth.has(to)) {
        depth.set(to, depth.get(at)! + 1);
        queue.push(to);
      }
    }
  }
  return depth;
}

/** The shares in force for a leftover: the stored split (normalised), else the solver's. */
export function effectiveSharesOf(
  bearerKeys: readonly string[],
  solved: readonly number[],
  stored: Record<string, number> | undefined,
): number[] {
  if (!stored) return [...solved];
  const raw = bearerKeys.map((key) => Math.max(0, stored[key] ?? 0));
  const total = raw.reduce((a, b) => a + b, 0);
  if (!(total > 0)) return [...solved];
  return raw.map((v) => v / total);
}

export function sparesFromInput(
  input: Pick<RailInput, "aliases" | "spares" | "cards" | "wires">,
): CoreSpare[] {
  const out: CoreSpare[] = [];
  if (input.spares.length === 0) return out;
  const depth = depthOfCards(input.cards, input.wires);
  for (const s of input.spares) {
    if (s.bearers.length === 0) continue;
    const order = s.bearers.map((_, i) => i);
    const rank = (i: number) =>
      (depth.get(s.bearers[i]!.nodeId) ?? 1e9) + (s.bearers[i]!.output ? 0.5 : -0.5);
    // Most upstream first: a place leaving after a card sits just past it, one coming in just before it.
    order.sort((a, b) => rank(a) - rank(b) || a - b);
    const sorted = order.map((i) => s.bearers[i]!);
    const solved = sorted.map((b) => b.share);
    out.push({
      key: s.key,
      importing: s.importing,
      custom: s.custom || s.stored !== undefined,
      bearers: sorted.map((b) => ({
        key: b.key,
        nodeId: b.nodeId,
        handle: b.handle,
        output: b.output,
        name: b.name,
        machine: b.machine,
        resourceKey: canonicalKey(input.aliases, b.resource),
        max: b.max,
        solverPick: b.solverPick,
        energy: b.energy,
        ports: b.ports,
      })),
      solved,
      shares: effectiveSharesOf(
        sorted.map((b) => b.key),
        solved,
        s.stored,
      ),
    });
  }
  return out;
}

/** How much of each resource the spares with a choice of place carry in (`importing`) or out, by canonical key. */
export function sparedOf(spares: readonly CoreSpare[], importing: boolean): Map<string, number> {
  const out = new Map<string, number>();
  for (const spare of spares) {
    if (spare.importing !== importing || spare.bearers.length < 2) continue;
    spare.bearers.forEach((b, i) => {
      out.set(b.resourceKey, (out.get(b.resourceKey) ?? 0) + spare.shares[i]! * b.max);
    });
  }
  return out;
}

// endregion

// region Power dial

/** What a power figure is divided by to read in the dial's unit; undefined for no division. */
export function dialDivisor(unit: string, ownTier?: string): number | undefined {
  if (unit === "eu") return undefined;
  if (unit === "amps") {
    return ownTier && ownTier !== "DEMO" && GT_VOLTAGE_TIERS.some(({ tier }) => tier === ownTier)
      ? getVoltageTierMaxEuT(ownTier as Exclude<MachineTier, "DEMO">)
      : undefined;
  }
  return getVoltageTierMaxEuT(unit as Exclude<MachineTier, "DEMO">);
}

export function dialValue(euT: number, unit: string, ownTier?: string): number {
  const d = dialDivisor(unit, ownTier);
  return d === undefined ? euT : euT / d;
}

export function dialSuffix(unit: string, ownTier?: string): string {
  if (unit === "eu") return "EU/t";
  if (unit === "amps") return dialDivisor(unit, ownTier) === undefined ? "EU/t" : `A ${ownTier}`;
  return `A ${unit}`;
}

// endregion

// region View

export const RAIL_EMPTY = {
  inputs: "gtnhplanner.rail.empty.inputs",
  outputs: "gtnhplanner.rail.empty.outputs",
  internal: "gtnhplanner.rail.empty.internal",
  machines: "gtnhplanner.rail.empty.machines",
  matches: "gtnhplanner.rail.empty.matches",
} as const;

/** A resource filter: the trimmed text, lower-cased, found in the label; no text keeps everything. */
export function matchesFilter(filter: string, label: string): boolean {
  const f = filter.trim().toLowerCase();
  return f === "" || label.toLowerCase().includes(f);
}

/**
 * What a section lists a line at: what the plan's spares carry is theirs, so a line left with nothing but that (and no
 * drawer or pin to set) is not listed. An internal line is always listed whole. A host that sets a rate on an unwired
 * port from the line (`keepOpen`) keeps a line with such a port too.
 */
export function keptAmount(
  line: {
    key: string;
    amount: number;
    drawers: readonly unknown[];
    pins: readonly unknown[];
    open?: readonly unknown[];
  },
  internal: boolean,
  spared: ReadonlyMap<string, number>,
  keepOpen = false,
): number | undefined {
  if (internal) return line.amount;
  const left = Math.max(0, line.amount - (spared.get(line.key) ?? 0));
  // What the spares leave within rounding of nothing is nothing: a kept line reads 0, never the rounding.
  const none = left <= Math.max(RAIL_CORE_EPS, line.amount * 1e-4);
  if (
    none &&
    line.drawers.length === 0 &&
    line.pins.length === 0 &&
    !(keepOpen && (line.open?.length ?? 0) > 0)
  )
    return undefined;
  return none ? 0 : left;
}

export function keptLines(
  lines: readonly CoreLine[],
  internal: boolean,
  spared: ReadonlyMap<string, number>,
  keepOpen = false,
): CoreLine[] {
  const out: CoreLine[] = [];
  for (const line of lines) {
    const amount = keptAmount(line, internal, spared, keepOpen);
    if (amount !== undefined) out.push({ ...line, amount });
  }
  return out;
}

export interface ViewFig {
  value: number;
  suffix: string;
}

export interface ViewEu {
  kind: "tooLow" | "made" | "steam" | "used" | "none";
  value?: number;
  suffix?: string;
}

export interface ViewEntry {
  nodeId: string;
  label: string;
  count: number;
  pinned: boolean;
  rule: string;
  pin?: number;
  upTo?: RailInUpTo;
  tier?: string;
  amps?: number;
  multiblock: boolean;
  state: string;
  eu: ViewEu;
}

export interface RailViewJson {
  dial: { unit: string; suffix: string; divisor?: number };
  sections: Array<{
    id: "INPUTS" | "OUTPUTS" | "INTERNAL";
    count: number;
    shown: number;
    empty?: string;
    lines: CoreLine[];
    spares: CoreSpare[];
  }>;
  machines: {
    count: number;
    shown: number;
    total: ViewFig;
    made?: ViewFig;
    net?: ViewFig;
    empty?: string;
    groups: Array<{ name: string; entries: ViewEntry[] }>;
  };
}

function viewEntry(e: RailInMachine, view: RailInput["view"]): ViewEntry {
  const { peak, power: unit } = view;
  const made = peak ? e.madeEuT : e.avgMadeEuT;
  const used = peak ? e.euT : e.avgEuT;
  const steam = peak ? e.steamLs : e.avgSteamLs;
  let eu: ViewEu;
  if (e.state === "under-powered") eu = { kind: "tooLow" };
  else if (made !== undefined && made > 0)
    eu = {
      kind: "made",
      value: dialValue(made, unit, e.madeTier),
      suffix: dialSuffix(unit, e.madeTier),
    };
  else if (steam !== undefined) eu = { kind: "steam", value: steam, suffix: "L/s" };
  else if (used !== undefined)
    eu = { kind: "used", value: dialValue(used, unit, e.tier), suffix: dialSuffix(unit, e.tier) };
  else eu = { kind: "none" };
  return {
    nodeId: e.nodeId,
    label: e.label,
    count: e.count,
    pinned: e.pinned,
    rule: e.rule,
    ...(e.pin !== undefined ? { pin: e.pin } : {}),
    ...(e.upTo ? { upTo: e.upTo } : {}),
    ...(e.tier !== undefined ? { tier: e.tier } : {}),
    ...(e.amps !== undefined ? { amps: e.amps } : {}),
    multiblock: e.multiblock,
    state: e.state,
    eu,
  };
}

/** The rail as the player sees it: every section, the machine list, and the dial. */
export function railViewOf(input: RailInput): RailViewJson {
  const model = railFromInput(input);
  const spares = sparesFromInput(input);
  const { filter, peak, power: unit } = input.view;
  const keepOpen = input.view.keepOpen === true;
  const dialUnitDivisor = dialDivisor(unit);
  const resources = (
    id: "INPUTS" | "OUTPUTS" | "INTERNAL",
    lines: readonly CoreLine[],
    sources: boolean,
    empty: string,
  ): RailViewJson["sections"][number] => {
    const internal = id === "INTERNAL";
    const kept = keptLines(
      lines,
      internal,
      internal ? new Map() : sparedOf(spares, sources),
      keepOpen,
    );
    const shown = kept.filter((line) => matchesFilter(filter, line.label));
    const groups = internal
      ? []
      : spares.filter(
          (s) =>
            s.importing === sources &&
            s.bearers.length >= 2 &&
            s.bearers.some((b) => matchesFilter(filter, b.name)),
        );
    const why =
      shown.length === 0 && groups.length === 0
        ? kept.length === 0
          ? empty
          : RAIL_EMPTY.matches
        : undefined;
    return {
      id,
      count: kept.length + groups.length,
      shown: shown.length + groups.length,
      ...(why !== undefined ? { empty: why } : {}),
      lines: shown,
      spares: groups,
    };
  };

  const groupsOf = new Map<string, ViewEntry[]>();
  let shownWhole = 0;
  for (const entry of model.machines) {
    if (!matchesFilter(filter, entry.label)) continue;
    const list = groupsOf.get(entry.label);
    const row = viewEntry(entry, input.view);
    if (list) list.push(row);
    else groupsOf.set(entry.label, [row]);
    shownWhole += Math.ceil(entry.count - 1e-9);
  }
  const power = peak ? model.peak : model.average;
  const suffix = dialSuffix(unit);
  const machinesEmpty =
    groupsOf.size === 0
      ? model.machines.length === 0
        ? RAIL_EMPTY.machines
        : RAIL_EMPTY.matches
      : undefined;
  return {
    dial: {
      unit,
      suffix,
      ...(dialUnitDivisor !== undefined ? { divisor: dialUnitDivisor } : {}),
    },
    sections: [
      resources("INPUTS", model.inputs, true, RAIL_EMPTY.inputs),
      resources("OUTPUTS", model.outputs, false, RAIL_EMPTY.outputs),
      resources("INTERNAL", model.internal, false, RAIL_EMPTY.internal),
    ],
    machines: {
      count: model.wholeMachines,
      shown: shownWhole,
      total: { value: dialValue(power.used, unit), suffix },
      ...(model.hasMade
        ? {
            made: { value: dialValue(power.made, unit), suffix },
            net: { value: dialValue(power.made - power.used, unit), suffix },
          }
        : {}),
      ...(machinesEmpty !== undefined ? { empty: machinesEmpty } : {}),
      groups: [...groupsOf].map(([name, entries]) => ({ name, entries })),
    },
  };
}

// endregion
