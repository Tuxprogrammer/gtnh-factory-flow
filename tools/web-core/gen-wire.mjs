#!/usr/bin/env node
// Writes the Java codec of the records that cross between the website and the compiled core as JavaScript objects
// (jvm/web/README.md, "Records across the boundary"), from the one description of each record: its Java source, read
// by gen-records.mjs into the shape table (conformance/view/records.json). Which records: jvm/web/wire-records.json,
// "in" (the website hands the object in, Java reads it) and "out" (Java builds the object, the website reads it).
//
//   jvm/web/src/main/java/com/gtnhplanner/web/wire/ViewJs.java   tracked (the JVM build needs no Node), written only
//                                                                by this command; gen-wire.test.mjs fails when it is stale
//
// The website side needs no code: the object it hands in and the objects it gets back are the records' TypeScript
// declarations (records.generated.ts), the same objects JSON.parse gives of the records' JSON, key order included.
// What the Java does with an object:
//   read   one dedicated accessor per component (`@JSBody(params = "o", script = "return o.name;")`): a string, a
//          number, a boolean or an object (a nested record, a list, a map) crosses as itself, no text in between; an
//          optional is read as absent when its key is missing (undefined) or null; an enum is read as its ordinal
//          through a name table in the accessor, a list item by index, a map by its keys
//   build  one factory per record whose parameters are its components in order (`@JSBody` with a body that sets each
//          key, an absent optional left out), lists built by pushing items, enums as their names, maps by key
//
//   node tools/web-core/gen-wire.mjs           write the Java file when it differs
//   node tools/web-core/gen-wire.mjs --check   exit 1 when it differs from the sources
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { closure, readSources } from "./gen-records.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
export const WIRE_REGISTRY = path.join(root, "jvm", "web", "wire-records.json");
export const JAVA_OUTPUT = path.join(
  root,
  ..."jvm/web/src/main/java/com/gtnhplanner/web/wire/ViewJs.java".split("/"),
);

/** The registry: { in: [record], out: [record] } by flat name (CardInputCase, CardView). */
export function readWireRegistry(file = WIRE_REGISTRY) {
  const registry = JSON.parse(fs.readFileSync(file, "utf8"));
  for (const side of ["in", "out"])
    if (!Array.isArray(registry[side])) throw new Error(`${file}: no "${side}" list`);
  return registry;
}

/**
 * Every record and enum one side needs, as { records: [{ flat, javaClass, components: [{ name, shape, type,
 * nullable }] }], enums: [{ flat, javaClass, values }] }: the closure of `roots` in the shape table, with each
 * component's Java type (the table says "number" for int and double alike; Java needs which).
 */
export function plan(sources, roots) {
  const { table } = closure(sources, roots);
  const records = Object.entries(table.records).map(([flat, rec]) => {
    const source = sources.records.get(flat);
    if (source.components.length !== rec.components.length)
      throw new Error(`${flat}: the table and the source disagree on its components`);
    return {
      flat,
      javaClass: rec.class.replace(/\$/g, "."),
      components: rec.components.map(([name, shape], i) => {
        const c = source.components[i];
        if (c.name !== name) throw new Error(`${flat}.${name}: the table and the source disagree`);
        const nullable = shape.endsWith("?");
        return { name, shape: nullable ? shape.slice(0, -1) : shape, type: c.type, nullable };
      }),
    };
  });
  const enums = Object.entries(table.enums).map(([flat, e]) => ({
    flat,
    javaClass: e.class.replace(/\$/g, "."),
    values: e.values,
  }));
  return { records, enums };
}

const lowerFirst = (s) => s[0].toLowerCase() + s.slice(1);
const upperFirst = (s) => s[0].toUpperCase() + s.slice(1);
const quote = (s) => JSON.stringify(s);

/** The kind of a shape: "string", "number", "boolean", "enum", "record", "list", "map". */
function kindOf(shape, model) {
  if (shape.endsWith("[]")) return "list";
  if (shape.startsWith("Record<")) return "map";
  if (["string", "number", "boolean"].includes(shape)) return shape;
  if (model.enums.some((e) => e.flat === shape)) return "enum";
  return "record";
}

