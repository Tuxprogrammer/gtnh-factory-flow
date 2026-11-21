import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

import { attachClogLocks } from "@/components/flow/clog-lock";
import { parseFactoryProjectJson } from "@/lib/import-export/factory-json";
import type { FactoryProject, ThroughputResult } from "@/lib/model/types";
import { differences, numbersEqual } from "@/lib/solver/plan/__fixtures__/compare";
import { caseNames, loadInput, parseSolveInput } from "@/lib/solver/plan/__fixtures__/load";
import { compareIds } from "@/lib/solver/plan/ids";
import { loadWebsiteCases } from "@/lib/solver/plan/__fixtures__/website-cases";
import { projectForPlanBoard } from "@/lib/solver/plan/adapter/open";
import { node, project, recipe, wire } from "@/lib/solver/plan/adapter/test-kit";
import { calculateThroughput } from "@/lib/solver/throughput";
import type { Answer, SolveInput } from "@/lib/solver/plan/types";
import { CORE_LOAD_MS, coreSolve, useCoreSolver } from "@/test-support/core-solve";
import { resetCoreForTests } from "./loader";
import { solveEngine } from "./solve-adopt";

/**
 * The plan solve through the compiled core, the way the website hands plans to it (`solve-adopt.ts`).
 *
 * 1. Every recorded solve input (the website cases, the corpus and the Up to plans, all in conformance/solver, and the
 *    oil line), solved with its UUIDs and again with the website's own kind of ids (`withWebsiteIds`): the core is
 *    handed renamed ids and its answer is named back, so the two answers are the same answer. (Each recorded answer
 *    itself is held by `website-cases.test.ts`, `golden.test.ts` and the kernel's `UpToParityTest`.)
 * 2. Every reference plan (the plans the capture records, `tools/conformance/solver/reference-plans.capture.ts`) sent
 *    to the solve Worker's own code (`src/store/solve-books.worker.ts`, its message in and out): the whole
 *    `ThroughputResult` the board reads equals the main thread's for the same plan, on the same engine.
 *
 * Sets compare in any order, and the LP engine's own status word in a SOLVER_UNSATISFIABLE note is not compared.
 */
const root = process.cwd();

interface Recorded {
  name: string;
  input: SolveInput;
}

function recordedInputs(): Recorded[] {
  const out: Recorded[] = loadWebsiteCases().map((c) => ({
    name: c.name,
    input: parseSolveInput(c.input),
  }));
  for (const name of caseNames()) out.push({ name: `corpus: ${name}`, input: loadInput(name) });
  const upTo = path.join(root, "conformance", "solver", "up-to");
  for (const f of readdirSync(upTo)
    .filter((f) => f.endsWith(".json"))
    .sort()) {
    const plan = JSON.parse(readFileSync(path.join(upTo, f), "utf8")) as { input: unknown };
    out.push({ name: `up to: ${f.slice(0, -5)}`, input: parseSolveInput(plan.input) });
  }
  // The oil line as the website hands it over (its own ids, not renamed to UUIDs as the recorded cases are).
  const oil = JSON.parse(
    readFileSync(
      path.join(root, "src/lib/solver/plan/__fixtures__/up-to/oil-line-up-to-one.json"),
      "utf8",
    ),
  ) as SolveInput;
  const electrolyzer = "node-2964b946-4f03-45bc-b142-ee4b5da3a671";
  for (const capacity of [1, 0.5, 0.31, 2])
    out.push({
      name: `oil-line Up to ${capacity}`,
      input: {
        ...oil,
        pools: oil.pools.map((p) => (p.machines.includes(electrolyzer) ? { ...p, capacity } : p)),
      },
    });
  return out;
}

/** Every id an input names, the spares keys' node ids among them. */
function idsOf(i: SolveInput): Set<string> {
  const all = new Set<string>();
  const keyNodes = (k: string) =>
    k.split("|").flatMap((part) => {
      const io = part.lastIndexOf(":", part.lastIndexOf(":") - 1);
      return io < 0 ? [] : [part.slice(0, io)];
    });
  for (const m of i.machines) all.add(m.id);
  for (const e of i.edges) all.add(e.id).add(e.source).add(e.target);
  for (const p of i.pools) for (const m of p.machines) all.add(m);
  for (const d of i.drawers) {
    all.add(d.id);
    for (const l of [...d.links, ...(d.takers ?? [])]) all.add(l.node);
  }
  for (const r of i.choice ?? []) all.add(r.node);
  for (const k of Object.keys(i.extentPins)) all.add(k);
  for (const [k, shares] of Object.entries(i.spares ?? {}))
    for (const key of [k, ...Object.keys(shares)]) keyNodes(key).forEach((n) => all.add(n));
  return all;
}

/**
 * The input with every id written as the website writes its own (`node-<uuid>`, a shared card's `#r1` and the like are
 * not UUIDs), in the order the solve reads them (`compareIds`), so the plan is the same plan: the core is handed renamed
 * ids (`solve-adopt.ts`) and its answer is named back, so every recorded input also runs through that path. Stored
 * leftover splits are keyed by the renamed ids, sorted again as the website sorts them. `back` names an answer's ids
 * as the input's.
 */
