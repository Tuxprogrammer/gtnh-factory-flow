/**
 * The compiled core behind the plan solve's seam (`src/lib/solver/plan/adapter/engine.ts`). Plan code may not reach
 * the loader, so the core's side lives here: importing this file installs the bridge. Only the threads that solve
 * import it: the solve Worker (`src/store/solve-books.worker.ts`), the server's plan routes
 * (`src/lib/server/plan-engine.ts`) and, where there is no Worker, the main thread (`src/store/solve-books.ts`, on
 * demand). A main thread with a Worker never fetches the solver module.
 *
 * Everything between a plan and the solver's input, and between its answer and the board, stays the website's
 * (`adapter/model.ts`, `answer.ts`, `finalize.ts`): only the engine changes. One call per solve:
 * `SolverExports.solverSolveCached(input JSON)` answers the golden digest (`SolverCodec` in core-solver), which is turned
 * back into the website's `Answer` here.
 *
 * Which engine solves (`solveEngine`): the core when the solve area is adopted (`adopt.ts`), the solver module has
 * loaded, and it loaded on a target in `SOLVE_TARGETS` (WebAssembly GC). An engine without WebAssembly GC (the loader
 * would fall back to the JavaScript build, which is slower than the website's engine there) fetches no solver and keeps
 * the website's engine. When the load fails the website's engine answers too; the solve Worker waits for the load
 * (`src/store/solve-engines.ts`), and a main thread with no Worker uses the website's engine until it is done.
 */
import {
  setSolveCoreBridge,
  typescriptSolveCore,
  type SolveCore,
} from "@/lib/solver/plan/adapter/engine";
import type {
  Alternative,
  Alternatives,
  Answer,
  Bearer,
  ChoiceKey,
  DrawerReadout,
  External,
  Id,
  Leftover,
  Note,
  NoteArg,
  PortRef,
  Shortfall,
  SolutionView,
  SolveInput,
  UpToLoop,
  UpToReadout,
} from "@/lib/solver/plan/types";
import { coreFor, isAdopted } from "./adopt";
import type { CoreExports } from "./exports.generated";
import { coreInfo, loadCore, wasmGcSupported, type CoreTarget, type LoadOptions } from "./loader";

/**
 * The targets the core solves on. WebAssembly GC: from an edit to the answer no slower than the website's engine on
 * any reference plan in a browser (10 to 1,000 cards; about half the time on a 484-card board), and the first solve
 * after a page load no later. The JavaScript build is slower than the website's engine on the first solve and on
 * small and mid-sized plans (about twice on a 33-card plan): the website's engine stays there.
 */
export const SOLVE_TARGETS: readonly CoreTarget[] = ["wasm-gc"];

/** The solver's module: the split build loads it alone, on first use, inside the solve Worker. */
const SOLVER_LOAD: LoadOptions = { modules: ["solver"] };

/** Which target the solver module runs on, once loaded. */
export function solverTarget(): CoreTarget | null {
  const info = coreInfo() as
    | (ReturnType<typeof coreInfo> & {
        modules?: Record<string, { target: CoreTarget } | undefined>;
      })
    | null;
  if (!info) return null;
  return info.modules?.solver?.target ?? info.target;
}

/**
 * Whether this engine can load the core on a target it solves on. Without WebAssembly GC the loader would fetch the
 * JavaScript build, which does not solve here: nothing is fetched at all.
 */
function solvesHere(): boolean {
  return SOLVE_TARGETS.includes("js") || wasmGcSupported();
}

/**
 * Starts loading the solver module when the solve area is adopted and this engine can run it on a solving target.
 * Resolves true when the core will solve, false when it will not (off, missing, failed, or a target not chosen); never
 * rejects. The solve Worker waits for it (bounded); elsewhere the website's engine answers until it is ready.
 */
export async function warmSolveCore(): Promise<boolean> {
  if (!isAdopted("solve") || !solvesHere()) return false;
  try {
    await loadCore(SOLVER_LOAD);
  } catch {
    return false;
  }
  return coreEngine() !== null;
}

/** The engine the next solve uses: "core" or "website". For tests and the measurements. */
export function solveEngine(): "core" | "website" {
  return coreEngine() ? "core" : "website";
}

function coreEngine(): SolveCore | null {
  if (!isAdopted("solve") || !solvesHere()) return null;
  const core = coreFor("solve");
  if (!core) return null;
  const target = solverTarget();
  if (!target || !SOLVE_TARGETS.includes(target)) return null;
  return engineOf(core);
}

let engine: { core: CoreExports; solve: SolveCore } | undefined;
let reported = false;

