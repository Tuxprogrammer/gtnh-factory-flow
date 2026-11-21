import { agentMethodNotAllowed, agentPost } from "@/lib/agent-api/route";
import { plansDeliverRequest } from "@/lib/agent-api/delivery-schemas";
import { deliverPlan } from "@/lib/agent-api/handoff";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const POST = agentPost("plans/deliver", plansDeliverRequest, deliverPlan);
export const GET = agentMethodNotAllowed;
