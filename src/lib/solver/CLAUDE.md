# Solver (src/lib/solver)

Doctrine: if it would fail in the game, it fails in the planner; otherwise everything
runs. A fed machine with somewhere to put its output never idles. Nothing comes from
nowhere or goes nowhere except through a source, a drawer or trash. Longer version:
`docs/solver-equations.md`.

## Two LPs

`throughput.ts` is the entry. Build and Solve/Pool answer different questions:

- BUILD (counts are given): every act, edge flow and total comes from `equations-core.ts`
  (behind `EQUATION_BOOKS`). Stage chain, each optimum locked before the next: max total
  act; max-min fairness over acts (the game's round-robin); recycle before import; ship
  before banking; min total flow (determinism). No "product purpose" or "least machinery"
  stage: both idled machines the game would run. Equal-fill rows make co-consumers of one
  output port fill at the same rate (what kills a tapped break-even ring; only
  output-side figures may exempt a consumer). Power stalls are pinned to act 0 in the LP.
- SOLVE and POOL (counts are the unknowns): `solve-mode.ts` (`solveSolveMode`, finished by
  `finalizeSolveModeResult`). Targets ARE rows here. Objective: least machinery, then
  least imports, fill and flow. No fairness, equal-fill or stall pinning.
- The iterative engine (`equilibrium.ts`) still runs and supplies the DIAGNOSIS
  (capability, clog names, "one wire fixes it"). If an LP fails, the old books stand; a
  solve failure degrades, never crashes. Balance dust snaps at 1e-5 relative
  (`balances.ts`).
- Engines: every plan solves on the compiled core (below). The website's own solvers (the plan solve's stand-in, the
  old Build, Solve and Pool engines) run on the homegrown simplex (`simplex.ts`); there is no other LP engine. Big or
  slow boards solve in a worker (`src/store/solve-books.ts`); nothing that size may run on the main thread.
- The plan solve's engine is `getSolveCore()` (`plan/adapter/engine.ts`), the one seam. In the solve Worker and the
  server's plan routes, and on a main thread with no Worker (`solve-books.ts` imports it on the first solve there),
  `src/lib/core/solve-adopt.ts` puts the compiled core behind it (`ADOPT.solve`, WebAssembly GC
  or its JavaScript build, same answers): the input goes over as JSON (`SolverExports.solverSolveCached`, island cache in the module), the golden digest
  comes back as the website's `Answer`; ids the website mints are renamed to UUIDs in the solve's own order and named
  back. In the Worker every solve waits for the core (`src/store/solve-engines.ts`, bounded), so a plan never shows the
  stand-in's numbers first; the TypeScript solver answers only where the core does not: a failed or hung load (the page
  then says solving is degraded: `src/store/solve-health.ts`, `SolveDegradedNotice`), and a main thread with no Worker
  until the core has loaded. The
  server's plan routes solve on the core only (`src/lib/server/plan-engine.ts`): without it they answer 503, and
  `npm run build` fails without a core (`prebuild`: `tools/copy-core.mjs --required`). The path through the core is held by `src/lib/core/solve-through-core.test.ts` (every recorded
  input, also with the website's kind of ids, and the Worker's books for every reference plan).
- Plan solver dust (`plan/validate.ts` `dustScales`) is judged per network, never over the whole point: a
  reactor's billions of EU a second must not zero a 0.03/s item wire (the answer then fails its own check).
- Node rates in results are FULL SPEED; actual = nameplate x `min(1, max(0, utilization))`.
- The core's LP engine answers to about 14 significant digits, so a ratio like 2/3 comes back a few ulps off and a figure
  on a display half (0.625 A) printed one step down. Every number a solve hands out is read once, where it leaves the
  solver (`SolutionView`), as the small ratio it is within 1e-12 of (core-base `SolvedNumber`: continued fraction,
  q <= 100000); dust is still judged on the raw values. Never round for display anywhere else. Exams: `SolvedNumberTest`,
  `solved-digits.test.ts`, the mod's `SolvedDigitsTest`.