function engineOf(core: CoreExports): SolveCore {
  if (engine?.core === core) return engine.solve;
  const solve: SolveCore = (input) => {
    try {
      const answer = solveThroughCore(core, input);
      if (answer) return answer;
    } catch (error) {
      // A throw is a malformed input or a bug in the core, never a plan the player can fix: the website's engine
      // answers instead, and the console says so once.
      if (!reported) {
        reported = true;
        console.error("the core's solver failed; the website's engine solves instead", error);
      }
    }
    return typescriptSolveCore(input);
  };
  engine = { core, solve };
  return solve;
}

/** The core's answer to one input, or null for an input the core cannot be given (the website's engine answers). */
export function solveThroughCore(core: CoreExports, input: SolveInput): Answer | null {
  const ids = idsFor(input);
  if (!ids) return null;
  const wire = inputJson(input, ids.forward);
  if (wire === null) return null;
  const text = core.SolverExports.solverSolveCached(wire);
  const digest = JSON.parse(text) as Digest;
  const answer = answerOf(ids.identity ? digest : (renameBack(digest, ids.back) as Digest));
  // A leftover's key named back holds the website's ids in the order of the core's: sorted again, as the website keys it.
  if (!ids.identity && answer.kind === "solved")
    for (const leftover of answer.solution.spares) leftover.key = keyWith(leftover.key, (id) => id);
  // The digest lists the unneeded machines by their text; the website's engine in machine order (the input's), and the
  // board's lists keep the order the website's engine gives.
  if (answer.kind === "solved" && answer.solution.unneeded.length > 1) {
    const at = new Map(input.machines.map((m, k) => [m.id, k]));
    answer.solution.unneeded.sort((a, b) => (at.get(a) ?? 0) - (at.get(b) ?? 0));
  }
  return answer;
}

// ---------------------------------------------------------------------------------------------------------------------
// Ids. The core reads every id as a UUID. The website's are UUIDs except a few of its own making (a shared card's
// sections, `card#rN`). The solve orders ids as Java's UUID.compareTo, with the website's own ids after every UUID by
// their text (`plan/ids.ts` compareIds); a renaming that keeps that order keeps the answer. A UUID written another way
// (capitals, no hyphens) becomes its canonical text, which compares the same; every other id becomes
// 7fffffff-ffff-ffff-0000-<k>, k its place among them by text: the largest UUIDs there are, in the same order.

const CANONICAL = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const UUID_ANY = /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/i;
const UUID_IN_TEXT = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g;

interface Ids {
  identity: boolean;
  forward: (id: Id) => Id;
  back: Map<Id, Id>;
}

/**
 * A spares key: one bearer (`node:i:port`, `spares.ts` bearerKey) or a leftover's bearers, sorted by their text and
 * joined by "|" (`port-key.ts` makeLeftoverKey, the kernel's `Spares` the same).
 */
const KEY_SEPARATOR = "|";

/** A spares key's node ids. */
function keyNodes(key: string): string[] {
  return key.split(KEY_SEPARATOR).flatMap((part) => {
    const io = part.lastIndexOf(":", part.lastIndexOf(":") - 1);
    return io < 0 ? [] : [part.slice(0, io)];
  });
}

/** A spares key with its node ids renamed, its bearers sorted again by their new text as both engines sort them. */
function keyWith(key: string, rename: (id: Id) => Id): string {
  return key
    .split(KEY_SEPARATOR)
    .map((part) => {
      const io = part.lastIndexOf(":", part.lastIndexOf(":") - 1);
      return io < 0 ? part : `${rename(part.slice(0, io))}${part.slice(io)}`;
    })
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
    .join(KEY_SEPARATOR);
}

function idsFor(input: SolveInput): Ids | null {
  const all = new Set<Id>();
  for (const m of input.machines) all.add(m.id);
  for (const e of input.edges) all.add(e.id).add(e.source).add(e.target);
  for (const p of input.pools) for (const m of p.machines) all.add(m);
  for (const d of input.drawers) {
    all.add(d.id);
    for (const l of d.links) all.add(l.node);
    for (const l of d.takers ?? []) all.add(l.node);
  }
  for (const r of input.choice ?? []) all.add(r.node);
  for (const id of Object.keys(input.extentPins)) all.add(id);
  for (const [k, shares] of Object.entries(input.spares ?? {}))
    for (const key of [k, ...Object.keys(shares)]) for (const id of keyNodes(key)) all.add(id);
  const to = new Map<Id, Id>();
  const own: Id[] = [];
  for (const id of all) {
    if (CANONICAL.test(id)) continue;
    if (UUID_ANY.test(id)) {
      const h = id.replace(/-/g, "").toLowerCase();
      to.set(
        id,
        `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`,
      );
    } else own.push(id);
  }
  own.sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  own.forEach((id, k) => to.set(id, `7fffffff-ffff-ffff-0000-${k.toString(16).padStart(12, "0")}`));
  if (to.size === 0) return { identity: true, forward: (id) => id, back: new Map() };
  const back = new Map<Id, Id>();
  for (const id of all) {
    const coreId = to.get(id) ?? id;
    // Two ids that would meet as one: the core cannot be told them apart.
    if (back.has(coreId)) return null;
    back.set(coreId, id);
  }
  return { identity: false, forward: (id) => to.get(id) ?? id, back };
}

