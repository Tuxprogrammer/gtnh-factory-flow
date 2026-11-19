import fs from "node:fs";
import path from "node:path";

// The pack's rate mod scales yields and recipe times, so the dataset carries
// the factors the exporter saw. Sidecar keys match the app's gameRatesSchema.
export const GAME_RATES_FILE = "game-rates.json";
export const GAME_RATES_KEYS = ["beesYield", "ic2CropsYield", "recipeDurationDivisor"];

// Rate mod config keys and the mod's own defaults, used only when the file
// exists but a key is absent (the mod then runs on its default).
const CONFIG_KEYS = {
  beesYield: { key: "beesYield", fallback: 4 },
  ic2CropsYield: { key: "ic2CropsYield", fallback: 4 },
  recipeDurationDivisor: { key: "gtRecipesEnergyDiscount", fallback: 4 },
};

/** Reads `S:name=value` entries of the config's `general` block. */
export function parseRateConfig(text) {
  const values = new Map();
  let section = "";
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const open = /^([^\s{]+)\s*\{$/.exec(line);
    if (open) {
      section = open[1];
      continue;
    }
    if (line === "}") {
      section = "";
      continue;
    }
    const entry = /^[A-Z]:([^=\s]+)\s*=\s*(.*)$/.exec(line);
    if (entry && section === "general") values.set(entry[1], entry[2].trim());
  }
  return values;
}

/** Builds the sidecar record from config text; throws on a value that is not a positive number. */
export function gameRatesFromConfig(text) {
  const values = parseRateConfig(text);
  const rates = {};
  for (const [name, { key, fallback }] of Object.entries(CONFIG_KEYS)) {
    const raw = values.get(key);
    if (raw === undefined) {
      rates[name] = fallback;
      continue;
    }
    const value = Number(raw);
    if (raw === "" || !Number.isFinite(value) || value <= 0) {
      throw new Error(`Rate config ${key} is "${raw}"; expected a positive number.`);
    }
    rates[name] = value;
  }
  return rates;
}

/** Validates a parsed sidecar: exactly the three keys, each a positive finite number. */
export function validateGameRates(value, source = GAME_RATES_FILE) {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(`${source} must be a JSON object with ${GAME_RATES_KEYS.join(", ")}.`);
  }
  for (const key of Object.keys(value)) {
    if (!GAME_RATES_KEYS.includes(key)) {
      throw new Error(`${source} has an unknown key "${key}"; allowed keys are ${GAME_RATES_KEYS.join(", ")}.`);
    }
  }
  const rates = {};
  for (const key of GAME_RATES_KEYS) {
    const entry = value[key];
    if (typeof entry !== "number" || !Number.isFinite(entry) || entry <= 0) {
      throw new Error(`${source} needs ${key} as a positive number; found ${JSON.stringify(entry)}.`);
    }
    rates[key] = entry;
  }
  return rates;
}

/** Returns the validated sidecar of a dataset directory, or undefined when there is none. */
export function readGameRatesSidecar(datasetDir) {
  const file = path.join(datasetDir, GAME_RATES_FILE);
  if (!fs.existsSync(file)) return undefined;
  let parsed;
  try {
    parsed = JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (error) {
    throw new Error(`${GAME_RATES_FILE} in ${path.basename(datasetDir)} is not valid JSON: ${error.message}`);
  }
  return validateGameRates(parsed, `${GAME_RATES_FILE} in ${path.basename(datasetDir)}`);
}

/** Finds the rate mod's config under an instance, ignoring the file name's case. */
export function findRateConfig(instanceRoot) {
  const configDir = path.join(instanceRoot, "config");
  if (!fs.existsSync(configDir)) return undefined;
  const name = fs.readdirSync(configDir).find((entry) => entry.toLowerCase() === "gtnhrates.cfg");
  return name ? path.join(configDir, name) : undefined;
}
