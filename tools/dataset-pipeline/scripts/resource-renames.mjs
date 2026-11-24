import fs from "node:fs";
import path from "node:path";

// Items the game renames in old saves. When a pack moves an item to a new id
// (IC2's plantball became a GregTech meta item), the mod that moved it
// registers a Postea replacement and worlds convert old stacks on load. The
// exporter reads that table from the running game (`resourceRenames` in the
// raw export); this module turns it into resource ids, the dataset's
// `resource-renames.json` sidecar, and the rule that applies it. The website
// keeps the same rule in src/lib/datasets/resource-renames.ts.
//
// A row is { from, kind, to }. `from` ending in `@*` is every meta of that
// item (Postea's wildcard); a `from` with a meta wins over the wildcard. `to`
// ending in `@*` keeps the stack's own meta. Custom Postea handlers are code,
// not pairs: they are listed by id under `handlers` and never applied.

export const RESOURCE_RENAMES_FILE = "resource-renames.json";
const WILDCARD = 32767;

function canonical(registryName) {
  const name = String(registryName ?? "").trim();
  const colon = name.indexOf(":");
  return colon < 0
    ? name.toLowerCase()
    : `${name.slice(0, colon).toLowerCase()}:${name.slice(colon + 1).toLowerCase()}`;
}

function withMeta(name, meta) {
  if (meta === WILDCARD) return `${name}@*`;
  return meta === 0 ? name : `${name}@${meta}`;
}

/** The sidecar from the raw export's `resourceRenames`, or undefined when the export has none. */
export function resourceRenamesFromExport(raw) {
  if (!raw || !Array.isArray(raw.items)) return undefined;
  const renames = [];
  const seen = new Set();
  for (const row of raw.items) {
    const fromName = canonical(row.from);
    const toName = canonical(row.to);
    if (!fromName || !toName || !Number.isInteger(row.fromMeta) || !Number.isInteger(row.toMeta)) continue;
    const from = withMeta(fromName, row.fromMeta);
    const to = withMeta(toName, row.toMeta);
    if (from === to || seen.has(from)) continue;
    seen.add(from);
    renames.push({ from, kind: "item", to });
  }
  renames.sort((a, b) => (a.from < b.from ? -1 : a.from > b.from ? 1 : 0));
  // A lambda's class name ends in the address the JVM gave it this launch (`$$Lambda/0x...`): cut there, then
  // sort, so two exports of one pack write the same file.
  const handlers = (Array.isArray(raw.handlers) ? raw.handlers : [])
    .map((row) => ({ from: canonical(row.from), handler: String(row.handler ?? "").replace(/\$\$Lambda[$/].*$/, "$$$$Lambda") }))
    .filter((row) => row.from)
    .sort((a, b) => (a.from + "\0" + a.handler < b.from + "\0" + b.handler ? -1 : a.from + "\0" + a.handler > b.from + "\0" + b.handler ? 1 : 0));
  return { schemaVersion: 1, source: String(raw.source ?? "postea"), renames, handlers };
}

/** A lookup for `renameResourceId`: from id -> to id. */
export function renameLookup(table) {
  return new Map((table?.renames ?? []).map((row) => [row.from, row.to]));
}

/** The id the game turns `id` into, or `id` itself. Fluids and other kinds have no renames. */
export function renameResourceId(lookup, id, kind = "item") {
  if (kind !== "item" || lookup.size === 0 || typeof id !== "string") return id;
  const at = id.lastIndexOf("@");
  const hasMeta = at > 0 && /^\d+$/.test(id.slice(at + 1));
  const name = hasMeta ? id.slice(0, at) : id;
  const meta = hasMeta ? Number(id.slice(at + 1)) : 0;
  const to = lookup.get(id) ?? lookup.get(`${name}@*`);
  if (!to) return id;
  return to.endsWith("@*") ? withMeta(to.slice(0, -2), meta) : to;
}

/** The sidecar beside a dataset, or undefined. */
export function readResourceRenamesSidecar(datasetDir) {
  const file = path.join(datasetDir, RESOURCE_RENAMES_FILE);
  if (!fs.existsSync(file)) return undefined;
  const value = JSON.parse(fs.readFileSync(file, "utf8"));
  if (value?.schemaVersion !== 1 || !Array.isArray(value.renames)) {
    throw new Error(`${file}: expected { schemaVersion: 1, renames: [...] }.`);
  }
  return value;
}
