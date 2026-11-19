# format

How a number is spelled for a player. One value list (`values.json`) is put through each formatter; the answers are
pinned in files, and a table lines them up (`COMPARISON.md`).

The number formatter is written once, in the Java core (`jvm/core/core-base`, `Format.java` and `RateUnit.java`), as a
translation of the website's `src/lib/format/numbers.ts` and `rate-unit.ts`. The mod calls it through `Fmt` (core-view);
the website's TypeScript formatters stay and are held to the same answers. Where the two sides used to print one quantity
two ways, the website's spelling won.

| file | what | made by |
| --- | --- | --- |
| `values.json` | the inputs (groups) and the rows (which formatter on each side reads which groups) | by hand |
| `website-out.json` | what the website's formatters return | `src/test-support/format-conformance.ts`; `GTNH_WRITE_GOLDEN=1` rewrites it |
| `mod-out.json` | what the mod's `Fmt` and the core's `Format` return | `FmtConformanceTest`; `-PwriteVectors` rewrites it |
| `mod-recipe-overlay-out.json` | what the recipe overlay's `GuiHelper.formatRate` and `formatCount` return | `RecipeOverlayFormatTest`; `-PwriteVectors` rewrites it |
| `COMPARISON.md` | the table | `node tools/conformance/format/compare.mjs` |

Who checks what:

- the website's vitest pins `website-out.json` to the TypeScript;
- `FormatGoldenTest` (core-base) holds `Format` to `website-out.json`, character for character, on the JVM;
- `tools/web-core/format.mjs` holds the compiled core to the same file in Node on both web targets (wasm and js), as part
  of `tools/ci/web-core.sh`;
- `FmtConformanceTest` pins what the mod's `Fmt` prints to `mod-out.json`.

After changing a formatter on either side: rewrite its answer file, run `compare.mjs`, run
`node tools/conformance/manifest.mjs --write`. Each answer file has a test that fails when the code and the file disagree,
and `compare.test.mjs` fails when the table is stale.

An output of `throws X` is an exception the formatter raises on that input.

## What was decided, and what is left apart

The website's spelling is the rule. The mod now prints, through the core:

1. **Tiny values** print their decimal, however small (`0.000000001`), not `0`. A rate that small is still a rate.
2. **Rounding on the half** follows JavaScript's `toFixed`, which reads the exact binary value: `1.005` is `1`, `9.995` is
   `9.99`. (`NumberText.fixed` is that `toFixed`.)
3. **Past a quadrillion** the suffixes go on to `E` (`1E`, `1000E`).
4. **Not a number**: `NaN` and the infinities read `unbounded` (EU/t, compact, rates). A machine count reads `NaN` or
   `Infinity` as the website does. Nothing throws.
5. **EU/t** reads `<0.01` and `>-0.01` for a trickle, then the compact ladder.
6. **Machine counts** (`Format.solvedMachines`): `0` at half a millionth or less, `<0.001` below 0.0005, then three
   decimals under 1, two under 10, one under 100, trailing zeros dropped. From 100 up a count rounds **up** to a whole
   machine, within a millionth: `100.0000001` reads `100`, `100.00001` reads `101`. (Both sides always rounded up from
   100; they differed only in that tolerance, a billionth in the mod.)
7. **The list-panel count and the share** (`machineListCount`, `ratioShare`) had no mod peer; they are in the core now,
   with the rate-unit helpers (`slotRate`, `energyPerUnit`) and the plain `rate`, `thousands` and `trimZeros`.

Left apart on purpose:

- **`brief`.** `Fmt.brief` is the mod's width-fitting number (three significant figures: `33.3`, `1k`, `180k`) and has
  four callers (the overview rail, card paint, the drawer card and the minimap). It is a different design from the
  website's `formatCompactStable` (whole numbers under 1000, fixed decimals with a suffix above: `1.00k`, `180.0k`), which
  the core also has as `Format.compactStable`. Both are in the table; the `brief` row compares the mod's with the
  website's and differs by design. Below 1 `brief` is `compact`, so it no longer floors at a millionth; it reads
  `unbounded` for a number that is not finite.
- **The recipe overlay's own spelling** (`compact.recipeOverlay`, `machines.recipeOverlay`). `GuiHelper.formatRate(float)`
  and `formatCount(double)` keep fixed decimals (`1.00`, `2.50`, `1.0k`), take a float, never round across a tier and give a
  negative no suffix. They follow NEI and AE2's readable-number suffixes, a quantity the website never prints, so they are
  unchanged and still differ.
- **The power display dial.** The website's amps tiers are not ported; only the default EU/t dial is.

## Percent, multiplier and duration text is still written by hand

No formatter was invented for these. The `percent.whole`, `percent.supply` and `times` rows pin the website's own
formatters (`formatPct`, `formatSupplyPercent`, `formatTimes`); the core has no peer.

- Percent. Website: `formatPct`, `formatSupplyPercent`, `formatRatioShare` (the last is in the core), and hand-made
  `${Math.round(x * 10000) / 100}%` for a chance (`clean-model.ts`, `tips.ts`, `CropPickerMenu.tsx`, `MobPickerMenu.tsx`),
  `formatCompact(...)}%` (`card-tips.ts`). Mod: `Fmt.compact(x) + "%"` (`CardFace`, `RecipeCard`, `BoardScreen` for a
  chance), `Math.round(...) + "%"` (`RecipeCard`, `BoardCanvas`), and the core's `Bees`, `CropsNh` and `Endgame` text.
- Multiplier. Website: `formatTimes`, `` `×${formatSolvedMachines(...)}` `` (`clean-model.ts`),
  `` `×${formatCompact(...)}` ``, `×${formatRate(...)}` (`MachinePicker.tsx`). Mod: `"×" + Fmt.machines(...)` in `CardFace`,
  `RecipeCard`, `OverviewRail` and `Minimap`.
- Duration. Website: ticks over twenty with a hand-added `s` in the tooltips. Mod: `Fmt.compact(ticks / 20.0) + " s"`
  (`PowerPanel`, `RecipeCard`), `Bees` (`FormulaLine.number(ticks / 20.0) + " s"`), `Railcraft`.

A later item can give each its own core formatter, with these rows as the vectors.

## Reading `COMPARISON.md`

`COMPARISON.md` is the table. Every row the core formats agrees with the website on every input; the rows still differing
are `brief` and the two `recipeOverlay` rows, above, and the rows with no mod peer are listed as `none`.
