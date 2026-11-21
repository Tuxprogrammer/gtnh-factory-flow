import { agentMethodNotAllowed, agentPost } from "@/lib/agent-api/route";
import { plansSolveRequest } from "@/lib/agent-api/plan-schemas";
import { solveDraft } from "@/lib/agent-api/solve";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const POST = agentPost("plans/solve", plansSolveRequest, solveDraft);
export const GET = agentMethodNotAllowed;
