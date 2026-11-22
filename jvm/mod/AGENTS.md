# AGENTS.md

GTNH Planner: the in-game production planner for GregTech: New Horizons (mod id `gtnhplanner`, package
`com.gtnhplanner`). Minecraft 1.7.10, Forge, client side only, built with the GTNH Gradle convention plugin.

The mod lives in `jvm/mod/` of the `Tuxprogrammer/gtnh-factory-flow` repo, a fork kept for one private server's client
pack. It is a Gradle subproject of `jvm/`, beside the plain-Java core modules in `jvm/core/` that it shares with the
website and shades into its jar. The repo is public. The root `AGENTS.md` holds the rules for the whole repo; this file
holds the mod's.

This file is loaded with `CLAUDE.md`, which imports it. `CLAUDE.md` and everything under `docs/` describe the code, its
invariants and the dev harness. Where one of them still describes the original mod repo, its branches, releases or
hosting, this file wins.

Where code lives: the solver, layout and routing, machine math, power, the plan model and the view records are in the
core modules (`jvm/core/core-*`), plain Java 21 with no game imports, checked by the purity gate
(`jvm/core/purity`). They keep their `com.gtnhplanner` package names, and the mod compiles them in as dependencies
(`shadowImplementation(project(':core-...'))` in `dependencies.gradle`). Everything that touches Minecraft, Forge, the
GUI, the network and the config stays in `jvm/mod/src`. Shared test data is in `conformance/` at the repo root.

## Upstream

- Never contact upstream. No issues, pull requests, comments, reviews, discussions or mentions on `jackwrichards/*`,
  and no pushes there. The same goes for the repos further up the fork chain (`GTNewHorizons/*`). Reading is fine:
  fetch, browse issues, read code.
- A pushed commit, issue or comment that names an upstream issue as `#123`, `jackwrichards/...#123` or a URL leaves a
  visible backlink on that issue. Refer to one in plain words only: "upstream issue 123". No `@name` mentions
  anywhere.
- Push only to `origin` (`Tuxprogrammer/gtnh-factory-flow`). The `upstream` push URL is disabled in a correct
  checkout; if `git remote -v` shows a real push URL for it, run `git remote set-url --push upstream DISABLED`.
- `gh` on a fork acts on the parent by default. Every `gh` command that writes names
  `--repo Tuxprogrammer/gtnh-factory-flow`. Never run `gh pr create`; this fork uses no pull requests.
- The mod names no remote host. Its website address, live service, update-check address, help page and the extra web
  origins allowed to use "Open in the mod" come from the `network` category of `config/gtnhplanner.cfg`
  (`siteUrl`, `liveUrl`, `updateUrl`, `supportUrl`, `websiteOrigins`, read by `library/SiteConfig`) or from the
  `-Dgtnhplanner.library.url`, `.live.url`, `.update.url` and `.support.url` system properties, which win. Two more
  keys, `headerName` and `headerValue` (`-Dgtnhplanner.header.name` and `.value`), name one extra header for a site
  behind a guarding proxy; `library/SiteHeader` holds it, sends it only to the site's and the live service's host, and
  never logs it (`conformance/wire/README.md`, "A guarded site"). Every one is
  empty in a fresh jar and empty switches its feature off: a call that needs the site throws `SiteOff` and sends
  nothing. `NoRemoteHostTest` scans the mod and core sources for remote host literals and fails on one. What a pack
  puts in the config is the operator's decision: add no default address, and no new call to a host the config does not
  name.
- So a dev game never runs against a real site. A dev run has no address unless you give one: set `PLANNH_LIBRARY_URL`
  to the local stand-in (`node tools/dev/mock-library.mjs`, `tools/dev/mock-live.mjs`), or leave it unset for a game
  that contacts nothing. Never put a real site's address in a dev run's config, and never sign in, publish, share or
  send Feedback against one.
- The original mod repo is not a remote of this one, and its files sit under `jvm/mod/` here with the shared code moved
  out into the core. Changes from there are read as source and applied by hand, with the fewest lines that fix the
  bug. No reformatting beyond what `spotlessApply` does to the lines you touch.

## Working rules

- One branch per fix, `fix/<short-name>`, cut from `main`. Merge it into `main` once it is verified and delete it.
  `main` is what gets handed off, so it stays shippable.
- Commit and push finished work unless told otherwise.
- CI is the repo's one workflow (`.github/workflows/ci.yml`); its `jvm` job runs `tools/ci/jvm.sh`: the whole Gradle
  build under `jvm/` (mod and core modules, their tests, spotless, checkstyle), `:mod:compatCheck`, then a test-count
  check against `tools/ci/jvm-test-floors.txt`. It runs when a change touches `jvm/`, `conformance/` or `tools/ci/`.
  No tolerated failures. Run `./gradlew spotlessApply` in `jvm/` before committing: CI checks formatting.
- A test that pins player-facing text changes in the same commit as the text.
- Nothing generated goes in the repo root. Screenshots, probe scripts, captures and logs go in the session scratchpad
  or `scratch/` (gitignored); the build and the dev game keep theirs in `build/` and `run/`. Delete screenshots once
  you have looked at them.
- Comments state the rule and why. No dates, names or "used to" stories; git has those.
- The repo is public. No hostnames, addresses, private paths or credentials of the private server, its build machine
  or its planner site in code, docs, commits, issues or this file.
- Setup, fixes and merges only: no refactors and no dependency updates unless the task is one. The pinned mod
  versions in `dependencies.gradle` move only with the target pack.

