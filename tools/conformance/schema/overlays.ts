/**
 * What the Zod schemas check that `z.toJSONSchema` cannot say by itself: refinements (`.refine`) and transforms.
 * Each is described here, in JSON Schema where JSON Schema has the word for it (`maxProperties`, `anyOf`) and as an
 * `x-` keyword where it does not. A validator ignores an `x-` keyword it does not know; the Java model reads them
 * as limits to enforce by hand.
 *
 * `src/test-support/schema-drift.test.ts` walks the Zod graph and fails when a refinement exists that is not listed in
 * `REFINEMENTS` (so a new `.refine` cannot be added without saying here what it does to the schema), and when a listed
 * one is gone.
 */

/** A change to one place in a schema file. `def` is a name in `$defs`; the root properties when it is `ROOT`. */
export type Overlay = {
  def: string;
  /** Path below the definition: property names and `items`/`additionalProperties` steps, as in the JSON. */
  path: string[];
  set: Record<string, unknown>;
};

export const ROOT = "#";

/** Every refinement and transform in the exported Zod schemas, by place, and what the schema file says about it. */
export const REFINEMENTS: Record<string, string> = {
  "FactoryNode.parallel": "transform: rounded to a whole number, at least 1, on load (input described: any positive number)",
  "FactoryNode.portRates": "at most 256 entries",
  "FuelProfile": "euPerLiter or euPerBucket is present",
  "GtnhplannerData": "at most 64 KB as JSON in UTF-8, nested at most 63 levels",
  "Recipe.metadata": "nested at most 63 levels",
  "FactoryProject.spareShares": "at most 1024 leftovers, each with at most 64 bearers",
  "FactoryProject.settingPins": "at most 512 machines",
};

const nestedAtMost63 = { "x-maxDepth": 63 };

export const OVERLAYS: readonly Overlay[] = [
  {
    def: "FactoryNode",
    path: ["properties", "parallel"],
    set: { "x-normalizedTo": "an integer, at least 1: Math.max(1, Math.round(value)) on load" },
  },
  { def: "FactoryNode", path: ["properties", "portRates"], set: { maxProperties: 256 } },
  {
    def: "FuelProfile",
    path: [],
    set: { anyOf: [{ required: ["euPerLiter"] }, { required: ["euPerBucket"] }] },
  },
  { def: "GtnhplannerData", path: [], set: { "x-maxJsonBytes": 64 * 1024, ...nestedAtMost63 } },
  { def: "Recipe", path: ["properties", "metadata"], set: nestedAtMost63 },
  { def: ROOT, path: ["properties", "spareShares"], set: { maxProperties: 1024 } },
  { def: ROOT, path: ["properties", "spareShares", "additionalProperties"], set: { maxProperties: 64 } },
  { def: ROOT, path: ["properties", "settingPins"], set: { maxProperties: 512 } },
];

type Json = Record<string, unknown>;

/**
 * Applies the overlays that belong to this file: those whose definition it has, and the root ones when the file's root
 * has the property. A listed place that is missing throws: a renamed field must not silently lose its limit.
 */
export function applyOverlays(schema: Json, overlays: readonly Overlay[], rootHasPath: (path: string[]) => boolean): Json {
  const defs = (schema.$defs ?? {}) as Record<string, Json>;
  for (const o of overlays) {
    let node: Json | undefined;
    if (o.def === ROOT) {
      if (!rootHasPath(o.path)) continue;
      node = schema;
    } else {
      node = defs[o.def];
      if (node === undefined) continue;
    }
    for (const step of o.path) {
      const next: unknown = (node as Json)[step];
      if (typeof next !== "object" || next === null) throw new Error(`overlay ${o.def}/${o.path.join("/")}: no "${step}"`);
      node = next as Json;
    }
    Object.assign(node, o.set);
  }
  return schema;
}
