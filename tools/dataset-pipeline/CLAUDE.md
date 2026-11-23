# Dataset pipeline (tools/dataset-pipeline)

A Forge mod (`gtnh-calc-oracle`) running inside a real GTNH 2.9 client exports every
recipe map, NEI layout, catalyst, multiblock config and rendered icon. Node scripts in
`scripts/` normalize that into the `RecipeDataset` the app reads:
`normalize-oracle-export.mjs`, `machine-configs.mjs`, `build-resource-index.mjs`,
`build-recipe-index.mjs` (recipe index, lookup index, 250-recipe shards).

## Rebuild and publish

The GitHub "GTNH dataset pipeline" workflow has no runner here. Never dispatch it.

- The instance's dataset is exported from its own pack and published by the instance operator.
  Agents here fix pipeline code; they never publish a dataset or write into the operator's
  export, cache or dataset directories.
- To check a pipeline fix, an agent may run a full export on the build box in its own scratch
  directory. Several exports and game instances may run there at once; there is no lock and no
  limit on their number. Each run gets its own directory, its own display and its own port
  (pick a free random port, never a fixed one), and stops only the processes it started. When
  the run ends, stop them and remove what it created. Reuse an existing raw export
  (`oracle-export.json` with `rendered-icons/` beside it) whenever the fix is in the Node
  scripts, not the mod: an export runs a real client and takes about 20 minutes.
- Never export with `GTNH_RENDER_STACK_ICONS=false`: the export stamps icon filenames, and a
  run without them breaks search.
- Only 2.9 is supported.
- Before a dataset replaces the one in use, compare icon counts and icon tables against it.
  A fix that changes the dataset's format or needs a re-export says so in its handoff.

Rebuilds must run `normalize-fluid-icon-alpha.mjs` (with `--rename`) after the index
builds and before publishing, or faint fluids ship invisible. Texture URLs are cache-immutable: fix pixels under a NEW filename (`--rename`),
and patch whole basenames only.

Two exports of one pack write the same dataset, byte for byte, when both set
`SOURCE_DATE_EPOCH` (the time every file stamps, the asset fingerprint and icon report
included), apart from what the game itself rolls anew each launch (listed below). The
normalizer is a pure function of the raw export: its time is the export's, and its powers
are the game's own products (`scripts/game-math.mjs`), never `Math.pow`, so Node 22 and 24
agree. The exporter never relies on hash order: furnace and crafting recipes, ore names,
bee species and products, crop drops, soils and biomes, aspects, IC2 crop samples
(seeded), GregTech recipe order (by content), hash-built slot lists and outputs are all
sorted or pinned; joke item names are pinned (`StableNames`); a Postea lambda handler is
named without its launch address. NBT text is kept as the game prints it (plans store icon
paths minted from it). A new rule that reads a game collection says what fixes its order.

`scripts/compare-datasets.mjs <A> <B>` compares two datasets and exits 1 on any difference
outside this list of launch noise (`LAUNCH_NOISE` in the script, kept equal to it):
- `shown-member`: a slot of several blocks shows the one the game lists first from a hash
  set (Crop Breeder soils, an assembly-line ore). The shown block, its resource and, for
  GregTech, the recipe id move. It names a cell, so it is not pinned; `carry-recipe-ids`
  keeps the previous dataset's shown block and id instead (`keptByShown`).
- `slot-members`: such a slot can also list another subset of blocks (a Crop Breeder
  soil lacking two bartworks ores one launch); carry cannot match it, so its id is new.
- `thaumcraft-list-position`: infusion, arcane and crucible ids are list positions, and a
  few recipes register in another order (Thaumic Tinkerer's soul aspects): same recipes,
  swapped ids. Not sorted: the served ids follow the game's order; carry matches by content.
- `thaumcraft-generated-aspects`: aspects Thaumcraft generates for items without any
  (about 45 essentia smelting recipes: aspects and time).
- `thaumcraft-addon-aspect-costs`: Gadomancy's Aura Core and Element of Darkness draw
  their aspect costs at launch.
- `eec-live-entity`: EEC mobs whose health or drops MobsInfo reads from a live entity
  (slime size, mandrakes): numbers and the spawner's tooltip, never ids.
- `icon-bytes`: icons drawn from animated textures (bytes only, same name).
- `instance-fingerprint`: the asset fingerprint's hash of the instance, whose configs the
  game rewrites as it runs.
- `icon-report-size`, `export-timing`, `derived`: the icon report's byte count of
  recipes.json, the oracle report's durations, and the indexes and shards built from
  recipes.json when it differs by the noise above.

When an export only ADDS fields, graft them onto the published export
(`~/graft-sparge.js`, matching recipes by content key) and confirm with a shard diff
(`~/cmp-field.js`). The 2.9 export in WSL is such a graft.

A new pack (or any fresh export) keeps the previous dataset's recipe ids with
`scripts/carry-recipe-ids.mjs <previous recipes.json.gz> <new recipes.json> [report]`, run
after normalize and before the indexes: same map (GT++'s `gtpp.recipe.*` read as RC-2's
`gt.recipe.*`), slots, amounts, chances, ticks and EU keep the old id (its old dataset
prefix included); bees, crops, veins, mobs and underground fluids keep theirs by identity.
Content keys ignore NBT, so recipes that differ only in a slot's NBT (crop seeds, tool
materials, a bio culture) first take the id of the previous recipe with the same NBT; file
order never decides which. The normalizer folds two registrations of one recipe into the
first, and that fold keys on NBT too: without it, which of such recipes survived followed
the export's order.
`local-2.9.0-rc-2` carries beta-2's ids this way (`~/rebuild-rc2.sh` in WSL; the report is
`oracle/recipe-id-carry.json`).

