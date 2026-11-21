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

Cards the shared rules do not cover yet keep the mod's own count and power rows: shared machines, crop cards and an
empty crop farm. The holders (custom rate card, fluid tank) and generators take the shared card rules and their own
parts from the special rules (section "Special cards" below). The website draws the
others' own parts through `special/use-special.ts`, not translated yet.

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
| 5 | Count title, custom rate | "Runs exactly this many..." | "Runs at its dial." | union (the mod's line is right for a dial) | here: "Runs at its dial." on both (`count.title.dial`) |
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
| 25 | Card name sizes | 10/9/8 px; special cards 14/13/12/9 | 1.5x/1.25x/1x, cut | host by nature (fonts); one ladder rule | here: special cards take the plain card's ladder (`nameStep`) on the website too |
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
| 49 | Generator strip | enabled pinned settings only | readings and disabled settings too | union (decided): a pinned setting switched off shows greyed; pinned readings on both | here: one record, a pin for readings in the website's sheet |
| 50 | Holder tips | fixed notes | supplies/requests, held amounts | union | next: tips |
| 51 | Custom rate dial | in the board's rate unit | always /s, L/s, EU/t | more general (the dial follows the unit) | here: the board's unit on both, typed in it on both |
| 52 | Holder socket tips | the socket word, tank-aware | an explanation, same on a tank | union | next |
| 53 | Empty crop farm | "Pick a crop..." name, a full-width key | "Crop Farm", a key on the output rail | same feature, one wording | next |
| 54 | Crop card parts | sprout key, harvester count chip | harvesters in tips and the glance | union | next |
| 55 | Crop power row | only when the harvester draws | always ("0 EU/t") | game decides (hand picking draws nothing) | next |
| 56 | Typing a count | live, 300 ms after typing rests | on Enter or a click away | more general (website's) | W11 interaction rules |
| 57 | Ring outline | a 2 px inset frame replacing the edge | a 2 px ring inside the edge | host by nature | stays |

Same on both sides and now one rule: the solved count's format, the set count's text, the wheel and the rule a typed
count keeps, the foot bar's words, lines, fraction, fill and ghost, DEAD LOOP and CLOG LOCK, the frame's tone, the lit
rows' stripes, port rates and units, the Forced row word, the circuit number.

## Special cards

A shared machine, a generator, the two holders (a custom rate card, a fluid tank) and a crop card are the plain card
with parts of their own (the website's `special/`, the mod's branches in `RecipeCard`). Those parts are a second record
in `core-view` (`com.gtnhplanner.core.view.special`), drawn over the card's own `CardView`:

- `SpecialInput` (in): the kind and its kind's facts: a holder's (empty or not, a custom rate card's dial: which way it
  faces, its rate a second, the held resource's kind, which of its two chips the card shows) or a generator's (its
  source's name, its tier select's value and whether it is fixed, every other setting with its kind, value, unit, whether
  the others leave it meaning anything and whether the plan pins it, its readings with their pins, its warnings). The card's own
  facts are its `CardInput`. `SpecialInput.Case` (dials, card, special) is the shared case's input.
- `SpecialView` (out): what the card shows in place of its card's own parts: the name and its step on the card's ladder,
  its ink, the head's chips, whether the rails are replaced (empty holder, empty crop farm), a shared machine's
  sections with their rule rows, the power row, an empty holder's sockets and their hints, the gauge, the count (none on
  a fluid tank), the bar along the foot, the strip's chips (a chip typed into carries its text and its scale to the
  stored value, and whether it is off: a pinned setting the others make moot, greyed), warnings. The rows, picture, ring
  and zoomed-out bar stay the card's own.
- `SpecialViews.special(input, card, cardView, dials, measure, host)`: the rules, a translation of use-special.ts kind by
  kind. `SpecialViews.covers(kind)` says which kinds are translated: the custom rate card, the fluid tank and the
  generator. A shared machine and a crop card keep each host's own rules until they are.

Geometry: `SpecialViews.webHolder(view, cardView)` is a holder card on the website: the head, the rails (the card's
rows, or an empty holder's two sockets anchored at their middle, 62 px down, named `take` and `give` for the host's
socket handles) beside an 80 px well (special.css's holder picture, not the plain card's 120), the strip, the bar. The
geometry probe measured every holder of a board of 36 (empty and holding custom rate cards, empty and holding tanks):
heights, rails, strip tops and anchors equal (`HolderGeometryTest` holds the numbers). The mod's holder is
`CardLayout`'s, as every card's (`CardGeometry.mod`).

Shared cases: `conformance/view/special/cases.json` (25 holder cards: empty and holding, supply and request, items,
fluids and EU under every rate unit and power dial, rates past a thousandth, unpinned chips, a forced input, nothing
set; 14 generators: nothing pinned, a choice, numbers with and without a unit, a switch, a pinned setting switched off,
a fraction, readings after the settings, two rows of chips, a fixed tier, no tier select, a long name, warnings, the
Amps dial, Exactly, nothing set), written by the website's rules (`special/special-conformance.ts`, `special-parts.ts` over `cleanCardView`; the view
generator runs it), read by `SpecialCasesTest` (JVM), `special-conformance.test.ts` and the Node run on both targets
(`tools/web-core/view.mjs`, group `view/special`).

