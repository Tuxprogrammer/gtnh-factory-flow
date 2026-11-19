# format

How the website and the mod spell numbers: one value list (`values.json`) put through each side's formatters, the answers
pinned in three files, and a table that lines them up (`COMPARISON.md`). The formatters are **not** merged and the two
sides are **not** required to agree. This tree records where they do not, so the view work can decide each difference
once instead of finding it by eye.

| file | what | made by |
| --- | --- | --- |
| `values.json` | the inputs (groups) and the rows (which formatter on each side reads which groups) | by hand |
| `website-out.json` | what the website's formatters return | `src/test-support/format-conformance.ts`; `GTNH_WRITE_GOLDEN=1` rewrites it |
| `mod-out.json` | what `Fmt` (core-view) returns | `FmtConformanceTest`; `-PwriteVectors` rewrites it |
| `mod-recipe-overlay-out.json` | what the recipe overlay's `GuiHelper.formatRate` and `formatCount` return | `RecipeOverlayFormatTest`; `-PwriteVectors` rewrites it |
| `COMPARISON.md` | the table | `node tools/conformance/format/compare.mjs` |

After changing a formatter on either side: rewrite its answer file, run `compare.mjs`, run
`node tools/conformance/manifest.mjs --write`. Each answer file has a test that fails when the code and the file disagree,
and `compare.test.mjs` fails when the table is stale.

An output of `throws X` is an exception the formatter raises on that input.

## What the table says, by kind of difference

Each item names the rows it shows up in. "Decision" is the work order for the view item: the answer to each is one line
in its plan, not code in this item.

1. **Tiny values** (`compact`, `rate.*`, `power`, `brief`). The website prints a value below a millionth as the decimal it
   is (`0.000000000001`), because a rate that small is still a rate. `Fmt` has a floor (`NOTHING = 1e-6`): below it
   `Fmt.compact` and `Fmt.brief` print `0`. (Between a millionth and a hundredth `Fmt.brief` keeps two figures where the
   website's `formatCompactStable` prints `0`; that is item 7.) Decision: does the mod keep its floor, or does it show
   what the website shows? A floor is shorter on a card; the website's choice keeps a trickle visible.
2. **Rounding on the half** (`compact`, `power`, `rate.*`, `machines`). Rounds `1.005` to `1` and `9.995` to `9.99` on the
   website (JavaScript's `toFixed` reads the exact binary value) and to `1.01` and `10` in the mod (`BigDecimal.valueOf` reads
   the shortest decimal and rounds half up). The same value reads one digit differently on the two sides. Decision: take the
   website's rule in `Fmt`. `core-base`'s `NumberText.fixed` is the JavaScript-faithful `toFixed` and is the tool for it.
3. **Past a quadrillion** (`compact`, `rate.*`). The website's ladder goes on to `E` (`1E`, then `1000E`, `1000000E`); the
   mod's ends at `P` (`1000P`, `1000000P`). Decision: add `E` to `Fmt`, or accept the mod's ladder (a plan
   that reaches 1e18 per second is not a plan a player builds).
4. **Not a number** (`compact`, `brief`, `power`, `machines`). `NaN` and the infinities read `unbounded` on the website
   (`formatSolvedMachines` prints `NaN` and `Infinity` as JavaScript does). In the mod `NaN` reads `0`, and an infinity
   makes `Fmt.compact`, `Fmt.brief` and `Fmt.power` throw `NumberFormatException` out of `BigDecimal`; `Fmt.machines`
   throws on `NaN` and prints `9223372036854775807` for `Infinity`. Decision: one answer for all three, chosen by the view item. A formatter
   that throws on a value the solver can produce (an infeasible plan) is a bug whichever answer wins.
5. **EU/t** (`power`). The website's `formatPowerValue` shows `<0.01` and `>-0.01` for a tiny draw and otherwise the
   compact ladder; `Fmt.power` is `Fmt.compact` as it is. They agree on every whole tier value in the list (8 EU/t up to
   34 billion). They differ only on the tiny values and rounding of 1 to 4 above. Decision: follow what items 1 and 2 give.
6. **Machine counts** (`machines`). The website: `0` under half a millionth, `<0.001` under 0.0005, then three decimals,
   `formatSolvedMachines`. The mod: `0` only at zero or below, `<0.001` for every positive count under 0.001, and from 100
   up it rounds **up** to a whole machine (`100.0000001` reads `101`; the website reads `100`). The round-up is the mod's
   on purpose (you cannot build part of a machine); the website rounds. Decision: which rule is the plan's. The
   website's `formatMachineListCount` (the list panel) has no mod peer.
7. **"brief"** (`brief`). The website's `formatCompactStable` and `Fmt.brief` are two different designs under similar jobs.
   The website's keeps a number the same width while it animates: whole numbers under 1000, fixed decimals with the
   suffix above (`1.00k`, `180.0k`, `1000.0k`). The mod's keeps three significant figures (`33.3`, `1k`, `180k`). No
   non-test code in the mod calls `Fmt.brief` yet. Decision: say which one the mod's zoomed-out drawer wants before
   anything is built on it; they should not share a name if they stay different.
8. **The recipe overlay's own spelling** (`compact.recipeOverlay`, `machines.recipeOverlay`). `GuiHelper.formatRate(float)`
   and `formatCount(double)` are a second formatter family in the mod, beside `Fmt`. They keep fixed decimals (`1.00`,
   `2.50`, `1.0k`), take a float, read a thousand to a million with one decimal, never round across a tier
   (`999950` reads `1000.0k`) and give a negative no suffix at all (`-1500`, `-2147483648`). They agree with the website on
   24 of 80 rates and 14 of 28 counts. Decision: does the overlay move to `Fmt`, or stay as NEI and AE2 spell numbers. Its
   own comment says it follows NEI and AE2's readable-number suffixes, which is a reason to keep it apart.
9. **No peer in the mod.** `machines.list`, the three percent formatters (`percent.share`, `percent.whole`,
   `percent.supply`) and `times` (an ask bigger than the machine, as a multiplier) exist only on the website. The mod draws
   no percent or multiplier. Neither side has a duration formatter at all; the plan's times are drawn as rates. Decision:
   whether the mod's view needs any of them; if it does, they are ported with these rows as their vectors.

Agreement worth keeping: `euEach` and `euEach.suffix` (EU per unit made) agree on every input, as do the tier values of
`power` and every ordinary whole or one-decimal rate in `compact` and `rate.*`.

## For the view item

`COMPARISON.md` is the table; this file is the list of decisions. When a decision changes a formatter, rewrite the
answer file for that side, run `compare.mjs`, and the table shows the difference closed or kept. A difference kept on
purpose gets one line here saying so.
