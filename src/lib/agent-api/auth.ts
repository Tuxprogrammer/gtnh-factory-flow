import { createHash, timingSafeEqual } from "node:crypto";
import { agentErrorResponse } from "./errors";

/**
 * The agent API is off unless `AGENT_API_TOKEN` is set: every route then answers a bare 404, so an instance that never
 * configured it shows nothing at those paths. A set token is required as `Authorization: Bearer <token>`; anything
 * else is 401. No cookie or session is read. The compare runs over SHA-256 digests so it is constant time whatever
 * length the caller sends.
 */
export function agentApiToken(): string | undefined {
  const token = process.env.AGENT_API_TOKEN?.trim();
  return token ? token : undefined;
}

function digest(value: string): Buffer {
  return createHash("sha256").update(value, "utf8").digest();
}

export function bearerToken(request: Request): string | undefined {
  const header = request.headers.get("authorization");
  const match = header ? /^Bearer\s+(\S+)\s*$/i.exec(header) : null;
  return match?.[1];
}

/** The response that stops the call (404 when the API is off, 401 on a wrong or missing token), or null to go on. */
export function checkAgentAuth(request: Request): Response | null {
  const token = agentApiToken();
  if (!token) {
    return new Response("Not Found", { status: 404 });
  }
  const given = bearerToken(request);
  if (!given || !timingSafeEqual(digest(given), digest(token))) {
    return agentErrorResponse("unauthorized", "Missing or wrong bearer token.");
  }
  return null;
}
