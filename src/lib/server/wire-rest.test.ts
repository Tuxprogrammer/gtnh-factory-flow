import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { WebSocket } from "ws";
import { LiveService } from "@/live/service";
import { supabasePlanStore } from "@/live/store";
import { useInProcessLocalDb } from "@/lib/server/local-db/client";
import { openLocalDatabase, type LocalDatabase } from "@/lib/server/local-db/database";
import { getCommunityDb, makeSessionToken } from "@/lib/server/community";
import { fill, readWire, type RestStep } from "@/test-support/wire-server";
import { expectProblems, shapeProblems } from "@/test-support/wire-shape";
import { corePresent } from "@/test-support/core-solve";
import * as loginRoute from "@/app/api/community/auth/login/route";
import * as registerRoute from "@/app/api/community/auth/register/route";
import * as meRoute from "@/app/api/community/auth/me/route";
import * as libraryRoute from "@/app/api/library/route";
import * as designRoute from "@/app/api/library/designs/[designId]/route";
import * as membersRoute from "@/app/api/library/designs/[designId]/members/route";
import * as memberRoute from "@/app/api/library/designs/[designId]/members/[userId]/route";
import * as searchRoute from "@/app/api/accounts/search/route";
import * as plansRoute from "@/app/api/community/plans/route";
import * as planRoute from "@/app/api/community/plans/[planId]/route";
import * as downloadRoute from "@/app/api/community/plans/[planId]/download/route";
import * as feedbackRoute from "@/app/api/feedback/route";
import * as resourcesRoute from "@/app/api/datasets/[versionId]/resources/route";

// The feedback route's three outside effects (a claim in the database, a stored file, the GitHub call) are the same
// stand-ins its own test uses; everything the mod can see of the route (the checks, the status, the body) is real.
vi.mock("@/lib/server/feedback-guard", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/server/feedback-guard")>();
  return { ...actual, claimIssue: vi.fn(async () => undefined) };
});
vi.mock("@/lib/server/feedback-files", () => ({
  storeFeedbackFile: vi.fn(
    async (origin: string, id: string, file: string) => `${origin}/api/feedback/files/${id}/${file}`,
  ),
}));
vi.mock("@/lib/server/feedback", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/server/feedback")>();
  return {
    ...actual,
    openIssue: vi.fn(async () => ({ number: 42, url: "https://example.invalid/issues/42" })),
  };
});

/**
 * The website's side of the wire contract (conformance/wire): every step of rest.json is sent to the real route
 * handlers, in the local database mode, and the answer must have the contract's status, shape and expected values.
 * The live service's two HTTP routes run against a real LiveService on a loopback port. The mod's side of the same
 * steps is jvm/mod/.../cloud/wire/WireRestTest.java.
 */

const wire = readWire();
const ORIGIN = "http://localhost:3000";
const DATASET_DIR = path.join(process.cwd(), "public", "datasets", "gtnh");

let local: LocalDatabase;
let service: LiveService;
let livePort = 0;
let journalDir = "";
const users: Record<string, { id: string; token: string }> = {};
const vars: Record<string, unknown> = {};

const params = <T extends object>(value: T) => ({ params: Promise.resolve(value) });

type Handler = (request: Request, context: { params: Promise<never> }) => Promise<Response> | Response;

/** The route handler for a request, with its path parameters. */
function route(method: string, pathname: string): { handler: Handler; context: unknown } {
  const m = method.toUpperCase();
  const table: Array<[RegExp, Record<string, Handler>, string[]]> = [
    [/^\/api\/community\/auth\/login$/, loginRoute as never, []],
    [/^\/api\/community\/auth\/register$/, registerRoute as never, []],
    [/^\/api\/community\/auth\/me$/, meRoute as never, []],
    [/^\/api\/library$/, libraryRoute as never, []],
    [/^\/api\/library\/designs\/([^/]+)$/, designRoute as never, ["designId"]],
    [/^\/api\/library\/designs\/([^/]+)\/members$/, membersRoute as never, ["designId"]],
    [/^\/api\/library\/designs\/([^/]+)\/members\/([^/]+)$/, memberRoute as never, ["designId", "userId"]],
    [/^\/api\/accounts\/search$/, searchRoute as never, []],
    [/^\/api\/community\/plans$/, plansRoute as never, []],
    [/^\/api\/community\/plans\/([^/]+)$/, planRoute as never, ["planId"]],
    [/^\/api\/community\/plans\/([^/]+)\/download$/, downloadRoute as never, ["planId"]],
    [/^\/api\/feedback$/, feedbackRoute as never, []],
    [/^\/api\/datasets\/([^/]+)\/resources$/, resourcesRoute as never, ["versionId"]],
  ];
  for (const [pattern, module, names] of table) {
    const hit = pattern.exec(pathname);
    if (!hit) continue;
    const handler = module[m];
    if (!handler) throw new Error(`${pathname} has no ${m} handler`);
    const found = Object.fromEntries(names.map((name, i) => [name, decodeURIComponent(hit[i + 1]!)]));
    return { handler, context: params(found) };
  }
  throw new Error(`no route for ${pathname}`);
}

