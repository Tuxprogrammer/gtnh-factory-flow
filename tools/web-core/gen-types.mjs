#!/usr/bin/env node
// Writes src/lib/core/exports.generated.ts: the TypeScript declarations of the compiled core's exports, read from the
// Java sources that define them (jvm/web/src/main/java/com/gtnhplanner/web). Gitignored like the view tokens: every
// build that needs it runs this first (the npm pre-hooks and the vitest global setup), so a declaration cannot be
// written by hand and cannot go stale. src/lib/core/loader.test.ts checks the same declarations against the compiled
// module, which is what catches a build that does not match its sources.
//
// What it reads (the rules ExportConventionTest enforces on the Java side):
//   exports/<Module>Exports.java   every `@JSExport public static` method is one function of the namespace <Module>Exports
//   <Main>.java                    the main class of each module (jvm/web/modules.txt: WebCore, WebSolver): its
//                                  `@JSExport public static` methods are top-level functions (names start webCore)
// The result is one merged CoreExports (what core() returns once the modules are loaded) and CORE_MODULES, which says
// which namespaces and functions each module holds, so the loader can name the module a missing one belongs to.
// Parameter and return types are int, double, boolean, String, void or JSObject; anything else is an error here too.
// A String return marked `@Nullable` is typed `string | null`.
//
//   node tools/web-core/gen-types.mjs            write the file
//   node tools/web-core/gen-types.mjs --check    exit 1 when the file on disk is not what the sources give
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { moduleOfNamespace, readModules } from "./modules.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const webDir = path.join(root, "jvm", "web", "src", "main", "java", "com", "gtnhplanner", "web");
export const OUTPUT = path.join(root, "src", "lib", "core", "exports.generated.ts");

const TS_TYPES = {
  int: "number",
  double: "number",
  boolean: "boolean",
  String: "string",
  void: "void",
  JSObject: "unknown",
};

/** One Java type as TypeScript, or an error naming the method. */
function tsType(javaType, where, jsObjects = new Set()) {
  // A JSObject declared in the same source (`interface X extends JSObject`) is a plain JavaScript object.
  const t = jsObjects.has(javaType) ? "Record<string, unknown>" : TS_TYPES[javaType];
  if (t === undefined)
    throw new Error(
      `${where}: type ${javaType} cannot cross the boundary (int, double, boolean, String, void, JSObject)`,
    );
  return t;
}

/** A Javadoc block as one short paragraph for a JSDoc comment, or "". */
function summary(block) {
  if (!block) return "";
  const lines = block
    .trim()
    .replace(/^\/\*\*|\*\/$/g, "")
    .split("\n")
    .map((l) => l.replace(/^\s*\*? ?/, ""));
  const paragraph = [];
  for (const l of lines) {
    if (l.trim() === "" || l.trim() === "<p>") {
      if (paragraph.length) break;
      continue;
    }
    paragraph.push(l.trim());
  }
  return paragraph
    .join(" ")
    .replace(/\{@code ([^}]*)\}/g, "$1")
    .replace(/\{@link ([^}]*)\}/g, "$1")
    .replace(/<\/?[a-z]+>/g, "")
    .replace(/\*\//g, "* /")
    .trim();
}

const METHOD =
  /(\/\*\*(?:(?!\*\/)[\s\S])*\*\/\s*)?@JSExport\s*(@Nullable\s*)?public\s+static\s+(\w+)\s+(\w+)\s*\(([^)]*)\)/g;

/** The exported functions of one Java source: [{ name, params: [{name, type}], returns, nullable, doc }]. */
export function parseExports(source, file) {
  const out = [];
  const jsObjects = new Set(
    [...source.matchAll(/interface\s+(\w+)\s+extends\s+JSObject/g)].map((m) => m[1]),
  );
  for (const m of source.matchAll(METHOD)) {
    const [, doc, nullable, ret, name, rawParams] = m;
    const where = `${file}: ${name}`;
    const params = rawParams
      .split(",")
      .map((p) => p.trim())
      .filter(Boolean)
      .map((p) => {
        const parts = p.replace(/^final\s+/, "").split(/\s+/);
        if (parts.length !== 2) throw new Error(`${where}: cannot read parameter '${p}'`);
        return { name: parts[1], type: tsType(parts[0], `${where}(${parts[1]})`, jsObjects) };
      });
    if (nullable && ret !== "String")
      throw new Error(`${where}: @Nullable is read on String returns only`);
    const returns = tsType(ret, where, jsObjects) + (nullable ? " | null" : "");
    out.push({ name, params, returns, doc: summary(doc) });
  }
  const annotated = (source.match(/@JSExport/g) ?? []).length;
  if (annotated !== out.length)
    throw new Error(
      `${file}: ${annotated} @JSExport but ${out.length} read; an export must be 'public static <type> name(...)'`,
    );
  return out;
}

function signature(fn) {
  const params = fn.params.map((p) => `${p.name}: ${p.type}`).join(", ");
  return `${fn.name}(${params}): ${fn.returns};`;
}

