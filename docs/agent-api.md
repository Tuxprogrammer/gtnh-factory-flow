# Agent API (v1)

An HTTP API that lets an assistant look up recipes in the site's dataset and design plans for a player with the site's
own solver. It is meant for a tool server on the instance's internal network that maps its tools one to one to these
endpoints. Players never call it.

Status: `resources/search`, `recipes/search`, `recipes/get` and the draft endpoints `plans/create`, `plans/add-cards`,
`plans/remove-cards`, `plans/set-targets`, `plans/connect`, `plans/disconnect`, `plans/get`, `plans/solve` and
`plans/expand-inputs` are built, and so are `plans/deliver`, `plans/import`, `plans/diff` and the read route
`GET /api/deliveries/{id}`.
Every endpoint of v1 is built.

Code: `src/app/api/agent/v1/**` (routes) and `src/lib/agent-api/**` (logic, request and response schemas in
`schemas.ts`). Examples: `docs/agent-api/examples/*.json`, one real request and response per built endpoint, checked
against the schemas by `src/lib/agent-api/agent-api-examples.test.ts`.

## Transport and auth

- Base path `/api/agent/v1/`. JSON in and out. Every endpoint is `POST` with a JSON object body; any other method
  answers `bad_request` (after auth).
- `Authorization: Bearer <token>`, where the token is the server's `AGENT_API_TOKEN` environment variable, compared in
  constant time. No cookie or session is read, and none is accepted in place of the token.
- **Off by default.** With `AGENT_API_TOKEN` unset or blank, every path under `/api/agent/` answers a bare
  `404 Not Found` (text, not the error envelope), the same as a path that does not exist.
