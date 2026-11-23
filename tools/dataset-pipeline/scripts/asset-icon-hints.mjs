import fs from "node:fs";
import fsp from "node:fs/promises";
import readline from "node:readline";
import { once } from "node:events";

/**
 * The exporter names a texture it did not render as `asset:<mod>:<path inside the jar>` (the add-on Thaumcraft
 * aspects). That is a note for apply-texture-icons.mjs, never a URL: the step extracts the texture and points the
 * resource at it, or drops the note. A dataset above the step's parse limit is too large to load whole, so this
 * rewrites only the lines that carry a note. dataset-json-writer.mjs writes every array element and every streamed
 * object entry on a line of its own, so a line is one JSON value or one `"key": value` pair, with a trailing comma
 * between elements.
 */
export const ASSET_HINT_MARKER = '"iconPath":"asset:';

/** Rewrites `datasetPath` in place; `resolve(resource)` answers the served path for a noted resource, or nothing. */
export async function rewriteAssetIconHints(datasetPath, resolve) {
  const tempPath = `${datasetPath}.asset-hints.tmp`;
  const out = fs.createWriteStream(tempPath);
  const lines = readline.createInterface({ input: fs.createReadStream(datasetPath), crlfDelay: Infinity });
  const cache = new Map();
  let resolved = 0;
  let dropped = 0;
  const fix = async (resource) => {
    const key = `${resource.kind}:${resource.id}:${resource.iconPath}`;
    if (!cache.has(key)) cache.set(key, await resolve(resource));
    const iconPath = cache.get(key);
    if (iconPath) {
      resource.iconPath = iconPath;
      resolved += 1;
    } else {
      delete resource.iconPath;
      dropped += 1;
    }
  };
  let first = true;
  for await (const line of lines) {
    const next = line.includes(ASSET_HINT_MARKER) ? await rewriteLine(line, fix) : line;
    if (!out.write(`${first ? "" : "\n"}${next}`)) await once(out, "drain");
    first = false;
  }
  out.write("\n");
  await new Promise((resolveEnd, reject) => out.end((error) => (error ? reject(error) : resolveEnd())));
  await fsp.rename(tempPath, datasetPath);
  return { resolved, dropped };
}

async function rewriteLine(line, fix) {
  const match = /^(\s*)(.*?)(,?)$/.exec(line);
  const [, indent, body, comma] = match;
  const pair = /^("(?:[^"\\]|\\.)*"):\s*([\s\S]*)$/.exec(body);
  let prefix = "";
  let value;
  try {
    value = JSON.parse(body);
  } catch {
    if (!pair) throw new Error(`Cannot read a dataset line carrying an asset icon note: ${line.slice(0, 200)}`);
    prefix = `${pair[1]}: `;
    value = JSON.parse(pair[2]);
  }
  await walk(value, fix);
  return `${indent}${prefix}${JSON.stringify(value)}${comma}`;
}

async function walk(node, fix) {
  if (Array.isArray(node)) {
    for (const item of node) await walk(item, fix);
    return;
  }
  if (!node || typeof node !== "object") return;
  if (typeof node.iconPath === "string" && node.iconPath.startsWith("asset:")) await fix(node);
  for (const value of Object.values(node)) await walk(value, fix);
}
