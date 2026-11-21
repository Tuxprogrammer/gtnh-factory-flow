# Display formatting (src/lib/format)

How numbers, rates and the display dials read on screen. This is where the shared Java core's
website side stops: `model`, `solver`, `machines` and `power` hold plan logic that moves into
Java, and everything a player reads off it is spelled here, in TypeScript.

## Layering

- `format` imports `model` and `solver`; they never import it. Nothing under `src/lib/model`,
  `solver`, `machines` or `power` may reach this directory, directly or through a file it
  imports. `src/test-support/core-boundary.test.ts` fails the build when one does, and when a
  core file names React, the DOM, browser storage, Next or Zustand. Its `ALLOWED` list holds each
  exception with a reason; the aim is an empty list.
- `numbers.ts`: `formatCompact`, `formatCompactStable`, `formatRate`, `formatPowerValue`,
  `formatNumberWithThousands`, `formatMachineListCount`, `formatRatioShare`. Cut over unchanged
  from where they lived; a change to what a number reads is a player-facing change.
- `rate-unit.ts` (`/s`, `/t`, `/min`, `/hr`, amps, the EU-per-unit reading), `setting-memory.ts`
  and `power-working.ts` are display state and host-side display helpers. They read plans; they
  do not solve.
- Display dials repaint numbers only. They never re-solve.

## The core spells numbers too

The shared Java core has the same formatters (`Format` in `jvm/core/core-base`), and the mod prints through it (`Fmt` in
core-view is its facade). They are a translation of `numbers.ts` and `rate-unit.ts` and must print the same characters:
`conformance/format/` runs one value list through the website's formatters and keeps the answers
(`website-out.json`); the core is held to that file on the JVM (`FormatGoldenTest`) and in Node on both web targets
(`tools/web-core/format.mjs`). So a change to what a number reads here is a change in the core too: update `Format`, the
value list if it needs a new case, and the answer files. `COMPARISON.md` is the table and `README.md` says what still
differs on purpose (the mod's width-fitting `Fmt.brief`, the recipe overlay's own spelling).

```bash
GTNH_WRITE_GOLDEN=1 npx vitest run src/test-support/format-conformance.test.ts
node tools/conformance/format/compare.mjs
node tools/conformance/manifest.mjs --write
```

Tests that pin this area: `numbers.test.ts`, `rate-unit.test.ts`, `rate-unit-amps.test.ts`,
`src/test-support/format-conformance.test.ts`, `tools/conformance/format/compare.test.mjs`.
