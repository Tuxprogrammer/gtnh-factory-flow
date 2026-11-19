// Runs the website's test suite and the reference plans with capture-setup.ts recording every plan-solver input
// (tools/conformance/solver/generate.mjs). The two files that replay recorded answers are left out: they would only
// record the cases again.
import path from "node:path";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: { alias: { "@": path.resolve(__dirname, "../../../src") } },
  test: {
    environment: "node",
    root: path.resolve(__dirname, "../../.."),
    globalSetup: [
      path.resolve(__dirname, "../../tokens/vitest-setup.mjs"),
      path.resolve(__dirname, "../../web-core/vitest-setup.mjs"),
    ],
    include: [
      "src/**/*.test.ts",
      "src/**/*.test.tsx",
      "tools/conformance/solver/reference-plans.capture.ts",
    ],
    exclude: [
      "**/node_modules/**",
      "**/*.local.test.*",
      "src/lib/solver/plan/parity/up-to-export.test.ts",
      "src/lib/solver/plan/__fixtures__/website-cases.test.ts",
    ],
    setupFiles: [path.resolve(__dirname, "capture-setup.ts")],
    testTimeout: 600_000,
  },
});
