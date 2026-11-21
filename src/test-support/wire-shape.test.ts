import { describe, expect, it } from "vitest";
import { expectProblems, shapeProblems, type Shapes } from "./wire-shape";
import { readConformanceJson } from "./conformance";

const shapes = readConformanceJson("wire", "shapes.json") as Shapes;

/** Cases both checkers must agree on (WireShapeTest.java has the same table). */
const CASES: Array<{ shape: unknown; value: unknown; ok: boolean }> = [
  { shape: "string", value: "a", ok: true },
  { shape: "string", value: 1, ok: false },
  { shape: "integer", value: 1, ok: true },
  { shape: "integer", value: 1.5, ok: false },
  { shape: "number", value: 1.5, ok: true },
  { shape: "string|null", value: null, ok: true },
  { shape: "string|null", value: 3, ok: false },
  { shape: ["string"], value: [], ok: true },
  { shape: ["string"], value: ["a", 2], ok: false },
  { shape: { a: "string", "b?": "integer" }, value: { a: "x" }, ok: true },
  { shape: { a: "string", "b?": "integer" }, value: { a: "x", b: "y" }, ok: false },
  { shape: { a: "string" }, value: { a: "x", extra: 1 }, ok: true },
  { shape: { a: "string" }, value: {}, ok: false },
  { shape: { "=": true }, value: true, ok: true },
  { shape: { "=": true }, value: false, ok: false },
  { shape: { in: ["a", "b"] }, value: "b", ok: true },
  { shape: { in: ["a", "b"] }, value: "c", ok: false },
  { shape: "$Account", value: { id: "1", username: "ann" }, ok: true },
  { shape: "$Account", value: { id: "1" }, ok: false },
  { shape: "$LiveCursor|null", value: null, ok: true },
  { shape: "any", value: undefined, ok: true },
  { shape: "object", value: [], ok: false },
  { shape: "array", value: [], ok: true },
];

describe("wire shape language", () => {
  it.each(CASES)("$shape against $value", ({ shape, value, ok }) => {
    expect(shapeProblems(shapes, shape, value).length === 0).toBe(ok);
  });

  it("names every problem with its path", () => {
    expect(shapeProblems(shapes, "$Account", { id: 1 })).toEqual([
      "$.id: expected string, got 1",
      "$.username: missing",
    ]);
  });

  it("reads dotted paths into arrays and objects", () => {
    expect(expectProblems({ a: [{ b: 2 }] }, { "a.0.b": 2 })).toEqual([]);
    expect(expectProblems({ a: [{ b: 2 }] }, { "a.0.b": 3 })).toEqual(["a.0.b: expected 3, got 2"]);
  });

  it("every shape in the file is well formed", () => {
    for (const [name, shape] of Object.entries(shapes)) {
      if (name.startsWith("$")) continue;
      expect(() => shapeProblems(shapes, shape, null), name).not.toThrow();
    }
  });
});
