/**
 * The view golden corpus (`conformance/view/<record>/<set>.json`): how a file is written and how a test reads one. The
 * layout, the envelope and the generator are in `conformance/view/README.md`. Node only; never import this from app
 * code.
 *
 * Every file is one envelope `{record, set, measure, cases | vectors}`:
 * - `cases`: `[{name, input, view}]`, an input record and the view the website's own code made of it;
 * - `vectors`: groups of plain arithmetic, `{group: [{...inputs, out}]}`;
 * - `measure`: the text measure the numbers were computed with: `none` when the record puts no text through a measure,
 *   `six-px` for the fixed test measure (six pixels a character, one and a half times that for the row figure).
 */
import { readdirSync, statSync } from "node:fs";
import path from "node:path";
import { conformancePath, readConformanceJson } from "@/test-support/conformance";

export type ViewMeasure = "none" | "six-px";

export interface ViewCase<I = unknown, V = unknown> {
  name: string;
  input: I;
  view: V;
}

/** The text of one corpus file: the envelope, 1-space indent, a final newline (the one spelling every file has). */
export function viewEnvelope(
  record: string,
  set: string,
  measure: ViewMeasure,
  body: { cases: unknown[] } | { vectors: unknown },
): string {
  return `${JSON.stringify({ record, set, measure, ...body }, null, 1)}\n`;
}

/** A file of cases, read from the tree. Fails when the envelope is not what the layout says. */
export function readViewCases<I, V>(
  record: string,
  set: string,
): { measure: ViewMeasure; cases: Array<ViewCase<I, V>> } {
  const file = readEnvelope(record, set);
  if (!Array.isArray(file.cases)) throw new Error(`view/${record}/${set}.json holds no cases`);
  return { measure: file.measure, cases: file.cases as Array<ViewCase<I, V>> };
}

/** A file of vectors, read from the tree. */
export function readViewVectors<T>(record: string, set: string): { measure: ViewMeasure; vectors: T } {
  const file = readEnvelope(record, set);
  if (file.vectors === undefined) throw new Error(`view/${record}/${set}.json holds no vectors`);
  return { measure: file.measure, vectors: file.vectors as T };
}

function readEnvelope(
  record: string,
  set: string,
): { measure: ViewMeasure; cases?: unknown; vectors?: unknown } {
  const file = readConformanceJson("view", record, `${set}.json`) as Record<string, unknown>;
  if (file.record !== record || file.set !== set) {
    throw new Error(`view/${record}/${set}.json names itself ${String(file.record)}/${String(file.set)}`);
  }
  if (file.measure !== "none" && file.measure !== "six-px") {
    throw new Error(`view/${record}/${set}.json names the measure ${String(file.measure)}`);
  }
  if ((file.cases === undefined) === (file.vectors === undefined)) {
    throw new Error(`view/${record}/${set}.json holds exactly one of cases and vectors`);
  }
  return file as { measure: ViewMeasure; cases?: unknown; vectors?: unknown };
}

/**
 * Files under `conformance/view/` no record family makes: the shape table of the Java records, written by
 * `tools/web-core/gen-records.mjs --write-shapes` and checked by `tools/web-core/gen-records.test.mjs`.
 */
export const VIEW_TOOL_FILES = ["records.json"];

/** Every file the families make under `conformance/view/`, as paths under it with `/`, sorted (README.md and the tool files excluded). */
export function viewTreeFiles(): string[] {
  const root = conformancePath("view");
  const out: string[] = [];
  const walk = (dir: string) => {
    for (const name of readdirSync(dir).sort()) {
      const full = path.join(dir, name);
      if (statSync(full).isDirectory()) walk(full);
      else if (name !== "README.md") out.push(path.relative(root, full).split(path.sep).join("/"));
    }
  };
  walk(root);
  return out.filter((name) => !VIEW_TOOL_FILES.includes(name)).sort();
}
