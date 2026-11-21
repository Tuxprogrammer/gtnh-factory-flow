import { agentMethodNotAllowed, agentPost } from "@/lib/agent-api/route";
import { plansDiffRequest } from "@/lib/agent-api/delivery-schemas";
import { diffPlan } from "@/lib/agent-api/handoff";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const POST = agentPost("plans/diff", plansDiffRequest, diffPlan);
export const GET = agentMethodNotAllowed;