## Player-facing copy

- Plain and short: the number, the rule, the consequence. No quips, no internal jargon, no em dashes.
- `CLAUDE.md` has the rest: what the UI may name, and how release notes read.

## Target pack

- Only the GTNH 2.9.0 full release is targeted. Betas, release candidates and other pack versions are not: a report
  that only concerns one of them is not worked.
- The pack's own mod versions decide what the code may call. `jvm/mod/tools/dev/compat/manifests/` holds each pack
  build's list and `./gradlew :mod:compatCheck` (from `jvm/`) checks the jar against them; a fix must keep that check
  passing.
- Game source for checking a mechanic is the source at the refs the target pack ships, kept by the pack operator's
  tooling outside this repo. Ask for it; do not read a newer public branch and call it truth.

## Versions and release notes

- The mod's version comes from git, through `addon.gradle` (`gtnh.modules.gitVersion` is off): the `VERSION`
  environment variable if set, else the nearest `mod-*` tag without its prefix. At the tag with nothing changed under
  `jvm/` that is the tag's version; past it, `<tag>-<branch>.<commits under jvm/ since the tag>+<hash>`, with `-dirty`
  for uncommitted changes under `jvm/`. With no `mod-*` tag reaching `HEAD` the jar is `NO-GIT-TAG-SET`. Nothing in the
  tree holds a version number. The repo's other tags (`web-<version>`) belong to the website and never name the jar.
- A fork version is on the fork's own line: `mod-1.0.0`, then `mod-1.1.0`, `mod-1.2.0`. One step of the middle number
  per release; the last number moves only for a hotfix of that release. No stage and no suffix. Releases before 1.0.0
  were the original mod's tag plus a fork suffix (`mod-0.2.0-beta.3-tux.5` was the last).
- Tag once per handoff, not per commit: an annotated `mod-<version>` tag on the handed-off commit of `main`, pushed to
  `origin`. Build the jar from the tag with `tools/ci/mod.sh --release mod-<version>` (from the repo root; add `--test`
  to run the whole suite as CI does). It needs `HEAD` at the tag and nothing changed under `jvm/`, never creates a
  tag, and prints the jar's path and checksum.
- CI's own jars are named `0.0.0-ci.<commit>`: they are build output for a run, not a release.
- After the base moves to a newer original version, restart the suffix at `-tux.1` on the new base.
- The fork's player notes build up in `.changelogs/unreleased-tux.md` (New / Better / Fixed, one line a change, in a
  player's words): a commit a player would notice adds its line in the same commit. At a handoff, rename it to
  `.changelogs/<fork tag without mod->.md` and start a fresh one. The original mod's `.changelogs/unreleased.md` and
  release notes are history; leave them as they are.

## Handoffs

- This repo releases nothing and agents here never publish. No GitHub release, no upload to a mod site, no Maven
  publish. No workflow here builds or publishes a mod release, so pushing a tag builds nothing; keep it that way.
- A finished jar is handed to the pack operator (the Dave-Agent project), who puts it in the client pack. Hand over:
  the jar built at the tag, the commit SHA and tag, the notes for players, and anything the operator must do with it
  (a changed config file such as a new `network` entry, a new JVM argument, a changed dependency on a pack mod).
- The jar names no site: the operator sets the website, live service, update-check and help addresses in
  `config/gtnhplanner.cfg` for the pack, or leaves them empty.
- Batch fixes into one handoff.
- Never test against the private server or its planner site with real accounts.

## Build and test

Run from `jvm/`, the Gradle root (wrapper 9.4.0). The mod is the project `:mod`; the core modules are `:core-*`.

```bash
./gradlew spotlessApply       # format; CI fails on unformatted code
./gradlew build               # every project: compile, downgrade, checks and the full JUnit suite
./gradlew :mod:build          # the mod alone (it builds the core modules it shades); jars land in mod/build/libs/
./gradlew :mod:test           # the mod's suite alone, headless, no Minecraft
./gradlew :core-solver:test   # one core module's suite (any :core-* project)
./gradlew :mod:compatCheck    # the jar against each pack build's mod versions
```

- `tools/ci/jvm.sh` is CI's job: `build :mod:compatCheck` with no daemon, then `tools/ci/check-jvm-tests.py` compares
  the number of tests that ran with the floors in `tools/ci/jvm-test-floors.txt`, so a suite that silently ran
  nothing fails. Run the whole pipeline with `tools/ci/run.sh`, which ends in `CI-LOCAL: PASS` or `CI-LOCAL: FAIL`.
- Sources are Java 25 syntax in the mod, downgraded to Java 8 bytecode for the game; the core modules are Java 21 with
  no game imports. Gradle needs JDK 25 to run and JDKs 8, 17 and 21 as toolchains; it downloads what is missing unless
  the installations are listed in the Gradle user home.
- The jar for the pack is `jvm/mod/build/libs/gtnhplanner-<version>.jar`. The `-dev`, `-sources` and `-predowngrade`
  jars are not for players.
- A test that moved to a core module runs under that module (`:core-solver:test`), not under `:mod:test`; a count
  that drops in one place and rises in the other is a move.
- If `test` reports `NO-SOURCE`, nothing ran: see Build and test in `CLAUDE.md`.
- The dev game (`jvm/mod/tools/dev/mc.sh`) opens a window on the desktop it runs on. Run it only where the owner has
  said a window is welcome, and only against the local stand-ins or no site at all (see Upstream).
