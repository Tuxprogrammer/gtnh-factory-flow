import { describe, expect, it } from "vitest";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// The files an export writes beside recipes.json stamp the dataset's time, so two exports of one pack with
// SOURCE_DATE_EPOCH set write them byte for byte alike.
const scripts = path.dirname(new URL(import.meta.url).pathname);
const env = { ...process.env, SOURCE_DATE_EPOCH: "1763600000" };
const STAMP = "2025-11-20T00:53:20.000Z";

function run(script, args, extraEnv = {}) {
  const result = spawnSync(process.execPath, [path.join(scripts, script), ...args], {
    env: { ...env, ...extraEnv },
    encoding: "utf8",
  });
  expect(result.status, result.stderr).toBe(0);
}

describe("export side files under SOURCE_DATE_EPOCH", () => {
  it("the asset fingerprint stamps the dataset's time", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "fingerprint-"));
    fs.mkdirSync(path.join(dir, "instance", "mods"), { recursive: true });
    fs.writeFileSync(path.join(dir, "instance", "mods", "a.jar"), "jar");
    const out = path.join(dir, "out", "runtime-asset-fingerprint.json");
    run("compute-asset-fingerprint.mjs", [path.join(dir, "instance"), out]);
    expect(JSON.parse(fs.readFileSync(out, "utf8")).generatedAt).toBe(STAMP);
  });

  it("the asset fingerprint follows the instance's files and the extra text, nothing else", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "fingerprint-"));
    const instance = path.join(dir, "instance");
    fs.mkdirSync(path.join(instance, "mods"), { recursive: true });
    fs.writeFileSync(path.join(instance, "mods", "a.jar"), "jar");
    const hashOf = (name, extra) => {
      const out = path.join(dir, "out", name);
      run("compute-asset-fingerprint.mjs", [instance, out, ...extra]);
      return JSON.parse(fs.readFileSync(out, "utf8")).hash;
    };
    const first = hashOf("a.json", ["exporter-1"]);
    expect(hashOf("b.json", ["exporter-1"])).toBe(first);
    expect(hashOf("c.json", ["exporter-2"])).not.toBe(first);
    fs.mkdirSync(path.join(instance, "config"));
    fs.writeFileSync(path.join(instance, "config", "written-by-the-game.cfg"), "x");
    expect(hashOf("d.json", ["exporter-1"])).not.toBe(first);
  });

  it("the icon report of a skipped texture pass stamps the dataset's time", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "texture-icons-"));
    fs.mkdirSync(path.join(dir, "instance"));
    const out = path.join(dir, "stable-test");
    fs.mkdirSync(out);
    const dataset = path.join(out, "recipes.json");
    fs.writeFileSync(
      dataset,
      JSON.stringify({ datasetVersionId: "stable-test", resources: [], recipes: [] }),
    );
    run("apply-texture-icons.mjs", [path.join(dir, "instance"), dataset, out], {
      GTNH_TEXTURE_ICON_MAX_DATASET_BYTES: "1",
    });
    const report = JSON.parse(
      fs.readFileSync(path.join(out, "textures", "icon-report.json"), "utf8"),
    );
    expect(report.skipped).toBe(true);
    expect(report.generatedAt).toBe(STAMP);
  });
});
