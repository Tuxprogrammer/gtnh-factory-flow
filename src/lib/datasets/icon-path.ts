/**
 * An icon path the browser can load, or undefined. A dataset published before the texture step resolved
 * `asset:<mod>:<path>` notes (tools/dataset-pipeline/scripts/asset-icon-hints.mjs) still carries them on the add-on
 * Thaumcraft aspects; such a note names a file inside a mod jar, and requesting it only logs an error.
 */
export function servableIconPath(path: string | undefined): string | undefined {
  return path && !path.startsWith("asset:") ? path : undefined;
}
