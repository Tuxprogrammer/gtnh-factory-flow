import { agentMethodNotAllowed, agentPost } from "@/lib/agent-api/route";
import { plansRemoveCardsRequest } from "@/lib/agent-api/plan-schemas";
import { removeCards } from "@/lib/agent-api/plans";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const POST = agentPost("plans/remove-cards", plansRemoveCardsRequest, removeCards);
export const GET = agentMethodNotAllowed;
