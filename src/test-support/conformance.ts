/**
 * Where a test finds the conformance tree (`conformance/` at the repository root): the one copy of the test data the
 * website and the game must agree on. Node only; never import this from app code.
 *
 * The tree is found from this file's own place, or from `GTNH_CONFORMANCE` when set (a directory holding
 * `manifest.json`). Tests read it by `conformancePath("solver", "up-to", ...)`, never by a path of their own, and
 * nothing is fetched or generated at test time. The JVM side has the same rule (`jvm/conformance-support`).
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const CONFORMANCE_DIR: string = process.env.GTNH_CONFORMANCE
  ? path.resolve(process.env.GTNH_CONFORMANCE)
  : fileURLToPath(new URL("../../conformance/", import.meta.url));

/** A path under the tree. Not checked to exist (a data writer creates it). */
export function conformancePath(...parts: string[]): string {
  return path.join(CONFORMANCE_DIR, ...parts);
}

/** A file of the tree, as bytes. Throws when it is not there. */
export function readConformanceBytes(...parts: string[]): Buffer {
  return readFileSync(conformancePath(...parts));
}

export function readConformanceText(...parts: string[]): string {
  return readFileSync(conformancePath(...parts), "utf8");
}

export function readConformanceJson(...parts: string[]): unknown {
  return JSON.parse(readConformanceText(...parts));
}

/** The regular files directly under a directory of the tree, sorted by name. */
export function listConformance(...parts: string[]): string[] {
  const dir = conformancePath(...parts);
  return readdirSync(dir)
    .filter((f) => statSync(path.join(dir, f)).isFile())
    .sort();
}