## The mod's machine data

The in-game mod has no dataset: `jvm/core/core-machines/src/main/resources/assets/gtnhplanner/machines/handlers.json`
carries what its cards need of one (each recipe map's machine templates, controls and machine blocks). After a new
dataset is published, regenerate it from that dataset and commit it with the mod's tests green:

    NODE_OPTIONS=--max-old-space-size=12000 npx tsx tools/audits/export-mod-machine-data.mjs \
      --dataset public/datasets/gtnh/<version> --report <scratch>/handlers-report.json

It reads `recipes.json.gz` and needs the generated sources (`npm run dev` setup). It exits non-zero unless every
recipe's handlers and controls come back exactly from the file. The normalized dataset keeps instantiated handlers,
not the exporter's per-map catalysts, so the report lists what it keeps from the previous file: maps with no recipes
in the dataset, single-machine templates, and a handler's full block list (the dataset has its face and one block
per tier). `tools/audits/export-mod-machine-data.test.mjs` checks the parts that come from this repository (tiers,
coils, machine table names); it cannot see a stale dataset.

## Game rates sidecar

The pack's rate mod (`config/gtnhrates.cfg`) scales bee and IC2 crop yields and recipe
durations. The export step (`write-game-rates.mjs`) writes `game-rates.json` beside the
dataset (`beesYield`, `ic2CropsYield`, `recipeDurationDivisor` from `gtRecipesEnergyDiscount`);
a pack without the mod writes none. `rebuild-manifest.mjs` validates it (three positive
numbers, nothing else) and copies it onto the version's manifest entry as `gameRates`. It is
not part of the dataset checksum, so adding or changing it needs a manifest rebuild, not a
re-export.

## Import principles

- Prefer data exported from NEI or the runtime over hand-written tables and fallbacks.
- A missing recipe: check the raw oracle export first, then the mod source at the pack's
  exact GT tag.
- A map with one uncapped machine family carries no `machineHandlers` by design; a lone
  capped singleblock keeps its handler (below).
- Never parse tooltips globally. Multiblock stats come from tooltips only for catalysts
  flagged `multiblock` (`isMultiblockCatalyst` in `machine-configs.mjs`); singleblocks read
  only `Voltage IN` and `Machine Type`.
- Fold singleblock tier variants into one family (Liquefying Suckers are Fluid
  Extractors); multiblocks stay separate handlers (Large Fluid Extractor, Steam
  Separator). A map with one real family keeps the map name.
- Singleblock families: group voltage-input singleblocks by Java class plus their
  `Machine Type` tooltip (else recipe-map membership), named from the lowest tier. Keep a
  lone capped handler, or its `maximumTier` disappears. Exam:
  `scripts/singleblock-tiers.test.mjs`. A multiblock whose name collides with a
  singleblock gets its own id.
- Concrete items carry their oredict membership.
- What a slot accepts (`mAltFluidInputs`, `mOreDictAlt`) stays on that recipe's input. The
  catalog and the resource index carry only item-level alternatives: the server gives
  every slot without its own the index entry's. Exam: `build-resource-index.test.mjs`.
- NEI layout: keep exported slot positions and progress bars; empty slots stay visible;
  non-consumed slots stay visible except TGS tool placeholders; never replace a real slot
  with a fake label.
- Every fluid-touching Canner recipe is mirrored into the synthesized "Tank" map
  (`addTankRecipe`, 0 EU, 1 tick, real slots).
- Extreme Entity Crusher mobs come from the `kubatech-eec-mobs` oracle adapter (drop
  chances replayed in `eec-drops.mjs`). Sparge Tower rolled outputs and fusion startup EU
  are per-recipe export fields; `sparge-byproducts.mjs` appends rolled slots AFTER the
  exported ones so recipe ids stay stable.
- An item input's chance (`inputChance`, out of 10000) becomes `amount` x chance plus `consumeChance`; the recipe id
  hashes the whole stack, so it keeps the id an export without the chance gave it.
- Faint fluid icons: repair only captures under 20% mean opacity, starting from the
  original PNGs (`normalize-fluid-icon-alpha.mjs`).
