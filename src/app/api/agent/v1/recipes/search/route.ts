import { agentMethodNotAllowed, agentPost } from "@/lib/agent-api/route";
import { recipesSearchRequest } from "@/lib/agent-api/schemas";
import { searchRecipes } from "@/lib/agent-api/recipes";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const POST = agentPost("recipes/search", recipesSearchRequest, searchRecipes);
export const GET = agentMethodNotAllowed;