- With the token set, a missing or wrong token answers `401` with code `unauthorized`.
- The path is for the internal network only: the instance's public proxy answers 404 for `/api/agent/`.
- Request bodies are at most 64 KB (`plans/import` alone takes up to 512 KB: it carries the player's open plan). Unknown fields are refused (`bad_request`), so a misspelt option fails loudly.
- Every request may carry `player: {name, uuid?}`: the player the assistant acts for. It is logged for audit and
  never used to grant anything.
- Each call writes one log line: `[agent-api] route=<route> player=<name> outcome=<status or code> ms=<n> bytes=<n>`.
  The token and request and response bodies are never logged. The player name is cut to Minecraft name characters.

## Conventions

- Rates are per second. Durations are in seconds (`seconds`), power in EU/t (`euT`).
- Tiers are names: `ULV LV MV HV EV IV LuV ZPM UV UHV UEV UIV UMV UXV MAX`.
- Every id in a response comes with its display name (`name`).
- A resource is named by its dataset id. In a request it may be a bare id (`"gregtech:..."`, tried as an item, then a
  fluid, then an aspect) or `{id, kind}` with `kind` one of `item`, `fluid`, `aspect`. Find ids with
  `resources/search`.
- Every response names the dataset version it was answered from (`dataset`): the one the server prewarms (latest
  stable, else latest daily).
- Sizes: a response aims at 12 KB of JSON. A list answer that would go over drops results from its end and says
  `truncated: true`; ask again with a smaller `limit`, a later `offset`, or without `detail`.
- Time: each lookup answers in under 2 s after the server's prewarm; a solve in under 5 s. The prewarm that counts
  is the dataset prewarm route (`GET /api/datasets/<version>/prewarm`), which also builds the agent API's own indexes
  when the API is on; the start-up prewarm runs in a module copy the routes do not share. Searches on the most common
  resources (water, oxygen: thousands of recipes) take the longest, about 1 to 2 s.

## Errors

Every error except the disabled-API 404 is `{"error": {"code": "...", "message": "..."}}`. Branch on `code`; the
message is for people and may change.

| code                  | HTTP | when                                                                       |
| --------------------- | ---- | -------------------------------------------------------------------------- |
| `unauthorized`        | 401  | missing or wrong bearer token                                              |
| `bad_request`         | 400  | not JSON, over 64 KB, unknown field, wrong type, missing field, wrong method |
| `unknown_resource`    | 404  | a resource id the dataset does not have                                    |
| `unknown_recipe`      | 404  | a recipe id the dataset does not have                                      |
| `no_recipe`           | 422  | nothing makes the resource: no card in the draft makes a target            |
| `tier_too_low`        | 422  | a card's tier is below its machine's minimum, or nothing runs it at or below the draft's `tierCap` |
| `plan_not_found`      | 404  | unknown or expired draft id                                                |
| `plan_too_big`        | 413  | a draft would go over 60 cards (200 for an imported plan)                  |
| `rate_limited`        | 429  | a daily limit is spent (UTC days: 300 drafts, 100 deliveries, 300 proposals) |
| `dataset_unavailable` | 503  | the server has no dataset installed or cannot read it                      |
| `internal`            | 500  | anything else, and `plans/solve` when the server cannot load its solver; the server log has the detail |

## Constraints

Accepted by `recipes/search`, `plans/create` (a draft keeps its constraints; `plans/solve` takes none of its own) and
`plans/expand-inputs` (joined with the draft's; see its section):

```json
{ "tierCap": "HV", "heat": 4500, "cleanroom": false, "lowgrav": false, "banned": ["..."], "free": ["..."], "steam": false }
```

What `recipes/search` does with each:

| field       | effect in search                                                                                       |
| ----------- | ------------------------------------------------------------------------------------------------------ |
| `tierCap`   | **filters**: recipes whose minimum tier is above the cap are left out                                  |
| `heat`      | **filters**: a recipe on a heat machine (Electric Blast Furnace and kin) that needs more heat in K is left out |
| `banned`    | **filters**: ids or names, matched without case against the recipe id, its recipe map, and each machine's id, name and type. A recipe is left out when the recipe or its map is banned, or every machine that runs it is; otherwise the first machine that is not banned is the one shown |
| `steam`     | **filters**, default `false`: unless it is `true` or `tierCap` is `ULV`, a steam or primitive machine is never the machine shown, and a recipe only they run is left out and counted in `steamOnly`. Naming one in `machine` shows it |
| `cleanroom` | **not enforced**: the dataset carries no cleanroom flag on any recipe, so nothing can be filtered on it |
| `lowgrav`   | **not enforced**: the dataset carries no low-gravity flag on any recipe                                 |
| `free`      | **not enforced** by search: it means "treat as raw" to expand and solve                                |

A search given any field it does not enforce lists those fields in `notEnforced`, so the caller knows the results were
not narrowed by them.

Steam and primitive machines (no electricity):

- Steam: every machine the site bills in litres of steam instead of EU, the "Steam ..." and "High Pressure ..." line
  (21 in the pack: the eight steam multiblocks, their single blocks, and the Steam Ammo Press).
- Primitive: the Bricked Blast Furnace and Railcraft's Coke Oven (a named list: the dataset has no flag for them, and
  their 0 EU and ULV are what generators, crafting and the Multi Smelter read too; a check against the pack dataset
  fails when a new 0 EU machine is neither steam, primitive, nor known to be something else).
- Every tier filter counts them as ULV, whatever their tier reads in the dataset (it comes from the recipe's EU).

What a draft does with its constraints: `tierCap` caps the default machine and tier of every card and refuses a tier
above it; `banned` keeps a machine from being chosen; `steam` (unless `true`, or `tierCap` is `ULV`) keeps a steam or
primitive machine from being a card's default machine, though a card may name one; `free` marks those resources
`free: true` in the solve's `unsupplied` list. `heat`, `cleanroom` and `lowgrav` do nothing to a draft (a card shows the
`heat` its recipe needs). The draft's `cardTier` (from `plans/create`, default `tierCap`) is the tier cards default to, and its `minTier` the
lowest a card may run at.

## resources/search

Find resources by name.

Request: `{text, kind?, limit?, player?}`

- `text` (1 to 100 characters): words of the name, as typed in the site's search box.
- `kind`: `item` or `fluid`; omitted searches both.
- `limit`: 1 to 25, default 8.

Response: `{dataset, results: [{id, kind, name}], total, truncated?}`. `total` is every match; `results` are the first
`limit` in the site's own order (exact names and common items first).

Example: `docs/agent-api/examples/resources-search.json`.

## recipes/search

Find recipes that make or take a resource, or by name.

Request: `{makes?, takes?, text?, machine?, constraints?, limit?, offset?, detail?, player?}`

- At least one of `makes`, `takes`, `text`. `makes` and `takes` together mean both.
- `text` (1 to 100 characters): words of the recipe or its resources, as in the site's recipe search.
- `machine`: a recipe map or machine name or id (`"Electrolyzer"`, `"Blast Furnace"`); only recipes it can run.
- `limit`: 1 to 25, default 8. `offset`: 0 to 1000, default 0.
- `detail`: adds each recipe's map, its machines, and up to 5 alternatives for an "any of" input.

Response: `{dataset, results, total, offset, more, truncated?, notEnforced?, steamOnly?}`. `total` counts every match
before constraints; `more` says another page may hold results. `steamOnly`: how many of the recipes looked at were left
out because only steam or primitive machines run them (set `constraints.steam` to see them).

A compact recipe:

```
{recipeId, name, machine, minTier, euT, seconds, heat?, circuit?,
 inputs: [{id, kind, name, amount, consumed, alternatives?, moreAlternatives?}],
 outputs: [{id, kind, name, amount, chance?}],
 map?, machines?: [{id, name, kind}]}            (map and machines with detail only)
```

- `machine` is the machine shown for the recipe: its default one, or the first not banned, and not a steam or
  primitive one unless `steam` allows it or `machine` names it.
- `consumed: false` marks an input the recipe holds but does not use up (a catalyst, a mold, a circuit).
- `chance` (0 to 1) appears only on an output that is not certain.
- `heat` is the heat in K the recipe needs, only for recipes on a heat machine. `circuit` names the programmed circuit.

Ranking:

- `makes`: recipes whose first output is the resource at full chance (its main product) before recipes that make it
  second or by chance (byproducts); then recipes that produce it before recipes that only give it back (a replicator;
  a recipe whose inputs are all the same material in a form that is not ore, such as dust from an ingot or from tiny
  piles; a one-input macerator, arc furnace or pulverizer recipe from something that is not ore, such as a crafted
  part); then fewer outputs; then the lower minimum tier; then fewer consumed inputs. So "what makes Titanium Dust"
  lists ore washing and centrifuging first and the macerating of titanium parts last.
- `takes`: recipes that use the resource up before ones that only hold it; then the lower minimum tier; then fewer
  consumed inputs.
- `text` alone: recipes whose name holds every word first; then the lower minimum tier.
- Anything still equal keeps the site's own order. A resource with more than 160 matching recipes is searched recipe
  map by recipe map (the 48 largest maps, each its own best few), so one map's many recipes do not hide another's main
  recipe.

Example: `docs/agent-api/examples/recipes-search.json`.

## recipes/get

One recipe in full, with every machine that can run it and that machine's dials.

Request: `{recipeId, player?}`

Response: `{dataset, recipe}` where `recipe` is

```
{recipeId, name, map, minTier, euT, seconds, heat?, circuit?, inputs, outputs,
 machines: [{id, name, kind, minTier, maxTier?, tiers?, euT, seconds, parallels?, perfectOverclock?,
             heatOverclock?, dials: [{id, label, min, default, options?, numeric?}]}]}
```

- `inputs` carry alternatives as in `detail`.
- `machines`: the default machine first, then the others (single blocks, multiblocks), each with the recipe's numbers
  on that machine before overclocking. `kind` is `single`, `multiblock`, `crafting` or `automation`. `tiers` lists the
  tiers it can be built at when they are limited.
- `parallels`: recipes the machine runs at once at its minimum tier with every dial at its minimum, when the machine
  table states it.
- `perfectOverclock`: 4x speed per tier for 4x power. `heatOverclock`: the machine overclocks on its coil heat.
- `dials`: the machine's settings (coils, tier parts, numeric settings). `options` lists choices with `key`, `label`,
  and their `heat` or `parallels` where they set one; `numeric` gives a range.

Example: `docs/agent-api/examples/recipes-get.json`.

## plans/* (drafts)

A draft is a plan the server holds for the assistant to build: a real plan in the site's own model (the same plan the
planner opens, in plan mode), in the server's memory, under a random id. It is kept 24 hours after its last change,
holds at most 60 cards, and at most 300 are created a day (UTC). A restart drops drafts; the assistant starts over.
Nothing here reads or writes a player's design, library entry, account or session, or the community hub.

