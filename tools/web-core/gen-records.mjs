#!/usr/bin/env node
// Writes src/lib/core/records.generated.ts: the TypeScript declarations of the view records (the records of
// jvm/core/core-view that cross to a host as JSON), read from their Java sources. Gitignored like exports.generated.ts:
// gen-types.mjs calls run() on every build that needs it, so a declaration is never written by hand and never stale.
//
// Which records: the roots in jvm/core/core-view/view-records.json and every record and enum they name. A host that
// draws a view needs its record and nothing else, so the declarations stop where the wire does.
//
// The record convention it reads (the one `Json` writes, `conformance/view/README.md`):
//   int, double, Integer, Double, boolean, Boolean   number or boolean
//   String, Uuid                                     string
//   an enum                                          a union of its constant names
//   List<T>                                          T[]
//   Map<String, T>                                   Record<string, T>
//   a nested record                                  an interface, named by its outer classes (RailView.Dial: RailViewDial)
//   @Nullable on a component                         optional: the key is omitted on the wire, `name?: T`
// Anything else (long, float, arrays, Object, a type outside the closure) is an error naming the component.
//
// The same read is written as conformance/view/records.json (--write-shapes): the shape table the JVM test
// ViewRecordsTest compares with what the compiler (reflection) says the records are, and the table the vitest check
// validates every corpus case against. A parser that read a record wrongly cannot agree with both.
//
//   node tools/web-core/gen-records.mjs                  write src/lib/core/records.generated.ts
//   node tools/web-core/gen-records.mjs --write-shapes   write conformance/view/records.json
//   node tools/web-core/gen-records.mjs --check          exit 1 when either file differs from the sources
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const moduleDir = path.join(root, "jvm", "core", "core-view");
export const VIEW_SOURCES = path.join(
  moduleDir,
  "src",
  "main",
  "java",
  "com",
  "gtnhplanner",
  "core",
  "view",
);
export const REGISTRY = path.join(moduleDir, "view-records.json");
export const OUTPUT = path.join(root, "src", "lib", "core", "records.generated.ts");
export const SHAPES = path.join(root, "conformance", "view", "records.json");
const PACKAGE = "com.gtnhplanner.core.view";

const SCALARS = {
  int: "number",
  Integer: "number",
  double: "number",
  Double: "number",
  boolean: "boolean",
  Boolean: "boolean",
  String: "string",
  Uuid: "string",
};

// region Reading Java

/** The tokens of a Java source: identifiers, one-character punctuation, literals, and the Javadoc blocks. */
export function tokenize(src, file = "source") {
  const toks = [];
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if (/\s/.test(c)) {
      i++;
    } else if (src.startsWith("//", i)) {
      const end = src.indexOf("\n", i);
      i = end < 0 ? src.length : end + 1;
    } else if (src.startsWith("/*", i)) {
      const end = src.indexOf("*/", i + 2);
      if (end < 0) throw new Error(`${file}: a comment never ends`);
      if (src.startsWith("/**", i)) toks.push({ t: "doc", v: src.slice(i, end + 2) });
      i = end + 2;
    } else if (src.startsWith('"""', i)) {
      const end = src.indexOf('"""', i + 3);
      if (end < 0) throw new Error(`${file}: a text block never ends`);
      toks.push({ t: "lit", v: "" });
      i = end + 3;
    } else if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < src.length && src[j] !== c) j += src[j] === "\\" ? 2 : 1;
      toks.push({ t: "lit", v: "" });
      i = j + 1;
    } else if (/[A-Za-z_$]/.test(c)) {
      let j = i + 1;
      while (j < src.length && /[A-Za-z0-9_$]/.test(src[j])) j++;
      toks.push({ t: "id", v: src.slice(i, j) });
      i = j;
    } else if (/[0-9]/.test(c)) {
      let j = i + 1;
      while (j < src.length && /[0-9A-Za-z_.]/.test(src[j])) j++;
      toks.push({ t: "lit", v: "" });
      i = j;
    } else {
      toks.push({ t: "p", v: c });
      i++;
    }
  }
  return toks;
}

