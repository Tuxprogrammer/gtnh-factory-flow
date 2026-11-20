// Which areas of the website compute through the compiled core. One flag per area, one line each: this file is the only
// place that says so. Rules for an adoption item: restructure/adoption.md (shadow first, then switch behind the flag,
// then delete the TypeScript in a later item).
//
//   const c = coreFor("machines");          // the core when it is loaded and the area is on, else null
//   const out = c ? c.MachinesExports.machinesReadout(json) : tsReadout(input);
//
// A caller never waits for the core: until loadCore() has finished, or when it failed, coreFor() is null and the
// TypeScript path runs. The first coreFor() of an area that is on starts the load in the background (nothing awaits it,
// a failure is dropped: the caller keeps the TypeScript), so no page entry has to call loadCore() for an area to work.
//
// Adding an area: one line in ADOPT, `<area>: false`. Turning an area on is changing its line to `true` in the commit
// that switches that area; nothing else lists areas.
import type { CoreExports } from "./exports.generated";
import { coreIfLoaded, coreState, loadCore } from "./loader";

export const ADOPT = {
  // machines: per-card machine, bee and crop readouts (MachinesExports).
  machines: false,
  // power: the power planner's sources and models (PowerExports).
  power: true,
  // layout: the wire router and Arrange (LayoutExports).
  layout: false,
  // solve: the plan solve (SolverExports), in the solve Worker and the server's plan routes, on WebAssembly GC only
  // (src/lib/core/solve-adopt.ts).
  solve: true,
} as const satisfies Record<string, boolean>;

export type AdoptArea = keyof typeof ADOPT;

// A dev override: for tests and for trying an area in a running dev server (`__gtnhAdopt.set("machines", true)` in the
// console of a dev build; a production build has no such object). Held in memory only, never written to storage: nothing in the UI shows it, and a setting with
// no visible control that persisted would leave a player stuck in it (root AGENTS.md). It lives on globalThis, as the
// loader's state does, so two copies of this module in one page agree.
const OVERRIDE_KEY = Symbol.for("gtnh.core.adopt");
type Overrides = Partial<Record<AdoptArea, boolean>>;

function overrides(): Overrides {
  const g = globalThis as unknown as Record<symbol, Overrides | undefined>;
  return (g[OVERRIDE_KEY] ??= {});
}

/** Whether the area computes through the core: the override if one is set, else the committed flag. */
export function isAdopted(area: AdoptArea): boolean {
  return overrides()[area] ?? ADOPT[area];
}

/**
 * The core for a caller in `area`: the loaded exports when the area is on, null when it is off or the core is not
 * (yet) loaded. A null means "use the TypeScript".
 */
export function coreFor(area: AdoptArea): CoreExports | null {
  if (!isAdopted(area)) return null;
  if (coreState() === "idle") loadCore().catch(() => undefined);
  return coreIfLoaded();
}

/** Forces an area on or off (true, false), or back to its committed flag (undefined). Memory only. */
export function setAdoptOverride(area: AdoptArea, value: boolean | undefined): void {
  const o = overrides();
  if (value === undefined) delete o[area];
  else o[area] = value;
}

/** Drops every override. */
export function clearAdoptOverrides(): void {
  const g = globalThis as unknown as Record<symbol, Overrides | undefined>;
  delete g[OVERRIDE_KEY];
}

/** Runs `fn` with the areas forced as given and puts the overrides back, also when it throws or rejects. */
export function withAdopt<T>(areas: Overrides, fn: () => T): T {
  const before = { ...overrides() };
  for (const [area, value] of Object.entries(areas) as [AdoptArea, boolean | undefined][])
    setAdoptOverride(area, value);
  const restore = () => {
    clearAdoptOverrides();
    Object.assign(overrides(), before);
  };
  let result: T;
  try {
    result = fn();
  } catch (e) {
    restore();
    throw e;
  }
  if (result instanceof Promise) {
    return result.finally(restore) as T;
  }
  restore();
  return result;
}

if (process.env.NODE_ENV !== "production") {
  (globalThis as unknown as { __gtnhAdopt?: unknown }).__gtnhAdopt = {
    set: setAdoptOverride,
    clear: clearAdoptOverrides,
    status: () => Object.fromEntries(Object.keys(ADOPT).map((a) => [a, isAdopted(a as AdoptArea)])),
  };
}
