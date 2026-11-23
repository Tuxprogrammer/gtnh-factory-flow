import { useMemo } from "react";
import { onServedDataset, useFactoryStore } from "./factory-store";

/**
 * `value` with its dataset paths on a version the site serves, by the rule an opened plan follows (`onServedDataset`).
 * For what is drawn from a stored summary rather than the open plan: a design's or a post's face, a post's needs and
 * outputs. Those were saved on whatever dataset was current then, and an old version's files are no longer served.
 * Re-runs when the dataset or the manifest loads, so a face drawn before then is moved once they arrive.
 */
export function useServedPaths<T>(value: T): T {
  const dataset = useFactoryStore((state) => state.dataset);
  const datasetManifest = useFactoryStore((state) => state.datasetManifest);
  return useMemo(() => onServedDataset(value, { dataset, datasetManifest }), [value, dataset, datasetManifest]);
}