const isP = (tok, v) => tok && tok.t === "p" && tok.v === v;

/** The index of the token closing the bracket at `open` ("(" ")" or "<" ">"), or an error. */
function closing(toks, open, file) {
  const pair = toks[open].v === "(" ? [")", "("] : [">", "<"];
  let level = 0;
  for (let i = open; i < toks.length; i++) {
    if (isP(toks[i], pair[1])) level++;
    else if (isP(toks[i], pair[0]) && --level === 0) return i;
  }
  throw new Error(`${file}: a ${toks[open].v} is never closed`);
}

/** The tokens of a list split at its top-level commas (no split inside (), <> or {}). */
function splitTop(list) {
  const out = [[]];
  let level = 0;
  for (const tok of list) {
    if (tok.t === "p" && "(<{".includes(tok.v)) level++;
    else if (tok.t === "p" && ")>}".includes(tok.v)) level--;
    if (level === 0 && isP(tok, ",")) out.push([]);
    else out[out.length - 1].push(tok);
  }
  return out.filter((part) => part.length > 0);
}

/** One type in a component: { name, args } with the name as written (dots kept). */
function parseType(toks, at, where) {
  let name = "";
  let i = at;
  while (toks[i] && toks[i].t === "id") {
    name += toks[i].v;
    i++;
    if (isP(toks[i], ".") && toks[i + 1] && toks[i + 1].t === "id") {
      name += ".";
      i++;
    } else break;
  }
  if (name === "") throw new Error(`${where}: cannot read the type`);
  const args = [];
  if (isP(toks[i], "<")) {
    i++;
    for (;;) {
      const [arg, next] = parseType(toks, i, where);
      args.push(arg);
      i = next;
      if (isP(toks[i], ",")) i++;
      else break;
    }
    if (!isP(toks[i], ">")) throw new Error(`${where}: cannot read the type arguments of ${name}`);
    i++;
  }
  if (isP(toks[i], "[")) throw new Error(`${where}: an array is not a wire type; use a List`);
  return [{ name, args }, i];
}

/** One component: { name, type, nullable }. */
function parseComponent(part, where) {
  const toks = [...part];
  let nullable = false;
  while (isP(toks[0], "@")) {
    toks.shift();
    let annotation = toks.shift().v;
    while (isP(toks[0], ".") && toks[1] && toks[1].t === "id") annotation += toks.shift().v + toks.shift().v;
    if (isP(toks[0], "(")) toks.splice(0, closing(toks, 0, where) + 1);
    if (annotation === "Nullable" || annotation.endsWith(".Nullable")) nullable = true;
  }
  if (toks[0] && toks[0].v === "final") toks.shift();
  const last = toks[toks.length - 1];
  if (!last || last.t !== "id") throw new Error(`${where}: cannot read a component`);
  const [type, end] = parseType(toks.slice(0, -1), 0, where);
  if (end !== toks.length - 1) throw new Error(`${where}: cannot read the type of ${last.v}`);
  return { name: last.v, type, nullable };
}

/** A Javadoc block as { summary, params: { name: text } }. */
function docOf(block) {
  if (!block) return { summary: "", params: {} };
  const clean = (s) =>
    s
      .replace(/\{@code ([^}]*)\}/g, "$1")
      .replace(/\{@link ([^}]*)\}/g, "$1")
      .replace(/<\/?[a-z]+>/g, "")
      .replace(/\*\//g, "* /")
      .replace(/\s+/g, " ")
      .trim();
  const lines = block
    .replace(/^\/\*\*|\*\/$/g, "")
    .split("\n")
    .map((l) => l.replace(/^\s*\*? ?/, "").trimEnd());
  const body = [];
  const params = {};
  let current = null;
  for (const line of lines) {
    const param = /^@param\s+(\w+)\s*(.*)$/.exec(line.trim());
    if (param) {
      current = param[1];
      params[current] = param[2];
    } else if (line.trim().startsWith("@")) {
      current = null;
    } else if (current) {
      params[current] += ` ${line.trim()}`;
    } else {
      body.push(line);
    }
  }
  const first = [];
  for (const line of body) {
    if (line.trim() === "" || line.trim() === "<p>") {
      if (first.length) break;
      continue;
    }
    first.push(line.trim());
  }
  return {
    summary: clean(first.join(" ")),
    params: Object.fromEntries(Object.entries(params).map(([k, v]) => [k, clean(v)])),
  };
}

