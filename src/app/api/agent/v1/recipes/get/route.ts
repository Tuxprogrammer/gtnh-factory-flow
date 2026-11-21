import { agentMethodNotAllowed, agentPost } from "@/lib/agent-api/route";
import { recipesGetRequest } from "@/lib/agent-api/schemas";
import { getRecipe } from "@/lib/agent-api/recipe-get";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const POST = agentPost("recipes/get", recipesGetRequest, getRecipe);
export const GET = agentMethodNotAllowed;