const itemOf = (shape) =>
  shape.endsWith("[]") ? shape.slice(0, -2) : /^Record<string, (.*)>$/.exec(shape)[1];

/** The Java type of a value of `shape` whose declared Java type is `type`. */
function javaType(shape, type, model, boxed = false) {
  const kind = kindOf(shape, model);
  if (kind === "list")
    return `java.util.List<${javaType(itemOf(shape), type.args[0], model, true)}>`;
  if (kind === "map")
    return `java.util.Map<String, ${javaType(itemOf(shape), type.args[1], model, true)}>`;
  if (kind === "string") return type.name === "Uuid" ? "com.gtnhplanner.core.base.Uuid" : "String";
  if (kind === "number")
    return boxed ? ({ int: "Integer", double: "Double" }[type.name] ?? type.name) : type.name;
  if (kind === "boolean") return boxed ? "Boolean" : type.name;
  return (model.enums.find((e) => e.flat === shape) ?? model.records.find((r) => r.flat === shape))
    .javaClass;
}

class Natives {
  constructor() {
    this.lines = [];
    this.names = new Set();
  }

  add(name, ret, params, script) {
    if (this.names.has(name)) return name;
    this.names.add(name);
    this.lines.push(
      `    @JSBody(${params.length === 0 ? "" : `params = ${params.length === 1 ? quote(params[0][1]) : `{ ${params.map(([, n]) => quote(n)).join(", ")} }`}, `}script = ${quote(script)})`,
      `    private static native ${ret} ${name}(${params.map(([t, n]) => `${t} ${n}`).join(", ")});`,
      "",
    );
    return name;
  }
}

// region read (the website's object into a Java record)

/** A Java expression converting the JavaScript value `js` of a list item or map value of `shape` (a JSObject, String or double). */
function readItem(shape, type, model, n, accessorOf) {
  const kind = kindOf(shape, model);
  if (kind === "record") return (x) => `read${shape}(${x})`;
  if (kind === "string")
    return type.name === "Uuid"
      ? (x) => `com.gtnhplanner.core.base.Uuid.fromString(${x})`
      : (x) => x;
  if (kind === "number")
    return type.name === "int" || type.name === "Integer" ? (x) => `(int) ${x}` : (x) => x;
  if (kind === "boolean") return (x) => x;
  if (kind === "enum") {
    const en = model.enums.find((e) => e.flat === shape);
    const table = Object.fromEntries(en.values.map((v, i) => [v, i]));
    const ord = n.add(`ordinal${shape}`, "int", [["String", "name"]], ordinalScript(table, "name"));
    return (x) => `${constName(shape)}[${ord}(${x})]`;
  }
  throw new Error(`${accessorOf}: a list or map of ${kind} is not carried`);
}

/** The JavaScript-side item reader's return type for a list item or map value of `shape`. */
function itemReturn(shape, model) {
  return {
    record: "JSObject",
    string: "String",
    number: "double",
    boolean: "boolean",
    enum: "String",
  }[kindOf(shape, model)];
}

/**
 * A script answering the ordinal of the enum name in `value`: -1 when it is missing (an absent optional), and past the
 * last ordinal for a name the enum lacks, so the Java array read fails loudly instead of picking a constant.
 */
function ordinalScript(table, value) {
  const size = Object.keys(table).length;
  return `var __t = ${JSON.stringify(table)}; var __k = ${value}; return __k == null ? -1 : __t.hasOwnProperty(__k) ? __t[__k] : ${size};`;
}

function constName(flat) {
  return flat.replace(/([a-z0-9])([A-Z])/g, "$1_$2").toUpperCase();
}

