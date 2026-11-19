#!/usr/bin/env node
// The conformance manifest: conformance/manifest.json lists every data file of the tree with its area, size and
// sha256, so a changed or stray file is caught by both suites (src/test-support/conformance-manifest.test.ts and the
// mod's ConformanceManifestTest) and by this script.
//
//   node tools/conformance/manifest.mjs --check   exit 1, one line per problem, when the tree and the manifest differ
//   node tools/conformance/manifest.mjs --write   rewrite "files" from the tree (keeps "areas", which a person writes)
//
// Plain Node, no dependencies, so a runner that is neither vitest nor JUnit can use it. Exported for that reason too.
import { createHash } from "node:crypto";
import { readFileSync, readdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const ROOT = fileURLToPath(new URL("../../conformance/", import.meta.url));

/** Files that are about the tree, not data in it: never listed, never hashed. */
const NOT_DATA = (rel) => rel === "manifest.json" || rel === ".gitattributes" || path.posix.basename(rel) === "README.md";

export function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

/** Every data file under `root`, as sorted posix paths relative to it. */
export function dataFiles(root = ROOT) {
  const out = [];
  const walk = (dir, rel) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const r = rel ? `${rel}/${e.name}` : e.name;
      if (e.isDirectory()) walk(path.join(dir, e.name), r);
      else if (e.isFile() && !NOT_DATA(r)) out.push(r);
    }
  };
  walk(root, "");
  return out.sort();
}

/** The area a file belongs to: the longest area key that is a directory prefix of it, or null. */
export function areaOf(areas, rel) {
  let best = null;
  for (const key of Object.keys(areas)) if (rel.startsWith(`${key}/`) && (best === null || key.length > best.length)) best = key;
  return best;
}

export function readManifest(root = ROOT) {
  return JSON.parse(readFileSync(path.join(root, "manifest.json"), "utf8"));
}

/** The "files" object the tree has right now. Throws when a file belongs to no area. */
export function measure(areas, root = ROOT) {
  const files = {};
  for (const rel of dataFiles(root)) {
    const area = areaOf(areas, rel);
    if (area === null) throw new Error(`${rel} belongs to no area of manifest.json (add one to "areas")`);
    const bytes = readFileSync(path.join(root, rel));
    files[rel] = { area, bytes: bytes.length, sha256: sha256(bytes) };
  }
  return files;
}

/** Problems between the manifest and the tree; an empty list means they agree. */
export function problems(root = ROOT) {
  const manifest = readManifest(root);
  const out = [];
  if (manifest.version !== 1) out.push(`manifest version is ${manifest.version}, expected 1`);
  for (const [key, a] of Object.entries(manifest.areas ?? {})) {
    if (typeof a.specifies !== "string" || a.specifies === "") out.push(`area ${key}: no "specifies"`);
    if (!Array.isArray(a.readers)) out.push(`area ${key}: "readers" is not a list`);
  }
  const listed = manifest.files ?? {};
  const onDisk = new Set(dataFiles(root));
  for (const rel of onDisk) {
    const entry = listed[rel];
    if (!entry) {
      out.push(`${rel}: not in the manifest`);
      continue;
    }
    const bytes = readFileSync(path.join(root, rel));
    if (sha256(bytes) !== entry.sha256) out.push(`${rel}: sha256 differs from the manifest`);
    else if (bytes.length !== entry.bytes) out.push(`${rel}: size differs from the manifest`);
    if (areaOf(manifest.areas ?? {}, rel) !== entry.area) out.push(`${rel}: area ${entry.area} is not its directory's area`);
  }
  for (const rel of Object.keys(listed)) if (!onDisk.has(rel)) out.push(`${rel}: in the manifest, not in the tree`);
  return out;
}

export function write(root = ROOT) {
  const manifest = readManifest(root);
  const files = measure(manifest.areas, root);
  const sorted = (o) => Object.fromEntries(Object.entries(o).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
  const next = { version: 1, areas: sorted(manifest.areas), files: sorted(files) };
  writeFileSync(path.join(root, "manifest.json"), `${JSON.stringify(next, null, 2)}\n`);
  return Object.keys(files).length;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const mode = process.argv[2];
  if (mode === "--write") console.log(`manifest.json: ${write()} files`);
  else if (mode === "--check") {
    const found = problems();
    for (const p of found) console.error(p);
    if (found.length > 0) process.exit(1);
    console.log("conformance tree matches manifest.json");
  } else {
    console.error("usage: node tools/conformance/manifest.mjs --check | --write");
    process.exit(2);
  }
}
