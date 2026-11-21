import { agentMethodNotAllowed, agentPost } from "@/lib/agent-api/route";
import { IMPORT_MAX_BYTES, plansImportRequest } from "@/lib/agent-api/delivery-schemas";
import { importPlan } from "@/lib/agent-api/handoff";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const POST = agentPost("plans/import", plansImportRequest, importPlan, { maxBytes: IMPORT_MAX_BYTES });
export const GET = agentMethodNotAllowed;
