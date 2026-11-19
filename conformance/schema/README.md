# conformance/schema/

One description of the data that crosses between the website and the mod, as JSON Schema (draft 2020-12), checked in so
either side can read it without the other's toolchain.

| File | What it describes | Zod source |
|---|---|---|
| `plan.schema.json` | the plan (project) file: what the website exports, what the mod's two writers make | `src/lib/model/schemas.ts` |
| `board-selection.schema.json` | a blueprint payload (a selection of cards copied from a board) | `src/lib/model/schemas.ts` |
| `dataset.schema.json`, `dataset-manifest.schema.json` | the recipe dataset records and its manifest | `src/lib/datasets/schemas.ts` |
| `solve-input.schema.json` | the solver's input (`SolveInput`) as the corpus holds it | `src/lib/solver/plan/schemas.ts` |
| `solve-digest.schema.json` | the solver's answer as both engines digest it (`{solution, alternatives}`) | `src/lib/solver/plan/schemas.ts` |

## Direction: Zod is the source, JSON Schema is generated

The Zod schemas already are the plan format. The website's importers, the cloud routes and the live service run on them,
and the type of every plan object is `z.infer` of one. Writing a neutral schema first would make the Zod schemas a copy
of it, and a copy has to be kept in step with a test. Generating the other way needs no such test beyond "the file is what
the generator writes". Zod 4's `z.toJSONSchema` does it (`io: "input"`, because the `parallel` transform has no output
form; `unrepresentable: "throw"`, so a new Zod construct it cannot state stops the build instead of vanishing).

`tools/conformance/schema/build.ts` lists the files and their roots; `write.ts` writes them and then the manifest.

```
npx tsx tools/conformance/schema/write.ts            # rewrite conformance/schema/*.schema.json and manifest.json
npx tsx tools/conformance/schema/write.ts --check    # exit 1 and name the files that differ
```

Existing Zod schemas are unchanged. The only new file under `src/lib` is `src/lib/solver/plan/schemas.ts`, which states
the solver's input and digest shapes (there was no schema for them) and fails the type check when `SolveInput` and its
schema disagree.

## What Zod cannot say, and what is done about it

`.refine` and the `parallel` transform exist only as code, so the generator drops them. Seven places have one
(`REFINEMENTS` in `tools/conformance/schema/overlays.ts`): `FactoryNode.parallel`, `FactoryNode.portRates` (at most 256),
`FuelProfile` (`euPerLiter` or `euPerBucket`), `GtnhplannerData` (at most 64 KB, depth 63), `Recipe.metadata` (depth 63),
`FactoryProject.spareShares` (at most 1024 entries, 64 bearers each) and `FactoryProject.settingPins` (at most 512).
Two mechanisms keep this honest:

- Overlays (`OVERLAYS`, applied after generation) add what JSON Schema can state: `maxProperties` and
  `anyOf: [{required: [euPerLiter]}, {required: [euPerBucket]}]`. A path that no longer exists throws.
- Limits JSON Schema cannot state (a byte size, a nesting depth, "normalised to an integer of at least 1 on load") go in
  `x-maxJsonBytes`, `x-maxDepth` and `x-normalizedTo`. They are documentation: a validator ignores them (networknt
  does). The website's Zod schemas still enforce them; the mod's reader has to enforce them itself.
- A guard test (`schema-drift.test.ts`) walks the Zod schemas and fails when a `.refine` or transform exists that
  `REFINEMENTS` does not list, so a new rule cannot be added without deciding how the schema carries it.

Also, by design:

- Unknown keys are accepted at every level (Zod strips them; the schema does not set `additionalProperties`). A plan
  written by a newer version still validates. `plans/valid/unknown-keys.json` pins it.
- A field with a Zod default is optional in the schema.
- No `$ref` cycles other than the solver's `Note`, named `Note` in `solve-digest.schema.json`.

## The two checks

- Website: `src/test-support/schema-drift.test.ts` fails when a checked-in file is not what the generator writes today
  (and for the guard above). `plan-fixtures.test.ts` reads every plan in `conformance/plans/` through the Zod schemas and
  the real importer, and checks that the plans the website writes are what its writer makes now.
  `solver-contract.test.ts` does the same for the solver's files.
- Mod: `cloud/map/PlanSchemaTest` validates every plan the mod writes (the shared-plan mapper and the website export, for
  all 23 corpus and website boards) and every committed plan against `plan.schema.json`, regenerates the committed
  `plans/mod/` files and compares them, and checks that the invalid plans are refused. `SolverSchemaTest` validates
  the corpus inputs, goldens, Up to plans and board scenarios against the solver schemas and has negative cases so that
  a schema accepting everything fails. The validator is `com.networknt:json-schema-validator:1.5.6`, a test
  dependency of `:mod` only (`jvm/mod/dependencies.gradle`).

