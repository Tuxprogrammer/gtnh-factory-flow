import { agentMethodNotAllowed, agentPost } from "@/lib/agent-api/route";
import { plansExpandInputsRequest } from "@/lib/agent-api/expand-schemas";
import { expandInputs } from "@/lib/agent-api/expand";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const POST = agentPost("plans/expand-inputs", plansExpandInputsRequest, expandInputs);
export const GET = agentMethodNotAllowed;
