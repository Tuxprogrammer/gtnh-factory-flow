import { withServedDatasetPaths } from "@/lib/datasets/served-paths";
import { getDefaultDatasetVersionId, loadManifest } from "./dataset-query";

/**
 * `value` with every path into a dataset version this server does not serve moved onto its default version
 * (`withServedDatasetPaths`, the rule the client applies to an opened plan). For what the server reads from a stored
 * row and draws itself, such as a post's card image: the row names the dataset the post was made on. Without a
 * manifest (no dataset installed) the value is returned as it is.
 */
export async function onServerDataset<T>(value: T): Promise<T> {
  try {
    const [manifest, current] = await Promise.all([loadManifest(), getDefaultDatasetVersionId()]);
    if (!current) return value;
    return withServedDatasetPaths(value, new Set(manifest.versions.map((version) => version.id)), current);
  } catch {
    return value;
  }
}
