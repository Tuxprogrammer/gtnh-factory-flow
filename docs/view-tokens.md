# View tokens

One source for what both hosts draw with: `jvm/core/core-view/tokens/tokens.json`. The values were
inventoried from the website (`clean/tokens.ts`, `clean.css`, `tier-colors.ts`, `up-to-reason.ts`) and the
mod (`Hyb`, `CardPaint`, `CardFace`, `DrawerPaint`, `CardLayout`); where both held a value it is the same
number, and no look changed.

## How it is read

- Website: `tools/tokens/generate.mjs` writes `src/components/flow/clean/tokens.generated.ts` and
  `tokens.generated.css` (gitignored). The npm pre-hooks (`predev`, `prebuild`, `pretypecheck`,
  `prelint`, `predev:local`) and the vitest global setup run it, so no step needs the JVM toolchain.
- Java: `jvm/gradle/tokens.gradle.kts` is a script plugin. A core module sets `extra["tokensPackage"]` (and,
  if it wants only some groups, `extra["tokensGroups"]`), applies the script, and gets a generated
  `<package>.Tokens` in its build directory. No step needs Node. `core-layout` sits above `core-view` in the
  build order and so gets its own copy instead of depending on it.
- Why not committed: both toolchains can write their files before they are used. If a build ever cannot, commit
  the generated files and add a match check to both suites.
- Colour mixes: the mod's `Hyb.mix` is float32 with half-up rounding; the website's `mixHex` computes the same
  way. Every mix of every kind is written in `tokens/mixes.expected.json`; the Node generator, the Gradle
  generator, `TokensTest` (core-view), `HybMixTest` (mod) and `tokens.test.ts` (website) are all held to it.
  After a deliberate change to a colour or share, run `node tools/tokens/generate.mjs --accept` and review the diff.

## Names

JSON camelCase keys become UPPER_SNAKE in Java and kebab-case in CSS. A geometry value both hosts share is
`NAME`; one that differs by host is `{web, mod}` in the JSON, `NAME_WEB` and `NAME_MOD` in Java and TypeScript.

## Same on both hosts

All 27 palette colours, the 8 tones, the tile, free-in and free-out sets, the foot colours, the 15 tier
colours (`text` is `#ffffff` or `#111111`; dark text on MV, HV, ZPM and MAX), the pulse periods (1.9, 1.43, 1
seconds), the nine kind mixes and their shares, the board grid (20), the card width (320), rail width (112),
rail inset (4), icon size (32), circuit badge (24), head chip height (20), settings chip height (18), the drawer
(116 by 48) and the wire lane constants.

## Differs by host, kept as a deliberate choice

The website lays a card on its 20 px grid, rows two cells; the mod rows are 42 px so a 32 px icon sits
centred. These stay per host, named in the file with both values.

| Name | Website | Mod |
|---|---|---|
| port row height | 40 | 42 |
| head height | 40 | 32 |
| picture minimum height | 100 | 120 |
| picture art maximum | 88 | 80 |
| icon inset | 4 | 3 |
| words x | 40 | 39 |
| words width | 70 | 71 |
| settings row pitch | 20 | 22 |

Mod only: the drawer's bar height (8, inside the shared 116 by 48).

Website only: key size, chip width, strip gap, strip padding, strip chip gap, strip inset.

## Drift picks

None. No value was changed on either side, so there is no before and after to list.

## Left where it was

Literals that exist on one side only stay in that side's code: the mod's card frame bevel, the picture well and
key-cap colours in `CardPaint`, the minimap and group colours, and the website's `drawer.css` hexes that
`drawer-model.test.ts` pins to the mixes. Each can move to the file when a second reader needs it.
