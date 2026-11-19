# AGENTS.md

GTNH Factory Flow: a production-chain planner for GregTech: New Horizons. Next.js App
Router, TypeScript strict, Tailwind, React Flow, Zustand, Zod, Vitest.

This repository is an independent fork (`Tuxprogrammer/gtnh-factory-flow`) of
`jackwrichards/gtnh-factory-flow`, kept for one private instance. It carries bug fixes on
top of upstream and merges upstream regularly. The repo is public.

This file is the always-loaded core. Each area keeps its own rules in a `CLAUDE.md`
beside its code, which Claude Code loads when you open files there. Read the area file
before changing that area; it names the tests that pin its behaviour.

| Area | Notes |
|---|---|
| Board: cards, drawers, boards, wires, routing, arrange, board perf | `src/components/flow/CLAUDE.md` |
| App UI: interface scale, compact mode, recipe search, inspector, sounds | `src/components/CLAUDE.md` |
| Pool worksheet | `src/components/pool/CLAUDE.md` |
| Solver: LP books, Build/Solve/Pool, drawers, shared machines, verdicts | `src/lib/solver/CLAUDE.md` |
| Machine math: overclocks, parallels, curated machine table, special machines | `src/lib/machines/CLAUDE.md` |
| Domain model: resources, cells vs fluids, oredict context, sections, boards | `src/lib/model/CLAUDE.md` |
| Power generation | `src/lib/power/CLAUDE.md` |
| Designs, tabs, saving, account sync, plan import/export, public view | `src/store/CLAUDE.md` |
| Server, recipe API, Supabase, cloud plans, local database | `src/lib/server/CLAUDE.md` |
| Live service: live plan editing over WebSockets | `src/live/CLAUDE.md`, `docs/live.md` |
| Dataset pipeline: export, normalize, rebuild, publish | `tools/dataset-pipeline/CLAUDE.md` |

Longer references, read on demand: `docs/solver-equations.md` (solver doctrine),
`docs/power-planner-math.md` (the decoded power workbook), `docs/COMMUNITY.md`.

Area files and docs were written upstream. Where one describes upstream's hosting,
deploys or branches, this file wins.

## Commands

```bash
npm run dev          # http://localhost:3000
npm run dev:local    # the same with a local database and the live service: no Supabase credentials
npm run typecheck
npm run test         # full vitest suite, must be green
```

On Node 25 or newer, run the suite as `NODE_OPTIONS=--no-experimental-webstorage npm run
test`: Node's own `localStorage` shadows the test one and fails about 170 tests that are
not broken. CI uses Node 24.

`npm run dev:local` serves whatever dataset is in `public/datasets/gtnh/` (gitignored).

Scratch harnesses are `*.local.test.ts` (excluded from the suite); run one with
`npx vitest run <file> --config vitest.local.config.ts`.

## Upstream

- Never contact upstream. No issues, pull requests, comments, reviews, discussions or
  mentions on `jackwrichards/*`, and no pushes there. Reading is fine: fetch, browse
  issues, read code.
- A pushed commit that names an upstream issue as `#123`, `jackwrichards/...#123` or a URL
  leaves a visible backlink on that issue. Refer to one in plain words only: "upstream
  issue 123".
- Push only to `origin` (`Tuxprogrammer/gtnh-factory-flow`). The `upstream` push URL is
  disabled in a correct checkout; if `git remote -v` shows a real push URL for it, run
  `git remote set-url --push upstream DISABLED`.
- Pull requests, if any, are opened inside the fork. `gh pr create` on a fork targets the
  parent by default: always pass `--repo Tuxprogrammer/gtnh-factory-flow`.
- Upstream merges into `main` regularly, so a fix must stay easy to merge over: change the
  fewest lines that fix the bug, in place. No reformatting, renames, file moves or
  drive-by cleanups in files upstream owns. New code goes in new files where that is
  natural.
- When a merge conflicts, upstream's code wins unless it brings back the bug; then
  re-apply the fix on upstream's new shape. `AGENTS.md`, `src/lib/version.ts` and the top
  of `src/lib/changelog.ts` conflict on every merge: keep this fork's `AGENTS.md`, and see
  Versions for the other two.
- When upstream fixes a bug this fork also fixed, take upstream's fix and drop the fork's.

## Working rules

- One branch per fix, `fix/<short-name>`, cut from `main`. Merge it into `main` once it is
  verified and delete it. `main` is what gets deployed, so it stays shippable.
