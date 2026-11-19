/**
 * Drift check for conformance/schema: the JSON Schema files are what tools/conformance/schema/build.ts generates from the
 * Zod schemas today, and every check the Zod schemas make that JSON Schema cannot say by itself (a refinement or a
 * transform) is listed in tools/conformance/schema/overlays.ts. A new `.refine` or `.transform` therefore cannot be added
 * without saying here what it does to the schema the mod validates against.
 *
 * Fix a failure with: npx tsx tools/conformance/schema/write.ts
 */
import { describe, expect, it } from "vitest";
import type { z } from "zod";

import * as datasetSchemas from "@/lib/datasets/schemas";
import * as modelSchemas from "@/lib/model/schemas";

import { buildAll } from "../../tools/conformance/schema/build";
import { REFINEMENTS } from "../../tools/conformance/schema/overlays";
import { readConformanceText } from "./conformance";

describe("conformance/schema matches the Zod schemas", () => {
  const built = buildAll();

  it("has the four files", () => {
    expect([...built.keys()].sort()).toEqual([
      "board-selection.schema.json",
      "dataset-manifest.schema.json",
      "dataset.schema.json",
      "plan.schema.json",
    ]);
  });

  for (const [file, text] of buildAll()) {
    it(`${file} is what the generator writes (run tools/conformance/schema/write.ts after changing a schema)`, () => {
      expect(readConformanceText("schema", file) === text).toBe(true);
    });
  }
});

type Def = {
  type: string;
  shape?: Record<string, z.ZodType>;
  element?: z.ZodType;
  valueType?: z.ZodType;
  innerType?: z.ZodType;
  options?: z.ZodType[];
  in?: z.ZodType;
  out?: z.ZodType;
  checks?: Array<{ _zod: { def: { check: string } } }>;
  getter?: () => z.ZodType;
};
const defOf = (s: z.ZodType): Def => (s as unknown as { _zod: { def: Def } })._zod.def;

/** Where the Zod graph does something JSON Schema cannot say: a custom check (`.refine`) or a pipe (`.transform`). */
function customPlaces(): string[] {
  const names = new Map<z.ZodType, string>();
  const isZod = (v: unknown): v is z.ZodType => typeof v === "object" && v !== null && "_zod" in v;
  const nameable = (s: z.ZodType) =>
    defOf(s).type === "object" || defOf(s).type === "enum" || s === modelSchemas.gtnhplannerDataSchema;
  for (const mod of [modelSchemas, datasetSchemas] as Array<Record<string, unknown>>) {
    for (const [name, v] of Object.entries(mod)) {
      if (!isZod(v) || names.has(v) || !nameable(v)) continue;
      const base = name.replace(/Schema$/, "");
      names.set(v, base.charAt(0).toUpperCase() + base.slice(1));
    }
  }
  const found = new Set<string>();
  const done = new Map<z.ZodType, Set<string>>();
  const leaves = new Set(["string", "number", "boolean", "enum", "literal", "null", "unknown", "any", "int", "transform"]);
  const walk = (s: z.ZodType, owner: string, props: string[]) => {
    const own = names.get(s);
    if (own !== undefined && own !== owner) return; // another named definition: walked from its own export
    const key = props.length === 0 ? owner : `${owner}.${props.join(".")}`;
    const at = done.get(s) ?? new Set<string>();
    if (at.has(key)) return;
    done.set(s, at.add(key));
    const d = defOf(s);
    for (const c of d.checks ?? []) if (c._zod.def.check === "custom") found.add(key);
    switch (d.type) {
      case "object":
        for (const [k, v] of Object.entries(d.shape ?? {})) walk(v, owner, [...props, k]);
        break;
      case "array":
        walk(d.element!, owner, props);
        break;
      case "record":
        walk(d.valueType!, owner, props);
        break;
      case "optional":
      case "nullable":
      case "default":
      case "prefault":
      case "readonly":
        walk(d.innerType!, owner, props);
        break;
      case "union":
        for (const o of d.options ?? []) walk(o, owner, props);
        break;
      case "pipe":
        found.add(key);
        walk(d.in!, owner, props);
        walk(d.out!, owner, props);
        break;
      case "lazy":
        walk(d.getter!(), owner, props);
        break;
      default:
        if (!leaves.has(d.type)) throw new Error(`${key}: the guard does not know the Zod type "${d.type}"`);
    }
  };
  for (const [s, name] of names) walk(s, name, []);
  return [...found].sort();
}

describe("every refinement and transform of the exported Zod schemas is described in the schema", () => {
  it("is exactly the list in overlays.ts", () => {
    expect(customPlaces()).toEqual(Object.keys(REFINEMENTS).sort());
  });
});
