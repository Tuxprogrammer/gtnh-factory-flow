import type { z } from "zod";
import { checkAgentAuth } from "./auth";
import { AgentApiError, agentErrorResponse, type AgentErrorCode } from "./errors";

/** Request bodies are small JSON objects; anything bigger is refused before parsing. */
export const MAX_REQUEST_BYTES = 64 * 1024;

export interface AgentCallContext {
  route: string;
  /** The player the assistant acts for, as sent (audit only; never trusted for access). */
  playerName?: string;
}

/**
 * One audit line per call: route, player name, outcome, time and response size. Never the token, never a request or
 * response body. The player name is reduced to Minecraft name characters so a crafted name cannot forge log lines.
 */
export function auditLine(
  route: string,
  playerName: string | undefined,
  outcome: number | AgentErrorCode,
  ms: number,
  bytes?: number,
): string {
  const player = playerName ? playerName.replace(/[^A-Za-z0-9_]/g, "").slice(0, 32) || "-" : "-";
  const size = bytes === undefined ? "" : ` bytes=${bytes}`;
  return `[agent-api] route=${route} player=${player} outcome=${outcome} ms=${Math.round(ms)}${size}`;
}

function firstIssue(error: z.ZodError): string {
  const issue = error.issues[0];
  if (!issue) {
    return "Invalid request.";
  }
  const where = issue.path.length ? `${issue.path.join(".")}: ` : "";
  return `${where}${issue.message}`;
}

/**
 * Builds a route's POST handler: auth, JSON body, Zod validation, the call, the error envelope and the audit line.
 * `run` throws `AgentApiError` for an answer the client can act on; any other throw is logged and answered `internal`.
 */
export function agentPost<S extends z.ZodType>(
  route: string,
  schema: S,
  run: (body: z.infer<S>, context: AgentCallContext) => Promise<unknown>,
) {
  return async function POST(request: Request): Promise<Response> {
    const started = performance.now();
    const denied = checkAgentAuth(request);
    if (denied) {
      if (denied.status === 401) {
        console.info(auditLine(route, undefined, "unauthorized", performance.now() - started));
      }
      return denied;
    }

    let playerName: string | undefined;
    const finish = (response: Response, outcome: number | AgentErrorCode, bytes?: number) => {
      console.info(auditLine(route, playerName, outcome, performance.now() - started, bytes));
      return response;
    };
    const fail = (code: AgentErrorCode, message: string) =>
      finish(agentErrorResponse(code, message), code);

    let raw: unknown;
    try {
      const text = await request.text();
      if (Buffer.byteLength(text, "utf8") > MAX_REQUEST_BYTES) {
        return fail("bad_request", `Request body is over ${MAX_REQUEST_BYTES / 1024} KB.`);
      }
      raw = text.trim() ? JSON.parse(text) : {};
    } catch {
      return fail("bad_request", "Request body must be JSON.");
    }
    if (raw && typeof raw === "object" && "player" in raw) {
      const player = (raw as { player?: { name?: unknown } }).player;
      playerName = typeof player?.name === "string" ? player.name : undefined;
    }
    const parsed = schema.safeParse(raw);
    if (!parsed.success) {
      return fail("bad_request", firstIssue(parsed.error));
    }

    try {
      const result = await run(parsed.data, { route, playerName });
      const body = JSON.stringify(result);
      return finish(
        new Response(body, {
          status: 200,
          headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
        }),
        200,
        Buffer.byteLength(body, "utf8"),
      );
    } catch (error) {
      if (error instanceof AgentApiError) {
        return fail(error.code, error.message);
      }
      console.error(`[agent-api] route=${route} failed`, error);
      return fail("internal", "Something went wrong on the server.");
    }
  };
}

/** Every agent route takes POST only; other methods get the same auth answer first, then bad_request. */
export async function agentMethodNotAllowed(request: Request): Promise<Response> {
  return checkAgentAuth(request) ?? agentErrorResponse("bad_request", "Use POST with a JSON body.");
}
