import { agentMethodNotAllowed, agentPost } from "@/lib/agent-api/route";
import { plansGetRequest } from "@/lib/agent-api/plan-schemas";
import { getPlan } from "@/lib/agent-api/plans";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const POST = agentPost("plans/get", plansGetRequest, getPlan);
export const GET = agentMethodNotAllowed;