There is no JSON Schema validator on the website side (the only ajv in the tree is the unrelated version 6), and none is
added: both sides read the same files, the website through Zod and the mod through the schema, so a file one accepts and
the other refuses fails a test on one of them. Every file in `plans/valid/`, `plans/website/` and `plans/mod/` is accepted
by both, every file in `plans/invalid/` is refused by both.

## Numbers and ids (the kernel's decision)

- Numbers in JSON are written with `NumberText.json` and read with `NumberText.parse`: JavaScript's spelling
  (`String(n)`, `JSON.stringify`, `Number(s)`), shortest round-trip digits, `1e+21` exponent form. `-0` is written as `0`.
  A value that must be an integer is at or below 2^53 - 1. The vectors are in `conformance/core-base/number-text/`.
- Ids are strings in JSON. The mod maps them to a `Uuid` with `PlanIds`' rule (a UUID-shaped string is that UUID; any
  other string is `nameUUIDFromBytes`) and back. Order and derived ids depend on Java's signed `UUID.compareTo` and
  `nameUUIDFromBytes`, which `Uuid` reproduces (`conformance/core-base/uuid/`). The schema only says "string".

## The solver's answer

`SolveInput` is what crosses: the mod writes it (`SolveInputJson`), the corpus holds it, and `solveInputSchema` is checked
against the type in `types.ts`. The engine's `Answer` is not what crosses: each side has its own class, and the goldens,
the Up to plans and `board/mod-answers.json` hold the digest both sides write to compare them (`__fixtures__/digest.ts`,
the mod's `SolverCorpus.digest`): ports as `{nodeId, portIndex, input}`, sets sorted, numbers rounded by the same rule. So
`solve-digest.schema.json` describes the digest. One golden, `platline-x10` (a bench case), was recorded before
`solution.unneeded` existed, so that key is optional.
`board/mod-answers.json`'s per-scenario `answer` (a flatter read-out of the mod's own `Answer`) is not described: only
its `input` is, and the website test that reads it does so by name.

## What else crosses, left to the cloud stream

Not described here and not generated: live-service operations (`src/live/validate.ts` is the specification and the
`conformance/live/` vectors pin it), blueprint and feedback bodies on the server routes, cloud and community payloads
(`src/lib/server`), dataset shards and indexes (only the record and manifest shapes are in `dataset*.schema.json`), and
the mod's own save (`plans.dat`, Gson), which is not shared at all. If one of them should be generated, add a root to
`SCHEMA_FILES` in `build.ts` and a drift check comes with it.

## Java types from this schema: decision record (for `core-model`)

Question: can the model's Java types be generated from `plan.schema.json`? Tried on the real schema
(`conformance/schema/plan.schema.json`, 60 KB, 30 definitions) with both generators that read JSON Schema.

quicktype 23.2.6 (`-l java`, Jackson classes):

- It crashes on the schema (`s.codePointAt is not a function`) because `schemaVersion` is `{"type":"number","const":1}`. Taking
  `const` out lets it run; it writes 97 files, 4233 lines.
- It writes classes, not records (no option), with a getter and a setter pair per field, and `double` for every `number`
  (an integer such as `1` or `480` is a `double`).
- It has no way to keep an unknown key and its converter turns `FAIL_ON_UNKNOWN_PROPERTIES` off, so unknown keys are
  dropped. `Map<String, Object>` is used for the `gtnhplanner` blocks.
- Round trip on this schema's own fixtures (the benzene plan the mod writes and seven plans the website writes): every
  integer comes back as a float (`"schemaVersion":1` becomes `1.0`, `"eut":480` becomes `480.0`; 50 to 640 numbers per plan),
  every absent field comes back as an explicit `null`, keys are reordered alphabetically, and in
  `plans/valid/unknown-keys.json` the keys `futureField` and `somethingNew` are gone.

jsonschema2pojo 1.2.1 (classes, `-a JACKSON2`, with `-D` for additional properties):

- It runs, 78 classes, but gives `Double` fields too (`-i` switches to `BigDecimal`, which keeps digits but not the
  spelling: `1E+21` for `1e+21`), and it names a definition once per place it is used (`Alternative`, `Alternative__1`,
  `Alternative__2`, `Position` to `Position__3`) instead of once per `$defs` entry.
