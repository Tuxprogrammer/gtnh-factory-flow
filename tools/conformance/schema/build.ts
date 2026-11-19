/**
 * Builds the JSON Schema files in `conformance/schema/` from the website's Zod schemas, which stay the one source of
 * truth for the data that crosses between the website and the game. Nothing here changes what a Zod schema accepts;
 * it only describes it. The files are written by `write.ts` and checked byte for byte by
 * `src/test-support/schema-drift.test.ts`.
 *
 * Direction and limits are explained in `conformance/schema/README.md`.
 */
import { z } from "zod";

import * as datasetSchemas from "../../../src/lib/datasets/schemas";
import * as modelSchemas from "../../../src/lib/model/schemas";
import { OVERLAYS, applyOverlays, type Overlay } from "./overlays";

export type SchemaFile = {
  /** File name under `conformance/schema/`. */
  file: string;
  /** What the file describes, as its `title`. */
  title: string;
  /** The root: what one document of this file is. */
  root: z.ZodType;
  /** The modules whose exported schemas are named in `$defs`. */
  modules: Array<Record<string, unknown>>;
  overlays?: readonly Overlay[];
};

const isZod = (v: unknown): v is z.ZodType =>
  typeof v === "object" && v !== null && "_zod" in (v as Record<string, unknown>);

/** `factoryNodeSchema` -> `FactoryNode`. */
export function defName(exportName: string): string {
  const base = exportName.replace(/Schema$/, "");
  return base.charAt(0).toUpperCase() + base.slice(1);
}

/** Exports that are shared pieces worth a name in `$defs`: whole objects and enums, not wrappers or one-line strings. */
function nameable(s: z.ZodType): boolean {
  const type = (s as unknown as { _zod: { def: { type: string } } })._zod.def.type;
  return type === "object" || type === "enum" || s === modelSchemas.gtnhplannerDataSchema;
}

export const SCHEMA_FILES: SchemaFile[] = [
  {
    file: "plan.schema.json",
    title: "Factory Flow plan (project) JSON",
    root: modelSchemas.factoryProjectSchema,
    modules: [modelSchemas],
  },
  {
    file: "board-selection.schema.json",
    title: "Factory Flow board selection (blueprint payload)",
    root: modelSchemas.boardSelectionPayloadSchema,
    modules: [modelSchemas],
  },
  {
    file: "dataset.schema.json",
    title: "Recipe dataset",
    root: datasetSchemas.recipeDatasetSchema,
    modules: [modelSchemas, datasetSchemas],
  },
  {
    file: "dataset-manifest.schema.json",
    title: "Recipe dataset manifest",
    root: datasetSchemas.datasetManifestSchema,
    modules: [datasetSchemas],
  },
];

/** The JSON Schema for one file, as a plain object. */
export function buildSchema(spec: SchemaFile): Record<string, unknown> {
  const registry = z.registry<{ id: string }>();
  const taken = new Set<z.ZodType>();
  for (const mod of spec.modules) {
    for (const [name, value] of Object.entries(mod)) {
      if (!isZod(value) || taken.has(value) || !nameable(value)) continue;
      taken.add(value);
      registry.add(value, { id: defName(name) });
    }
  }
  const raw = z.toJSONSchema(spec.root, {
    target: "draft-2020-12",
    // What a reader must accept: transforms read as their input, `.default()` fields as optional.
    io: "input",
    unrepresentable: "throw",
    metadata: registry,
    reused: "inline",
  }) as Record<string, unknown>;
  const withTitle = { ...raw, title: spec.title };
  const rootHasPath = (path: string[]) => {
    let node: unknown = withTitle;
    for (const step of path) {
      if (typeof node !== "object" || node === null || !(step in node)) return false;
      node = (node as Record<string, unknown>)[step];
    }
    return true;
  };
  return applyOverlays(withTitle, spec.overlays ?? OVERLAYS, rootHasPath);
}

/** The file's text: two-space JSON and a newline, so a diff is readable. */
export function render(schema: Record<string, unknown>): string {
  return `${JSON.stringify(schema, null, 2)}\n`;
}

/** Every schema file, name to text. */
export function buildAll(): Map<string, string> {
  const out = new Map<string, string>();
  for (const spec of SCHEMA_FILES) out.set(spec.file, render(buildSchema(spec)));
  return out;
}
