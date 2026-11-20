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

Measured with a headless Playwright probe on the dev server (every `.cc-card` of five plans with full faces held:
titanium-line-chembath-slim, titanium-line-posted-alpha5, early-baux-ti-w-boards, crusher-and-empty-crop-farm,
biodiesel-demo; 63 cards, 58 with a power row, 44 with a foot bar, 7 with a strip, 2 forced rows), against
`CardGeometry.web` over the same structure:

| | equal | differs |
|---|---|---|
| card height and board node height (whole cells) | 59 of 63 | the 3 shared machines (+130 px: section rule rows, not modelled) and the empty crop farm (120 against 160: no rails, a full-width key) |
| port anchors | 245 of 255 | the 10 on the shared machines (+20: each section's rule row) |
| strip top | 62 of 62 | |
| foot bar top | 60 of 60 non-shared | |

One finding the probe made, now in the record: on the website a port with a forced row under it is one element two
rows tall and its wire docks between the two rows (anchor +20 from the port line); the mod docks at the port's own
row. Both are drawn as they route; which one is right is an interaction question (the forced row is dragged off as a
drawer on both). The special cards' geometry comes with their rules.

## The shadow: the website's card against the compiled core

The core's card rules are in the web build (`ViewExports` in the `core` module, `jvm/web/README.md`), and no production
path calls them yet. `src/components/flow/clean/card-shadow.test.ts` holds the two equal on both targets:

- the 99 shared cases: the core over each stored input gives the stored view, text for text; and the input the website
  writes from its own facts for each case (`cardInputOf`, `card-input.ts`) is the stored input;
- 722 recorded cards (`conformance/view-inputs/card-website-test-inputs.jsonl.gz`): every card the website's component
  tests draw, and every recipe card of the plan fixtures (conformance/plans, examples/) solved by the website's engine
  under three sets of dials (per second, EU/t, average; per minute, Amps, peak; per tick, an HV tier, average) and once
  more with every running card set to Up to its count. Each replays through `cleanCardView` and, as the core's input,
  through `CardViews` with the website's own widths called back (`WEB_CARD_MEASURE`). Recorder:
  `node tools/audits/card-test-inputs/record.mjs`.

Result: no difference on either target (views equal key for key, numbers spelt the same; geometry has one anchor per
row). A planted change (every width three times the website's) differs on all 722 cards, so the comparison is not empty.
What the shadow does not reach: the special cards' own parts (`use-special.ts`: shared machine sections, generators'
rows, holders, crop cards; only their base card is `cleanCardView`'s), the rails' choice of rows (`buildRailPorts` stays
in front of the core), tips, and the geometry against the DOM (the probe above; the website has no TypeScript of its
card's geometry to compare).

What one card costs (Node 24 on the build box, the 722 recorded cards, warm, median of nine passes):

| Path | Per card |
|---|---|
| website `cleanCardView` | 17 us |
| website writes the core's input and its JSON (`cardInputOf`) | 16 to 21 us |
| core, Wasm GC, one call per card, widths called back (14.7 widths a card) | 90 to 94 us |
| core, Wasm GC, one call per card, six px (no call back) | 78 to 83 us (63 us of it inside the call; 8 us `JSON.parse` of the answer) |
| core, Wasm GC, one call per board (5 boards of 75 to 220 cards) | 106 to 120 us |
| core, JavaScript, one call per card, widths called back | 189 to 210 us |
| core, JavaScript, one call per board | 206 to 238 us |
| an empty call | 0.2 us (Wasm GC), 0.4 us (JavaScript) |

On the JVM (warm, the shared cases) a card costs 6 us to read its JSON, 2.3 us of rules and 6.6 us to write its view:
the codec is most of a call. Batching per board saves nothing (a crossing costs 0.2 us; the long JSON text costs more
than it saves), and the widths' call backs cost about 0.7 us each.

## Switching the website to the compiled core (not in this item)

The website keeps its TypeScript builders, bound by the shared cases and the shadow. To switch (`src/lib/core/adopt.ts`,
a `card` area), it will need:

1. A per-card call (`viewCardMeasured`) behind a per-card cache keyed by the input's text: per board saves nothing.
   Uncached, the core is about five times the TypeScript on Wasm GC and eleven times on JavaScript (a board of a
   thousand cards rebuilt whole: about 90 ms against 17 ms, 200 ms on JavaScript), so a card that did not change must
   not cross. Most of a call is JSON (on the JVM 12 of 15 us): a cheaper crossing (JSO records, or the view's numbers
   written without the exact decimal printer where they are whole) is the first thing to try if the cache is not
   enough. Measure on `next start` with a real board before switching (adoption rule 2).
2. `TextMeasure` from JavaScript: done as a call back (`WEB_CARD_MEASURE`, 0.7 us a width); a two-pass design is not
   needed.
3. `Text` on the website: `viewCardWords` with the `gtnhplanner.card.*` words. The shadow reads them from the mod's
   `en_US.lang` in Node; the site needs them as data it can ship (generated from the language file at build time, as
   the tokens are).
4. The facts the website builds before the builders (`CleanCardFacts`: machine maths, pictures, RailPorts) stay
   TypeScript in front of the core; `cardInputOf` writes them as the core's input. The special cards (`use-special.ts`)
   are not translated yet.
5. A before/after profile of plan open and a setting change on the stress and issue-156 boards (`next start` after
   prewarm, WebAssembly and JavaScript), as the adoption rules require; the TypeScript path stays the fallback.
