// The website's tests that draw recipe cards, with every card's facts recorded (setup.ts). Written by record.mjs.
import path from "node:path";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: { alias: { "@": path.resolve(__dirname, "../../../src") } },
  test: {
    root: path.resolve(__dirname, "../../.."),
    globalSetup: ["tools/tokens/vitest-setup.mjs", "tools/web-core/vitest-setup.mjs"],
    setupFiles: ["tools/audits/card-test-inputs/setup.ts"],
    environment: "node",
    include: [
      "src/components/**/*.test.ts",
      "src/components/**/*.test.tsx",
      "tools/audits/card-test-inputs/*.test.tsx",
    ],
    exclude: [
      "**/node_modules/**",
      "**/*.local.test.*",
      // The shadow replays what this records; the shared cases are their own corpus.
      "src/components/flow/clean/card-shadow.test.ts",
      "src/components/flow/clean/card-conformance.test.ts",
    ],
  },
});
