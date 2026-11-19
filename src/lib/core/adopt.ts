// Which areas of the website compute through the compiled core: one flag per area, one line each. Stand-in written by
// the power item with the API the web stream's adopt.ts has (ADOPT, isAdopted, coreFor, setAdoptOverride,
// clearAdoptOverrides, withAdopt); when that file lands, it replaces this one and keeps its own lines.
import type { CoreExports } from "./exports.generated";
import { coreIfLoaded } from "./loader";

export const ADOPT = {
  // power: the power planner's sources and models (PowerExports).
  power: true,
} as const satisfies Record<string, boolean>;

export type AdoptArea = keyof typeof ADOPT;

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

/** The loaded core when the area is on, else null (null means: use the TypeScript). */
export function coreFor(area: AdoptArea): CoreExports | null {
  return isAdopted(area) ? coreIfLoaded() : null;
}

/** Forces an area on or off, or back to its committed flag (undefined). Memory only. */
export function setAdoptOverride(area: AdoptArea, value: boolean | undefined): void {
  const o = overrides();
  if (value === undefined) delete o[area];
  else o[area] = value;
}

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
  if (result instanceof Promise) return result.finally(restore) as T;
  restore();
  return result;
}
