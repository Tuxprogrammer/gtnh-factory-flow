// Runs the website's router tests with capture-setup.ts recording every router call (tools/conformance/layout).
import path from "node:path";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: { alias: { "@": path.resolve(__dirname, "../../../src") } },
  test: {
    environment: "node",
    root: path.resolve(__dirname, "../../.."),
    include: [
      "src/components/flow/grid-edge-router.test.ts",
      "src/components/flow/incremental-router.test.ts",
      "tools/conformance/layout/boards.capture.ts",
      "tools/conformance/layout/frames.capture.ts",
      "src/lib/board-arrange.test.ts",
      "tools/conformance/layout/arrange.capture.ts",
    ],
    setupFiles: [path.resolve(__dirname, "capture-setup.ts")],
    // The biggest reference board (600 wires) takes most of a minute in the TypeScript router.
    testTimeout: 600_000,
  },
});
