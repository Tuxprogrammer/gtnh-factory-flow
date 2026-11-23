/**
 * Dataset file paths a saved plan carries, moved onto a version the site serves.
 *
 * Every icon, atlas and NEI picture in a plan is a path into the dataset it was made on
 * (`/datasets/gtnh/<version>/textures/...`). When the site moves to another dataset version the old version's files
 * are no longer served, so those paths would only ever answer 404. The pipeline names a texture by what it draws (the
 * stack it rendered, a fluid's pixels), so the same file name under the current version is the same picture; a
 * resource the current dataset lacks has no file there, and its icon falls back as any missing picture does.
 *
 * A path into a version the site still serves is left alone, and so is anything that is not a dataset path. The
 * in-game planner's own data (`gtnhplanner`) is opaque here and never rewritten. Pure: no store, no DOM.
 */

const DATASET_ROOT = "/datasets/gtnh/";

/** `path` under `current` when it names a dataset version not in `served`; otherwise `path` itself. */
export function servedDatasetPath(path: string, served: ReadonlySet<string>, current: string): string {
  if (!path.startsWith(DATASET_ROOT)) return path;
  const end = path.indexOf("/", DATASET_ROOT.length);
  if (end <= DATASET_ROOT.length) return path;
  const version = path.slice(DATASET_ROOT.length, end);
  return version === current || served.has(version) ? path : DATASET_ROOT + current + path.slice(end);
}

/**
 * `value` with every dataset path in it on a served version (`servedDatasetPath`). Unchanged parts keep their
 * identity, so a plan with nothing to move comes back as the same object and nothing downstream re-renders.
 */
export function withServedDatasetPaths<T>(value: T, served: ReadonlySet<string>, current: string): T {
  const walk = (node: unknown): unknown => {
    if (typeof node === "string") return servedDatasetPath(node, served, current);
    if (Array.isArray(node)) {
      let out: unknown[] | undefined;
      for (let index = 0; index < node.length; index += 1) {
        const item: unknown = node[index];
        const next = walk(item);
        if (next !== item) {
          out ??= node.slice();
          out[index] = next;
        }
      }
      return out ?? node;
    }
    if (node && typeof node === "object" && isPlainObject(node)) {
      let out: Record<string, unknown> | undefined;
      for (const [key, item] of Object.entries(node)) {
        if (key === "gtnhplanner") continue;
        const next = walk(item);
        if (next !== item) {
          out ??= { ...node };
          out[key] = next;
        }
      }
      return out ?? node;
    }
    return node;
  };
  return walk(value) as T;
}

function isPlainObject(value: object): value is Record<string, unknown> {
  const proto = Object.getPrototypeOf(value) as unknown;
  return proto === Object.prototype || proto === null;
}
