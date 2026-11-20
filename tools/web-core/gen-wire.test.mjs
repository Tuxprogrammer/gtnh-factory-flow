import fs from "node:fs";
import { describe, expect, it } from "vitest";
import { readRegistry, readSources } from "./gen-records.mjs";
import { JAVA_OUTPUT, WIRE_REGISTRY, generate, readWireRegistry } from "./gen-wire.mjs";

describe("the records' codec for JavaScript objects (gen-wire.mjs)", () => {
  const present = fs.existsSync(WIRE_REGISTRY);

  it("ViewJs.java is what the records give now (run node tools/web-core/gen-wire.mjs after changing one)", () => {
    if (!present) return;
    expect(fs.readFileSync(JAVA_OUTPUT, "utf8")).toBe(generate().java);
  });

  it("every record that crosses is declared for the website (a root of view-records.json)", () => {
    if (!present) return;
    const roots = new Set(readRegistry().roots);
    const wire = readWireRegistry();
    for (const name of [...wire.in, ...wire.out]) expect(roots.has(name), name).toBe(true);
  });

  it("refuses a shape it cannot carry instead of writing a wrong reader", () => {
    if (!present) return;
    const sources = readSources();
    const nested = {
      outer: [],
      name: "Nested",
      flat: "Nested",
      class: "p.Nested",
      kind: "record",
      file: "Nested.java",
      doc: { summary: "", params: {} },
      components: [
        {
          name: "rows",
          nullable: false,
          type: { name: "List", args: [{ name: "List", args: [{ name: "String", args: [] }] }] },
        },
      ],
    };
    sources.records.set("Nested", nested);
    expect(() => generate(sources, { in: ["Nested"], out: [] })).toThrow(/list or map of list/);
    expect(() => generate(sources, { in: [], out: ["Nested"] })).toThrow(/list or map of list/);
  });
});
