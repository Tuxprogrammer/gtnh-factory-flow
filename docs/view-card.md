# The recipe card as one view (core-view `CardViews`)

What a recipe card shows is computed once, in `jvm/core/core-view` (`com.gtnhplanner.core.view.card`), and each host
draws it: the website's `CleanCard.tsx`, the mod's `RecipeCard`. This file is the map: the records, the shared cases,
what each side draws from them, what still differs and the rule that settles each difference, and what switching the
website to the compiled core will need.

## Records

- `CardInput` (in): a card's facts as plain data: its name and picker, its tier ladder and hatch chip, its machine's
  power or steam report, parallels, pinned settings, pictures (icon keys), circuit, rows on each rail (kind, rate,
  energy per unit, chance, used-up chance, alternatives, free, the rate the player set), the count's facts (rule, pin,
  what the solve needs, utilization, the node's parallel, custom rate, unset), a generator's own tier, this card's Up
  to read-out, forced flows and spills at its rows, its ring, the leftovers with a place on it, a turbine's flow
  check, the names of the cards its read-out mentions, selected, and the menu's facts. `CardInput.Dials` (rate unit,
  power key, Peak or Avg) and `CardInput.Case` (dials and card) are the shared case's input.
- `CardView` (out): the website's `CleanCardView` in Java, every string final: name and its step on the host's
  ladder, tier and amps chips, port rows (number, unit, title, gold, the fit decisions, forced row, spill, the reason
  that lights it), the power row, pictures, the count (text, rule word, set box, prompt), the foot bar with its facts,
  the ring, the glance bar, the circuit, the strip's chips laid out, the frame's tone, the menu rows. Tones are
  `Tones.Role`, never colours. `CardView.write` is the one JSON spelling.
- `CardGeometry`: where the parts stand in board px: size, rails, every port's anchor, strip, foot. `web(view)` is the
  website's card from its stylesheet's grid; `mod(layout, ...)` is `CardLayout`'s. The router and Arrange read sizes
  and anchors.