function readRecord(rec, model, n, out) {
  const args = [];
  const pre = [];
  rec.components.forEach((c, i) => {
    const kind = kindOf(c.shape, model);
    const acc = `${lowerFirst(rec.flat)}${upperFirst(c.name)}`;
    const key = c.name;
    const v = `c${i}`;
    const type = javaType(c.shape, c.type, model);
    if (kind === "list" || kind === "map") {
      const get = n.add(
        acc,
        "JSObject",
        [["JSObject", "o"]],
        `return o.${key} == null ? null : o.${key};`,
      );
      const item = itemOf(c.shape);
      const itemType = kind === "list" ? c.type.args[0] : c.type.args[1];
      const ret = itemReturn(item, model);
      const conv = readItem(item, itemType, model, n, `${rec.flat}.${key}`);
      const boxedItem = javaType(item, itemType, model, true);
      pre.push(`        final JSObject a${i} = ${get}(o);`);
      if (kind === "list") {
        const at = n.add(
          `at${upperFirst(ret === "JSObject" ? "object" : ret)}`,
          ret,
          [
            ["JSObject", "a"],
            ["int", "i"],
          ],
          "return a[i];",
        );
        pre.push(`        final ${type} ${v};`);
        pre.push(
          `        if (a${i} == null) ${v}${c.nullable ? " = null" : " = new java.util.ArrayList<>()"};`,
        );
        pre.push("        else {");
        pre.push(`            final int n${i} = length(a${i});`);
        pre.push(
          `            final java.util.List<${boxedItem}> l${i} = new java.util.ArrayList<>(n${i});`,
        );
        pre.push(
          `            for (int i = 0; i < n${i}; i++) l${i}.add(${conv(`${at}(a${i}, i)`)});`,
        );
        pre.push(`            ${v} = l${i};`);
        pre.push("        }");
      } else {
        const get2 = n.add(
          `value${upperFirst(ret === "JSObject" ? "object" : ret)}`,
          ret,
          [
            ["JSObject", "m"],
            ["String", "k"],
          ],
          "return m[k];",
        );
        pre.push(`        final ${type} ${v};`);
        pre.push(
          `        if (a${i} == null) ${v}${c.nullable ? " = null" : " = new java.util.TreeMap<>()"};`,
        );
        pre.push("        else {");
        pre.push(`            final JSObject k${i} = keys(a${i});`);
        pre.push(`            final int n${i} = length(k${i});`);
        pre.push(
          `            final java.util.Map<String, ${boxedItem}> m${i} = new java.util.TreeMap<>();`,
        );
        pre.push(`            for (int i = 0; i < n${i}; i++) {`);
        pre.push(`                final String k = atString(k${i}, i);`);
        pre.push(`                m${i}.put(k, ${conv(`${get2}(a${i}, k)`)});`);
        pre.push("            }");
        pre.push(`            ${v} = m${i};`);
        pre.push("        }");
      }
      args.push(v);
      return;
    }
    if (kind === "record") {
      const get = n.add(
        acc,
        "JSObject",
        [["JSObject", "o"]],
        `return o.${key} == null ? ${c.nullable ? "null" : "{}"} : o.${key};`,
      );
      pre.push(`        final JSObject o${i} = ${get}(o);`);
      args.push(
        c.nullable ? `o${i} == null ? null : read${c.shape}(o${i})` : `read${c.shape}(o${i})`,
      );
      return;
    }
    if (kind === "string") {
      const get = n.add(
        acc,
        "String",
        [["JSObject", "o"]],
        `return o.${key} == null ? ${c.nullable ? "null" : '""'} : o.${key};`,
      );
      if (c.type.name === "Uuid") {
        pre.push(`        final String s${i} = ${get}(o);`);
        args.push(
          c.nullable
            ? `s${i} == null ? null : com.gtnhplanner.core.base.Uuid.fromString(s${i})`
            : `com.gtnhplanner.core.base.Uuid.fromString(s${i})`,
        );
      } else args.push(`${get}(o)`);
      return;
    }
    if (kind === "enum") {
      const en = model.enums.find((e) => e.flat === c.shape);
      const table = Object.fromEntries(en.values.map((x, j) => [x, j]));
      const get = n.add(acc, "int", [["JSObject", "o"]], ordinalScript(table, `o.${key}`));
      pre.push(`        final int e${i} = ${get}(o);`);
      args.push(
        c.nullable
          ? `e${i} < 0 ? null : ${constName(c.shape)}[e${i}]`
          : `${constName(c.shape)}[e${i}]`,
      );
      return;
    }
    // number or boolean
    const prim = kind === "number" ? "double" : "boolean";
    const get = n.add(
      acc,
      prim,
      [["JSObject", "o"]],
      kind === "number" ? `return o.${key} == null ? 0 : o.${key};` : `return o.${key} === true;`,
    );
    const conv =
      kind === "number" && (c.type.name === "int" || c.type.name === "Integer")
        ? `(int) ${get}(o)`
        : `${get}(o)`;
    if (c.nullable) {
      const has = n.add(
        `${acc}Present`,
        "boolean",
        [["JSObject", "o"]],
        `return o.${key} != null;`,
      );
      args.push(`${has}(o) ? ${javaType(c.shape, c.type, model, true)}.valueOf(${conv}) : null`);
    } else args.push(conv);
  });
  out.push(
    `    /** A {@code ${rec.flat}} from the website's object (records.generated.ts {@code ${rec.flat}}). */`,
  );
  out.push(`    public static ${rec.javaClass} read${rec.flat}(final JSObject o) {`);
  out.push(...pre);
  out.push(`        return new ${rec.javaClass}(${args.join(",\n            ")});`);
  out.push("    }", "");
}