function renameBack(value: unknown, back: Map<Id, Id>): unknown {
  if (typeof value === "string") {
    const whole = back.get(value);
    if (whole !== undefined) return whole;
    return value.includes("-") ? value.replace(UUID_IN_TEXT, (m) => back.get(m) ?? m) : value;
  }
  if (Array.isArray(value)) return value.map((v) => renameBack(v, back));
  if (value !== null && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value))
      out[renameBack(k, back) as string] = renameBack(v, back);
    return out;
  }
  return value;
}

// ---------------------------------------------------------------------------------------------------------------------
// The input, as `SolveInputJson` writes it (`conformance/solver/corpus/README.md`). Null when a number cannot be
// written as JSON (the core would read it as nothing).

function inputJson(i: SolveInput, id: (x: Id) => Id): string | null {
  let finite = true;
  const n = (x: number) => {
    if (!Number.isFinite(x)) finite = false;
    return x;
  };
  const rates = (r: Record<number, number>) =>
    Object.fromEntries(Object.entries(r ?? {}).map(([k, v]) => [k, n(v)]));
  const port = (p: SolveInput["machines"][number]["inputs"][number]) => ({
    amount: n(p.amount),
    chance: n(p.chance),
    multiplier: n(p.multiplier),
    resource: p.resource,
    name: p.name,
    free: p.free,
  });
  const link = (l: { node: Id; port: number }) => ({ node: id(l.node), port: l.port });
  const sparesKey = (key: string) => keyWith(key, id);
  const body = {
    mode: i.mode,
    ...(i.choice
      ? { choice: i.choice.map((r) => ({ node: id(r.node), port: r.port, input: r.input })) }
      : {}),
    machines: i.machines.map((m) => ({
      id: id(m.id),
      name: m.name,
      durationTicks: n(m.durationTicks),
      energyPerTick: n(m.energyPerTick),
      throughputFactor: n(m.throughputFactor),
      inputs: m.inputs.map(port),
      outputs: m.outputs.map(port),
      countFixed: m.countFixed,
      machineCount: n(m.machineCount),
      targetOutputRates: rates(m.targetOutputRates),
      targetInputRates: rates(m.targetInputRates),
    })),
    edges: i.edges.map((e) => ({
      id: id(e.id),
      source: id(e.source),
      sourceOutput: e.sourceOutput,
      target: id(e.target),
      targetInput: e.targetInput,
    })),
    pools: i.pools.map((p) => ({
      machines: p.machines.map(id),
      capacity: n(p.capacity),
      exact: p.exact,
      ...(p.upTo ? { upTo: true } : {}),
    })),
    drawers: i.drawers.map((d) => ({
      id: id(d.id),
      label: d.label,
      kind: d.kind,
      rule: d.rule,
      rate: n(d.rate),
      links: d.links.map(link),
      ...(d.takers && d.takers.length > 0 ? { takers: d.takers.map(link) } : {}),
    })),
    extentPins: Object.fromEntries(Object.entries(i.extentPins).map(([k, v]) => [id(k), n(v)])),
    spares: Object.fromEntries(
      Object.entries(i.spares ?? {}).map(([k, shares]) => [
        sparesKey(k),
        Object.fromEntries(Object.entries(shares).map(([b, share]) => [sparesKey(b), n(share)])),
      ]),
    ),
  };
  return finite ? JSON.stringify(body) : null;
}

// ---------------------------------------------------------------------------------------------------------------------
// The answer: the golden digest (`SolverCodec.digest`, `plan/__fixtures__/digest.ts` writes the same from an `Answer`)
// read back into the website's records. Ports are `{nodeId, portIndex, input}` there; the Up to read-out writes every
// field, null or empty where the website leaves one out.

type W = Record<string, unknown>;
type Digest = W;

const port = (p: W): PortRef => ({
  node: p.nodeId as Id,
  port: p.portIndex as number,
  input: p.input as boolean,
});
const external = (e: W): External => ({
  port: port(e.port as W),
  ratePerSecond: e.ratePerSecond as number,
});
const externals = (list: unknown): External[] => (list as W[]).map(external);
const key = (k: W): ChoiceKey => ({ gateAnchors: (k.gateAnchors as W[]).map(port) });