- `CardViews`: the rules, a function-for-function translation of `clean-model.ts` (`buildPortView`, `layoutStrip`,
  `cardCountState` and `buildCountView`, `wheelCount`, `parseCountText`, `wheelDraft`, `buildFootView`,
  `frameTone`, `withReasonPorts`, `parallelReading`, the assembly in `useCleanCardModel`, now `cleanCardView`),
  `glance.ts` `glanceBar`, `loops.ts` `cardLoop`, `idle-trade.ts`, and the card menu's rows
  (`menus/card-recipe-rows.ts` with `CardRows`' order). Words through core-base `Text` (keys `gtnhplanner.card.*` in
  the mod's language file), numbers through `Format`, widths through the host's `TextMeasure` (three new styles:
  `NAME_LARGE`, `NAME_MEDIUM`, `NAME_SMALL`, the card name's ladder: web 10/9/8 px, mod 1.5x/1.25x/1x).
- Moved from the mod in this item (packages kept): `ui/card/CardFace`, `ui/Loops`, `ui/Notice`, `ui/NoticeRow`,
  `ui/note/NoteText` and their tests; `ui/card/PowerInput` holds the POWER INPUT panel's facts that `PowerPanel`
  mixed with drawing (one run at an amp count comes from the host).

## Shared cases

`conformance/view/card/cases.json` (99 whole cards) and `count.json` (the wheel, typed counts and the open box's
wheel), envelope and layout as `conformance/view/README.md` (view-a's corpus) says, measure `six-px`. The cases are the
builder tests of `clean-model.test.ts`, `glance.test.ts`, `loops.test.ts`, `idle-trade.test.ts`,
`turbine-foot.test.ts`, `clean-model-amps.test.ts` and `parallel-reading.test.ts` made whole cards, plus the dials,
the head, ports, the strip, pictures and the menu.

- Written by the website's own builders: `src/components/flow/clean/card-conformance.ts` builds each `CardInput`, maps
  it onto `cleanCardView`'s facts (RailPorts, a plan with the read-outs, the dials set), measures with
  `SIX_PX_MEASURE` (`text-measure.ts`'s test seam `setFixedTextMeasure`) and writes the view in the record's shape.
  `GTNH_WRITE_VIEW=1 npx vitest run src/components/flow/clean/card-conformance.test.ts` writes the files (the view
  generator `tools/conformance/view/generate.ts` runs it: the builders need the test environment's styles and store),
  then `node tools/conformance/manifest.mjs --write`.
- Read by `card-conformance.test.ts` (the files are what the code makes now, and the builders give each stored view
  from its stored input) and by `CardCasesTest` / `CardCountTest` in core-view (the core's rules give the same bytes,
  read back through the record).

## The mod draws from it

`ui/card/CardInputs` builds a card's `CardInput` from its game-free `CardModel` and the board session (handles
`i<n>` / `o<n>`, port keys `<node>|i|i<n>`). `RecipeCard` keeps the view with its count (rebuilt when the model, the
wheel, the read-out, the prompt or the dials change) and draws from it:

- the count under the picture (`CardView.count`): the rule word on every rule and the set box;
- the power row (`CardView.power`): the draw under the power key in the card's tier, steam litres, TIER!;
- the menu's rows (`CardViews.menu`), with the mod's own rows put in their places;
- every port row's number and unit through `CardViews.powerFigure` and the core's rate format, and energy readings
  through `CardViews.energyFigure`, on every card (generators, crops and shared machines too).

Cards the shared rules do not cover yet keep the mod's own count and power rows: shared machines, crop cards, holders
(custom rate, fluid tank), generators (their rows' figures are shared), and cards with no count. The website draws
those through `special/use-special.ts`, which is not translated yet.

What a mod player sees change:

1. Every card's count line shows its rule word, "Any" too, and a set box: "Set" in grey on Any (pulsing gold while
   nothing sets that part of the plan, where "(set count)" stood), the count in gold once set. Clicking "Any" opens the
   rule menu, so Up to or Exactly can be picked straight from Any; clicking "Set" types a count.
2. Rule words are capitalised as the menu and tips have them: "Up to", "Exactly" (were "up to", "exactly").
3. A generator's EU rows (and the forced row under one) read amps of the generator's own tier under the Amps key
   ("1 A LV"), the tier being the lowest whose one amp carries one machine's output; they always read EU/t before.
4. The power row: at Peak a card with no count bills whole machines (it billed the solved fraction on a recipe card
   whose solve ran it partly); with a count set it bills the count, or what the solve needs when that is more. At Avg
   an Up to card bills its count by how full it got (its read-out's reached over capacity).
5. A high-pressure steam machine's row is named "HP steam".
6. The power row reads "0" in grey for nothing, and the amps figure takes the power formatter ("<0.01" for a trickle).
7. Energy readings (rate key on EU) print with the power formatter ("13.33 EU/L", were three significant figures)
   and keep reading EU under the Amps key.
8. The card menu's row labels come from the shared rules (same words, same order); Add another recipe and Replace
   the recipe stay out of the mod's menu until it has the search to carry them (see below).

Two leftovers fixed in this item: the snap of cards carried over a board group goes through the sound table
(`groupAimed`), and a card's name when zoomed out keeps its gap from every other card. The "title strip" that touched
the card above after a compact Arrange is that name (`ZoomedOutLabels`, placed above a card): re-shot, Arrange's card
boxes were right, but the name kept its 3 screen px gap only from its own card and sat 1.6 screen px under the card
above. A name's height is screen pixels, so it cannot be part of a card's board box; it now keeps the same gap from
every box, and goes below its card, or is left out, where there is no room above.

## Differences between the two cards, and what settles each

Rules from `parity/09-decisions.md`: the game decides mechanics; the target is the union of features; the more
general design wins. "Here" means settled in this item; "next" names who carries it.

| # | Area | Website | Mod | Settled by | State |
|---|---|---|---|---|---|
| 1 | Rule word on Any | "Any", opens the rule menu | none; menu only once a count is set | union | here (mod gains it) |
| 2 | Set box and prompt | box always: "Set", blinking gold while unset, the count in gold | "(set count)" note; gold number with no box | more general (box covers both) | here |
| 3 | Rule word case | "Up to", "Exactly" | "up to", "exactly" | more general (one label table) | here |
| 4 | Crop count unit | "seed"/"seeds" beside the number on every rule | "crop(s) planted" on Any only | game decides (seeds on crop sticks); unit on every rule | next: crop cards through the shared rules (use-special) |
| 5 | Count title, custom rate | "Runs exactly this many..." | "Runs at its dial." | union (the mod's line is right for a dial) | mod keeps its line; website to gain it |
| 6 | Count tip rows | title note on Any, one zero note | per-kind zero notes, crop harvester rows | union | next: tips as records (TipView) |
| 7 | Rule word tip | "The plan decides how many." on Any | "Let the plan decide" middle action always | union | next: tips |
| 8 | IDLE foot bar | "IDLE", "<input> is sent out instead" | none (no leftover read-out on the card) | union | rule shared here; mod needs the solver's leftover bearers per card (solve) |
| 9 | FLOW foot bar (turbines) | "FLOW", "N at 63% of optimal, make ..." | none | game decides (turbine efficiency by flow) | rule shared here; mod needs the turbine flow check (power) |
| 10 | Spills into a buffer | forced rows carry the buffer, hover lights it | no spill read-out | union (09: every buffer shows its spillover) | rule shared here; mod needs `DrawerReadout.spills` on its board (W10) |
| 11 | Parallel reading chip | "Parallel ×N", power cap in its tip | a row in the name tip only | game decides (ParallelHelper caps by power), union | rule shared here; mod strip still its own (next) |
| 12 | Generator EU rows under Amps | amps of the generator's own tier | always EU/t | more general; the game decides the tier | here |
| 13 | Who reads amps | every card with a power report; crops in the harvester's tier | GregTech cards only | more general | here for recipe cards; crops next |
| 14 | Shared machine power row | amps only when every recipe runs one tier | divides the whole by the first recipe's tier | game decides (amps exist at one voltage) | next: shared cards |
| 15 | Power figure format | `powerValue` ("<0.01") | `compact` for amps | more general (one formatter) | here |
| 16 | Peak and Avg billing | Peak billed set or solved fraction | Peak billed whole machines | decided (09 and the orchestrator): Peak bills whole machines, `max(set, built)` | here on both sides (cards); shared pools at Peak: view-a's rail item |
| 17 | Steam row | "HP steam" for high pressure; Avg/Peak scale | "Steam"; solved machines | union; game decides | here |
| 18 | Power row tip | demand follows the dial; TIER! gives the reason | EU/t; no reason | union | next: tips |
| 19 | Cannot run | stalled power report only | also a coil too cold (red ring, red gear) | game decides (a cold coil stops the recipe) | mod keeps its rule; website to gain it (next) |
| 20 | Bee card name | the housing; species is a "Not used" row | "<Species> Bee"; pins keyed by it | more general (named by its machine) | next: bee cards (mod adapter names, pin keys) |
| 21 | Extreme Entity Crusher name | the machine; mob in a controller row | the mob | as 20; union keeps the mob rows in a tip | next |
| 22 | Machine list pictures | map icon lent with no bee/crop check | no stand-in | game decides | website fix, next (menus) |
| 23 | Single-block tier chip | steps the recipe's available tiers | steps tiers with a machine item | game decides (a tier exists where a machine does) | next (W11 interaction rules) |
| 24 | Amps chip | multiblocks on curated maths, hatch chips | every GT multiblock, compact | union (09 gives the mod hatch supplies and the EU/t budget) | next (machines) |
| 25 | Card name sizes | 10/9/8 px; special cards 14/13/12/9 | 1.5x/1.25x/1x, cut | host by nature (fonts); one ladder rule | rule shared here (`nameStep`); special ladder next |
| 26 | Name as a menu | chevron only with more than one machine | chevron always | union loses nothing; website's | next (head drawing) |
| 27 | Machine list headers and notes | one header | per-kind headers and notes | union | next (menus) |
| 28 | Card menu rows | Clone, Add another recipe, Replace the recipe, Add to a task (live), Delete | Clone, Put in another mob, Pick another crop, Add to a task, world rows, Delete | union | rows shared here; mod lacks the search behind Add another recipe and Replace (next item: NEI-side dispatch); website to add the crop and mob rows |
| 29 | Actions key tip | lists a settings row neither menu has | same; generators "Clone, delete" | union (say what the menu has) | next: tips |
| 30 | Place key and its menu | none | map pin, world rows | host by nature | stays |
| 31 | Rows that run nothing | an unwired port at zero has no row | every port drawn | more general, union | next: rails (`buildRailPorts` is not translated) |
| 32 | Kept (not used up) inputs | left off the rails | drawn as ordinary tiles | game decides (it must sit in the machine); union: a "Not used" row | next |
| 33 | Free row words and tip | "free", "in controller", "Not used"; a browser title | "free"; tip host "Free input" | union | next: tips |
| 34 | Ore-dictionary slot names | "Any <member>" | the chosen stack's name | game decides (09: slot alternatives) | model P4 |
| 35 | Unwired EU output | open tint | not open | more general | next |
| 36 | Port tip | Consumed/Produced, chance, used up | Average output, chance, Energy, NEI lines | union | next: tips |
| 37 | Forced row tip | brought in / choose who takes it | names the alternatives | union | next: tips |
| 38 | Open port box tip | "this output gives" on inputs too | "input takes" | the mod's wording (website bug) | next |
| 39 | Energy reading | `powerValue`, "A amps/Item" under the Amps key (bug) | three significant figures | more general (one formatter); the unit bug fixed | here on both sides |
| 40 | Power key choices | EU/t, Amps, one fixed tier | EU/t, Amps | union | next (settings) |
| 41 | Glance product corner | first output in the corner | none | union | rule shared here (`glanceProduct`); mod drawing next |
| 42 | Glance count | the count text | builds (harvesters for crops), grey at zero | game decides (machines to build) | next |
| 43 | Glance extras | none | task dot, reveal on hover, rendered structures | union; structures host by nature | stays |
| 44 | Names when zoomed out | under the card | beside the card | owner's call (placement) | stays |
| 45 | Glance bar at capacity 0 | fill 0 | falls back to the set count | more general (the card's own bar does) | next (the shared glanceBar is the website's today) |
| 46 | Lit rows | falls back to the resource's row; never a free row | by port only | more general | here |
| 47 | Shared section rule words | "Take this recipe off the machine"; circuit tip | "Remove this recipe from the card"; no circuit tip | union | next: shared cards |
| 48 | Generator tips | name and "Generator" | per-machine rows, unknown-source note | union | next: tips |
| 49 | Generator strip | enabled pinned settings only | readings and disabled settings too | union | next |
| 50 | Holder tips | fixed notes | supplies/requests, held amounts | union | next |
| 51 | Custom rate dial | in the board's rate unit | always /s, L/s, EU/t | more general (the dial follows the unit) | next |
| 52 | Holder socket tips | the socket word, tank-aware | an explanation, same on a tank | union | next |
| 53 | Empty crop farm | "Pick a crop..." name, a full-width key | "Crop Farm", a key on the output rail | same feature, one wording | next |
| 54 | Crop card parts | sprout key, harvester count chip | harvesters in tips and the glance | union | next |
| 55 | Crop power row | only when the harvester draws | always ("0 EU/t") | game decides (hand picking draws nothing) | next |
| 56 | Typing a count | live, 300 ms after typing rests | on Enter or a click away | more general (website's) | W11 interaction rules |
| 57 | Ring outline | a 2 px inset frame replacing the edge | a 2 px ring inside the edge | host by nature | stays |

Same on both sides and now one rule: the solved count's format, the set count's text, the wheel and the rule a typed
count keeps, the foot bar's words, lines, fraction, fill and ghost, DEAD LOOP and CLOG LOCK, the frame's tone, the lit
rows' stripes, port rates and units, the Forced row word, the circuit number.

## Geometry: the record against the website's DOM

See the state note (`_orch/state/rs-view-b.md`) for the probe and its numbers; in short the website's card is the
stylesheet's grid and `CardGeometry.web` is that grid written as arithmetic. Where they differ is listed there.

## Switching the website to the compiled core (not in this item)

The website keeps its TypeScript builders, bound by the shared cases. To switch (`src/lib/core/adopt.ts`, a `card`
area), it will need:

1. An export `cardViews(dialsJson, cardsJson)`: one call per board edit, every card's `CardInput` in, a JSON array of
   `CardView`s out (the web stream's convention: JSON text in and out, ids as strings, no 64-bit numbers). A card's
   view is on the render path: per-card crossings cost more than a cached TypeScript call (the machines stream measured
   0.1 to 0.2 ms per card crossing warm against 10 to 20 us cached TypeScript), so the batch is the design, and the
   website keeps a per-card cache keyed by the card's input text.
2. `TextMeasure` from JavaScript: the core asks widths by style, the website answers from `text-measure.ts`
   (`measureClean`, `drawnWidth` per `ROW_TYPE`, `mcTextWidth`). A batch call needs the widths without a callback per
   string: either a JSO measure object the core calls synchronously (TeaVM allows it; cost per string to be measured)
   or a two-pass design (the core returns the strings it must measure, the website measures them once, the core
   finishes). Measure both before choosing.
3. `Text` on the website: an installed `Text` that answers `gtnhplanner.card.*` from a word table (the website has no
   language file; one generated from the mod's `en_US.lang` would do).
4. The facts the website builds before the builders (`CleanCardFacts`: machine maths, pictures, RailPorts) become a
   `CardInput` writer: the rails (`buildRailPorts`, node-verdict.ts) and the special cards (`use-special.ts`) are not
   translated yet and stay TypeScript in front of the core.
5. A before/after profile of plan open and a setting change on the stress and issue-156 boards (`next start` after
   prewarm, WebAssembly and JavaScript), as the adoption rules require; the TypeScript path stays the fallback.
