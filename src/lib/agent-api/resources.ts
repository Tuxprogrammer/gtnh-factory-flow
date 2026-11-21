import type { z } from "zod";
import { queryDatasetResources } from "@/lib/server/dataset-query";
import { agentDatasetVersion, withDataset } from "./dataset";
import { nameOf } from "./format";
import { fitList } from "./size";
import type { resourcesSearchRequest, resourcesSearchResponse } from "./schemas";

/** Resources by name, in the site's own search order (exact names and common items first), each with its id. */
export async function searchResources(
  body: z.infer<typeof resourcesSearchRequest>,
): Promise<z.infer<typeof resourcesSearchResponse>> {
  return withDataset(async () => {
    const versionId = await agentDatasetVersion();
    const page = await queryDatasetResources(versionId, {
      query: body.text,
      offset: 0,
      limit: body.limit,
      kind: body.kind,
    });
    const results = page.resources.map((resource) => ({
      id: resource.id,
      kind: resource.kind,
      name: nameOf(resource),
    }));
    return fitList(results, (items, truncated) => ({
      dataset: versionId,
      results: items,
      total: page.total,
      ...(truncated ? { truncated } : {}),
    }));
  });
}
