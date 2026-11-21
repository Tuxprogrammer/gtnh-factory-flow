import { agentMethodNotAllowed, agentPost } from "@/lib/agent-api/route";
import { plansSetTargetsRequest } from "@/lib/agent-api/plan-schemas";
import { setTargets } from "@/lib/agent-api/plans";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const POST = agentPost("plans/set-targets", plansSetTargetsRequest, setTargets);
export const GET = agentMethodNotAllowed;
