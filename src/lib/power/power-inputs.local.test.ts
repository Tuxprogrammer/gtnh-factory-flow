/**
 * Writes conformance/power/website-test-inputs.jsonl.gz: every (source, stored settings) pair the website's own power tests build
 * a settings reader for, deduplicated. power-core.test.ts replays them through the TypeScript sources and the compiled
 * core. Run it after adding a power test:
 *
 *   npx vitest run src/lib/power/power-inputs.local.test.ts --config vitest.local.config.ts
 */
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { describe, it, vi } from "vitest";

const seen = new Map<string, { source: string; settings: Record<string, string> }>();

vi.mock("@/lib/power/types", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/power/types")>();
  return {
    ...original,
    buildPowerSettingsReader(
      definition: Parameters<typeof original.buildPowerSettingsReader>[0],
      values: Record<string, string> | undefined,
    ) {
      const settings = { ...(values ?? {}) };
      const key = `${definition.id}\u0000${JSON.stringify(Object.entries(settings).sort())}`;
      if (!seen.has(key)) seen.set(key, { source: definition.id, settings });
      return original.buildPowerSettingsReader(definition, values);
    },
  };
});

// Importing a test file registers its tests in this one, so they run under the recorder.
import.meta.glob(["./*.test.ts", "./sources/*.test.ts", "!./power-core.test.ts"], { eager: true });

describe("record the power tests' inputs", () => {
  it("writes them out, after the tests above ran", () => {
    const rows = [...seen.values()].sort(
      (a, b) =>
        a.source.localeCompare(b.source) ||
        JSON.stringify(a.settings).localeCompare(JSON.stringify(b.settings)),
    );
    const text = `${rows.map((row) => JSON.stringify(row)).join("\n")}\n`;
    // Node writes no timestamp into the gzip header, so the same inputs give the same bytes.
    fs.writeFileSync(
      path.join(__dirname, "../../../conformance/power/website-test-inputs.jsonl.gz"),
      zlib.gzipSync(text, { level: 9 }),
    );
    console.log(`${rows.length} inputs recorded`);
  });
});