function withWebsiteIds(i: SolveInput): { input: SolveInput; back: (a: Answer) => Answer } {
  const order = [...idsOf(i)].sort(compareIds);
  const to = new Map(order.map((x, k) => [x, `n-${k.toString().padStart(6, "0")}`]));
  const from = new Map([...to].map(([a, b]) => [b, a]));
  const id = (x: string) => to.get(x) ?? x;
  const key = (k: string) =>
    k
      .split("|")
      .map((part) => {
        const io = part.lastIndexOf(":", part.lastIndexOf(":") - 1);
        return io < 0 ? part : `${id(part.slice(0, io))}${part.slice(io)}`;
      })
      .sort()
      .join("|");
  const input: SolveInput = {
    ...i,
    choice: i.choice ? i.choice.map((r) => ({ ...r, node: id(r.node) })) : null,
    machines: i.machines.map((m) => ({ ...m, id: id(m.id) })),
    edges: i.edges.map((e) => ({ ...e, id: id(e.id), source: id(e.source), target: id(e.target) })),
    pools: i.pools.map((p) => ({ ...p, machines: p.machines.map(id) })),
    drawers: i.drawers.map((d) => ({
      ...d,
      id: id(d.id),
      links: d.links.map((l) => ({ ...l, node: id(l.node) })),
      ...(d.takers ? { takers: d.takers.map((l) => ({ ...l, node: id(l.node) })) } : {}),
    })),
    extentPins: Object.fromEntries(Object.entries(i.extentPins).map(([k, v]) => [id(k), v])),
    spares: Object.fromEntries(
      Object.entries(i.spares ?? {}).map(([k, shares]) => [
        key(k),
        Object.fromEntries(Object.entries(shares).map(([b, share]) => [key(b), share])),
      ]),
    ),
  };
  const name = (text: string) => text.replace(/n-\d{6}/g, (m) => from.get(m) ?? m);
  const unkey = (k: string) => name(k).split("|").sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)).join("|");
  const walk = (v: unknown, inKey = false): unknown => {
    if (typeof v === "string") return (v.includes("|") && /n-\d{6}/.test(v)) || inKey ? unkey(v) : name(v);
    if (Array.isArray(v)) return v.map((x) => walk(x));
    if (v !== null && typeof v === "object")
      return Object.fromEntries(
        Object.entries(v).map(([k, x]) => [walk(k), walk(x, k === "key")]),
      );
    return v;
  };
  return { input, back: (a) => walk(a) as Answer };
}

/** The tiled plans are the corpus's timing cases: solved once, with UUIDs. */
const ONCE = new Set([
  "corpus: platline-x4",
  "corpus: platline-x10",
  "corpus: gtnh-flow-palladium_line",
]);

/** Arrays that are sets in the answer, in a fixed order: the engines list them in different orders. */
const SETS = new Set(["unneeded", "unmet", "limitingNodes", "limitingDrawers"]);

/**
 * The argument of a SOLVER_UNSATISFIABLE note is the LP engine's own status word ("infeasible" from the simplex, "INFEASIBLE"
 * or "INVALID" from ojAlgo): never compared, as in the corpus (core-solver `WebsiteCasesTest.engineWords`), and never
 * shown (the rail reads only the note's kind, `rail/notices.ts`).
 */
function sortedSets(value: unknown, key = "", sets = true): unknown {
  if (
    value !== null &&
    typeof value === "object" &&
    (value as { message?: unknown }).message === "SOLVER_UNSATISFIABLE"
  )
    return { message: "SOLVER_UNSATISFIABLE", args: ["(the engine's status)"] };
  if (Array.isArray(value)) {
    const items = value.map((v) => sortedSets(v, "", sets));
    return sets && SETS.has(key)
      ? [...items].sort((a, b) => (JSON.stringify(a) < JSON.stringify(b) ? -1 : 1))
      : items;
  }
  if (value !== null && typeof value === "object")
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, sortedSets(v, k, sets)]));
  return value;
}

const plansRoot = (dir: string) => path.join(root, dir);
const PLAN_DIRS = [
  "conformance/plans/website",
  "conformance/plans/mod",
  "conformance/plans/valid",
  "src/components/flow/__fixtures__",
  "jvm/mod/src/test/resources/factory-flow",
  "examples",
];

/**
 * q (pinned, 1 W/s) -> p (W -> X) -> c (pinned, wants 2 X/s): one X/s short, bought at either of two places, so one
 * leftover with two bearers (`adapter/extras.test.ts`), with the website's own ids. With `split`, a stored split of it,
 * carried through the core with the website's ids.
 */
