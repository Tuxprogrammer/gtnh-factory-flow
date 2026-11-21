import { agentMethodNotAllowed, agentPost } from "@/lib/agent-api/route";
import { plansCreateRequest } from "@/lib/agent-api/plan-schemas";
import { createPlan } from "@/lib/agent-api/plans";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const POST = agentPost("plans/create", plansCreateRequest, createPlan);
export const GET = agentMethodNotAllowed;
