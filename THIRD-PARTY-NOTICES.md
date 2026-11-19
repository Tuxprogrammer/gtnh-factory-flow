# Third-party notices

The planner's own code is MIT licensed (see `LICENSE`). Some of its data tables are transcribed from other MIT licensed
projects; their notices are kept here as the licences require. GTNH, Minecraft, mod assets, generated datasets and icons are
not included and remain under their owners' licences.

## ShadowTheAge/gtnh

The curated machine table (what each multiblock's coils, casings and hatches do to speed, power, parallels and overclocks) is
transcribed from the figures in this project's `machines.ts` and audited against the game's source. Files derived from it:

- `src/lib/machines/machine-table.ts`
- `jvm/core/core-machines/src/main/java/com/gtnhplanner/machines/web/MachineTable.java`
- `jvm/core/core-machines/src/main/java/com/gtnhplanner/machines/web/MachineTableEntries.java`
- `conformance/machines/reference-coefficients.json` (its `machines.ts` evaluated over a grid of tiers and choices), read by
  `src/lib/machines/machine-table.test.ts` and `MachineTableTest.java`

Source: https://github.com/ShadowTheAge/gtnh

MIT License

Copyright (c) 2025 ShadowTheAge

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Helfima/helmod

The equilibrium solver follows the approach of Helmod's matrix solver (solve the coupled system instead of iterating recipe
by recipe). No Helmod code is copied; the credit is kept for the idea. Source: https://github.com/Helfima/helmod (MIT,
Copyright (c) 2025 Helfima).

## PlanNH

The in-game mod began as PlanNH by Sbancuz, MIT licensed; his copyright is in `LICENSE` and `jvm/mod/LICENSE`.