function shortChain(
  split?: (bearers: string[], pick: string) => Record<string, number>,
): FactoryProject {
  const build = (extra = {}) =>
    project({
      recipes: [
        recipe("rq", [], [["W", 1]]),
        recipe("rp", [["W", 1]], [["X", 1]]),
        recipe("rc", [["X", 1]], [["Y", 1]]),
      ],
      nodes: [
        node("q", "rq", { solvePin: 1, solvePinExact: true }),
        node("p", "rp"),
        node("c", "rc", { solvePin: 2, solvePinExact: true }),
      ],
      edges: [wire("w1", "q", "p", "W"), wire("w2", "p", "c", "X")],
      extra,
    });
  if (!split) return build();
  const leftover = calculateThroughput(build(), { generatedAt: "t" }).plan!.leftovers[0]!;
  const bearers = leftover.bearers.map((b) => b.portKey);
  const pick = leftover.bearers.find((b) => b.solverPick)!.portKey;
  return build({ spareShares: { [leftover.key]: split(bearers, pick) } });
}

/** Each plan made when its test runs (a stored split is read off a solve, which needs the core loaded). */
function referencePlans(): { name: string; project: () => FactoryProject; split?: true }[] {
  const out: { name: string; project: () => FactoryProject; split?: true }[] = [
    { name: "a leftover with two places", project: () => shortChain() },
    {
      name: "a stored split of it",
      project: () => shortChain(([a, b]) => ({ [a!]: 0.25, [b!]: 0.75 })),
      split: true,
    },
    {
      name: "all of it at the other place",
      project: () => shortChain((bearers, pick) => ({ [bearers.find((b) => b !== pick)!]: 1 })),
      split: true,
    },
  ];
  for (const dir of PLAN_DIRS)
    for (const f of readdirSync(plansRoot(dir))
      .filter((f) => f.endsWith(".json"))
      .sort()) {
      try {
        const project = projectForPlanBoard(
          parseFactoryProjectJson(readFileSync(path.join(plansRoot(dir), f), "utf8")),
        );
        out.push({ name: `${dir}/${f}`, project: () => project });
      } catch {
        // Not a plan this build reads.
      }
    }
  return out;
}

/** What the board reads, less the stamp of when it was made: every list in its order, as the board shows it. */
function books(result: ThroughputResult): unknown {
  const rest: Partial<ThroughputResult> = { ...result };
  delete rest.generatedAt;
  return sortedSets(JSON.parse(JSON.stringify(rest)), "", false);
}

type WorkerReply = { key: string; result?: ThroughputResult; error?: string };

describe("the plan solve through the compiled core", () => {
  let send: (project: FactoryProject) => Promise<WorkerReply>;
  const g = globalThis as unknown as { self?: unknown };
  const hadSelf = "self" in g;
  const oldSelf = g.self;

  beforeAll(async () => {
    resetCoreForTests();
    await useCoreSolver();
    // The solve Worker's own module, in this thread: its `self` is a stand-in that hands its replies back.
    let reply: ((r: WorkerReply) => void) | undefined;
    const scope: {
      onmessage?: (e: MessageEvent) => Promise<void>;
      postMessage: (r: WorkerReply) => void;
    } = {
      postMessage: (r) => reply?.(r),
    };
    g.self = scope;
    await import("@/store/solve-books.worker");
    send = (project) =>
      new Promise<WorkerReply>((resolve) => {
        reply = resolve;
        void scope.onmessage!({ data: { key: "k", project } } as MessageEvent);
      });
  }, CORE_LOAD_MS);

  afterAll(() => {
    if (hadSelf) g.self = oldSelf;
    else delete g.self;
    resetCoreForTests();
  });

  const recorded = recordedInputs().filter((c) => !ONCE.has(c.name));
  it("has every recorded input", () => {
    expect(recorded.length).toBeGreaterThanOrEqual(223 + 30 + 74 + 4);
    expect(
      recorded.some((c) => Object.keys(c.input.spares ?? {}).length > 0),
      "a stored split",
    ).toBe(true);
  });

  it.each(recorded.map((c) => [c.name, c] as const))(
    "%s with the website's own ids",
    (_name, c) => {
      const plain = coreSolve(c.input);
      const website = withWebsiteIds(c.input);
      const back = website.back(coreSolve(website.input));
      const diffs = differences(sortedSets(plain), sortedSets(back));
      expect(diffs, diffs.slice(0, 20).join("\n")).toEqual([]);
    },
    120_000,
  );

  const plans = referencePlans();
  it.each(plans.map((p) => [p.name, p] as const))(
    "the solve Worker's books for %s",
    async (_name, plan) => {
      const p = plan.project();
      expect(solveEngine()).toBe("core");
      const here = calculateThroughput(p);
      // The Worker attaches the clog-lock proof (none on a plan board) the main thread reads off its own books.
      attachClogLocks(p, here);
      const worker = await send(p);
      expect(worker.error).toBeUndefined();
      const diffs = differences(books(here), books(worker.result!));
      expect(diffs, diffs.slice(0, 20).join("\n")).toEqual([]);
      if (plan.split)
        expect(worker.result!.plan!.leftovers[0]!.custom, "the stored split holds").toBe(true);
    },
    120_000,
  );

  it("the comparison rule is the golden one", () => {
    expect(numbersEqual(1, 1 + 5e-7)).toBe(true);
    expect(numbersEqual(1, 1 + 2e-6)).toBe(false);
  });
});