Ids: a draft is `x` and 19 characters from `abcdefghjkmnpqrstuvwxyz23456789`; cards are `c1`, `c2`, ... and wires
`w1`, `w2`, ..., never reused within a draft. An imported draft keeps the player's own card, drawer and wire ids (any
of `^[A-Za-z0-9_][A-Za-z0-9_.:-]{0,99}$`); cards and wires added to it continue the `c<n>` and `w<n>` numbering past
any such id the plan already has. A card id in a response names a card listed in the same draft (its name
is the card's `name` in `plans/get` and `plans/add-cards`).

Every change validates the whole request first: a refused request leaves the draft as it was.

| endpoint              | request                                                              | response                                              | built |
| --------------------- | -------------------------------------------------------------------- | ----------------------------------------------------- | ----- |
| `plans/create`        | `{name, constraints?, cardTier?, minTier?, player?}`                 | `{dataset, planId, name, expiresAt, constraints?, cardTier?, minTier?}` | yes |
| `plans/add-cards`     | `{planId, cards: [{recipeId, machine?, tier?, dials?}], player?}`    | `{planId, cards, wires, cardCount}`                   | yes   |
| `plans/remove-cards`  | `{planId, cardIds, player?}`                                         | `{planId, removed, wiresRemoved, cardCount, targets}` | yes   |
| `plans/set-targets`   | `{planId, targets: [{resource, rate}], player?}`                     | `{planId, targets}`                                   | yes   |
| `plans/connect`       | `{planId, from: {cardId, resource}, to: {cardId, resource?}, player?}` | `{planId, wire, created}`                           | yes   |
| `plans/disconnect`    | `{planId, wireId, player?}`                                          | `{planId, removed}`                                   | yes   |
| `plans/get`           | `{planId, fields?, cardIds?, player?}`                               | the draft's cards, wires and targets, compact         | yes   |
| `plans/solve`         | `{planId, fields?, player?}`                                         | summary first, then one row per card                  | yes   |
| `plans/import`        | `{snapshot, player?}`: the player's open plan as a slim snapshot (body up to 512 KB) | `{dataset, planId, name, expiresAt, cardCount, wireCount, warnings}` | yes |
| `plans/expand-inputs` | `{planId, resources?, constraints?, max?}` (max 3)                   | for each unsupplied input, up to `max` recipes ranked by trying each in the solver, each with a one-line reason (EU per item, machines needed, new raws it introduces) | yes |
| `plans/diff`          | `{planId, player?}` of an imported draft                             | `{proposalId, basePlanId, expiresAt, summary, text}`: what the draft adds to the imported plan, stored as a proposal | yes |
| `plans/deliver`       | `{planId, player: {name, uuid}}`                                     | `{name, id, code, url?, link?, expiresAt, cardCount}`  | yes   |

Examples, one real call each against the pack dataset (a titanium line): `docs/agent-api/examples/plans-*.json`.

`plans/create`'s `cardTier`: the tier the draft's cards default to, a tier name at most `constraints.tierCap` (above it
is `bad_request`). Default: `tierCap`; with neither, cards take the lowest tier that runs them. The response always
says the card tier the draft applies (the one given, else `tierCap`; absent only with neither). The draft keeps it;
`plans/get` returns it.

`plans/create`'s `minTier` (optional): no card runs below this tier. At most the card tier (above it is `bad_request`).
A card whose default tier would be lower takes the lowest tier its machine is built at from `minTier` up, within
`tierCap` (a machine built at none of those is `bad_request`); a card asked for at a lower `tier` is `bad_request`.
Steam and primitive machines, machines that draw no EU on the recipe and recipes with no voltage tier keep their own
tier. The draft keeps it; `plans/create` and `plans/get` return it, and `plans/expand-inputs` tries candidates at it.

### A card

What `plans/add-cards` sends and what the draft endpoints return for a card:

```
request:  {recipeId, machine?, tier?, dials?: {<dialId>: <option key or number>}}
response: {cardId, recipeId, name, machine: {id, name}, tier, dials?, heat?}
```

- `machine`: a machine that runs the recipe, by id or name (without case), as `recipes/get` lists them. Default: the
  recipe's first multiblock that runs it at or below the draft's `tierCap`, else its first single block that does
  (else its first machine that does), leaving out steam and primitive machines unless the draft's `steam` allows them
  (or its `tierCap` is `ULV`). A machine banned by the draft's constraints is never chosen, and asking for one is
  `bad_request`. Naming a steam or primitive machine is allowed: the player asked for it. A recipe only steam or
  primitive machines run, with no machine named and steam not allowed, is `bad_request` saying so.
- `tier`: the tier the card runs at. Default: the highest tier the machine is built at that is at or below the draft's
  `cardTier` (or `tierCap` when it has none), and never below the recipe's minimum: a recipe whose minimum is above
  `cardTier` but within `tierCap` runs at its minimum. With neither set, and for a machine that draws no EU on the
  recipe (a cauldron), the lowest it runs at. The solve overclocks the card at that tier by the site's own machine
  math; a multiblock gets an energy hatch of that tier when the plan is opened, with the same numbers. Below the
  machine's minimum for the recipe is `tier_too_low`; above the draft's `tierCap`, or a tier the machine is not built
  at, is `bad_request`. A recipe nothing runs at or below `tierCap` is `tier_too_low`. A recipe whose tier is not a
  voltage tier (crafting, `NONE`) keeps its own and is never capped. A steam or primitive machine keeps the tier the
  dataset gives it (it does not overclock); asking for another is `bad_request`.
- `dials`: a nested object, never spread into the card. Keys are the dial ids `recipes/get` lists for that machine
  (the coil is `heatingCoil`); values are an option `key` or, for a numeric dial, a number in its range. An unknown
  dial id, an unknown option, a number out of range or an option below the recipe's minimum (a coil too cold for the
  recipe) is `bad_request` naming the dial. Dials not given keep the machine's defaults (the coil the recipe needs).
