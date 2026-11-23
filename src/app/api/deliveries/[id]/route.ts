import { DELIVERY_ID_PATTERN, readDelivery } from "@/lib/agent-api/deliveries";
import { clientAddress, isCommunityConfigured, saltedKey } from "@/lib/server/community";
import { takeMemoryToken } from "@/lib/server/memory-rate";
import { onServerDataset } from "@/lib/server/served-dataset-paths";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** Reads per address per minute: the game opens one entry per command, the site one per link. */
const DELIVERY_READS_PER_MINUTE = 60;

function answer(status: number, body: unknown): Response {
  return Response.json(body, { status, headers: { "Cache-Control": "no-store" } });
}

/**
 * One plan the assistant delivered (`d...`) or one proposal it made (`p...`), by id, for the in-game planner and the
 * site. Read-only, no account and no agent token: the id is random and long enough not to be guessed. Serves stored
 * entries whether or not the agent API is switched on. Answers only that entry, never who it was made for.
 */
export async function GET(request: Request, { params }: { params: Promise<{ id: string }> }): Promise<Response> {
  if (!takeMemoryToken(`deliveries:${saltedKey(clientAddress(request))}`, DELIVERY_READS_PER_MINUTE, 60_000)) {
    return answer(429, { error: "Too many requests. Try again in a minute." });
  }
  const { id } = await params;
  if (!DELIVERY_ID_PATTERN.test(id) || !isCommunityConfigured()) {
    return answer(404, { error: "Plan not found." });
  }
  try {
    // A stored plan names the dataset it was made on: its paths are read from the version this server serves.
    const entry = await onServerDataset(await readDelivery(id));
    if (!entry) {
      return answer(404, { error: "Plan not found." });
    }
    const head = { id: entry.id, name: entry.name, createdAt: entry.createdAt, expiresAt: entry.expiresAt };
    return entry.kind === "delivery"
      ? answer(200, { ...head, kind: "delivery", plan: entry.payload })
      : answer(200, { ...head, kind: "proposal", basePlanId: entry.basePlanId ?? "", proposal: entry.payload });
  } catch (error) {
    console.error("[deliveries] read failed", error);
    return answer(503, { error: "Plans cannot be read right now. Try again in a few minutes." });
  }
}
