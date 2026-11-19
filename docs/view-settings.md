# Player settings: one list

`jvm/core/core-view/settings/settings.json` is the one list of every setting a player can change on the website or in the mod. Both sides read their keys, defaults, ranges and choices from it through generated code, and tests hold each side to it. This page is the table of that file, the drift decisions and the rules around them.

## How it is wired

- The mod gets `com.gtnhplanner.core.view.ViewSettings` (key, default, range and choice constants, resolved for the mod) from `jvm/gradle/settings.gradle.kts`.
- The website gets `src/lib/view-settings.generated.ts` from `tools/settings/generate.mjs`, which `tools/tokens/generate.mjs` runs, so every script that builds the tokens builds this too. Nothing generated is committed.
- A value that differs by host is written `{"web": x, "mod": y}` in the file. A zoom that is a fraction is stored as a percent with a `divisor`, so 35 over 100 is exactly 0.35 on both sides.
- `parity` says how the two hosts compare: `shared` (same on both), `drift` (both have it, and a difference is kept on purpose), `web` or `mod` (only one host has it).
- `display: true` marks a setting that changes how numbers are shown and never the solve.
- The sound rule table is `jvm/core/core-view/settings/sounds.json`: every sound, the events that play it, its volume and pitch, and the setting that gates it (Sounds). Sound files stay where they are: `public/sounds` on the website, `assets/gtnhplanner/sounds` in the mod. Playback stays per host.

## Tests

- `src/lib/view-settings.test.ts` (website): every source file that writes to browser storage is listed in the schema's `webStores`, a setting kind owns at least one schema setting, a memory or dev kind has a control and a state kind says why it has none; every control named for a setting is found in the source of its host; the generated constants equal the schema; the sound table names real sounds and events; changing a display setting leaves the solve and the project alone.
- `SettingsTest` (core-view) checks the generated Java against the JSON. `SettingsSchemaTest` (mod) checks that `PlannerSettings`, `Config` and `Sfx` read their keys, defaults and sounds from the schema.

## Display-only settings

