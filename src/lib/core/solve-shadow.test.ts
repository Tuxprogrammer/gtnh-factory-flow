import { existsSync, readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

import { parseFactoryProjectJson } from "@/lib/import-export/factory-json";
import type { FactoryProject, ThroughputResult } from "@/lib/model/types";
import { initLpEngine } from "@/lib/solver/lp-engine";
import { differences, numbersEqual } from "@/lib/solver/plan/__fixtures__/compare";
import { caseNames, loadInput, parseSolveInput } from "@/lib/solver/plan/__fixtures__/load";
import { loadWebsiteCases } from "@/lib/solver/plan/__fixtures__/website-cases";
import { projectForPlanBoard } from "@/lib/solver/plan/adapter/open";
import { node, project, recipe, wire } from "@/lib/solver/plan/adapter/test-kit";
import { calculateThroughput } from "@/lib/solver/throughput";
import { solvePlan } from "@/lib/solver/plan/solve-plan";
import type { Answer, SolveInput } from "@/lib/solver/plan/types";
import { withAdopt } from "./adopt";
import { core, loadCore, resetCoreForTests } from "./loader";
import { solveEngine, solveThroughCore } from "./solve-adopt";

/**
 * The shadow of the plan solve (restructure/adoption.md, step one): the website's own engine and the compiled core,
 * side by side, must answer alike.
 *
 * 1. Every recorded solve input (the 223 website cases, the 33 corpus cases and the 74 Up to plans, all in
 *    conformance/solver), and each again with the website's own kind of ids (`withWebsiteIds`), solved by the website's engine (`solvePlan`) and through the core the way the solve Worker
 *    calls it (`solveThroughCore`: the input as JSON, `SolverExports.solverSolveCached`, the answer read back into the
 *    website's `Answer`): the whole `Answer` compared, sets in any order, numbers by the golden rule (1e-6).
 * 2. Every reference plan (the plans the capture records, `tools/conformance/solver/reference-plans.capture.ts`) sent
 *    to the solve Worker's own code (`src/store/solve-books.worker.ts`, its message in and out) with the solve area
 *    off and on: the whole `ThroughputResult` the board reads compared, by the same rule.
 *
 * Sets compare in any order, and the LP engine's own status word in a SOLVER_UNSATISFIABLE note is not compared.
 * Listed below are the inputs where the two engines differ on purpose, each with why. A listed input that agrees
 * fails, so the list never outlives its reason.
 */
const root = process.cwd();
const present = existsSync(path.join(root, "public", "core", "manifest.json"));

const OIL_LINE =
  "src/lib/solver/plan/loosened-holds.test.ts > held levels read off a slightly inexact point never fail the plan > ";
const LOOSENED =
  "the website's HiGHS misses a held row by about 1e-7 and its least flow runs again with every hold loosened by 1e-6;" +
  " that point leaves Up to cards a millionth short of their counts (BOTTLENECK reads LIMITED, and at 0.5 a card" +
  " runs at 0.27 of its count that the core runs full). The core keeps the rows exactly: its answer is the right one.";

/** Inputs whose answers differ, beyond the LP's tolerance: the core's is the better one. */
const DIFFERS: Record<string, string> = {
  [`${OIL_LINE}Up to 1 solves at the 0.3 machines it needs (forward columns) #1`]: LOOSENED,
  [`${OIL_LINE}Up to 0.5 solves at the 0.3 machines it needs (forward columns) #2`]: LOOSENED,
  [`${OIL_LINE}Up to 0.31 solves at the 0.3 machines it needs (forward columns) #3`]: LOOSENED,
  "oil-line Up to 1": LOOSENED,
  "oil-line Up to 0.5": LOOSENED,
  "oil-line Up to 0.31": LOOSENED,
};

/**
 * Inputs the two engines answer alike only to within the LP's own tolerance (core-solver `WebsiteCasesTest`,
 * `UpToParityTest`): the Up to read-out compared whole, machine counts to a hundred-thousandth of a machine.
 */
const WITHIN_TOLERANCE: Record<string, string> = {
  [`${OIL_LINE}Up to 2 solves at the 0.3 machines it needs (forward columns) #4`]:
    "a 60-card oil line HiGHS solves to its own 1e-7 row tolerance",
  "oil-line Up to 2": "the same plan",
  "up to: mv-every-count-up-to":
    "the website's point loses 1.3e-6 charcoal/s at a wired output; the core keeps it",
  "up to: idle-noise":
    "the website's canonical point runs 1.6e-7 over the sink drawers' held total",
  "up to: idle-noise-beside-a-lone-card": "the same plan as idle-noise beside a lone Up to card",
};

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

/**
 * The input with every id written as the website writes its own (`node-<uuid>`, a shared card's `#r1` and the like are
 * not UUIDs): the core is handed renamed ids (`solve-adopt.ts`) and its answer is named back, so every recorded input
 * also runs through that path. Stored leftover splits are keyed by the renamed ids, sorted again as the website sorts
 * them.
 */
function withWebsiteIds(i: SolveInput): SolveInput {
  const id = (x: string) => `n-${x}`;
  const key = (k: string) =>
    k
      .split("|")
      .map((part) => {
        const io = part.lastIndexOf(":", part.lastIndexOf(":") - 1);
        return io < 0 ? part : `${id(part.slice(0, io))}${part.slice(io)}`;
      })
      .sort()
      .join("|");
  return {
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
 * The argument of a SOLVER_UNSATISFIABLE note is the LP engine's own status word ("infeasible" from HiGHS, "INFEASIBLE"
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

/** The parts WITHIN_TOLERANCE compares: the Up to read-out and rings, and the counts to 1e-5 of a machine. */
function withinTolerance(ts: Answer, viaCore: Answer): string[] {
  if (ts.kind !== "solved" || viaCore.kind !== "solved") return ["not both solved"];
  const out = differences(
    sortedSets({ upTo: ts.solution.upTo, loops: ts.solution.upToLoops }),
    sortedSets({ upTo: viaCore.solution.upTo, loops: viaCore.solution.upToLoops }),
  );
  for (const [id, count] of Object.entries(ts.solution.machineCounts)) {
    const other = viaCore.solution.machineCounts[id];
    if (other === undefined || Math.abs(other - count) > 1e-5)
      out.push(`machineCounts.${id}: ${count} / ${other}`);
  }
  return out;
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
 * leftover with two bearers (`adapter/extras.test.ts`), with the website's own ids. With `split`, a stored split of it.
 * The recorded cases hold no split of a leftover with several bearers (their capture renames ids within a key split on
 * ",", where the key joins its bearers with "|"), so these plans carry the stored splits through the core.
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

/** Each plan made when its test runs (a stored split is read off a solve, which needs the engines loaded). */
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

describe("plan solve shadow: the website's engine and the compiled core agree", () => {
  it("has the core when CI requires it", () => {
    if (process.env.GTNH_CORE_REQUIRED === "1") expect(present).toBe(true);
  });

  describe.skipIf(!present)("through the core", () => {
    let send: (project: FactoryProject) => Promise<WorkerReply>;
    const g = globalThis as unknown as { self?: unknown };
    const hadSelf = "self" in g;
    const oldSelf = g.self;

    beforeAll(async () => {
      await initLpEngine();
      resetCoreForTests();
      await loadCore();
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
    }, 120_000);

    afterAll(() => {
      if (hadSelf) g.self = oldSelf;
      else delete g.self;
      resetCoreForTests();
    });

    const recorded = recordedInputs();
    const inputs = [
      ...recorded,
      ...recorded
        .filter((c) => !ONCE.has(c.name) && !c.name.startsWith("oil-line"))
        .map((c) => ({ name: `${c.name} [website ids]`, input: withWebsiteIds(c.input) })),
    ];
    it("has every recorded input", () => {
      expect(recorded.length).toBeGreaterThanOrEqual(223 + 33 + 74 + 4);
      expect(
        recorded.some((c) => Object.keys(c.input.spares ?? {}).length > 0),
        "a stored split",
      ).toBe(true);
      for (const name of [...Object.keys(DIFFERS), ...Object.keys(WITHIN_TOLERANCE)])
        expect(
          inputs.map((i) => i.name),
          name,
        ).toContain(name);
    });

    it.each(inputs.map((c) => [c.name, c] as const))(
      "%s",
      (name, c) => {
        const ts = solvePlan(c.input);
        const viaCore = solveThroughCore(core(), c.input);
        expect(viaCore, "the core takes every recorded input").not.toBeNull();
        const diffs = differences(sortedSets(ts), sortedSets(viaCore));
        const listed = name.replace(/ \[website ids\]$/, "");
        if (DIFFERS[listed]) {
          expect(ts.kind).toBe("solved");
          expect(viaCore!.kind).toBe("solved");
          expect(diffs, `${name} is listed as differing but agrees`).not.toEqual([]);
          return;
        }
        if (WITHIN_TOLERANCE[listed]) {
          const d = withinTolerance(ts, viaCore!);
          expect(d, d.join("\n")).toEqual([]);
          return;
        }
        expect(diffs, diffs.slice(0, 20).join("\n")).toEqual([]);
      },
      120_000,
    );

    const plans = referencePlans();
    it.each(plans.map((p) => [p.name, p] as const))(
      "the solve Worker's books for %s",
      async (_name, plan) => {
        const p = { project: plan.project() };
        const before = await withAdopt({ solve: false }, () => send(p.project));
        const after = await withAdopt({ solve: true }, async () => {
          expect(solveEngine()).toBe("core");
          return send(p.project);
        });
        expect(before.error).toBeUndefined();
        expect(after.error).toBeUndefined();
        const diffs = differences(books(before.result!), books(after.result!));
        expect(diffs, diffs.slice(0, 20).join("\n")).toEqual([]);
        if (plan.split)
          expect(after.result!.plan!.leftovers[0]!.custom, "the stored split holds").toBe(true);
      },
      120_000,
    );

    it("the comparison rule is the golden one", () => {
      expect(numbersEqual(1, 1 + 5e-7)).toBe(true);
      expect(numbersEqual(1, 1 + 2e-6)).toBe(false);
    });
  });
});
