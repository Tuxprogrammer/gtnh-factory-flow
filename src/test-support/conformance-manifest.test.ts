/**
 * Drift check for the conformance tree: every data file under `conformance/` is listed in `manifest.json` with the
 * sha256 and size it has, nothing listed is missing, and every area says what it specifies and who reads it. The mod
 * has the same check in Java (`ConformanceManifestTest`); `tools/conformance/manifest.mjs --write` brings the manifest
 * up to date after a deliberate change to the data.
 */
import { createHash } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { CONFORMANCE_DIR, readConformanceJson } from "./conformance";

type Manifest = {
  version: number;
  areas: Record<string, { specifies: string; readers: string[] }>;
  files: Record<string, { area: string; bytes: number; sha256: string }>;
};

const manifest = readConformanceJson("manifest.json") as Manifest;

/** Files about the tree, not data in it. */
const isData = (rel: string) => rel !== "manifest.json" && rel !== ".gitattributes" && path.posix.basename(rel) !== "README.md";

function dataFiles(dir = "", out: string[] = []): string[] {
  for (const e of readdirSync(path.join(CONFORMANCE_DIR, dir), { withFileTypes: true })) {
    const rel = dir === "" ? e.name : `${dir}/${e.name}`;
    if (e.isDirectory()) dataFiles(rel, out);
    else if (e.isFile() && isData(rel)) out.push(rel);
  }
  return out.sort();
}

describe("conformance tree matches manifest.json", () => {
  const onDisk = dataFiles();

  it("is version 1 and every area says what it specifies and who reads it", () => {
    expect(manifest.version).toBe(1);
    expect(Object.keys(manifest.areas).length).toBeGreaterThan(0);
    for (const [key, a] of Object.entries(manifest.areas)) {
      expect(typeof a.specifies === "string" && a.specifies.length > 0, `area ${key} specifies`).toBe(true);
      expect(Array.isArray(a.readers), `area ${key} readers`).toBe(true);
    }
  });

  it("lists every file in the tree, and nothing that is not there", () => {
    expect(onDisk.filter((f) => !(f in manifest.files)), "files not in the manifest").toEqual([]);
    expect(Object.keys(manifest.files).filter((f) => !onDisk.includes(f)), "manifest entries with no file").toEqual([]);
  });

  it("holds the bytes the manifest hashed, in the area of its directory", () => {
    const wrong: string[] = [];
    for (const rel of onDisk) {
      const entry = manifest.files[rel];
      if (!entry) continue;
      const bytes = readFileSync(path.join(CONFORMANCE_DIR, rel));
      if (createHash("sha256").update(bytes).digest("hex") !== entry.sha256) wrong.push(`${rel}: sha256`);
      else if (bytes.length !== entry.bytes) wrong.push(`${rel}: size`);
      const area = Object.keys(manifest.areas)
        .filter((k) => rel.startsWith(`${k}/`))
        .sort((a, b) => b.length - a.length)[0];
      if (area !== entry.area) wrong.push(`${rel}: area ${entry.area}, directory says ${area}`);
    }
    expect(wrong).toEqual([]);
  });
});