/**
 * The records and enums of one Java source: records { outer, name, components, doc }, enums { outer, name, values }.
 * `outer` is the list of enclosing type names. Records declared inside a method are not members and are left out.
 */
export function parseSource(src, file = "source") {
  const toks = tokenize(src, file);
  const records = [];
  const enums = [];
  const stack = [];
  let depth = 0;
  let pending = null;
  let doc = null;
  for (let i = 0; i < toks.length; i++) {
    const tok = toks[i];
    if (tok.t === "doc") {
      doc = tok.v;
    } else if (isP(tok, ";")) {
      doc = null;
    } else if (isP(tok, "{")) {
      depth++;
      if (pending) {
        stack.push({ name: pending.name, depth });
        if (pending.kind === "enum") {
          let level = 0;
          let expect = true;
          const values = [];
          for (let j = i + 1; j < toks.length; j++) {
            const t = toks[j];
            if (t.t === "p" && "({".includes(t.v)) level++;
            else if (t.t === "p" && ")}".includes(t.v)) {
              if (level === 0) break;
              level--;
            } else if (level === 0 && isP(t, ";")) break;
            else if (level === 0 && isP(t, ",")) expect = true;
            else if (level === 0 && t.t === "id" && expect) {
              values.push(t.v);
              expect = false;
            }
          }
          enums.push({ outer: stack.slice(0, -1).map((s) => s.name), name: pending.name, values });
        }
        pending = null;
      }
      doc = null;
    } else if (isP(tok, "}")) {
      if (stack.length && stack[stack.length - 1].depth === depth) stack.pop();
      depth--;
      doc = null;
    } else if (
      tok.t === "id" &&
      ["class", "interface", "enum", "record"].includes(tok.v) &&
      !isP(toks[i - 1], ".") &&
      toks[i + 1] &&
      toks[i + 1].t === "id" &&
      (tok.v !== "record" || isP(toks[i + 2], "(") || isP(toks[i + 2], "<"))
    ) {
      const name = toks[i + 1].v;
      const member = depth === (stack.length ? stack[stack.length - 1].depth : 0);
      if (tok.v === "record") {
        let open = i + 2;
        if (isP(toks[open], "<")) open = closing(toks, open, file) + 1;
        const close = closing(toks, open, file);
        if (member) {
          const where = `${file}: ${name}`;
          records.push({
            outer: stack.map((s) => s.name),
            name,
            doc: docOf(doc),
            components: splitTop(toks.slice(open + 1, close)).map((part) => parseComponent(part, where)),
          });
        }
        i = close;
      }
      pending = { name, kind: tok.v };
      doc = null;
    }
  }
  return { records, enums };
}

