import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { gzipSync } from "node:zlib";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it } from "vitest";
import {
  findRateConfig,
  gameRatesFromConfig,
  readGameRatesSidecar,
  validateGameRates,
} from "./game-rates.mjs";

const scriptsDir = path.dirname(fileURLToPath(import.meta.url));
const sampleConfig = fs.readFileSync(path.join(scriptsDir, "__fixtures__", "gtnhrates.cfg"), "utf8");
const folders = [];
const tempDir = () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "game-rates-"));
  folders.push(dir);
  return dir;
};
afterEach(() => {
  for (const dir of folders.splice(0)) fs.rmSync(dir, { recursive: true, force: true });
});

describe("rate mod config", () => {
  it("maps the three settings from the general block", () => {
    expect(gameRatesFromConfig(sampleConfig)).toEqual({
      beesYield: 4,
      ic2CropsYield: 2.5,
      recipeDurationDivisor: 64,
    });
  });

  it("uses the mod default for a key the file lacks", () => {
    expect(gameRatesFromConfig("general {\n    S:beesYield=8.0\n}\n")).toEqual({
      beesYield: 8,
      ic2CropsYield: 4,
      recipeDurationDivisor: 4,
    });
  });

  it("rejects a value that is not a positive number", () => {
    for (const bad of ["0", "-1", "abc", "NaN", ""]) {
      expect(() => gameRatesFromConfig(`general {\n    S:beesYield=${bad}\n}\n`)).toThrow(/beesYield/);
    }
  });

  it("finds the config whatever the case of its name, and none when absent", () => {
    const root = tempDir();
    expect(findRateConfig(root)).toBeUndefined();
    fs.mkdirSync(path.join(root, "config"));
    expect(findRateConfig(root)).toBeUndefined();
    fs.writeFileSync(path.join(root, "config", "GTNHRates.cfg"), sampleConfig);
    expect(findRateConfig(root)).toBe(path.join(root, "config", "GTNHRates.cfg"));
  });
});

describe("write-game-rates", () => {
  const run = (instanceRoot, outDir) =>
    spawnSync(process.execPath, [path.join(scriptsDir, "write-game-rates.mjs"), instanceRoot, outDir], {
      encoding: "utf8",
    });

  it("writes the sidecar from the instance config", () => {
    const root = tempDir();
    const out = tempDir();
    fs.mkdirSync(path.join(root, "config"));
    fs.writeFileSync(path.join(root, "config", "gtnhrates.cfg"), sampleConfig);
    expect(run(root, out).status).toBe(0);
    expect(JSON.parse(fs.readFileSync(path.join(out, "game-rates.json"), "utf8"))).toEqual({
      beesYield: 4,
      ic2CropsYield: 2.5,
      recipeDurationDivisor: 64,
    });
  });

  it("writes no sidecar for a pack without the mod, and drops a stale one", () => {
    const root = tempDir();
    const out = tempDir();
    fs.writeFileSync(path.join(out, "game-rates.json"), "{}");
    expect(run(root, out).status).toBe(0);
    expect(fs.existsSync(path.join(out, "game-rates.json"))).toBe(false);
  });

  it("fails on an invalid config value instead of writing a bad sidecar", () => {
    const root = tempDir();
    const out = tempDir();
    fs.mkdirSync(path.join(root, "config"));
    fs.writeFileSync(path.join(root, "config", "gtnhrates.cfg"), "general {\n    S:ic2CropsYield=-3\n}\n");
    const result = run(root, out);
    expect(result.status).not.toBe(0);
    expect(fs.existsSync(path.join(out, "game-rates.json"))).toBe(false);
  });
});

describe("game rates sidecar", () => {
  const good = { beesYield: 4, ic2CropsYield: 4, recipeDurationDivisor: 64 };

  it("accepts three positive numbers", () => {
    expect(validateGameRates(good)).toEqual(good);
  });

  it("rejects anything else with a message naming the problem", () => {
    expect(() => validateGameRates([])).toThrow(/JSON object/);
    expect(() => validateGameRates({ ...good, beesYield: 0 })).toThrow(/beesYield.*positive/);
    expect(() => validateGameRates({ ...good, beesYield: "4" })).toThrow(/beesYield/);
    expect(() => validateGameRates({ ...good, ic2CropsYield: null })).toThrow(/ic2CropsYield/);
    expect(() => validateGameRates({ beesYield: 4, ic2CropsYield: 4 })).toThrow(/recipeDurationDivisor/);
    expect(() => validateGameRates({ ...good, extra: 1 })).toThrow(/unknown key "extra"/);
  });

  it("reads none when the file is absent and rejects invalid JSON", () => {
    const dir = tempDir();
    expect(readGameRatesSidecar(dir)).toBeUndefined();
    fs.writeFileSync(path.join(dir, "game-rates.json"), "{nope");
    expect(() => readGameRatesSidecar(dir)).toThrow(/not valid JSON/);
  });
});

describe("rebuild-manifest", () => {
  const versionId = "stable-2.9.0-test";

  function datasetsRoot() {
    const root = tempDir();
    const dir = path.join(root, versionId);
    fs.mkdirSync(dir);
    const header = [
      "{",
      `  "datasetVersionId": "${versionId}",`,
      '  "gtnhVersion": "2.9.0",',
      '  "generatedAt": "2026-01-01T00:00:00.000Z",',
      '  "sourceInfo": {"name": "test"},',
      '  "recipes": []',
      "}",
    ].join("\n");
    fs.writeFileSync(path.join(dir, "recipes.json.gz"), gzipSync(header));
    for (const file of ["resource-index.json.gz", "recipe-index.json.gz", "recipe-lookup-index.json.gz"]) {
      fs.writeFileSync(path.join(dir, file), gzipSync("{}"));
    }
    return { root, dir };
  }

  function rebuild(root) {
    const result = spawnSync(process.execPath, [path.join(scriptsDir, "rebuild-manifest.mjs")], {
      encoding: "utf8",
      env: { ...process.env, GTNH_DATASETS_ROOT: root },
    });
    const manifestFile = path.join(root, "datasets.manifest.json");
    return {
      result,
      version: fs.existsSync(manifestFile) ? JSON.parse(fs.readFileSync(manifestFile, "utf8")).versions[0] : undefined,
    };
  }

  it("copies the sidecar onto the entry and changes nothing else", () => {
    const { root, dir } = datasetsRoot();
    const before = rebuild(root);
    expect(before.result.status).toBe(0);
    expect(before.version.gameRates).toBeUndefined();
    expect("gameRates" in before.version).toBe(false);

    const rates = { beesYield: 4, ic2CropsYield: 4, recipeDurationDivisor: 64 };
    fs.writeFileSync(path.join(dir, "game-rates.json"), JSON.stringify(rates));
    const after = rebuild(root);
    expect(after.result.status).toBe(0);
    expect(after.version.gameRates).toEqual(rates);
    const { gameRates: _gameRates, ...rest } = after.version;
    expect(rest).toEqual(before.version);
  });

  it("rejects an invalid sidecar with a clear message", () => {
    const { root, dir } = datasetsRoot();
    fs.writeFileSync(
      path.join(dir, "game-rates.json"),
      JSON.stringify({ beesYield: 4, ic2CropsYield: 0, recipeDurationDivisor: 64 }),
    );
    const { result } = rebuild(root);
    expect(result.status).not.toBe(0);
    expect(result.stderr).toMatch(/ic2CropsYield as a positive number/);
    expect(fs.existsSync(path.join(root, "datasets.manifest.json"))).toBe(false);
  });
});
