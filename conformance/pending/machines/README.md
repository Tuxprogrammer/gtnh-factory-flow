# Bee goldens: the website's current answers

`bee-goldens.web-regenerated.jsonl.gz` is what `jvm/mod/tools/dev/bees/bee-goldens.local.test.ts` writes today from the
website's own functions. It has the same 25,440 rows as `conformance/machines/bee-goldens.jsonl.gz` (recorded 2026-10-13,
the one the mod's `BeesGoldensTest` passes against). 19,336 rows differ: `product` in 18,624, `specialty` in 12,416, `eut`
in 2,848. `duration` and `durationTicks` do not move.

The cause is the website taking the dataset's pack rates into Forestry's housings (the Apiary, Magic Apiary and
Alveary), a change made after the mod recorded its file. The mod's bee model does not have it yet.

Replace `machines/bee-goldens.jsonl.gz` with this file (and run `node tools/conformance/manifest.mjs --write`) when the
mod's `Bees` takes the same rates; until then `BeesGoldensTest` would fail on it. The crop goldens
(`machines/crop-goldens.jsonl.gz`) are likely behind in the same way for Crop Managers; there is no generator for them in
this repository, so that is not confirmed.