function noteArg(a: unknown): NoteArg {
  if (Array.isArray(a)) return a.map((x) => noteArg(x)) as Note[];
  if (a !== null && typeof a === "object") {
    const o = a as W;
    if ("message" in o) return note(o);
    if ("nodeId" in o) return port(o) as unknown as NoteArg;
  }
  return a as NoteArg;
}
function note(n: W): Note {
  return { message: n.message as Note["message"], args: (n.args as unknown[]).map(noteArg) };
}
const notes = (list: unknown): Note[] => (list as W[]).map(note);

function drawers(d: W): DrawerReadout {
  const portsOf = (m: unknown) =>
    Object.fromEntries(
      Object.entries(m as Record<string, unknown>).map(([k, v]) => [k, externals(v)]),
    );
  return {
    rates: d.rates as Record<Id, number>,
    unmet: d.unmet as Id[],
    shortfalls: (d.shortfalls as W[]).map(
      (s): Shortfall => ({
        drawer: s.drawer as Id,
        rule: s.rule as Shortfall["rule"],
        target: s.target as number,
        reachable: s.reachable as number,
        limitingNodes: s.limitingNodes as Id[],
        limitingDrawers: s.limitingDrawers as Id[],
        limitingGroups: s.limitingGroups as number,
      }),
    ),
    ports: portsOf(d.ports),
    ...(d.banked !== undefined ? { banked: d.banked as Record<Id, number> } : {}),
    ...(d.spills !== undefined ? { spills: portsOf(d.spills) } : {}),
  };
}

function upTo(r: W): UpToReadout {
  const tied = (r.tied as W[]).map(port);
  const upstream = r.upstream as Id[];
  return {
    node: r.node as Id,
    machines: r.machines as Id[],
    capacity: r.capacity as number,
    reached: r.reached as number,
    reason: r.reason as UpToReadout["reason"],
    ...(r.port !== null ? { port: port(r.port as W) } : {}),
    ...(tied.length > 0 ? { tied } : {}),
    ...(r.wants !== null ? { wants: r.wants as number } : {}),
    ...(upstream.length > 0 ? { upstream } : {}),
  };
}

function solution(s: W): SolutionView {
  return {
    extentsPerSecond: s.extentsPerSecond as Record<Id, number>,
    machineCounts: s.machineCounts as Record<Id, number>,
    edgeFlowsPerSecond: s.edgeFlowsPerSecond as Record<Id, number>,
    gatedSources: externals(s.gatedSources),
    gatedSinks: externals(s.gatedSinks),
    terminalInputs: externals(s.terminalInputs),
    terminalOutputs: externals(s.terminalOutputs),
    unneeded: s.unneeded as Id[],
    openGates: s.openGates as number,
    externalQuantity: s.externalQuantity as number,
    totalInternalFlow: s.totalInternalFlow as number,
    notes: notes(s.notes),
    key: key(s.key as W),
    drawers: drawers(s.drawers as W),
    spares: (s.spares as W[]).map(
      (l): Leftover => ({
        key: l.key as string,
        importing: l.importing as boolean,
        bearers: (l.bearers as W[]).map(
          (b): Bearer => ({
            anchor: port(b.anchor as W),
            flows: externals(b.flows),
            max: b.max as number,
            share: b.share as number,
            solverPick: b.solverPick as boolean,
          }),
        ),
        custom: l.custom as boolean,
      }),
    ),
    ...(s.upTo !== undefined ? { upTo: (s.upTo as W[]).map(upTo) } : {}),
    ...(s.upToLoops !== undefined
      ? {
          upToLoops: (s.upToLoops as W[]).map(
            (l): UpToLoop => ({
              kind: l.kind as UpToLoop["kind"],
              nodes: l.nodes as Id[],
              ports: (l.ports as W[]).map(port),
            }),
          ),
        }
      : {}),
  };
}

function alternatives(a: W): Alternatives {
  return {
    chosen: a.chosen === null ? null : key(a.chosen as W),
    options: (a.options as W[]).map(
      (o): Alternative => ({
        key: key(o.key as W),
        replaces: port(o.replaces as W),
        opens: port(o.opens as W),
        externals: externals(o.externals),
        rank: o.rank as Alternative["rank"],
      }),
    ),
    complete: a.complete as boolean,
    notes: notes(a.notes),
  };
}

/** A digest as the website's `Answer`. */
export function answerOf(d: Digest): Answer {
  if (d.failure !== undefined) {
    return {
      kind: "failed",
      failure: note(d.failure as W),
      notes: notes(d.notes),
      drawers: drawers(d.drawers as W),
    };
  }
  return {
    kind: "solved",
    solution: solution(d.solution as W),
    alternatives: alternatives(d.alternatives as W),
  };
}

setSolveCoreBridge(coreEngine);
