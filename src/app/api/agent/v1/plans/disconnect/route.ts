import { agentMethodNotAllowed, agentPost } from "@/lib/agent-api/route";
import { plansDisconnectRequest } from "@/lib/agent-api/plan-schemas";
import { disconnectWire } from "@/lib/agent-api/plans";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const POST = agentPost("plans/disconnect", plansDisconnectRequest, disconnectWire);
export const GET = agentMethodNotAllowed;