Website: `special-parts.ts` holds the website's rules (`specialParts`, kept as the path without the core) and
`specialViewFrom` (the parts and the card's view as the `SpecialView` SpecialCard.tsx draws, with the host's pictures,
handles and how a typed chip is stored). `special-view.ts` is the switch, under the recipe card's flag and targets
(`ADOPT.card`, `CARD_TARGETS`): `specialPartsOf(cardFacts, facts, cardView)` takes the core's parts read back by
`partsOfRecord`, kept per card by its inputs, dials and font epoch, else the website's rules; a core that throws leaves
the card to the website's rules. The core's call starts from the card view the core made for the card: every
`viewCardJsKept` holds its card (input, view, font state) under a ticket in a ring of 512 (`viewCardTicket`), card-view.ts
keeps the ticket with the view (`cardTicketOf`), and `ViewExports.viewSpecialJsHeld(ticket, special, measure, fontState)`
takes only the kind's facts; a card the ring has let go (`viewCardHeld` false) goes the long way
(`viewSpecialJsKept`: `{dials, card, special}` in, the card made again on the way). `useHolderCardModel` and
`usePowerCardModel` are the callers.

Mod: `ui/card/SpecialInputs` writes a holder's or a generator's `SpecialInput` from its node (`of`; a generator's
settings and readings from `PowerTiles`, its pins from `SettingPins`, its warnings from its model), a holder's (empty when it has no port, the dial from
`CustomRate`, the chips shown from the plan's pins) and turns the view's chips into the card's own setting controls.
`RecipeCard` draws a holder and a generator through the shared card rules (count, menu) and their parts from the
`SpecialView`: the name, the sockets' words and hints, the strip (laid out again when the dials turn; a reading's chip is
its `reading:<label>` control, an off chip greyed), and the rate chip typed in the board's unit (`rateBox`: the edit's
scale back to a rate a second, EU a tick on a card holding EU).

