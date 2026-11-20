import type { Recipe } from "./types";

/**
 * The stored recipe when the refreshed one holds the same data, else the refreshed one. A plan's recipes are refreshed
 * from the dataset every time a plan arrives; keeping the object a card already has when nothing in it changed keeps
 * every cache keyed on the recipe object (the card's machine figures, the recipe the compiled core holds) instead of
 * computing it all again for an equal copy.
 */
export function sameRecipeOr(stored: Recipe, refreshed: Recipe): Recipe {
  return sameData(stored, refreshed) ? stored : refreshed;
}

/** Plain-data equality: same keys (a key holding undefined counts), same values; arrays in order. */
function sameData(left: unknown, right: unknown): boolean {
  if (Object.is(left, right)) return true;
  if (typeof left !== "object" || typeof right !== "object" || left === null || right === null) return false;
  if (Array.isArray(left) !== Array.isArray(right)) return false;
  if (Array.isArray(left)) {
    const other = right as unknown[];
    if (left.length !== other.length) return false;
    for (let i = 0; i < left.length; i++) if (!sameData(left[i], other[i])) return false;
    return true;
  }
  if (Object.getPrototypeOf(left) !== Object.getPrototypeOf(right)) return false;
  const a = left as Record<string, unknown>;
  const b = right as Record<string, unknown>;
  const keys = Object.keys(a);
  if (keys.length !== Object.keys(b).length) return false;
  for (const key of keys) {
    if (!Object.prototype.hasOwnProperty.call(b, key) || !sameData(a[key], b[key])) return false;
  }
  return true;
}
