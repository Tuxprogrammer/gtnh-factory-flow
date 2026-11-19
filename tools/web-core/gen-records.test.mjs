import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import {
  OUTPUT,
  REGISTRY,
  SHAPES,
  closure,
  generate,
  parseSource,
  readRegistry,
  readSources,
} from "./gen-records.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");

/** Why `value` is not the wire form of `shape` ("" when it is). Keys the table does not name, and nulls, are errors. */
function wrong(table, value, shape, at) {
  const list = /^(.*)\[\]$/.exec(shape);
  if (list) {
    if (!Array.isArray(value)) return `${at}: not a list`;
    for (let i = 0; i < value.length; i++) {
      const bad = wrong(table, value[i], list[1], `${at}[${i}]`);
      if (bad) return bad;
    }
    return "";
  }
  const map = /^Record<string, (.*)>$/.exec(shape);
  if (map) {
    if (value === null || typeof value !== "object" || Array.isArray(value)) return `${at}: not a map`;
    for (const [k, v] of Object.entries(value)) {
      const bad = wrong(table, v, map[1], `${at}.${k}`);
      if (bad) return bad;
    }
    return "";
  }
  if (shape === "string") return typeof value === "string" ? "" : `${at}: not a string`;
  if (shape === "boolean") return typeof value === "boolean" ? "" : `${at}: not a boolean`;
  if (shape === "number") return typeof value === "number" && Number.isFinite(value) ? "" : `${at}: not a number`;
  if (table.enums[shape]) {
    return table.enums[shape].values.includes(value) ? "" : `${at}: ${String(value)} is not a ${shape}`;
  }
  const rec = table.records[shape];
  if (!rec) return `${at}: no declaration for ${shape}`;
  if (value === null || typeof value !== "object" || Array.isArray(value)) return `${at}: not a ${shape}`;
  const known = new Set(rec.components.map(([name]) => name));
  for (const key of Object.keys(value)) if (!known.has(key)) return `${at}: ${shape} has no "${key}"`;
  for (const [name, type] of rec.components) {
    const optional = type.endsWith("?");
    if (!(name in value)) {
      if (!optional) return `${at}: ${shape} needs "${name}"`;
      continue;
    }
    if (value[name] === null) return `${at}.${name}: null on the wire`;
    const bad = wrong(table, value[name], optional ? type.slice(0, -1) : type, `${at}.${name}`);
    if (bad) return bad;
  }
  return "";
}

describe("view record declarations", () => {
  const present = fs.existsSync(REGISTRY);
  const table = present ? closure(readSources(), readRegistry().roots).table : null;

  it("the generated declarations and the shape table are what the Java records give now", () => {
    if (!present) return;
    const made = generate();
    expect(fs.readFileSync(OUTPUT, "utf8")).toBe(made.ts);
    expect(fs.readFileSync(SHAPES, "utf8")).toBe(made.shapes);
  });

  it("every corpus case, input and view, is a value of the declared record", () => {
    if (!present) return;
    const { cases } = readRegistry();
    expect(Object.keys(cases).length).toBeGreaterThan(0);
    for (const [file, types] of Object.entries(cases)) {
      const body = JSON.parse(fs.readFileSync(path.join(root, "conformance", "view", `${file}.json`), "utf8"));
      expect(body.cases.length).toBeGreaterThan(0);
      for (const c of body.cases) {
        expect(wrong(table, c.input, types.input, `${file} ${c.name} input`)).toBe("");
        expect(wrong(table, c.view, types.view, `${file} ${c.name} view`)).toBe("");
      }
    }
  });

  it("the check itself refuses an unknown key, a missing key and a null", () => {
    if (!present) return;
    const dial = { unit: "eu", suffix: "EU/t" };
    expect(wrong(table, dial, "RailViewDial", "d")).toBe("");
    expect(wrong(table, { ...dial, extra: 1 }, "RailViewDial", "d")).toMatch(/no "extra"/);
    expect(wrong(table, { unit: "eu" }, "RailViewDial", "d")).toMatch(/needs "suffix"/);
    expect(wrong(table, { ...dial, divisor: null }, "RailViewDial", "d")).toMatch(/null on the wire/);
    expect(wrong(table, { ...dial, divisor: "4" }, "RailViewDial", "d")).toMatch(/not a number/);
  });

  it("every record the registry roots reach is in the table, and its types are all declared", () => {
    if (!present) return;
    for (const [name, rec] of Object.entries(table.records)) {
      for (const [field, type] of rec.components) {
        const base = type.replace(/\?$/, "").replace(/^Record<string, (.*)>$/, "$1").replace(/(\[\])+$/, "");
        const scalar = ["string", "number", "boolean"].includes(base);
        expect(scalar || base in table.records || base in table.enums, `${name}.${field}: ${base}`).toBe(true);
      }
    }
  });
});

describe("the Java record reader", () => {
  const read = (src) => parseSource(src, "T.java");

  it("reads components, nullable, nesting, generics and docs", () => {
    const { records } = read(`
      package p;
      /** A view. {@code x} is the x.
       * @param a  the first
       *           one
       */
      public record V(@Nullable Double a, final List<Map<String, Integer>> b, Uuid id) {
        public record In(String s) {}
        public V { b = List.copyOf(b); }
        void m() { record Local(long l) {} }
      }`);
    expect(records.map((r) => [...r.outer, r.name].join("."))).toEqual(["V", "V.In"]);
    const [v] = records;
    expect(v.components.map((c) => [c.name, c.nullable, c.type.name])).toEqual([
      ["a", true, "Double"],
      ["b", false, "List"],
      ["id", false, "Uuid"],
    ]);
    expect(v.doc.params.a).toBe("the first one");
    expect(v.doc.summary).toBe("A view. x is the x.");
  });

  it("reads an enum's constants through arguments and docs", () => {
    const { enums } = read(`interface T { enum Role { /** one */ A("a", 1), B("b", f(2, 3)), C; } }`);
    expect(enums).toEqual([{ outer: ["T"], name: "Role", values: ["A", "B", "C"] }]);
  });

  it("ignores the word record in code, comments and strings", () => {
    const { records } = read(`class C { void m() { Object record = null; run(record); String s = "record X(int a)"; } /* record Y(int b) */ }`);
    expect(records).toEqual([]);
  });

  it("refuses what the wire cannot carry", () => {
    const table = (src, roots) => closure(readSources_(src), roots);
    const readSources_ = (src) => {
      const parsed = read(src);
      return {
        records: new Map(parsed.records.map((r) => [[...r.outer, r.name].join(""), { ...r, file: "T.java", class: "T" }])),
        enums: new Map(),
      };
    };
    expect(() => table("record A(long n) {}", ["A"])).toThrow(/long/);
    expect(() => table("record A(int[] n) {}", ["A"])).toThrow(/array/);
    expect(() => table("record A(Object o) {}", ["A"])).toThrow(/Object/);
    expect(() => table("record A(Map<Integer, String> m) {}", ["A"])).toThrow(/String keys/);
    expect(() => table("record A(int n) {}", ["B"])).toThrow(/not a record/);
  });
});
