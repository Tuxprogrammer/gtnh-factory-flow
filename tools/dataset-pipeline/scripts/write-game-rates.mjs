import fs from "node:fs";
import path from "node:path";
import { GAME_RATES_FILE, findRateConfig, gameRatesFromConfig } from "./game-rates.mjs";

// Usage: write-game-rates.mjs <instanceRoot> <datasetOutDir>
// A pack without the rate mod gets no sidecar, and a stale one is removed.
const [instanceRoot, outDir] = process.argv.slice(2);
if (!instanceRoot || !outDir) {
  console.error("Usage: write-game-rates.mjs <instanceRoot> <datasetOutDir>");
  process.exit(2);
}

const sidecar = path.join(outDir, GAME_RATES_FILE);
const configPath = findRateConfig(instanceRoot);
if (!configPath) {
  fs.rmSync(sidecar, { force: true });
  console.log("No rate mod config found; writing no game rates sidecar.");
} else {
  const rates = gameRatesFromConfig(fs.readFileSync(configPath, "utf8"));
  fs.mkdirSync(outDir, { recursive: true });
  fs.writeFileSync(sidecar, `${JSON.stringify(rates, null, 2)}\n`);
  console.log(`Wrote ${GAME_RATES_FILE}: ${JSON.stringify(rates)}`);
}
