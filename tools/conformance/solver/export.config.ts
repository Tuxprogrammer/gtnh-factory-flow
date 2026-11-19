// Writes the website solver cases from a capture run (tools/conformance/solver/generate.mjs).
import path from "node:path";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: { alias: { "@": path.resolve(__dirname, "../../../src") } },
  test: {
    environment: "node",
    root: path.resolve(__dirname, "../../.."),
    include: ["tools/conformance/solver/export.capture.ts"],
    testTimeout: 3_600_000,
  },
});
