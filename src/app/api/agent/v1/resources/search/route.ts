import { agentMethodNotAllowed, agentPost } from "@/lib/agent-api/route";
import { resourcesSearchRequest } from "@/lib/agent-api/schemas";
import { searchResources } from "@/lib/agent-api/resources";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const POST = agentPost("resources/search", resourcesSearchRequest, searchResources);
export const GET = agentMethodNotAllowed;
