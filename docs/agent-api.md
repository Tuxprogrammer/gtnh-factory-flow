# Agent API (v1)

An HTTP API that lets an assistant look up recipes in the site's dataset and design plans for a player with the site's
own solver. It is meant for a tool server on the instance's internal network that maps its tools one to one to these
endpoints. Players never call it.

Status: `resources/search`, `recipes/search` and `recipes/get` are built. Every other endpoint below is specified and
marked **not yet built**; until it is, its path answers 404.

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
- Request bodies are at most 64 KB. Unknown fields are refused (`bad_request`), so a misspelt option fails loudly.
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
| `no_recipe`           | 422  | nothing makes the resource under the constraints (plans, not yet built)    |
| `tier_too_low`        | 422  | a card's tier is below its recipe's minimum (plans, not yet built)         |
| `plan_not_found`      | 404  | unknown or expired draft id (plans, not yet built)                         |
| `plan_too_big`        | 413  | a draft would go over 60 cards (plans, not yet built)                      |
| `rate_limited`        | 429  | a daily limit is spent (plans, not yet built)                              |
| `dataset_unavailable` | 503  | the server has no dataset installed or cannot read it                      |
| `internal`            | 500  | anything else; the server log has the detail                               |

## Constraints

Accepted by `recipes/search` now, and by `plans/expand-inputs` and `plans/solve` once built:

```json
{ "tierCap": "HV", "heat": 4500, "cleanroom": false, "lowgrav": false, "banned": ["..."], "free": ["..."] }
```

What `recipes/search` does with each:

| field       | effect in search                                                                                       |
| ----------- | ------------------------------------------------------------------------------------------------------ |
| `tierCap`   | **filters**: recipes whose minimum tier is above the cap are left out                                  |
| `heat`      | **filters**: a recipe on a heat machine (Electric Blast Furnace and kin) that needs more heat in K is left out |
| `banned`    | **filters**: ids or names, matched without case against the recipe id, its recipe map, and each machine's id, name and type. A recipe is left out when the recipe or its map is banned, or every machine that runs it is; otherwise the first machine that is not banned is the one shown |
| `cleanroom` | **not enforced**: the dataset carries no cleanroom flag on any recipe, so nothing can be filtered on it |
| `lowgrav`   | **not enforced**: the dataset carries no low-gravity flag on any recipe                                 |
| `free`      | **not enforced** by search: it means "treat as raw" to expand and solve                                |

A search given any field it does not enforce lists those fields in `notEnforced`, so the caller knows the results were
not narrowed by them.

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

Response: `{dataset, results, total, offset, more, truncated?, notEnforced?}`. `total` counts every match before
constraints; `more` says another page may hold results.

A compact recipe:

```
{recipeId, name, machine, minTier, euT, seconds, heat?, circuit?,
 inputs: [{id, kind, name, amount, consumed, alternatives?, moreAlternatives?}],
 outputs: [{id, kind, name, amount, chance?}],
 map?, machines?: [{id, name, kind}]}            (map and machines with detail only)
```

- `machine` is the machine shown for the recipe: its default one, or the first not banned.
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

## plans/* (drafts) - not yet built

A draft is a plan held by the server for the assistant to build: in the server's memory, keyed by a random id, kept 24
hours after its last change, at most 60 cards. A restart drops drafts. At most 300 drafts a day. Nothing here edits or
deletes a player's design or library entry, publishes to the community hub, or touches accounts.

| endpoint              | request                                                         | response                                                         |
| --------------------- | --------------------------------------------------------------- | ---------------------------------------------------------------- |
| `plans/create`        | `{name, player?, constraints?}`                                 | `{planId}`                                                       |
| `plans/import`        | `{snapshot, player?}`: the player's open plan, as the planner exports it | `{planId}`: a draft holding a copy of it                 |
| `plans/add-cards`     | `{planId, cards: [{recipeId, machine?, tier?, ...dials}]}`      | `{cardIds}`; wires are made by resource automatically            |
| `plans/remove-cards`  | `{planId, cardIds}`                                             | `{removed}`                                                      |
| `plans/connect`       | `{planId, from: {cardId, resource}, to: {cardId, resource}}`    | `{wireId}`: overrides the automatic wiring                       |
| `plans/disconnect`    | `{planId, wireId}`                                              | `{removed}`                                                      |
| `plans/set-targets`   | `{planId, targets: [{resource, rate}]}`                         | `{targets}`                                                      |
| `plans/get`           | `{planId}`                                                      | the draft's cards, wires and targets, compact                    |
| `plans/solve`         | `{planId, fields?}`                                             | summary first (unsupplied raws, byproducts with nowhere to go, total EU/t, warnings), then one line per card (machine, tier, count, rates) |
| `plans/expand-inputs` | `{planId, resources?, constraints?, max?}` (max 3)              | for each unsupplied input, up to `max` recipes ranked by the solver, each with a one-line reason (EU per item, machines needed, new raws it introduces) |
| `plans/diff`          | `{planId}` of an imported draft                                 | `{proposalId, summary}`: what the draft adds or changes relative to the imported snapshot (cards, wires, targets), stored like a delivery |
| `plans/deliver`       | `{planId, player: {name, uuid}}`                                | `{name, id, code, url?}`                                         |

`plans/deliver` stores the finished plan durably under a short random `id` (kept 30 days; at most 100 deliveries a
day) and returns it with the plan code. It creates a NEW plan; it never writes to an existing design and is not a
community post. The player opens it in game with `/gtnhplanner open <id>`, or by the plan code.

`plans/diff` stores a proposal the same way. The player applies it in game with `/gtnhplanner apply <id>`, as one
undoable step that only adds.

## Mod read route: GET /api/deliveries/{id} - not yet built

The one route outside `/api/agent/`: the in-game planner reads a delivered plan or a proposal by id. It sits beside
the routes the mod already calls (`/api/library`, `/api/community`, `/api/feedback`), behind the same guard as they
are, and is reachable wherever the mod's other paths are. It is read-only, needs no account and no agent token, and
answers only the one stored entry the id names.

- `id`: a `d` (delivery) or `p` (proposal) followed by 15 characters from `abcdefghjkmnpqrstuvwxyz23456789`
  (regex `^[dp][a-hjkmnp-z2-9]{15}$`): about 74 bits, not guessable. Anything else answers 404 without a lookup.
- `200`: `{id, kind: "delivery" | "proposal", name, createdAt, expiresAt, plan}` for a delivery, where `plan` is the
  plan document as the planner exports it; a proposal also carries `basePlanId` (the imported plan it was made from)
  and `summary`, and `plan` holds only what it adds.
- `404` `{error: "Plan not found."}` for an unknown or expired id. Only `GET` is served.