function member(fn, indent) {
  const doc = fn.doc ? `${indent}/** ${fn.doc} */\n` : "";
  return `${doc}${indent}${signature(fn)}\n`;
}

/** The sources' export map: { namespaces: { BaseExports: [fn...] }, top: [fn...] }. */
export function readSources(dir = webDir, modulesFile) {
  const exportsDir = path.join(dir, "exports");
  const namespaces = {};
  const classes = fs.existsSync(exportsDir)
    ? fs
        .readdirSync(exportsDir)
        .filter((f) => f.endsWith("Exports.java"))
        .sort()
    : [];
  for (const file of classes) {
    const name = file.replace(/\.java$/, "");
    namespaces[name] = parseExports(fs.readFileSync(path.join(exportsDir, file), "utf8"), file);
  }
  const declared = readModules(modulesFile);
  const modules = declared.map((m) => {
    const main = path.join(dir, `${m.main}.java`);
    return {
      name: m.name,
      namespaces: Object.keys(namespaces).filter((ns) => moduleOfNamespace(declared, ns) === m.name),
      top: fs.existsSync(main) ? parseExports(fs.readFileSync(main, "utf8"), `${m.main}.java`) : [],
    };
  });
  // The merged view lists each top-level function once: every module answers webCoreExportVersion.
  const top = [];
  for (const m of modules) for (const fn of m.top) if (!top.some((t) => t.name === fn.name)) top.push(fn);
  return { namespaces, top, modules, found: classes.length > 0 };
}

/** The text of exports.generated.ts for these sources. */
export function render({ namespaces, top, modules, found }) {
  let text =
    "// Generated by tools/web-core/gen-types.mjs from the Java exports under jvm/web (do not edit; gitignored).\n" +
    "// The declarations of the functions the compiled core exports, by namespace (one per <Module>Exports class).\n\n";
  if (!found) {
    // A checkout without the jvm tree: the loader still types, it just knows no function names.
    return (
      text +
      "export type CoreFunction = (...args: never[]) => unknown;\n" +
      "export interface CoreExports {\n  [name: string]: CoreFunction | { [name: string]: CoreFunction };\n}\n" +
      "export const CORE_EXPORT_NAMES: { namespaces: Record<string, string[]>; top: string[] } = { namespaces: {}, top: [] };\n" +
      "export type CoreModuleName = string;\n" +
      "export const CORE_MODULES: Record<string, { namespaces: string[]; top: string[] }> = {};\n"
    );
  }
  for (const [ns, fns] of Object.entries(namespaces)) {
    text += `export interface ${ns} {\n${fns.map((f) => member(f, "  ")).join("")}}\n\n`;
  }
  text += "export interface CoreExports {\n";
  for (const ns of Object.keys(namespaces)) text += `  ${ns}: ${ns};\n`;
  for (const fn of top) text += member(fn, "  ");
  text += "}\n\n";
  text +=
    "/** The function names the sources declare, for the test that compares them with the compiled module. */\n";
  text += "export const CORE_EXPORT_NAMES = {\n  namespaces: {\n";
  for (const [ns, fns] of Object.entries(namespaces))
    text += `    ${ns}: [${fns.map((f) => JSON.stringify(f.name)).join(", ")}],\n`;
  text += `  },\n  top: [${top.map((f) => JSON.stringify(f.name)).join(", ")}],\n} as const;\n\n`;
  text += "/** The modules of the build (jvm/web/modules.txt) and what each exports. A page loads a module on first use. */\n";
  text += `export type CoreModuleName = ${modules.map((m) => JSON.stringify(m.name)).join(" | ")};\n`;
  text += "export const CORE_MODULES = {\n";
  for (const m of modules) {
    text += `  ${JSON.stringify(m.name)}: {\n    namespaces: [${m.namespaces.map((n) => JSON.stringify(n)).join(", ")}],\n`;
    text += `    top: [${m.top.map((f) => JSON.stringify(f.name)).join(", ")}],\n  },\n`;
  }
  text += "} as const satisfies Record<CoreModuleName, { namespaces: readonly string[]; top: readonly string[] }>;\n";
  return text;
}

export function generate() {
  return render(readSources());
}

/** Writes the file when it differs; returns whether it changed. */
export function run() {
  const text = generate();
  const current = fs.existsSync(OUTPUT) ? fs.readFileSync(OUTPUT, "utf8") : null;
  if (current === text) return false;
  fs.mkdirSync(path.dirname(OUTPUT), { recursive: true });
  fs.writeFileSync(OUTPUT, text);
  return true;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.includes("--check")) {
    const current = fs.existsSync(OUTPUT) ? fs.readFileSync(OUTPUT, "utf8") : null;
    if (current !== generate()) {
      console.error(
        "src/lib/core/exports.generated.ts is missing or differs from the Java exports; run node tools/web-core/gen-types.mjs",
      );
      process.exit(1);
    }
  } else if (run()) {
    console.log(`wrote ${path.relative(root, OUTPUT)}`);
  }
}