async function makeUser(username: string) {
  const { data, error } = await getCommunityDb()
    .from("community_users")
    .insert({ username, password_hash: "x:y" })
    .select("id")
    .single();
  expect(error).toBeNull();
  const id = (data as { id: string }).id;
  users[username] = { id, token: makeSessionToken(id) };
  vars[`${username}.id`] = id;
  vars[`${username}.token`] = users[username]!.token;
}

interface Answer {
  status: number;
  headers: Headers;
  json: unknown;
}

async function send(step: RestStep): Promise<Answer> {
  const req = fill(step.request, vars);
  const headers: Record<string, string> = {};
  if (step.as && req.auth === "session") headers.cookie = `gtnh_session=${users[step.as]!.token}`;
  if (req.body !== undefined) headers["content-type"] = "application/json";
  const body = req.body === undefined ? undefined : JSON.stringify(req.body);
  let response: Response;
  if (req.path.startsWith("/live/")) {
    response = await fetch(`http://127.0.0.1:${livePort}${req.path}`, {
      method: req.method,
      headers,
      body,
    });
  } else {
    const url = new URL(req.path, "http://localhost");
    const { handler, context } = route(req.method, url.pathname);
    response = await handler(
      new Request(`http://localhost${req.path}`, { method: req.method, headers, body }),
      context as never,
    );
  }
  const text = await response.text();
  return { status: response.status, headers: response.headers, json: text ? JSON.parse(text) : undefined };
}

/** A game connection of `who` on `server` as `mcName`, held open until closed (what /live/nearby lists). */
async function gameSocket(who: string, mcName: string, server: string): Promise<WebSocket> {
  const ws = new WebSocket(`ws://127.0.0.1:${livePort}/live`, {
    headers: { cookie: `gtnh_session=${users[who]!.token}` },
  });
  await new Promise<void>((resolve, reject) => {
    ws.once("open", () => resolve());
    ws.once("error", reject);
  });
  const welcomed = new Promise<void>((resolve) =>
    ws.on("message", (data) => {
      if ((JSON.parse(data.toString()) as { t: string }).t === "welcome") resolve();
    }),
  );
  ws.send(JSON.stringify({ t: "hello", v: 1, client: "game", clientVersion: "test", game: { server, mcName } }));
  await welcomed;
  return ws;
}

function datasetId(): string | undefined {
  const file = path.join(DATASET_DIR, "datasets.manifest.json");
  if (!existsSync(file)) return undefined;
  const manifest = JSON.parse(readFileSync(file, "utf8")) as { versions: Array<{ id: string; gtnhVersion: string }> };
  return manifest.versions.find((v) => v.gtnhVersion === "2.9.0")?.id ?? manifest.versions[0]?.id;
}

beforeAll(async () => {
  vi.stubEnv("LOCAL_DB", "1");
  vi.stubEnv("COMMUNITY_HASH_SALT", "wire-rest-test-salt");
  vi.stubEnv("GITHUB_FEEDBACK_TOKEN", "test-token");
  vi.stubEnv("NEXT_PUBLIC_SITE_URL", "");
  local = await openLocalDatabase();
  useInProcessLocalDb(local.handle);
  for (const name of wire.rest.accounts) await makeUser(name);
  vars["dataset.id"] = datasetId() ?? "";
  vars.plan = wire.plans.plan;
  vars.emptyPlan = wire.plans.emptyPlan;
  journalDir = mkdtempSync(path.join(tmpdir(), "gtnh-wire-"));
  service = new LiveService({
    store: supabasePlanStore(),
    journalDir,
    allowedOrigins: [ORIGIN],
    log: () => undefined,
    timing: { flushMs: 100, renewMs: 1000, leaseMs: 5000, lingerMs: 0 },
  });
  livePort = await service.start(0);
}, 60_000);