/** Every record and enum under the view sources: { records: Map<flat, rec>, enums: Map<flat, enum> }. */
export function readSources(dir = VIEW_SOURCES) {
  const records = new Map();
  const enums = new Map();
  const walk = (d) => {
    for (const entry of fs.readdirSync(d, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      const full = path.join(d, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith(".java")) {
        const rel = path.relative(dir, full).split(path.sep);
        const pkg = [PACKAGE, ...rel.slice(0, -1)].join(".");
        const parsed = parseSource(fs.readFileSync(full, "utf8"), rel.join("/"));
        for (const [list, into, kind] of [
          [parsed.records, records, "record"],
          [parsed.enums, enums, "enum"],
        ]) {
          for (const item of list) {
            const flat = [...item.outer, item.name].join("");
            if (records.has(flat) || enums.has(flat))
              throw new Error(`${rel.join("/")}: two view types are named ${flat}`);
            const binary = `${pkg}.${[...item.outer, item.name].join("$")}`;
            into.set(flat, { ...item, flat, class: binary, kind, file: rel.join("/") });
          }
        }
      }
    }
  };
  walk(dir);
  return { records, enums };
}

// endregion

// region Closure

/** The flat name a written type name means, seen from the record `from`, or undefined. */
function resolve(written, from, sources) {
  const all = new Set([...sources.records.keys(), ...sources.enums.keys()]);
  const parts = written.split(".");
  // Seen from inside: the record's own nested types, then its outer chain, then the package.
  const chain = [...from.outer, from.name];
  for (let n = chain.length; n >= 0; n--) {
    const flat = [...chain.slice(0, n), ...parts].join("");
    if (all.has(flat)) return flat;
  }
  // A name that is unique among every nested type of the sources.
  const hits = [...all].filter((flat) => flat.endsWith(parts.join("")));
  return hits.length === 1 ? hits[0] : undefined;
}

/** A component's type as its shape text: "string", "number", "boolean", "Name", "Name[]", "Record<string, Name>". */
function shapeOf(type, from, sources, where, used) {
  const scalar = SCALARS[type.name];
  if (scalar !== undefined && type.args.length === 0) return scalar;
  if (type.name === "List" && type.args.length === 1) {
    return `${shapeOf(type.args[0], from, sources, where, used)}[]`;
  }
  if (type.name === "Map" && type.args.length === 2) {
    if (type.args[0].name !== "String" || type.args[0].args.length)
      throw new Error(`${where}: a Map on the wire has String keys`);
    return `Record<string, ${shapeOf(type.args[1], from, sources, where, used)}>`;
  }
  if (type.args.length === 0) {
    const flat = resolve(type.name, from, sources);
    if (flat !== undefined) {
      used.add(flat);
      return flat;
    }
  }
  throw new Error(
    `${where}: ${type.name} is not a wire type (int, double, boolean, String, Uuid, an enum, a record, List, Map<String, T>)`,
  );
}

/** The roots of the registry. */
export function readRegistry(file = REGISTRY) {
  const registry = JSON.parse(fs.readFileSync(file, "utf8"));
  if (!Array.isArray(registry.roots)) throw new Error(`${file}: no "roots"`);
  return registry;
}

/**
 * The shape table of the roots' closure: { enums: { Name: { class, values } }, records: { Name: { class, components:
 * [[name, type]] } } } with `?` after an optional type and the names sorted, plus the docs for the declarations.
 */
export function closure(sources, roots) {
  const table = { enums: {}, records: {} };
  const docs = {};
  const todo = [...roots];
  while (todo.length) {
    const flat = todo.shift();
    if (table.records[flat] || table.enums[flat]) continue;
    const en = sources.enums.get(flat);
    if (en) {
      table.enums[flat] = { class: en.class, values: en.values };
      continue;
    }
    const rec = sources.records.get(flat);
    if (!rec) throw new Error(`view-records.json names ${flat}, which is not a record or enum of the view sources`);
    const used = new Set();
    const components = rec.components.map((c) => {
      const where = `${rec.file}: ${flat}.${c.name}`;
      const shape = shapeOf(c.type, rec, sources, where, used);
      return [c.name, c.nullable ? `${shape}?` : shape];
    });
    table.records[flat] = { class: rec.class, components };
    docs[flat] = rec.doc;
    todo.push(...used);
  }
  const sorted = (o) => Object.fromEntries(Object.entries(o).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
  table.enums = sorted(table.enums);
  table.records = sorted(table.records);
  return { table, docs };
}

// endregion

// region Output

/** The tracked shape table, one record a line so a merge of two new records stays a merge. */
export function shapesText(table) {
  const lines = ["{", ' "enums": {'];
  const enums = Object.entries(table.enums);
  enums.forEach(([name, e], i) =>
    lines.push(`  ${JSON.stringify(name)}: ${JSON.stringify(e)}${i < enums.length - 1 ? "," : ""}`),
  );
  lines.push(" },", ' "records": {');
  const records = Object.entries(table.records);
  records.forEach(([name, r], i) =>
    lines.push(`  ${JSON.stringify(name)}: ${JSON.stringify(r)}${i < records.length - 1 ? "," : ""}`),
  );
  lines.push(" }", "}", "");
  return lines.join("\n");
}

function tsType(shape) {
  const m = /^Record<string, (.*)>$/.exec(shape);
  if (m) return `Record<string, ${tsType(m[1])}>`;
  if (shape.endsWith("[]")) return `${tsType(shape.slice(0, -2))}[]`;
  return shape;
}

/** The text of records.generated.ts. */
export function render(table, docs) {
  let text =
    "// Generated by tools/web-core/gen-records.mjs from the Java view records under jvm/core/core-view (do not edit; gitignored).\n" +
    "// The TypeScript declarations of the records a view crosses to a host as: one interface per record, the JSON the\n" +
    "// core's Json writer writes. An optional (@Nullable) component is a key left out, never null.\n\n";
  for (const [name, e] of Object.entries(table.enums)) {
    text += `export type ${name} = ${e.values.map((v) => JSON.stringify(v)).join(" | ")};\n\n`;
  }
  for (const [name, r] of Object.entries(table.records)) {
    const doc = docs[name];
    if (doc && doc.summary) text += `/** ${doc.summary} */\n`;
    text += `export interface ${name} {\n`;
    for (const [field, shape] of r.components) {
      const optional = shape.endsWith("?");
      const note = doc && doc.params[field];
      if (note) text += `  /** ${note} */\n`;
      text += `  ${field}${optional ? "?" : ""}: ${tsType(optional ? shape.slice(0, -1) : shape)};\n`;
    }
    text += "}\n\n";
  }
  text += "/** The shape table the declarations were made from (conformance/view/records.json). */\n";
  text += `export const VIEW_RECORDS = ${JSON.stringify(table, null, 1)} as const;\n`;
  return text;
}

function build() {
  const registry = readRegistry();
  return { registry, ...closure(readSources(), registry.roots) };
}

export function generate() {
  const { table, docs } = build();
  return { ts: render(table, docs), shapes: shapesText(table) };
}

/** Writes the declarations when they differ (no JDK-free checkout is a problem: the sources are text). */
export function run() {
  if (!fs.existsSync(REGISTRY) || !fs.existsSync(VIEW_SOURCES)) return false;
  const { ts } = generate();
  const current = fs.existsSync(OUTPUT) ? fs.readFileSync(OUTPUT, "utf8") : null;
  if (current === ts) return false;
  fs.mkdirSync(path.dirname(OUTPUT), { recursive: true });
  fs.writeFileSync(OUTPUT, ts);
  return true;
}

// endregion

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { ts, shapes } = generate();
  if (process.argv.includes("--check")) {
    const read = (f) => (fs.existsSync(f) ? fs.readFileSync(f, "utf8") : null);
    const bad = [
      [OUTPUT, ts],
      [SHAPES, shapes],
    ].filter(([f, text]) => read(f) !== text);
    if (bad.length) {
      for (const [f] of bad)
        console.error(`${path.relative(root, f)} is missing or differs from the Java view records`);
      console.error("run node tools/web-core/gen-records.mjs --write-shapes (and without a flag)");
      process.exit(1);
    }
  } else if (process.argv.includes("--write-shapes")) {
    fs.mkdirSync(path.dirname(SHAPES), { recursive: true });
    fs.writeFileSync(SHAPES, shapes);
    console.log(`wrote ${path.relative(root, SHAPES)}`);
  } else if (run()) {
    console.log(`wrote ${path.relative(root, OUTPUT)}`);
  }
}
