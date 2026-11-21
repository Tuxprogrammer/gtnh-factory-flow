import { agentMethodNotAllowed, agentPost } from "@/lib/agent-api/route";
import { plansAddCardsRequest } from "@/lib/agent-api/plan-schemas";
import { addCards } from "@/lib/agent-api/plans";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const POST = agentPost("plans/add-cards", plansAddCardsRequest, addCards);
export const GET = agentMethodNotAllowed;