- The plan adapter reads per-craft amounts from the nameplate loop (`PlanUnit.perCraft`): a card with no count has
  zero flows, and the bare recipe would drop machine effects (a TGS's tier and tools). Exam: `adapter/per-craft.test.ts`.
- Only synchronous solves write `lastSolveDurationMs` in `solve-books.ts`; worker timings
  never un-slow a plan.
  "A big plan lags every edit": look first for a diagnosis running on the main thread.
- Exams: `equations-doctrine.test.ts`, `simplex.test.ts`, `simplex-stall.test.ts`. The tick simulator
  (`src/lib/solver-lab/simulate.ts`) is the independent "what the game does" oracle.

## Modes

- Build (both flags off): you set machines, counts, wires; the board reports.
  Solve (`solveMode`): you type product rates; counts are solved. Pool (`solveMode` +
  `poolMode`): no wires; one shared pool per resource.
- Setup rules are gone: `getSetupRules` answers every plan the same
  (no free inputs or outputs). Tests needing an open boundary call `closeBoundaries`.
- Pool (`pool-mode.ts`, `expandPool`): hidden drawers and wires are added before the
  solve so the ordinary rules apply unchanged. Feeders + takers = a buffer (strict under
  Match, so intermediates balance; overflow under Ignore), feeders only = product drain,
  takers only = import source. Anything that walks the
  graph asks `getPoolProject(project)` first. Cell/fluid pairs bridge through a hidden
  free Tank using Canner-fetched litres (`poolCellRatios`), only from a side something feeds.
- Production groups (Pool only; Build and wired Solve ignore them): `productionGroupId` on
  nodes and storages, the tree in `productionGroups`, rules in `poolResourceRules` (root)
  and each group's `resourceRules`. Ignore is stored as `import` at the root, `share` in a
  group. Children resolve first, and a parent rule cannot undo a child's match.
  - Match (default): in a group, materials made and used there balance locally and
    one-sided ones reach the parent; at the root, anything nobody makes is imported.
    Pool Solve balances intermediates exactly, so a fixed input drives the chain.
  - Ignore: in a group, both sides pass to the parent; at the root, imports and surplus
    are allowed. It is permission to import: machinery is minimized first, so an unpinned
    producer may idle (pin its count).
  - Legacy scoped targets and direct outside-supply rules still load, labelled and editable.
  - Exams: `production-groups.test.ts` (solver and store), `input-driven-chain.test.ts`.

## Drawers and targets

- `model/storage-target.ts` is the one interpretation of `targetPerSecond` / `targetMode`
  (at-least, exact, ignore, at-most), shared by wired Solve and Pool. At most is a
  ceiling, not a demand. Build keeps settings dormant.
- Buffer modes: overflow banks surplus (Build's default); strict clogs (wired Solve's
  default for intermediates, see `effectiveBufferMode`); ratio splits by weights on both
  sides with a Setup output share (`storage-ratios.ts` here and in model/). Pool ignores
  manual ratio splits.
- Plan mode (`plan/`): a buffer wired only to cards is a BUFFER drawer (`DrawerIn.takers`), its rule a product's
  on what passes through (`docs/buffer-drawers.md`); `adapter/buffers.ts` joins only buffers wired to another drawer.
  The solver change mirrors the mod's line for line; adding any feature to both: `docs/cross-repo-features.md`.
- Drain drawers cycle product / byproduct / trash. Trash is free disposal with its books
  voided (`applyTrashedOutputBalances`). Old trash-can nodes convert on load.
- In Build, targets are display arithmetic; in Solve and Pool they are LP rows.

## Coupling and verdicts

The verdict and ring-diagnosis code lives in `src/components/flow/` (`node-verdict.ts`,
`death-spiral.ts`, `clog-lock.ts`, `bare-slots.ts`).

No plan board reaches the old engines: a plan-mode project always carries `solveMode`, so the
ring proofs return nothing, `deriveNodeVerdict` stops at its solve-mode line (off, no recipe,
unwired, balanced, demand set), and the Worker's `attachClogLocks` runs no LP.
`plan/one-mode-reach.test.ts` pins this on every reference plan. The blueprint stats
(`lib/blueprints/io-stats.ts`) still solve a payload as a Build plan, and the Inspector's selection
flow runs on the main thread's homegrown simplex, but no player reaches either: nothing opens the
blueprint save dialog, and plan boards show the overview, not the Inspector.

- Shared machines: `expandSharedMachines` stands each extra section up as a hidden node;
  the ONLY coupling is a time row (`listSharedMachineGroups`): sections' acts sum to at
  most one. A section held by that row reads BUSY (`findBusySharer`).
- Combine (`store/combine-machine.ts`) never depends on which card hosts: the machine is the one most cards name,
  and its tier, supply, coil and settings are the highest any of the cards had, raised until every recipe starts
  and gets one parallel. Tests: "Combine is the same whichever card hosts" in `combine-machine.test.ts` (every plan
  and selection order). The mod's Combine (`BoardSession.combine`) still copies the top-left card's settings.
- No wire crosses forms: a cell meets its fluid in a visible fluid tank card, a machine step like any other
  (`model/cell-wires.ts` turns an older plan's loose cell wires into tanks before the solver sees them).
- Ring diagnoses (`death-spiral.ts`, `clog-lock.ts`) stand down for an unfinished setup
  (a member with no power or a bare slot, `bare-slots.ts`). A card whose takers have all
  stopped reads CLOGGED naming them; a stopped taker's ask is never a deficit.
- A jammed second output reads CLOGGED naming the taker, never BOTTLENECK. A feeder of a
  backed-up machine reads on demand; held-taker checks are relative to the asking card, or
  loops go circular. Every diagnosis treats "stopped by its own setup" as the end.

## Debugging the diagnosis engine (equilibrium.ts)

- Split pull across unequal feeders by saturate-and-reoffer, never need / edge count.
- Never feed the settlement back into capability, demand or disposal mid-descent; damping
  breaks boards.
- Dead-loop tells: capability 1 plus a clog cascade = wrongly zeroed; 0 = truly dead.
- A hungry machine idle under a full buffer: dump the stage LP and check residuals first.
- Pool's hidden cell-fluid Tank is sized at 1000 machines; larger breaks the LP's range.

## Machine math

`overclock.ts` and `hatch-input.ts` live here; the rules are in
`src/lib/machines/CLAUDE.md`. Steam litres: `getNodeSteamReport` in `power-report.ts`.

## What lives elsewhere

Number and rate display (`formatCompact`, `formatRate`, the rate-unit and power-display
dials, `setting-memory`) is in `src/lib/format`, not here. This directory, `solver`,
`machines` and `power` are the code the shared Java core replaces: they import no React,
DOM, storage, Next, Zustand or `src/lib/format`. `src/test-support/core-boundary.test.ts`
enforces it over the real import graph.