Rate unit, energy view (the mod's second rate key), power display and power sense change only how numbers are drawn. On the website the unit setters in the factory store and the workspace view's average-draw switch leave `lastResult` and `project` as the same objects, and the test asserts that. The mod's two keys are read where numbers are drawn; a unit test there would need the board session without Minecraft, which the mod's tests do not have, so the mod side is by reading the code.

## Drift decisions

Two values changed so that the hosts agree where they had no reason to differ.

| Setting | Before | After |
| --- | --- | --- |
| Minimap corner, the stored value is not a corner (mod) | top right | the default, bottom right, as on the website |
| Names-when-zoomed-out threshold, a stored number outside 0.35 to 1 (website) | reset to 0.65 | clamped to the nearest end of 0.35 to 1 |

A key that was persisted without a control was removed: `netFlowRates` in the website's workspace view had no switch, no writer and no reader.

No stored value is lost: no key was renamed, and the mod's legacy `board.background` key is still read as it was. A later rename needs a read of the old key.

Differences kept on purpose, each with its reason in the file's `note`:

- Minimap size: the page's map covers a whole browser window and the game's a smaller GUI, so the sizes and the first-run size differ (web small, mod medium).
- Minimap follow: web off, mod on.
- Overview width: the pixel spaces differ (web 224 to 480 shell px, default 336; mod 160 to 300 board px, default 198).
- Interface size: the same four names, different units (a percent of browser zoom, a GUI scale step).
- Rate unit: the website keeps it in the store and in a plan's saved view, the mod on the device in two keys.
- Power display and power sense: the website has a unit and an average-draw switch, the mod a boolean for amps and one for peak power.
- Settings section: remembered on the website, in memory only in the mod.

## Table

Defaults and ranges are as the file has them. Storage is the key the host writes: the website's browser storage key and field (or store field), the mod's `settings.properties` key (or Forge config key). A control is the place the player changes it.

| Setting | Type | Default | Range or choices | Website storage | Mod storage | Scope | Website control | Mod control | Parity |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `uiSize` | choice | normal | tiny/small/normal/large | `gtnh-factory-flow.ui-scale.v1` | `board.planSize` | device | settings: Board/Size | settings: Board/Size | drift |
| `uiScalePercent` | int | 100 | 60 to 200 | `gtnh-factory-flow.ui-scale.v1` | none | device | settings: Board/Size | no | web |
| `zoomedOutNames` | bool | on | - | `gtnh-factory-flow.device-settings.v1.zoomedOutNames` | `board.zoomedOutNames` | device | settings: Board/Names when zoomed out | settings: Board/Names when zoomed out | shared |
| `zoomedOutAt` | int | 65 | 35 to 100 | `gtnh-factory-flow.device-settings.v1.zoomedOutAt` | `board.zoomedOutAt` | device | settings: Board/Zoomed-out view | settings: Board/Zoomed-out view | shared |
| `problemOutlines` | bool | on | - | `gtnh-factory-flow.device-settings.v1.problemOutlines` | `board.problemOutlines` | device | settings: Board/Problem outlines | settings: Board/Problem outlines | shared |
| `scrollChangesValues` | bool | on | - | `gtnh-factory-flow.device-settings.v1.scrollChangesValues` | `board.scrollChangesValues` | device | settings: Board/Scroll changes values | settings: Board/Scroll changes values | shared |
| `seeThrough` | int | 30 | 0 to 100 | none | `board.seeThrough` | device | no | settings: Board/See-through | mod |
| `itemsLeft` | bool | off | - | `gtnh-factory-flow.device-settings.v1.itemsLeft` | none | device | settings: Board/Item browser | no | web |
| `font` | choice | inter | minecraft/inter/lexend/atkinson-hyperlegible/open-dyslexic/andika/comic-neue/jetbrains-mono/system | `gtnh-app-font` | none | device | settings: Board/Font | no | web |
| `settingsSection` | choice | Board | Board/Minimap/Sound/Controls | `gtnh-factory-flow.device-settings.v1.settingsSection` | none | device | yes | no | web |
| `canvasPattern` | choice | dots | dots/lines/cross/ruled/graph/none | `gtnh-factory-flow-board-view.canvasPattern` | none | plan-view | yes | no | web |
| `canvasTheme` | text | charcoal | - | `gtnh-factory-flow-board-view.canvasTheme` | none | plan-view | yes | no | web |
| `fixedEdgeWidth` | bool | off | - | `gtnh-factory-flow-board-view.fixedEdgeWidth` | none | plan-view | yes | no | web |
| `glanceMode` | choice | identity | identity/status/usage/power | `gtnh-factory-flow-board-view.glanceMode` | none | plan-view | yes | no | web |
| `moveMotion` | bool | on | - | `gtnh-factory-flow-board-motion.moveMotion` | none | device | yes | no | web |
| `valueMotion` | bool | on | - | `gtnh-factory-flow-board-motion.valueMotion` | none | device | yes | no | web |
| `minimapShow` | bool | off | - | `boardMinimap.show` | `minimap.on` | device | yes | settings: Minimap/Show the minimap | shared |
| `minimapSize` | choice | web small, mod medium | small/medium/large/huge | `boardMinimap.size` | `minimap.size` | device | yes | settings: Minimap/Size | drift |
| `minimapCircle` | bool | off | - | `boardMinimap.shape` | `minimap.circle` | device | yes | settings: Minimap/Shape | shared |
| `minimapCorner` | choice | bottom-right | top-left/top-right/bottom-left/bottom-right | `boardMinimap.corner` | `minimap.corner` | device | yes | settings: Minimap/Position | shared |
| `minimapFollow` | bool | web off, mod on | - | `boardMinimap.follow` | `minimap.follow` | device | yes | settings: Minimap/Centre on the machine you look at | drift |
| `minimapZoom` | choice | 9 | 4/6/9/13/18/25/35/50/71/100 | none | `minimap.zoom` | device | no | settings: Minimap/Zoom | mod |
| `minimapSeeThrough` | int | 0 | 0 to 80 | none | `minimap.seeThrough` | device | no | settings: Minimap/See-through | mod |
| `pinList` | bool | on | - | none | `pinList.on` | device | no | settings: Pin list/Show the pin list | mod |
| `pinListCorner` | choice | top-right | top-left/top-right/bottom-left/bottom-right | none | `pinList.corner` | device | no | settings: Pin list/Position | mod |
| `arLens` | bool | on | - | none | `world.ar` | device | no | settings: In the world/Show the plan over the world | mod |
| `arFocus` | bool | on | - | none | `world.arFocus` | device | no | settings: In the world/Cards | mod |
| `arLabels` | bool | on | - | none | `world.arLabels` | device | no | settings: In the world/Machine labels | mod |
| `arWireLabels` | choice | always | always/looked/off | none | `world.arWireLabels` | device | no | settings: In the world/Wire labels | mod |
| `arGhosts` | choice | see-through | see-through/solid/hidden | none | `world.arGhosts` | device | no | settings: In the world/Machines | mod |
| `arKeys` | bool | on | - | none | `world.arKeys` | device | no | settings: In the world/Keys list | mod |
| `arRange` | choice | 32 | 16/32/64/128 | none | `world.arRange` | device | no | settings: In the world/Range | mod |
| `taskShow` | choice | near | always/near/never | none | `world.taskShow` | device | no | settings: Task locations/Locations | mod |
| `taskNear` | choice | 32 | 16/32/64/128 | none | `world.taskNear` | device | no | settings: Task locations/Distance | mod |
| `taskBeam` | bool | on | - | none | `world.taskBeam` | device | no | settings: Task locations/Beam | mod |
| `taskLabel` | bool | on | - | none | `world.taskLabel` | device | no | settings: Task locations/Label | mod |
| `soundLevel` | choice | normal | off/quiet/normal/loud | `gtnh-factory-flow.sound-level.v2` | `sound.level` | device | settings: Sound/Sounds | settings: Sound/Sounds | shared |
| `rateUnit` (display) | choice | second | tick/second/minute/hour/eu | `store rateUnit` | `board.rateUnit` | web plan-view, mod device | yes | yes | drift |
| `rateEnergy` (display) | bool | off | - | none | `board.rateEnergy` | device | no | yes | mod |
| `powerDisplay` (display) | choice | eu | eu/amps | `gtnh-factory-flow.power-display-unit.v1` | `board.powerAmps` | device | yes | yes | drift |
| `powerSense` (display) | choice | average | average/peak | `gtnh-factory-flow-workspace-view.averageMachineDraw` | `board.peakPower` | device | yes | yes | shared |
| `overviewOpen` | bool | on | - | `gtnh-factory-flow-overview-rail.open` | `overview.open` | device | yes | yes | shared |
| `overviewFolded` | set | INTERNAL | of INPUTS, OUTPUTS, INTERNAL, MACHINES | `gtnh-factory-flow-overview-rail.folded` | `overview.folded` | device | yes | yes | shared |
| `overviewWidth` | int | web 336, mod 198 | web 224 to 480 ; mod 160 to 300 | `gtnh-factory-flow.device-settings.v1.overviewWidth` | `overview.width` | device | yes | yes | drift |
| `itemsWidth` | int | 256 | 108 to 420 | `gtnh-factory-flow.device-settings.v1.itemsWidth` | none | device | yes | no | web |
| `leftPanelOpen` | bool | on | - | `gtnh-factory-flow-workspace-view.leftPanelOpen` | none | device | yes | no | web |
| `rightPanelOpen` | bool | on | - | `gtnh-factory-flow-workspace-view.rightPanelOpen` | none | device | yes | no | web |
| `showHiddenResources` | bool | off | - | `gtnh-factory-flow-workspace-view.showHiddenResources` | none | device | yes | no | web |
| `favouritesOnly` | bool | off | - | `gtnh-factory-flow-workspace-view.favouritesOnly` | none | device | yes | no | web |
| `trendsOpen` | bool | on | - | `gtnh-factory-flow-workspace-view.trendsOpen` | none | device | yes | no | web |
| `tourOffered` | bool | off | - | `gtnhplanner.tour.offered` | `tour.offered` | device | yes | yes | shared |
| `taskView` | choice | board | board/chain | `gtnhplanner.task-views.v1` | `tasks.view.` | per-plan | yes | yes | shared |
| `updateOffered` | text |  | - | none | `update.offered` | device | no | yes | mod |
| `solverEffortPercent` | int | 100 | 25 to 200 | none | `solverEffortPercent` (Forge config) | device | no | config file | mod |
| `freeIngredients` | set | Water | - | none | `freeIngredients` (Forge config) | device | no | config file | mod |
| `hideMachineCountsSection` | bool | off | - | none | `hideMachineCountsSection` (Forge config) | device | no | config file | mod |
| `debugRouteDump` | bool | off | - | none | `debugRouteDump` (Forge config) | device | no | config file | mod |