- `heat`: the heat in K the recipe needs, on a heat machine.
- A card's machine count is not set: the solver chooses it. A multiblock card gets the hatch the planner gives a new
  card, at the card's tier: the supply its full parallels need, in whole amps (`hatchVoltageTier`, `hatchAmps` in the
  plan). The amps count as regular hatches, so they raise the parallel tier of a "parallels per tier" machine as on the
  board. A card starts with no other settings (no other power choices); they are the planner's defaults.
- Errors name the card: `cards[2]: ...`. An unknown recipe is `unknown_recipe`.

### Wires

- **Automatic, by resource**, when cards are added: the board's own Auto-connect rule, for each new card against
  every card in the draft, both ways. Every output slot of one card is wired to every input slot of another that uses
  it up and takes it (same kind, same id, or a member of an "any of" slot, which the wire then sets to that resource;
  catalysts, molds and programmed circuits are never wired). One wire per pair of slot rows. So when two cards make the
  same thing, both are wired to its takers; the solver shares the flow.
- `plans/connect` adds one wire from a card's output of `from.resource` to the other card's input that uses it up and
  takes it; `to.resource` picks the input slot when several could (an "any of" slot by a member). A wire that already
  exists is returned with `created: false`. The response's `wire` carries `manual: true`.
- `plans/disconnect` removes a wire by id. Automatic wiring never makes that wire (same two cards, same resource) again;
  `plans/connect` can.
- A wire: `{wireId, from, to, id, kind, name, manual?}`: card ids and the resource it carries.

### Targets

- `plans/set-targets` replaces the whole target list (an empty list clears it). `rate` is per second, above 0, at most
  16 targets, each resource once.
- Each target sits on the card that makes it: the first card whose main (first) output it is, else the first card
  that makes it. It is plan mode's own target, a rate pinned on that card's output, so the solver sizes everything
  else around it. A target no card makes is `no_recipe`. A target whose card is removed moves to the next card that
  makes it, or stays without one (`cardId` absent; the solve warns).
- A target: `{id, kind, name, rate, cardId?}`.

### Positions

Cards are laid out left to right after every change: a card's column is the longest chain of wires feeding it (wires
that close a loop are not counted), cards stack in card order within a column. The board's own Arrange is pure and
could run here, but it takes about 0.3 s for 10 cards, 2.3 s for 30 and 11.6 s for 60 on the build box; the player can
press Arrange on the delivered plan.

### plans/get

`{dataset, planId, name, expiresAt, constraints?, cardTier?, minTier?, cardCount, wireCount, cards?, wires?, targets?, truncated?}`.

- `fields`: any of `cards`, `wires`, `targets` (default all three).
- `cardIds`: only those cards and the wires that touch them.
- Over the 12 KB aim the answer drops wires from its end, then cards, and says `truncated: true`; `cardCount` and
  `wireCount` are always the whole draft's. A 60-card draft of pack recipes is about 15 KB of cards and 7 KB of
  wires: ask for `fields: ["wires"]` and pages of `cardIds`.

### plans/solve

The draft solved by the site's plan solver on the compiled core, as the site solves a saved plan. About 0.4 s for the
first solve of a 60-card draft after the server starts, 20 to 50 ms after; a 60-card answer is under 12 KB.

```
{dataset, planId, solved, targetsMet,
 targets:    [{id, kind, name, rate, made, met, cardId?}],
 unsupplied: [{id, kind, name, perSecond, free?}],
 byproducts: [{id, kind, name, perSecond}],
 euT, warnings: [string],
 cardColumns: ["cardId", "recipe", "machine", "tier", "count", "in", "out", "dials"],
 cards: [[cardId, recipe, machine, tier, count, {<resource name>: perSecond}, {<resource name>: perSecond}, dials?]],
 truncated?}
```