- With `-D` and Jackson it keeps unknown keys in a `Map<String, Object>` (`@JsonAnyGetter`); with the Gson annotation style
  it does not, and Jackson would read the values back as `Integer` or `Double`.

Neither generates records, neither keeps a number's spelling, neither can use `Uuid`, `NumberText` or the id rule, and
both emit classes that import Jackson or Gson. The core is plain Java under the purity gate with no JSON library, and the
mod reads with Gson 2.2.4 on Java 8. Neither writes a union either: the plan schema has none (the only `anyOf` is the
either-or `FuelProfile` rule); the solver digest has two (a note's arguments are text, a number, a note or a list of
notes; several fields are nullable), which both generators turn into `Object` or an untyped class.

**Recommendation: hand-write the Java model in `core-model`, and check it against this schema with a test.** The test
(a core-model reflection or record-component check, or a small reader of `plan.schema.json`) fails when a property in a
`$defs` entry has no matching field in the Java type of the same name, when the Java type has a field the schema lacks
(other than the unknown-key bag), or when an enum differs. Generation can still serve the one thing that is a list of
names: the enum members and the property names, as a test input rather than as source code. The Java model must keep an
unknown-key bag of raw values (text of the number, not a double) so that a plan written by a newer version survives a
round trip, write numbers with `NumberText.json`, and treat ids as strings at the boundary.

## The differences between the two sides' plan JSON (work order for `model`)

Source: `_orch/parity/03-plan-model-and-format.md` (its "differs" rows, the two "mod drops" rows and the task-board row).
"Accepts" is the generated schema accepting what the mod writes today; it is true for all 46 plans tested (23 boards,
each as a shared plan and as a website export) and for every committed plan. Decisions are `_orch/parity/09-decisions.md`;
where it has no row the rule used is named (the numbered rules at its top).

| Row | The mod today | Schema accepts the mod's output | Settled by |
|---|---|---|---|
| Plan of record on disk | own Gson save (`plans.dat`), not plan JSON | not applicable: not shared | rule 4 (the plan JSON is the shared format); no row for the disk format |
| Copy plan code | gzip code of its own | the payload is a plan; the code wrapper is outside the schema | decision "Copy plan code": `gtnh1.` on both sides |
| Old Build, Solve, Pool plans | converts silently at import | yes: the old shapes are not in the schema, the converters run before it | no row; the website's funnel is the one to share (parity row) |
| Load-time repairs | its own, for its saves | yes: the schema describes plans before repair (repairs are on load, not in the file) | no row; rule 4, the website's funnel (parity row) |
| Machine count, pin, rule | writes the solved count as `machineCount` and, for a pinned card, as `solvePin`; reads pins ceiled | yes: both are non-negative numbers | decisions "Pins: fractional" and "Posts and links: set count" |
| Shared machines | reads `extraRecipes`; the website export does not write it | yes: `extraRecipes` is optional | rule 2 (union); parity row: the exporter must write it |
| Drawers (`drainMode`) | writes `product` or `trash`; reads `byproduct` as a product | yes: `drainMode` allows all three | rule 2 (union); the label is the only loss |
| Buffer drawer's rule | kept and solved | yes: the rule field is in the schema | decision "Buffers: keep a rule" |
| Wire between two drawers (mod drops on import) | drops | yes: the schema allows the wire | rule 2; the core keeps it, the mod board needs a representation |
| Wrong-way wire, missing card, `enabled: false`, recipe the game lacks (mod drops on import) | drops with a report line | yes: it only writes what it has | decision "Website-only recipe kinds... never dropped"; the other three cases rule 2 |
| Loose cell wire (`crossForm`) | drops unless a tank fits | yes: `crossForm` is optional on an edge | no row; the website's conversion (parity row) |
| `spareShares`, `settingPins` | cloud map only | yes: optional, with the caps above (the mod must write within them) | rule 2 (union) |
| Plan view | writes `view.rateUnit` only | yes: all 15 `view` fields are optional | rule 2 (union) |
| Task boards in a plan | in the cloud map, lost on link, file and post | yes: the task fields are optional | rule 2 (union) |
| Ids | UUIDs (`PlanIds` maps both ways) | yes: it writes UUID text, and ids are strings | rule 4; kernel's decision above |
| Recipe identity | NEI identity, matched to dataset recipes by content | yes: a recipe is an object with a string id and its content | no row; area 04 of the parity notes |

The parity table has 13 "differs" rows (the brief said 14); with the two "mod drops" rows and the task-board row it has
16 lines about plan content, all above. Nothing in the schema changes the mod's behaviour, and no row needed the schema to
refuse something the mod writes.
