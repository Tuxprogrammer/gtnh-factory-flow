import fs from "node:fs";
import { describe, expect, it } from "vitest";
import { OUTPUT, generate, parseExports, readSources } from "./gen-types.mjs";

describe("core export declarations", () => {
  it("the generated file is what the Java exports give now", () => {
    expect(fs.readFileSync(OUTPUT, "utf8")).toBe(generate());
  });

  it("declares every namespace and every WebCore function the sources have", () => {
    const { namespaces, top, found } = readSources();
    if (!found) return;
    const text = fs.readFileSync(OUTPUT, "utf8");
    for (const [ns, fns] of Object.entries(namespaces)) {
      expect(text).toContain(`export interface ${ns} {`);
      for (const fn of fns) expect(text).toContain(`${fn.name}(`);
    }
    expect(top.map((f) => f.name)).toContain("webCoreExportVersion");
    expect(namespaces.BaseExports.map((f) => f.name)).toContain("numberTextOf");
  });

  it("reads parameters, nullable strings and docs", () => {
    const [fn] = parseExports(
      "/** Says hi. More text. */\n@JSExport\n@Nullable\npublic static String hi(final double x,\n  final int digits) { return null; }",
      "T.java",
    );
    expect(fn).toEqual({
      name: "hi",
      params: [
        { name: "x", type: "number" },
        { name: "digits", type: "number" },
      ],
      returns: "string | null",
      doc: "Says hi. More text.",
    });
  });

  it("refuses a type that cannot cross the boundary and an export it cannot read", () => {
    expect(() =>
      parseExports("@JSExport\npublic static long id() { return 0; }", "T.java"),
    ).toThrow(/long/);
    expect(() =>
      parseExports("@JSExport\npublic static List<String> ids() { return null; }", "T.java"),
    ).toThrow();
    expect(() =>
      parseExports("@JSExport\nstatic String hidden() { return null; }", "T.java"),
    ).toThrow(/@JSExport/);
  });
});
