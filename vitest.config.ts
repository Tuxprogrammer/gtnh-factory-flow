import path from "node:path";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "src"),
    },
  },
  test: {
    // The shared view tokens and the core's TypeScript declarations are generated, not committed, and the compiled
    // core is copied to public/core (tools/tokens/generate.mjs, tools/web-core/gen-types.mjs, tools/copy-core.mjs).
    globalSetup: ["tools/tokens/vitest-setup.mjs", "tools/web-core/vitest-setup.mjs"],
    environment: "node",
    include: ["src/**/*.test.ts", "src/**/*.test.tsx", "tools/**/*.test.mjs"],
    // .local.test files are untracked scratch harnesses (corpus sweeps,
    // one-off probes); they run by explicit path only, never with the suite.
    exclude: ["**/node_modules/**", "**/*.local.test.*"],
  },
});
