// The website's machine tests with every machine-maths input recorded (setup.ts). Written by record.mjs.
import path from "node:path";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: { alias: { "@": path.resolve(__dirname, "../../../src") } },
  test: {
    root: path.resolve(__dirname, "../../.."),
    globalSetup: ["tools/tokens/vitest-setup.mjs"],
    setupFiles: ["tools/audits/machine-test-inputs/setup.ts"],
    environment: "node",
    include: [
      "src/lib/machines/**/*.test.ts",
      "src/lib/solver/{overclock,machine-effects,power-report,power-budget,hatch-input,heat-gate,high-amperage,subtick-power-cap,under-one-tick,game-rates,bee-game-maths}.test.ts",
      "src/lib/model/{recipe-rules,recipe-input-overrides,input-chance,tiers,machine-list,bee-housings}.test.ts",
    ],
    exclude: ["**/node_modules/**", "**/*.local.test.*"],
  },
});