Switch: on for WebAssembly GC, with the recipe card (`ADOPT.card`, `CARD_TARGETS`). As the recipe card's first view,
a special card's first parts are the website's rules' and the core makes them in idle time
(`fillWaitingSpecialViews`); every later draw is the core's or a kept one. Checked on `next start` after prewarm, headless
Chromium, with the website's probe: the board's DOM (every node's `outerHTML` with every card held mounted, and the
page's text) under four dial sets is identical with the card area off and on, on two boards with holders wired so they
hold resources (the chembath fixture with 36 holders, 60 nodes; the 89-card plan with 24, 113 nodes; empty and holding
custom rate cards with items, fluids and EU, empty and holding tanks). No player plan on the box has a holder on its
board (a holder with no wire lets go of its resource on load).

Time in a holder's own parts (`specialPartsOf`, summed over the holders React drew in the step), the 89-card plan
with 24 holders, ms, median and range of six interleaved runs each, card area off (the website's rules) and on
(WebAssembly):

| Interaction | Calls | off | WebAssembly |
|---|---|---|---|
| open (first parts by the rules, the core's in idle time) | 50 | 2.0 (1.5-8.4) | 3.7 (1.5-8.6) |
| rate unit | 24 | 0.2 (0.1-0.4) | 0.3 (0.1-0.6) |
| power display | 24 | 0.3 (0-2.1) | 2.6 (1.2-2.9) |
| one card's tier | 24 | 0.2 (0-0.4) | 0.3 (0.1-0.6) |
| a solve that moves every card | 48 | 0.3 (0.2-0.7) | 1.5 (1.2-1.7) |
| hover, pan, drag | 0 to 2 | 0 | 0 to 0.2 |

A changed holder costs about 50 to 100 us on WebAssembly against about 10 us by the rules (the core makes the card's
own view on the way: the special call does not reuse the recipe card's call); a kept one costs a comparison. The worst
step, a power display turn over 24 holders, is 2.3 ms more. Without the idle fill the open cost 18 ms more over 36
holders (the first calls of a page), which is why the first parts are the rules'.

What a player sees change:

- Website: every special card's name is drawn on the plain card's ladder, 10, 9 or 8 px (it was 14, 13, 12 or 9 px,
  measured in another face): the special cards had kept the sizes the card had before it took the mod's. A custom rate
  card's count tip says "Runs at its dial." (it said the Exactly line).
- Mod: a custom rate card's count line is the shared card's (the rule word "Exactly" and the set box with its count). Its
  rate chip reads in the board's rate unit ("Rate (/min)", "Rate (L/hr)") and is typed in it (it was always a second,
  L/s, or EU/t). Words, sockets and hints are the same.

### The special cards' parts, side by side

Inventory of every special card kind (website `special/` at this branch, mod `RecipeCard` and `power/`), what each shows
beyond the plain card, where the two differ and the rule that settles it. Order of work: how many players see the kind,
then how cleanly its parts come apart from the card.

| Kind | Parts beyond the plain card | Differences (website / mod) and the rule | State |
|---|---|---|---|
| Custom rate card | blue name, two sockets with hints while empty, gauge picture, count fixed to Exactly (its dial), mode and rate chips, no power row, no tier | rate chip: board's unit / always a second: the board's (more general); count title: Exactly line / "Runs at its dial.": the mod's (union); count line: shared look / the mod's old one: shared; chips unpinnable on the mod only: kept (union: the record carries which chips show) | shared record, both sides |
| Fluid tank | blue name, sockets while empty, the Tank map's picture, no count, no foot bar | none in the parts; the mod's count was already hidden | shared record, both sides |
| Generator | amber name, tier chip from its tier select ("Unlocks at" when fixed), pinned settings and readings on the strip, warnings under it, readings in the gear sheet, EU rows in its own tier (shared since W7) | decided: a pinned setting switched off shows greyed on both (the website hid it); readings pinned to the strip on both (the website gains a pin per reading in its sheet, kept as `reading:<label>` under `power:<source>` as the mod keeps it, and the plan carries it both ways); a number chip reads "Label" and "N unit" on both (the mod read "Label (unit)" and "N" with grouping); tier chip on a non-voltage tier select: none in the catalog (the only "tier" selects are voltage tiers; the heat exchanger's "tier" is a number), so the two tests agree; the mod's count is the shared one | shared record, both sides |
| Crop card | count in seeds (no times sign, "seed(s)" beside it, its own title), harvesters chip (Crop Managers / Industrial Farms, pinned by default, typed in machines), harvester's draw as the power row in its own tier, tier chip from the manager or seed bed control, crop key in the head | count unit on every rule / "crop(s) planted" on Any only: game (seeds), unit on every rule (row 4); harvesters chip / in tips and the glance only: union (row 54); power row only when the harvester draws / always "0 EU/t": game (row 55); the mod's power row bills its own way | next; left untouched |
| Empty crop farm | "Pick a crop..." name, a dashed full-width key in place of the rails, no count | "Pick a crop..." with a full-width key / "Crop Farm" with a key over the output rail and a hint: one wording to pick (row 53); geometry 120 / 160 px | next; left untouched |
| Shared machine | a rule row over each recipe (share %, its machines or UNUSED, circuit, up, down, off), each recipe's rows under its handle prefix, one power row after them all, the count and bar over the whole pool, the picker over every recipe's machines | power row: amps only when every recipe runs at one tier / divides the whole by the first recipe's tier: game (row 14); rule tips (row 47); geometry: a rule row 20 px and no gap / 16 px and an 8 px gap; the mod's count and bar still its own | next; left untouched |
| Bee card, Extreme Entity Crusher | not special on the website (the plain card); the mod names the card for the species or the mob | rows 20 and 21 (the card's name): a plain card question | not this record |

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

The core's card rules are in the web build (`ViewExports` in the `core` module, `jvm/web/README.md`); the board calls
them through the switch (next section). `src/components/flow/clean/card-shadow.test.ts` holds the two equal on both
targets:

- the 99 shared cases: the core over each stored input gives the stored view, text for text; and the input the website
  writes from its own facts for each case (`cardInputOf`, `card-input.ts`) is the stored input;
- 722 recorded cards (`conformance/view-inputs/card-website-test-inputs.jsonl.gz`): every card the website's component
  tests draw, and every recipe card of the plan fixtures (conformance/plans, examples/) solved by the website's engine
  under three sets of dials (per second, EU/t, average; per minute, Amps, peak; per tick, an HV tier, average) and once
  more with every running card set to Up to its count. Each replays through `cleanCardView` and, as the core's input,
  through `CardViews` with the website's own widths called back (`WEB_CARD_MEASURE`), the way the website will call it:
  records as JavaScript objects (`cardThroughCore`, `ViewExports.viewCardJs`); the JSON export must give the same answer
  text for text. Recorder:
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
| core, Wasm GC, one call per card, records as objects, widths called back (the website's path now) | 33 to 35 us |
| core, JavaScript, the same | 56 us |
| core, Wasm GC, one call per card, widths called back (14.7 widths a card), JSON | 90 to 94 us |
| core, Wasm GC, one call per card, six px (no call back) | 78 to 83 us (63 us of it inside the call; 8 us `JSON.parse` of the answer) |
| core, Wasm GC, one call per board (5 boards of 75 to 220 cards) | 106 to 120 us |
| core, JavaScript, one call per card, widths called back | 189 to 210 us |
| core, JavaScript, one call per board | 206 to 238 us |
| an empty call | 0.2 us (Wasm GC), 0.4 us (JavaScript) |

On the JVM (warm, the shared cases) a card costs 6 us to read its JSON, 2.3 us of rules and 6.6 us to write its view:
the codec is most of a call. The records now cross as JavaScript objects with no text (`jvm/web/README.md`, "Records
across the boundary", has the candidates measured and the table in Node and in Chromium). Batching per board saves nothing (a crossing costs 0.2 us; the long JSON text costs more
than it saves), and the widths' call backs cost about 0.7 us each.

## The switch: the board's cards through the core

`ADOPT.card` (`src/lib/core/adopt.ts`) is on, for WebAssembly GC only (`CARD_TARGETS` in
`src/components/flow/clean/card-view.ts`). `useCleanCardModel` makes its view with `cardViewOf(facts)`: the plain card,
the special cards' base card and the Overview rail's machine chips all go through it. With the area on and the core
loaded on WebAssembly GC:

- the card's facts are written as the core's input (`cardInputOf`), and the core's `CardView` comes back through
  `viewCardJsKept` (records as JavaScript objects, the website's widths called back and kept in the core per font
  epoch) and is read back as the website's `CleanCardView` (`cleanViewOf`: tone and rule names, the rail's own port
  objects, the programmed circuit; nothing is re-derived);
- a card's last two views are kept by (input, dials, font epoch), compared key for key (`sameData`): a remount, a render
  that changed nothing of the card, a drag frame and a solve that left its numbers alone make no call;
- a card the core has never drawn is drawn by the builders (the same view) and the core makes its view in idle time
  (`fillWaitingCardViews`, idle slices): opening a plan stays at the builders' cost; every later view of the card is
  the core's;
- a core that throws on a card leaves that card to the builders, and says so once in the console.

On the JavaScript build, with the area off, or before the core has loaded, the builders draw every card
(`cleanCardView`). `card-view.test.ts` holds the two equal on both targets: the 99 stored records read back equal the
builders' views, and the 722 recorded cards through the switch equal `cleanCardView` field for field.

Words: `src/lib/core/card-words.generated.ts` (gitignored) is written from the mod's `en_US.lang` (`gtnhplanner.card.*`)
by `tools/web-core/gen-card-words.mjs`, which `gen-types.mjs` runs on every run; the switch hands it to each loaded core
once (`viewCardWords`). Fonts that load late: text-measure.ts bumps its font epoch when the app's face lands
(`resetMeasuredFont`, also on a face picked in the settings, and in the tests' `setFixedTextMeasure`); the epoch is
part of every kept view's key and is the core's width state, so every card is measured again in the new face.

### What it costs (box, `next start` after prewarm, headless Chromium, cores shared with the server)

Time in the card view code (`useCleanCardModel`'s view, summed over the cards React rendered), ms, two runs each.
"base" is restructure 9c9e59a9 (the builders); the board is zoomed in until full cards mount (38 on the 89-card plan,
24 on the 1000-card stress plan). Views: how many card views React asked for in the step.

| Interaction | Plan | Views | base | WebAssembly | JavaScript (not switched) |
|---|---|---|---|---|---|
| open (until every card is drawn) | 89 cards | 89 | 23.3, 24.3 | 26.5, 33.5 (+15 idle fill) | 21.7, 33.0 (+26 idle fill) |
| open | stress | 1079-1103 | 91.3, 91.2 | 93.4, 122.5 (+107-126 idle fill, 22 slices) | 102, 98.6 (+174-183 idle fill) |
| zoom in (cards mount) | 89 | 39 | 3.6, 3.8 | 23.2, 10.3 | 14.3, 13.4 |
| zoom in | stress | 24 | 1.6, 1.4 | 2.8, 1.4 (kept) | 7.4, 5.5 (kept) |
| rate unit | 89 | 38 | 5.1, 1.8 | 3.8, 2.0 (kept) | 3.5, 2.9 (kept) |
| rate unit | stress | 24 | 2.0, 0.7 | 3.7, 4.8 | 9.8, 7.5 |
| power display | 89 | 38 | 3.4, 3.2 | 11.0, 11.2 | 17.6, 17.4 |
| power display | stress | 24 | 1.7, 0.7 | 2.7, 2.5 | 9.2, 8.3 |
| one card's tier | 89 | 40 | 2.4, 1.5 | 1.8, 3.1 (1 call) | 2.9, 2.0 (1 call) |
| one card's tier | stress | 25 | 1.2, 0.7 | 0.9, 1.3 (kept) | 1.8, 1.7 (kept) |
| a solve that moves every card | 89 | 76 | 3.4, 4.0 | 13.3, 14.2 | 39.8, 38.9 |
| a solve that moves every card | stress | 50 | 2.2, 2.0 | 6.5, 5.6 | 10.2, 13.0 |
| hover over three cards | both | 0 | 0 | 0 | 0 |
| drag a card 30 frames | both | 2 | 0.1-0.3 | 0.3-0.5 (1 call, 1 kept) | 0.5-0.7 |
| pan 30 frames | 89 / stress | 0 / 4 | 0 / 0.4-0.5 | 0 / 0.6 | 0 / 0.9-1.2 |
| zoom out and in | 89 | 59-67 | 3.8, 5.8 | 5.8, 7.6 (13 calls) | 12.1, 10.4 |
| zoom out and in | stress | 30-35 | 2.2, 1.8 | 2.7, 6.7 (6 calls) | 2.8, 4.7 |

The JavaScript column is the switch forced on for the measurement; the committed switch leaves that build on the
builders. A card is never rendered on hover, on a pan frame or on a drag frame of another card, so nothing here runs
per frame. The whole step's main-thread time (CDP) is not a measure of the card code: it is mostly the browser's own
work and garbage collection, and a major collection (30 to 90 ms) lands in a step's window or not at random. Re-run on
the 89-card plan with every waiting card filled before hover, six runs each of base, the switch forced on, the same
build with the area off and the build as committed (12 hover windows each, CPU profiles): hover medians 114 / 108 /
117 / 117 ms with the same spread in each (61 to 181), no card view and 4 to 15 ms of script in every window; every
high window holds a 30 to 90 ms collection, base included, and a step with no input at all shows the same (1 or 30 to
74 ms). The solve that moves every card: 184-245 / 192-213 / 152-223 / 204-211 ms. Hot, in the page,
microseconds a card over the cards drawn: builders 14-35, input 4.5-7.5 (12-16 before this item trimmed it), core on
WebAssembly 23-30 with the widths kept in the core (30-71 calling every width back), on JavaScript 54-71 (56-121).

What would let the JavaScript build switch too: its rules cost two to four times the builders' on every changed card
(a solve on the 89-card plan: +36 ms, two frames). The cost is inside the compiled rules, not the crossing (six px,
no call backs, is the same): profile `CardViews` on the JavaScript target (`-Pplain` build for names) for the
allocation and string building it does per row, or let the rules run once per board change for the cards in view in
idle time as the open does.

### Text: the switch draws what the builders drew

Every card's DOM (`outerHTML` of each board node with every card held mounted, `data-cv-hold`, so both the full face and
the glance layer are in it) and the page's text, under four dial sets (minute and Amps, second and EU/t, tick and EU/t,
second and Amps), base against the switch on WebAssembly and on JavaScript (forced), with every view counted as the
core's or a kept core view: identical on the 89-card plan (2.31 MB of card DOM a set) and the 1000-card stress plan
(14.2 MB a set).