// endregion

// region build (a Java record into the website's object)

/** The factory parameter type and Java argument expression of a list item or map value of `shape`. */
function buildItem(shape, model, where) {
  const kind = kindOf(shape, model);
  if (kind === "record") return ["JSObject", (x) => `js(${x})`];
  if (kind === "string") return ["String", (x) => `${x}.toString()`];
  if (kind === "number") return ["double", (x) => x];
  if (kind === "boolean") return ["boolean", (x) => x];
  if (kind === "enum") return ["String", (x) => `${x}.name()`];
  throw new Error(`${where}: a list or map of ${kind} is not carried`);
}

function buildRecord(rec, model, n, out) {
  const params = [];
  const args = [];
  const pre = [];
  const body = ["var __w = {};"];
  rec.components.forEach((c, i) => {
    const kind = kindOf(c.shape, model);
    const p = `_${c.name}`;
    const value = `v.${c.name}()`;
    const set = c.nullable ? `if (${p} !== null) __w.${c.name} = ${p};` : `__w.${c.name} = ${p};`;
    if (kind === "list" || kind === "map") {
      const item = itemOf(c.shape);
      const itemType = kind === "list" ? c.type.args[0] : c.type.args[1];
      const [t, conv] = buildItem(item, model, `${rec.flat}.${c.name}`);
      const jt = javaType(item, itemType, model, true);
      const name = t === "JSObject" ? "Object" : upperFirst(t);
      pre.push(`        final JSObject a${i};`);
      if (c.nullable) pre.push(`        if (${value} == null) a${i} = null;`, "        else {");
      else pre.push("        {");
      if (kind === "list") {
        const push = n.add(
          `push${name}`,
          "void",
          [
            ["JSObject", "a"],
            [t, "x"],
          ],
          "a.push(x);",
        );
        pre.push(`            a${i} = newArray();`);
        pre.push(`            for (final ${jt} x : ${value}) ${push}(a${i}, ${conv("x")});`);
      } else {
        const put = n.add(
          `put${name}`,
          "void",
          [
            ["JSObject", "m"],
            ["String", "k"],
            [t, "x"],
          ],
          "m[k] = x;",
        );
        pre.push(`            a${i} = newObject();`);
        pre.push(
          `            for (final java.util.Map.Entry<String, ${jt}> e : new java.util.TreeMap<>(${value}).entrySet()) ${put}(a${i}, e.getKey(), ${conv("e.getValue()")});`,
        );
      }
      pre.push("        }");
      params.push(["JSObject", p]);
      args.push(`a${i}`);
      body.push(set);
      return;
    }
    if (kind === "record") {
      params.push(["JSObject", p]);
      args.push(c.nullable ? `${value} == null ? null : js(${value})` : `js(${value})`);
      body.push(set);
      return;
    }
    if (kind === "string" || kind === "enum") {
      params.push(["String", p]);
      const text =
        kind === "enum"
          ? `${value}.name()`
          : c.type.name === "Uuid"
            ? `${value}.toString()`
            : value;
      args.push(c.nullable && text !== value ? `${value} == null ? null : ${text}` : text);
      body.push(set);
      return;
    }
    // number or boolean: an optional one crosses as a flag and the value
    const prim = kind === "number" ? "double" : "boolean";
    if (c.nullable) {
      params.push(["boolean", `${p}Present`], [prim, p]);
      args.push(
        `${value} != null`,
        `${value} == null ? ${kind === "number" ? "0" : "false"} : ${value}`,
      );
      body.push(`if (${p}Present) __w.${c.name} = ${p};`);
    } else {
      params.push([prim, p]);
      args.push(value);
      body.push(`__w.${c.name} = ${p};`);
    }
  });
  body.push("return __w;");
  const factory = n.add(`new${rec.flat}`, "JSObject", params, body.join(" "));
  out.push(
    `    /** A {@code ${rec.flat}} as the website's object (records.generated.ts {@code ${rec.flat}}). */`,
  );
  out.push(`    public static JSObject js(final ${rec.javaClass} v) {`);
  out.push(...pre);
  out.push(`        return ${factory}(${args.join(", ")});`);
  out.push("    }", "");
}