- `solved`: the solver found an answer. `targetsMet`: there is at least one target and every target's card makes at
  least its rate.
- `unsupplied`: what the draft still needs from outside, per second, largest first: every input no card supplies (an
  unwired input slot) and anything forced in at a wired one. `free: true` marks resources the draft's constraints list
  as `free` (raw on purpose).
- `byproducts`: what the draft makes that nothing takes and is not a target, per second, largest first.
- `euT`: the draft's total power draw at the solved counts. Steam machines draw no EU.
- `warnings`: plain sentences, at most 12: no target set, a target with no card, a target not met, the board's own
  notices (rates that cannot be met, loops that starve or clog, with the card ids), the steam the draft's steam
  machines burn, and cards no target needs (they run at 0).
- Steam: the site's solver does not carry steam as a flow, so it is in neither `unsupplied` nor `euT`. When a card
  burns steam, one warning gives the total, by the site's card math (litres per machine times the solved count), and
  the card ids: "Steam machines burn 32.81 L/s of steam, which is not in unsupplied or euT: c1."
- `cards`: one row per card, in card order, laid out as `cardColumns` says: the card's id, its recipe's name, its
  machine's name, its tier, the machine count the solve needs (fractional), what it takes and makes per second (by
  resource name; a name used by both an item and a fluid on one card gets ` (item)` or ` (fluid)`), and its `dials`
  when any are set. Rates are at the solved count; amounts round to 4 significant figures.
- `fields`: any of `targets`, `unsupplied`, `byproducts`, `power` (`euT`), `warnings`, `cards`; `dataset`, `planId`,
  `solved` and `targetsMet` are always there. An answer over 12 KB drops card rows from its end and says
  `truncated: true`.
- The solve reads no dataset and no account: the draft's recipes were copied into it when the cards were added.

### plans/expand-inputs

What could make each input the draft still needs, ranked by trying each recipe in the draft. This is where the
assistant chooses the next cards, so every candidate says what it costs and what it brings in.

Request: `{planId, resources?, constraints?, max?, player?}`

- `resources`: only these inputs, in this order (up to 16). Default: every unsupplied input of the draft's solve,
  largest rate first. A named resource the draft does not need from outside is answered with no candidates and a
  `note`.
- `constraints`: joined with the draft's for this call only: the lower `tierCap` and `heat`, `banned` and `free`
  lists together, and the call's `steam` when it gives one, else the draft's. The draft keeps its own. A steam
  candidate a call allows that the draft does not is added with `plans/add-cards` naming its `machine`.
- `max`: candidates per input, 1 to 3, default 3.
- A draft with no target needs nothing, so it is `bad_request`.

Response:

```
{dataset, planId,
 inputs: [{id, kind, name, perSecond, raw?, found, skipped?, steamOnly?, note?,
           candidates: [{recipeId, name, machine, tier, euT, seconds, inputs, outputs,
                         reason, newRaws: [{id, kind, name, perSecond}], usesByproducts?,
                         spares?: [{id, kind, name, perSecond}], machines, euPerItem, steamPerSecond?, works?}]}],
 accepted: [{id, kind, name, perSecond}],
 more, remaining?: [{id, kind, name}], notEnforced?, truncated?}
```

- `accepted`: unsupplied inputs the constraints mark `free` (matched by id or name, without case). They are raw on
  purpose and never expanded.
- `raw: true`: the input is itself raw to a player (see Rawness below); fine to leave unsupplied or add to `free`.
- `found`: recipes the search found that make it, before constraints. `skipped`: recipes left out (below);
  `steamOnly`: how many of them only steam or primitive machines run (constraints `steam` allows them).
- A candidate: the recipe in the compact form of `recipes/search` (`euT` and `seconds` are the recipe's own), the
  `machine` and `tier` `plans/add-cards` would give it under the joined constraints and the draft's `cardTier` (lowered
  to the joined `tierCap`), and what the trial measured: `machines` the solve needs of it, `euPerItem` (the draft's
  added EU per unit of the input it supplies, per L for a fluid; a steam machine's steam counts at 2 L per EU, GregTech's
  rate, so steam and electric candidates compare), `steamPerSecond` (litres of steam a steam machine burns at the
  solved count, by the site's card math), `newRaws` (inputs the draft did not need before and would need from outside with it, per second, largest
  first), `usesByproducts` (inputs it takes that the draft already makes and nothing uses: a good sign), `spares`
  (outputs that would leave the draft unused with it added and did not before, per second, largest first; absent when
  none and on a candidate that does not work).
  `works: false` marks a candidate the solve could not use (it fails, or supplies none of the input); it is ranked
  last.
