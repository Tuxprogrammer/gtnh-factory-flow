import { NextResponse } from "next/server";
import { prewarmDatasetVersion } from "@/lib/server/dataset-query";
import { getResourcePopularity } from "@/lib/server/resource-popularity";
import { agentApiToken } from "@/lib/agent-api/auth";
import { warmAgentApi } from "@/lib/agent-api/warm";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(
  request: Request,
  { params }: { params: Promise<{ versionId: string }> },
) {
  const startedAt = Date.now();

  try {
    const { versionId } = await params;
    const url = new URL(request.url);
    const includeShards = url.searchParams.get("includeShards") === "1";

    // The item list's default sort reads the community ranking and never
    // waits for it, so start the first sweep at boot rather than on the
    // first visitor's request. Fire and forget: a stalled database must not
    // hold up the prewarm.
    getResourcePopularity();
    await prewarmDatasetVersion(versionId, { includeShards });
    // The agent API's own lazy indexes, when it is on (instrumentation's prewarm does not reach route modules).
    if (agentApiToken()) {
      await warmAgentApi();
    }

    return NextResponse.json(
      {
        ok: true,
        datasetVersionId: versionId,
        includeShards,
        durationMs: Date.now() - startedAt,
      },
      { headers: datasetCacheHeaders() },
    );
  } catch (error) {
    return NextResponse.json(
      { error: error instanceof Error ? error.message : "Dataset prewarm failed." },
      { status: 500, headers: datasetCacheHeaders() },
    );
  }
}

function datasetCacheHeaders() {
  return {
    "Cache-Control": "no-store",
  };
}