// endregion

function javaSource(inModel, outModel) {
  const n = new Natives();
  // Shared by every record; the generated accessors and factories follow. The scripts' own local names start with
  // two underscores: the JavaScript target inlines a script into its caller, where a plain name could capture one of
  // the caller's variables.
  n.add("length", "int", [["JSObject", "a"]], "return a.length;");
  n.add("keys", "JSObject", [["JSObject", "m"]], "return Object.keys(m);");
  n.add(
    "atString",
    "String",
    [
      ["JSObject", "a"],
      ["int", "i"],
    ],
    "return a[i];",
  );
  n.add("newArray", "JSObject", [], "return [];");
  n.add("newObject", "JSObject", [], "return {};");
  const reads = [];
  const builds = [];
  for (const rec of inModel.records) readRecord(rec, inModel, n, reads);
  for (const rec of outModel.records) buildRecord(rec, outModel, n, builds);
  const enumConsts = inModel.enums.map(
    (e) =>
      `    private static final ${e.javaClass}[] ${constName(e.flat)} = ${e.javaClass}.values();`,
  );
  return [
    "// Generated by tools/web-core/gen-wire.mjs from the Java records named in jvm/web/wire-records.json (do not edit;",
    "// run `node tools/web-core/gen-wire.mjs` after changing one of them). How the records cross: jvm/web/README.md,",
    '// "Records across the boundary".',
    "package com.gtnhplanner.web.wire;",
    "",
    "import org.teavm.jso.JSBody;",
    "import org.teavm.jso.JSObject;",
    "",
    "/**",
    " * The view records the website hands the core and gets back, as JavaScript objects: {@code read<Record>} reads one",
    " * from the website's object, {@code js(record)} builds the website's object. The objects are the records'",
    " * TypeScript declarations (records.generated.ts), the same objects JSON.parse gives of the records' JSON.",
    " */",
    "public final class ViewJs {",
    "",
    "    private ViewJs() {}",
    "",
    ...enumConsts.flatMap((l) => [l, ""]),
    ...reads,
    ...builds,
    "    // region JavaScript",
    "",
    ...n.lines,
    "    // endregion",
    "}",
    "",
  ].join("\n");
}

export function generate(sources = readSources(), registry = readWireRegistry()) {
  return { java: javaSource(plan(sources, registry.in), plan(sources, registry.out)) };
}

const read = (f) => (fs.existsSync(f) ? fs.readFileSync(f, "utf8") : null);

/**
 * Writes the Java file when it differs; true when it wrote. Run by hand after a record changes, never from an npm
 * pre-hook: a stale file must fail the check in CI, not be rewritten before it.
 */
export function run() {
  if (!fs.existsSync(WIRE_REGISTRY)) return false;
  const { java } = generate();
  if (read(JAVA_OUTPUT) === java) return false;
  fs.mkdirSync(path.dirname(JAVA_OUTPUT), { recursive: true });
  fs.writeFileSync(JAVA_OUTPUT, java);
  return true;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.includes("--check")) {
    if (read(JAVA_OUTPUT) !== generate().java) {
      console.error(
        `${path.relative(root, JAVA_OUTPUT)} is missing or differs from the records; run node tools/web-core/gen-wire.mjs`,
      );
      process.exit(1);
    }
  } else if (run()) console.log(`wrote ${path.relative(root, JAVA_OUTPUT)}`);
}