- `reason`: one plain sentence or a few: rate, machines, tier and EU per unit; the steam it burns ("Burns 37.5 L/s of
  steam (counted at 2 L per EU)."); each new input with its rawness; the
  draft's spare byproducts it uses; spare byproducts it leaves; other unsupplied inputs it also supplies; how much of
  the need it covers when not all; and "recycling" when it gives back material made elsewhere.
- Adding a candidate: `plans/add-cards` with its `recipeId`. Auto-connect wires it as the trial did.

Where candidates come from: the `recipes/search` `makes` search (its ranking: main product before byproduct,
producing before giving back), the first 12, filtered by the joined constraints as search filters them (`tierCap`,
`heat`, `banned`, `steam`; `cleanroom` and `lowgrav` are listed in `notEnforced` when given). Skipped before trying, and
counted in `skipped`:

- a recipe that takes what it makes (a loop);
- a recipe that takes the same stuff in another container or state ("Chlorine Cell" or "Chlorine Plasma" for
  Chlorine): a canner or freezer, not a source;
- a recipe that undoes the draft's work: it takes a main product the draft makes from the input, or the same material
  in a form that is not ore (macerating the ingot back to the dust the furnace card smelts; titanium dust to make the
  titanium tetrachloride a titanium furnace takes). One that takes only a byproduct is kept: it is a use for it;
- a recipe already a card in the draft, and the same reaction on another machine at the same power and time
  (Chemical Reactor and Large Chemical Reactor), tried once;
- a recipe only steam or primitive machines run, unless the joined constraints allow steam (also counted in
  `steamOnly`);
- a recipe `plans/add-cards` would refuse (nothing runs it under the tier cap).

Trying a candidate: the card is added to a copy of the draft, wired by Auto-connect, targets re-seated, and the copy
is solved on the site's plan solver. The draft itself is never changed. At most 6 candidates are tried per input and
36 per call.

Ranking, lowest first, compared in order:

1. candidates that work before ones that do not;
2. fewer new inputs that are made (they need recipes of their own, crafting tools too);
3. fewer new inputs that are nearly raw (a step of ore processing of a material with an ore block, a plain dust of a
   material with no ore block);
4. fewer new inputs that are raw;
5. less added to the draft's leftovers (below);
6. less EU per unit;
7. fewer machines;
8. the search's own order.

Leftovers are counted as the plan solver trades them, in runs: every output that leaves the solved draft unused (not
a target), per second, over what one run of the recipe that makes it puts out, so 2000 L of a fluid a run and one dust
a run weigh the same. Step 5 compares the copy's total with the draft's. A candidate that takes the draft's spare
byproducts lowers it; one whose byproduct the draft takes (Auto-connect wires it to the card that needs it) adds
nothing for it; one that leaves a byproduct nothing takes adds that byproduct's runs. Like the solver, it settles what
comes in (steps 2 to 4) before what is left over, and both before power. A difference below a millionth of the
draft's total is no difference.

Rawness of a resource, by name and the dataset's ore blocks:

- raw: water, air and its gases (oxygen, nitrogen, argon, helium, neon, carbon dioxide), steam, lava, stone, sand,
  gravel, dirt, clay, ice, netherrack, end stone, logs and wood; an ore block in any stone, or a raw ore; a dust of a
  material that has an ore block ("Rutile Dust": Rutile Ore); an item mined under its own name ("Salt": Salt Ore); a
  `free` resource;
- nearly raw: crushed, purified, centrifuged or refined ore, impure or purified piles, shards, of a material with an
  ore block; a dust of a material with none;
- made: everything else.

Paging and limits: at most 6 inputs a call; inputs past them, past the trial budget, or past 3.5 s are listed in
`remaining` with `more: true`; ask again with `resources` set to them. An answer over 12 KB moves whole inputs from its
end to `remaining`. On the build box with the pack dataset: a draft with 9 unsupplied inputs answers in two pages of
5 and 4 inputs, 11.4 KB and 10.1 KB; the first call after a start takes about 2 s a page, then 0.2 to 0.9 s.

Known limits, honestly:

- The dataset has no world generation. GregTech makes ore blocks for materials that never spawn, so "Titanium Dust"
  and "Magnesium Dust" read as raw (Titanium Ore and Magnesium Ore exist as blocks) whether or not the pack spawns
  them. An assistant that knows the pack's veins should judge `raw` itself.
- Leftovers rank by their amount in runs, not by worth: a valuable byproduct and a worthless one weigh the same, and a
  candidate that leaves any spare ranks after one that leaves none however much less power it uses.
- Supplying another of the draft's unsupplied inputs (`Also supplies` in `reason`) does not change the rank by itself.
- Only the search's first 12 recipes are looked at, after its own ranking; a good recipe further down its list is not
  tried.

Example: `docs/agent-api/examples/plans-expand-inputs.json` (the titanium tetrachloride furnace line, first call).

### plans/deliver

Stores the draft as a NEW plan the player opens, under a short random id, and returns the id and the plan code. It never
writes to an existing design or library entry and is not a community post.

Request: `{planId, player: {name, uuid}}` (both required: the player it is for, kept with the entry for audit and never
served).

Response: `{name, id, code, url?, link?, expiresAt, cardCount}`.

- `id`: `d` and 15 characters (see the read route). The player opens it in game with `/gtnhplanner open <id>`; the
  site and the game read it from `GET /api/deliveries/{id}`. Kept 30 days.
- `code`: the plan code the site's own Copy plan code makes (`gtnh1.` and the plan, deflated, URL-safe base64); paste it
  into the planner's Paste a copied plan. It runs to thousands of characters: the id is what travels in chat.
- `url`: the site's link that opens the code (`<site>/#p=<code>`), only when the server knows its address
  (`NEXT_PUBLIC_SITE_URL`). Nothing is published to open it. Kilobytes long.
- `link`: the short form, `<site>/#d=<id>`, under the same condition. The site reads the delivery through
  `GET /api/deliveries/{id}` and opens it as a NEW plan, as it does a `#p=` code; the id is checked against
  `^d[a-hjkmnp-z2-9]{15}$` first (a proposal id is not opened this way). An unknown or expired id shows a short
  message, and the address is cleared so a reload does not try again.
- The stored plan is the draft as the site's own code reads it back: plan mode, a new plan id, the draft's name, the
  full dataset recipe bodies of every card. Opened, it lands as a new design like any pasted plan.
- At most 100 deliveries a day (UTC), counted in the database: `rate_limited`. A draft with no cards is `bad_request`.
- A card an import kept without its recipe (see `plans/import`) is delivered as it is, without a recipe body.

Example: `docs/agent-api/examples/plans-deliver.json`.

### plans/import

Makes a draft from the player's open plan, so the assistant can solve it, add to it and propose the additions
(`plans/diff`). The plan arrives as a slim snapshot (below), not the planner's export: recipe bodies are rebuilt from
the dataset by id.

Request: `{snapshot, player?}`, body up to 512 KB (this route only).

Response: `{dataset, planId, name, expiresAt, cardCount, wireCount, warnings}`.

- The draft keeps the snapshot's ids, positions, boards, drawers, wires and pins, and is not laid out again until a
  card is added. It counts toward the 300 drafts a day and lives 24 hours like any draft.
- Up to 200 cards (`plan_too_big` beyond), and add-cards may grow it to 200.
- A card whose recipe id (or a shared section's) the dataset does not have, but which carries that recipe's content
  (`card.recipe`, `card.sectionRecipes`: what the game's panel sends for a card the player built in game), is matched
  to the dataset recipe with the same content, by the rule the site uses to open a plan the game exported
  (`pickRecipeRefMatch`, `src/lib/import-export/recipe-ref-match.ts`): candidates are the dataset recipes that make its
  first output (in its `recipeMap` when the dataset has that map), then, when none fits, those with its `rawRecipeId`;
  one must take and make the same resources (score 300 or more), and the best
  one must be the only one with its score. A matched card is a normal card on the dataset recipe: it keeps the
  snapshot's card id, wires, board, tier, dials and count; its `recipeId` in the draft (and in `plans/get`,
  `plans/solve` and the draft's own snapshot) is the dataset's id, and its `machine` is kept only when the dataset
  recipe lists that machine (else the recipe's default). It solves, wires automatically and may be wired to; a
  proposal still names it by its card id. A match whose amounts, time or EU differ from the content is taken with a
  warning (`... is matched to the dataset recipe that takes and makes the same things, but its amounts, time or EU
  differ.`).
- A card whose recipe the dataset does not have and that no content matches (no content sent, no recipe with the same
  resources, or two or more equally good) is kept as an opaque card: it stays in the draft as it was, nothing wires to
  it automatically, the solve reads no recipe for it, and it must not be changed or removed (a diff that finds it
  changed refuses). Each one is one line in `warnings`, at most 20 lines, naming the card and why: `Card <id>: recipe
  <recipeId> is not in dataset <dataset> and the card carries no recipe content` / `... and no dataset recipe takes and
  makes the same things` / `... and its content fits <n> dataset recipes equally well`, then `; the card is kept as it
  is and cannot be changed.`
- Targets: each resource's first pin on a card's own main output slot (`output:<kind>:<id>`, no slot number or section)
  becomes one of the draft's targets, carried by that same card; `plans/get` and `plans/solve` show it. Every other pin
  (input pins, slot or section pins, a second pin of the same resource) stays on its card exactly as it was.
- A snapshot whose wire, target or board names something that is not in it, or that uses an id twice, is `bad_request`.

Example: `docs/agent-api/examples/plans-import.json`.

### The slim snapshot

What the planner (the site, or the game's panel) sends for its open plan. The website makes it with
`toSlimSnapshot(project)` in `src/lib/agent-api/slim.ts`; the game builds the same shape field for field. Version 1:

```
{
  version: 1,
  plan:    {id, name},                       the open plan's id: a proposal made from it names it as basePlanId
  boards?: [{id, name, parent?, position}],  the plan's boards (pockets); absent when it has none
  cards:   [{id, recipeId, machine?, tier, dials?, count?, exact?, sections?, recipe?, sectionRecipes?, position, board?}],
  drawers: [{id, resource, mode?, position, board?}],
  wires:   [{id, from, to, resource, fromPort?, toPort?}],
  targets: [{card, port, rate}]
}
position = {x, y}            resource = {id, kind}   kind: item | fluid | aspect | power
content  = {name?, machineType, recipeMap?, rawRecipeId?,
            inputs: [{kind, id, amount, consumed?: false}], outputs: [{kind, id, amount}] (at least one),
            durationTicks, eut}
```

| field                | meaning (the plan document's field)                                                       |
| -------------------- | ----------------------------------------------------------------------------------------- |
| `card.id`            | the card's id (`node.id`); ids match `^[A-Za-z0-9_][A-Za-z0-9_.:-]{0,99}$`                 |
| `card.recipeId`      | its recipe (`recipeId`), looked up in the dataset on import                               |
| `card.machine`       | the machine handler id (`machineHandlerId`), only when it is not the recipe's default     |
| `card.tier`          | the tier it runs at (`overclockTier`), as a string                                         |
| `card.dials`         | `heatingCoil` is the coil (`coilTier`); every other key is a tier part (`machineConfigTiers`); absent when none |
| `card.count`         | the pinned machine count (`solvePin`); absent: the solver chooses it                      |
| `card.exact`         | `true` when that count is Exactly (`solvePinExact`); only with `count`                    |
| `card.sections`      | a shared machine's further recipe ids in order (`extraRecipes[].recipeId`)                |
| `card.recipe`        | what `recipeId` does (a `content`), for a recipe id that may not be a dataset id; read only when the dataset lacks the id |
| `card.sectionRecipes` | the `content` of each of `sections`, one per section in order; read only for ids the dataset lacks |
| `content`            | the recipe body's fields of the same names (`source.recipeMap`, `source.rawRecipeId`): what the content match reads; `name` only breaks ties; `consumed: false` marks an input that is not used up (the match ignores those) |
| `card.board`         | the board it sits on (`pocketId`); absent is the root board                               |
| `drawer.mode`        | `byproduct` or `trash` (`drainMode`); absent is a product drain                           |
| `wire.from`, `wire.to` | the card or drawer ids at each end (`source`, `target`)                                 |
| `wire.fromPort`, `wire.toPort` | the slot handles as stored (`sourceHandle`, `targetHandle`), when the plan has them |
| `target`             | one pinned rate (`portRates[port]` on card `card`), per second, above 0                    |
| `board.parent`       | the board it sits in (`parentPocketId`)                                                   |

- Order: cards, drawers, wires and boards in the plan's order; targets in card order, each card's ports sorted by code
  unit. Optional fields are left out, never `null`. Unknown fields are refused.
- Not carried (the planner's defaults on import): oredict picks, power, hatch and parallel settings, custom rate cards'
  dials, colours, notes, annotations, tasks. A plan's snapshot is a few hundred bytes per card; the game's panel
  refuses one over 256 KiB and says the plan is too big.
- The site writes no `recipe` content: its cards are on dataset recipes. The game writes it for every card it built
  (recipe id `gtnhplanner:<card id>`), except power, tank, custom rate and empty crop farm cards, which no dataset
  recipe stands for; a card it shows from a site plan carries its dataset id and no content.
- Round trip: importing a snapshot and taking the snapshot of the draft gives the same snapshot (with the draft's own
  plan id), as `handoff.test.ts` checks, except that a card matched by content comes back with the dataset's recipe
  id, without `recipe` content, and without a `machine` the dataset recipe does not list.

### plans/diff and proposals

What an imported draft now has that the imported plan did not, stored as a proposal the player applies in game with
`/gtnhplanner apply <id>`, as one undoable step that only adds.

Request: `{planId, player?}`. Response: `{proposalId, basePlanId, expiresAt, summary, text}`.

- `summary`: `{addedCards, addedWires, addedDrawers, changedTargets}` (`changedTargets`: pins that are new or whose
  rate changed). `text`: one plain sentence for the player ("Adds 1 card (Bending Machine: Titanium Foil c2), 1 wire.").
- **Add-only.** If the draft removed or changed anything the snapshot had (a card's recipe, machine, tier, dials, count
  or board; a drawer; a wire; a board; a pinned rate that is gone), the answer is `bad_request` naming what, and no
  proposal is stored: tell the player the change in words instead. Positions do not count: the draft lays cards out
  again after each change, and the proposal never moves the player's cards.
- A draft that was not made by `plans/import` is `bad_request`; so is one that adds nothing.
- At most 300 proposals a day (UTC): `rate_limited`. Kept 30 days.

The stored proposal (served by the read route as `proposal`):

```
{version: 1, basePlanId, summary,
 recipes: [Recipe],          full dataset bodies of the recipes the added cards use
 cards:   [FactoryNode],     the added cards, in the plan document's own format, ids c<n> new to the plan
 drawers: [FactoryStorage],  the added drawers
 wires:   [FactoryEdge],     the added wires; an end on a card or drawer the plan already had (a card matched by content too) names its own id
 targets: [{card, port, rate}]}  pins to set: new ones and changed rates, on new or existing cards
```

- Added cards are placed to the right of the player's plan (600 to the right of its rightmost card or drawer, top
  aligned with its highest), keeping the draft's layout among themselves.
- Apply rule for the game: refuse unless the open plan's id is `basePlanId`; add the recipes it lacks, then the cards,
  drawers and wires, then set the pins; never remove or change anything else.

Example: `docs/agent-api/examples/plans-diff.json`.

## The read route: GET /api/deliveries/{id}

The one route outside `/api/agent/`: the in-game planner and the site read a delivered plan or a proposal by id. It
sits beside the routes the mod already calls (`/api/library`, `/api/community`, `/api/feedback`) and is reachable
wherever they are; its path goes on the proxy's list of public paths. It is read-only, needs no account and no agent
token, and answers only the one stored entry the id names. It serves stored entries whether or not the agent API is on.

- `id`: a `d` (delivery) or `p` (proposal) followed by 15 characters from `abcdefghjkmnpqrstuvwxyz23456789`
  (regex `^[dp][a-hjkmnp-z2-9]{15}$`, from a cryptographic random source): about 74 bits, not guessable. Anything else
  answers 404 without a lookup.
- `200`, delivery: `{id, kind: "delivery", name, createdAt, expiresAt, plan}`, `plan` the plan document as the planner
  exports it (open it as a NEW plan, never over the open one).
- `200`, proposal: `{id, kind: "proposal", name, createdAt, expiresAt, basePlanId, proposal}`, `proposal` as above.
- Never the player's name or uuid. `Cache-Control: no-store` on every answer.
- `404` `{error: "Plan not found."}` for an unknown, expired (30 days after it was made) or malformed id. `429`
  `{error}` past 60 reads a minute from one address. `503` `{error}` when the database cannot be read. Only `GET`.

Examples: `docs/agent-api/examples/deliveries/delivery.json` and `deliveries/proposal.json` (checked by
`handoff.test.ts`).

Storage: table `agent_deliveries` (`supabase/migrations/2026-11-21-agent-deliveries.sql`): id, kind, name, payload
(jsonb), base_plan_id, player_name, player_uuid, created_at, expires_at. Create-only; no route updates or deletes a
row. Expired rows may be deleted by a later sweep.
