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

## The mod spells numbers too

The mod has its own formatters (`Fmt` in core-view, and the recipe overlay's `GuiHelper`).
They are not meant to match this directory everywhere. `conformance/format/` runs one value
list through both and keeps the answers (`COMPARISON.md` is the table, `README.md` the list of
differences and what to decide). Change a formatter here and the answer file moves with it:

```bash
GTNH_WRITE_GOLDEN=1 npx vitest run src/test-support/format-conformance.test.ts
node tools/conformance/format/compare.mjs
node tools/conformance/manifest.mjs --write
```

Tests that pin this area: `numbers.test.ts`, `rate-unit.test.ts`, `rate-unit-amps.test.ts`,
`src/test-support/format-conformance.test.ts`, `tools/conformance/format/compare.test.mjs`.