afterAll(async () => {
  await service?.stop();
  useInProcessLocalDb(undefined);
  await local?.close();
  if (journalDir) rmSync(journalDir, { recursive: true, force: true });
  vi.unstubAllEnvs();
});

describe("the wire contract, the website's REST routes and the live service's HTTP routes", () => {
  // The plan routes solve on the compiled core and answer 503 without it (src/lib/server/plan-engine.ts): say why here
  // rather than leave the plan steps' 503s to explain it.
  it("has the compiled core the plan routes solve on", () => {
    if (!corePresent)
      throw new Error("public/core has no compiled core: build it (node tools/copy-core.mjs --build, JDK 25)");
  });

  it("is ordered so every step can run: each placeholder is one the runner fills", () => {
    const ids = new Set<string>();
    for (const step of wire.rest.steps) {
      expect(ids.has(step.id), `duplicate step ${step.id}`).toBe(false);
      ids.add(step.id);
      expect(wire.shapes[step.shape.slice(1)], `${step.id}: shape ${step.shape}`).toBeDefined();
    }
  });

  for (const step of wire.rest.steps) {
    const skip = step.server?.skip;
    const needsDataset = step.server?.dataset && !vars["dataset.id"];
    // A runnable step is awaited in order, so the steps share the database as the contract's order says.
    const name = `${step.id}: ${step.request.method} ${step.request.path.split("?")[0]} -> ${step.status}`;
    if (skip || needsDataset) {
      // Listed as skipped with its reason in the name, never silently left out.
      it.skip(`${name} (${skip ?? "this checkout has no dataset under public/datasets/gtnh"})`, () => undefined);
      continue;
    }
    it(name, { timeout: 60_000 }, async () => {
      const held: WebSocket[] = [];
      try {
        for (const hook of step.before ?? []) {
          const [kind, ...rest] = hook.split(":");
          if (kind === "holdLease") {
            const until = new Date(Date.now() + 90_000).toISOString();
            const { error } = await getCommunityDb()
              .from("library_designs")
              .update({ live_lease_until: until })
              .eq("user_id", users.ann!.id)
              .eq("id", rest[0]!);
            expect(error).toBeNull();
          } else if (kind === "liveGame") {
            held.push(await gameSocket(rest[0]!, rest[1]!, rest[2]!));
          } else {
            throw new Error(`unknown hook ${hook}`);
          }
        }
        for (let i = 0; i < (step.prime ?? 0); i += 1) await send(step);
        const answer = await send(step);

        expect(answer.status, `${step.id} status (body ${JSON.stringify(answer.json)})`).toBe(step.status);
        expect(shapeProblems(wire.shapes, step.shape, answer.json), `${step.id} shape`).toEqual([]);
        const expected = fill(step.serverExpect ?? step.expect ?? {}, vars);
        expect(expectProblems(answer.json, expected), `${step.id} expect`).toEqual([]);
        for (const [header, spec] of Object.entries(step.headers ?? {})) {
          expect(answer.headers.get(header) ?? "", `${step.id} ${header}`).toMatch(new RegExp(spec.re));
        }
        for (const [variable, at] of Object.entries(step.capture ?? {})) {
          vars[variable] = (answer.json as Record<string, unknown>)[at];
          expect(typeof vars[variable]).toBe("string");
        }
      } finally {
        for (const ws of held) ws.close();
        if (step.before?.some((h) => h.startsWith("holdLease"))) {
          await getCommunityDb()
            .from("library_designs")
            .update({ live_lease_until: null })
            .eq("user_id", users.ann!.id);
        }
      }
    });
  }

  it("keeps a reason for every step it does not run, and runs most of them", () => {
    const total = wire.rest.steps.length;
    const notRun = wire.rest.steps.filter((s) => s.server?.skip || (s.server?.dataset && !vars["dataset.id"]));
    for (const step of notRun) expect((step.server?.skip ?? "dataset").length).toBeGreaterThan(5);
    expect(total - notRun.length).toBeGreaterThan(total * 0.7);
  });
});
