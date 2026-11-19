# Up to plans: the website's newer answers

`idle-noise.json` and `idle-noise-beside-a-lone-card.json` are what the website's current engine exports
(`up-to-export.test.ts`) for the two plans whose files differ from the copies in `conformance/solver/up-to/`. The other
72 files regenerate byte for byte. The copies in `solver/up-to/` are the ones the mod last recorded and the only ones read.

The input of each is unchanged. The answer differs in `drawers.banked` (an idle drawer banks 1.6e-7 where the old file
has 0) and in the order or detail of `notes`, `terminalInputs` and `unneeded`: the website's least-flow tolerance fix of
2026-10-25 (commit 07b310f3) came after the mod's copy was written. Whether the mod's engine passes against these two is
recorded in the hand-over report; if it does, move them over the two files in `solver/up-to/` and run
`node tools/conformance/manifest.mjs --write`.
