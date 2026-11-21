import { agentMethodNotAllowed, agentPost } from "@/lib/agent-api/route";
import { plansConnectRequest } from "@/lib/agent-api/plan-schemas";
import { connectCards } from "@/lib/agent-api/plans";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const POST = agentPost("plans/connect", plansConnectRequest, connectCards);
export const GET = agentMethodNotAllowed;