- Commit and push finished work unless told otherwise.
- CI runs typecheck, the full suite and a Chromium/Firefox icon-sizing check on every PR
  and push to `main`. No tolerated failures.
- A test that pins player-facing copy changes in the same commit as the copy.
- Nothing generated goes in the repo root. Screenshots, probe scripts, captures and logs go
  in the session scratchpad or `scratch/` (gitignored). Probes live in `scratch/probes/`.
  Delete screenshots once you have looked at them.
- Check UI changes in the real app with Playwright; unit tests cannot see routing,
  layering or hover. Board performance changes need a before/after profile.
- Comments state the rule and why. No dates, names or "used to" stories; git has those.
- The repo is public. No hostnames, addresses, paths or credentials of the private
  instance in code, docs, commits or this file.

## Player-facing copy

- Plain and short: the number, the rule, the consequence. No quips, no internal jargon,
  no em dashes.
- Recipes are read-only in the UI. No manual recipe editing unless asked.

## Versions and the changelog

- `APP_VERSION` in `src/lib/version.ts` is upstream's version plus a fork suffix:
  `4.7.0-tux.1`, then `-tux.2`. The base number is always upstream's; never bump it here.
- Bump the suffix once per deploy handoff, not per commit, and add one entry to
  `src/lib/changelog.ts` for it: a headline and a few one-line notes about what changed
  for players. Work that has not been handed off yet folds into the top entry.
- After an upstream merge that moves the base version, keep upstream's changelog entries,
  take the new base and restart the suffix at `-tux.1` with the fork's entry on top.
- Players open the changelog from the header's version chip (unread dot, stamp
  `changelog-read.v1`); nothing opens by itself. Shift-click the chip for the dev menu.

## Deploys

- This repo deploys nothing and agents here never deploy. The instance is built and
  rolled from outside the repo by its operator. Pushing `main` deploys nothing.
- The GitHub deploy and dataset workflows are upstream's and have no runner here; never
  dispatch them.
- To hand work off, push `main` and report the commit SHA with: what changed; new or
  changed environment variables; new `supabase/migrations/*.sql`; anything that changes
  the dataset format. Batch fixes into one handoff.
- The deploy build patches the `publicUrl` return line in
  `src/app/api/community/images/route.ts` and fails if that line changes. Say so in the
  handoff when a change touches it.
- Never test against the live instance with real accounts.

## Datasets

- Only GTNH 2.9 datasets are supported.
- The instance's dataset is exported from its own pack by the dataset pipeline. Never ship
  or test against datasets published by gtnhplanner.com; they are a different pack build.
- The server prewarms datasets on start when `GTNH_PREWARM_ON_STARTUP=1`
  (`src/instrumentation.ts`). A slow first API call means prewarm regressed; don't make
  the client wait longer.

## Invariants across the app

- Raw exporter data is normalized by the pipeline before UI or solver code sees it.
  Prefer exported NEI/runtime data over hand-written tables and broad fallbacks.
- Board performance is a requirement: no DOM measurement per edge or per frame, nothing
  O(nodes x edges) per frame, hover never rebuilds the board.
- Every plan that arrives (import, paste, link, community copy) opens as a NEW design.
  Nothing overwrites the open design; no app path calls `setProject`.
- Display dials (rate units, power display) repaint numbers only. They never re-solve.
- A setting with no visible control must never be persisted: players get stuck in it,
  and a `finally` that restores it is not a durable undo.

## Probing the real app

- Playwright (`playwright-core` is a devDependency), always headless: a headed run takes
  over the owner's screen. Use `waitUntil: "load"`. Write probes into `scratch/probes/`.
  Install its browser with `node node_modules/playwright-core/cli.js install --only-shell
  chromium`.
- A hidden browser pane never fires `requestAnimationFrame`; shim it. A fresh profile
  lands on the Welcome tab. A production build has no `window.__gtnhFlow`.
- The board pane is a million px square under the scroll camera: aim at the `.react-flow`
  wrapper's box. Tooltips move `title` to `data-tip-title`.
- Compare against old behaviour with `git worktree add` at that commit. A worktree needs
  its own `npm install`; never link `node_modules` into one. Diff exported plans rather
  than trusting "same board" by eye.
- Measure performance on `next start` with `GTNH_PREWARM_ON_STARTUP=1`, after prewarm,
  not on the dev server.
