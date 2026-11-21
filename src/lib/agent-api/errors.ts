/**
 * The agent API's error envelope, `{ error: { code, message } }`. The codes are the contract
 * (docs/agent-api.md): a client branches on `code`, never on `message`, so a code is never renamed.
 */
export const AGENT_ERROR_CODES = [
  "unauthorized",
  "bad_request",
  "unknown_resource",
  "unknown_recipe",
  "no_recipe",
  "tier_too_low",
  "plan_not_found",
  "plan_too_big",
  "rate_limited",
  "dataset_unavailable",
  "internal",
] as const;

export type AgentErrorCode = (typeof AGENT_ERROR_CODES)[number];

export const AGENT_ERROR_STATUS: Record<AgentErrorCode, number> = {
  unauthorized: 401,
  bad_request: 400,
  unknown_resource: 404,
  unknown_recipe: 404,
  no_recipe: 422,
  tier_too_low: 422,
  plan_not_found: 404,
  plan_too_big: 413,
  rate_limited: 429,
  dataset_unavailable: 503,
  internal: 500,
};

/** Thrown by endpoint code; the route wrapper turns it into the envelope with the code's status. */
export class AgentApiError extends Error {
  readonly code: AgentErrorCode;

  constructor(code: AgentErrorCode, message: string) {
    super(message);
    this.name = "AgentApiError";
    this.code = code;
  }
}

export function agentErrorResponse(code: AgentErrorCode, message: string): Response {
  return Response.json({ error: { code, message } }, { status: AGENT_ERROR_STATUS[code] });
}
