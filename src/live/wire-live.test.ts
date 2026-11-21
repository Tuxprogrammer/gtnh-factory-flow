import { mkdtempSync, rmSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { WebSocket } from "ws";
import { docKey } from "@/lib/live/protocol";
import { PROJECT_SCHEMA_VERSION } from "@/lib/model/types";
import { useInProcessLocalDb } from "@/lib/server/local-db/client";
import { openLocalDatabase, type LocalDatabase } from "@/lib/server/local-db/database";
import { getCommunityDb, makeSessionToken } from "@/lib/server/community";
import * as designRoute from "@/app/api/library/designs/[designId]/route";
import * as memberRoute from "@/app/api/library/designs/[designId]/members/[userId]/route";
import { fill, readWire, type LiveFrameStep } from "@/test-support/wire-server";
import { expectProblems, shapeProblems, valueAt } from "@/test-support/wire-shape";
import { LiveService, type LiveServiceOptions } from "./service";
import { supabasePlanStore } from "./store";

/**
 * The live service's side of the wire contract (conformance/wire/live.json): a real LiveService on a loopback port, in
 * the local database mode, driven by real WebSocket clients. Every connect case, every close code, every script and
 * every rule of order in the contract is played against it, and each frame it sends must have the contract's shape.
 * The mod's side of the same file is jvm/mod/.../cloud/wire/WireLiveTest.java.
 */

const wire = readWire();
const live = wire.live;
const shapes = wire.shapes;
const INTERNAL_TOKEN = "wire-live-internal";

type Frame = Record<string, unknown> & { t: string };

let local: LocalDatabase;
const users: Record<string, { id: string; cookie: string }> = {};
const journalDirs: string[] = [];
const services: LiveService[] = [];
const sockets: Wire[] = [];

/** A socket with every frame it hears kept, in order, and the close code once it closes. */
class Wire {
  readonly frames: Frame[] = [];
  closeCode?: number;
  private waiters: Array<{ test: (f: Frame) => boolean; resolve: (f: Frame) => void }> = [];

  private constructor(readonly ws: WebSocket) {
    ws.on("message", (data) => {
      const frame = JSON.parse(data.toString()) as Frame;
      this.frames.push(frame);
      this.waiters = this.waiters.filter((waiter) => {
        if (waiter.test(frame)) {
          waiter.resolve(frame);
          return false;
        }
        return true;
      });
    });
    ws.on("close", (code) => {
      this.closeCode = code;
    });
  }

  /** Opens the socket; a refused upgrade rejects with its HTTP status. */
  static open(
    port: number,
    options: { cookie?: string; origin?: string; path?: string; address?: string } = {},
  ): Promise<Wire> {
    const headers: Record<string, string> = {};
    if (options.cookie) headers.cookie = options.cookie;
    if (options.origin) headers.origin = options.origin;
    if (options.address) headers["x-client-ip"] = options.address;
    const ws = new WebSocket(`ws://127.0.0.1:${port}${options.path ?? live.path}`, { headers });
    const wire = new Wire(ws);
    sockets.push(wire);
    return new Promise((resolve, reject) => {
      ws.once("open", () => resolve(wire));
      ws.once("error", () => undefined);
      ws.once("unexpected-response", (_req, res) =>
        reject(Object.assign(new Error(`HTTP ${res.statusCode}`), { status: res.statusCode })),
      );
    });
  }

  send(frame: unknown): void {
    this.ws.send(JSON.stringify(frame));
  }

  /** The first frame of type `t` not yet taken, now or later. */
  next(t: string, timeoutMs = 10_000): Promise<Frame> {
    const found = this.frames.find((f) => f.t === t);
    if (found) {
      this.frames.splice(this.frames.indexOf(found), 1);
      return Promise.resolve(found);
    }
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(
            new Error(
              `no ${t} frame within ${timeoutMs} ms; unclaimed frames ${JSON.stringify(this.frames)}, close ${this.closeCode}`,
            ),
          ), timeoutMs);
      this.waiters.push({
        test: (f) => f.t === t,
        resolve: (f) => {
          clearTimeout(timer);
          this.frames.splice(this.frames.indexOf(f), 1);
          resolve(f);
        },
      });
    });
  }

  /** Waits for the close and returns its code. */
  async closed(timeoutMs = 10_000): Promise<number> {
    await until(() => this.closeCode !== undefined, timeoutMs);
    return this.closeCode!;
  }
}

async function until(check: () => boolean | Promise<boolean>, timeoutMs = 10_000): Promise<void> {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    if (await check()) return;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error("condition not met in time");
}

async function startService(
  options: Partial<LiveServiceOptions> = {},
): Promise<{ service: LiveService; port: number }> {
  const journalDir = mkdtempSync(path.join(tmpdir(), "gtnh-wire-live-"));
  journalDirs.push(journalDir);
  const service = new LiveService({
    store: supabasePlanStore(),
    journalDir,
    allowedOrigins: ["http://localhost:3000"],
    internalToken: INTERNAL_TOKEN,
    log: () => undefined,
    ...options,
    timing: { flushMs: 100, renewMs: 1000, leaseMs: 5000, lingerMs: 0, ...options.timing },
  });
  services.push(service);
  const port = await service.start(0);
  vi.stubEnv("LIVE_INTERNAL_URL", `http://127.0.0.1:${port}`);
  vi.stubEnv("LIVE_INTERNAL_TOKEN", INTERNAL_TOKEN);
  return { service, port };
}

async function makeUser(username: string) {
  const { data } = await getCommunityDb()
    .from("community_users")
    .insert({ username, password_hash: "x:y" })
    .select("id")
    .single();
  const id = (data as { id: string }).id;
  users[username] = { id, cookie: `gtnh_session=${makeSessionToken(id)}` };
}

let designCount = 0;

/** A design owned by `owner`, shared with `members`. */
async function makeDesign(owner: string, name: string, members: string[] = []) {
  designCount += 1;
  const designId = `design-wire-${designCount}`;
  const plan = {
    schemaVersion: PROJECT_SCHEMA_VERSION,
    id: `plan-wire-${designCount}`,
    name,
    recipes: [],
    nodes: [],
    storages: [],
    edges: [],
    fuelProfiles: [],
    tasks: [{ id: "t1", title: "First", status: "todo", order: "a0" }],
  };
  const db = getCommunityDb();
  const { error } = await db
    .from("library_designs")
    .insert({ user_id: users[owner]!.id, id: designId, name, plan });
  expect(error).toBeNull();
  for (const member of members) {
    await db
      .from("library_design_members")
      .insert({ owner_id: users[owner]!.id, design_id: designId, member_id: users[member]!.id });
  }
  return { designId, doc: docKey(users[owner]!.id, designId), plan };
}

/** The REST calls that change who may be in a plan; the route tells the service through its internal door. */
async function share(owner: string, designId: string, member: string, on: boolean) {
  const response = await memberRoute[on ? "PUT" : "DELETE"](
    new Request(`http://localhost/api/library/designs/${designId}/members/${users[member]!.id}`, {
      method: on ? "PUT" : "DELETE",
      headers: { cookie: users[owner]!.cookie },
    }),
    { params: Promise.resolve({ designId, userId: users[member]!.id }) },
  );
  expect(response.status).toBe(200);
}

beforeAll(async () => {
  vi.stubEnv("LOCAL_DB", "1");
  vi.stubEnv("COMMUNITY_HASH_SALT", "wire-live-test-salt");
  local = await openLocalDatabase();
  useInProcessLocalDb(local.handle);
  for (const name of ["ann", "bob", "carol"]) await makeUser(name);
}, 60_000);

afterEach(async () => {
  await Promise.all(
    sockets.splice(0).map(
      (socket) =>
        new Promise<void>((resolve) => {
          if (socket.closeCode !== undefined) return resolve();
          socket.ws.once("close", () => resolve());
          socket.ws.terminate();
        }),
    ),
  );
  await Promise.all(services.splice(0).map((service) => service.stop()));
});

afterAll(async () => {
  useInProcessLocalDb(undefined);
  await local?.close();
  for (const dir of journalDirs) rmSync(dir, { recursive: true, force: true });
  vi.unstubAllEnvs();
});

// ---------------------------------------------------------------------------
// The contract's own vocabulary

/** The frame type a shape names: `$ServerWelcome` -> "welcome". */
function typeOf(shape: string): string {
  const def = (shapes as Record<string, Record<string, unknown>>)[shape.replace(/^\$/, "")];
  const t = (def?.t as { "=": string } | undefined)?.["="];
  if (!t) throw new Error(`${shape} names no frame type`);
  return t;
}

function setPath(target: Record<string, unknown>, dotted: string, value: unknown): void {
  const parts = dotted.split(".");
  let at: any = target;
  parts.forEach((part, i) => {
    if (i === parts.length - 1) {
      at[part] = value;
      return;
    }
    if (at[part] === undefined) at[part] = /^\d+$/.test(parts[i + 1]!) ? [] : {};
    at = at[part];
  });
}

/** The frame a client step stands for: its own `frame`, or one built from `via` and `expect`. */
function clientFrame(step: LiveFrameStep, vars: Record<string, unknown>): Frame {
  if (step.frame) return fill(step.frame, vars) as Frame;
  const via = fill(step.via!, vars);
  const kind = via.split(":")[0]!;
  const frame: Record<string, unknown> = { t: kind === "open" ? "hello" : kind };
  for (const [key, value] of Object.entries(step.expect ?? {})) {
    setPath(frame, key, fill(value, vars));
  }
  return frame as Frame;
}

/** What `heard` says about a frame itself (a path that starts with its type); the rest is the mod's conclusion. */
function heardOf(frame: Frame, heard: Record<string, unknown> | undefined, vars: Record<string, unknown>) {
  const own: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(heard ?? {})) {
    if (key.startsWith(`${frame.t}.`)) own[key.slice(frame.t.length + 1)] = fill(value, vars);
  }
  return own;
}

/** A frame the service sent must have the step's shape, `expect` and own `heard` values. */
function checkServerFrame(step: LiveFrameStep, frame: Frame, vars: Record<string, unknown>, label: string) {
  expect(shapeProblems(shapes, step.shape, frame), `${label}: shape ${step.shape} of ${JSON.stringify(frame)}`).toEqual([]);
  expect(expectProblems(frame, fill(step.expect ?? {}, vars)), `${label}: expect`).toEqual([]);
  expect(expectProblems(frame, heardOf(frame, step.heard, vars)), `${label}: heard`).toEqual([]);
}

/** Sends a client step after checking the frame has the step's shape. */
function playClient(socket: Wire, step: LiveFrameStep, vars: Record<string, unknown>, label: string): Frame {
  const frame = clientFrame(step, vars);
  expect(shapeProblems(shapes, step.shape, frame), `${label}: client frame ${JSON.stringify(frame)}`).toEqual([]);
  socket.send(frame);
  return frame;
}

function cookieFor(kind: string): string | undefined {
  switch (kind) {
    case "valid":
      return users.ann!.cookie;
    case "none":
      return undefined;
    case "forged":
      return `gtnh_session=${users.ann!.id}.4102444800000.forged`;
    case "unknown-account":
      return `gtnh_session=${makeSessionToken(randomUUID())}`;
  }
  throw new Error(`unknown cookie kind ${kind}`);
}

/** Opens a socket as ann, says hello (a game client) and joins `doc`. */
async function annIn(port: number, doc: string): Promise<Wire> {
  const ann = await Wire.open(port, { cookie: users.ann!.cookie });
  ann.send({ t: "hello", v: 1, client: "game", clientVersion: "test", game: { server: "srv1", mcName: "Alex" } });
  await ann.next("welcome");
  ann.send({ t: "join", doc });
  await ann.next("snapshot");
  return ann;
}

// ---------------------------------------------------------------------------

describe("the wire contract, the live service's socket", { timeout: 60_000 }, () => {
  describe("what the service does at the upgrade", () => {
    for (const row of live.connect) {
      const skip = row.server?.skip;
      const run = skip ? it.skip : it;
      run(`${row.id}: ${row.serverDoes}${skip ? ` (${skip})` : ""}`, async () => {
        const options: Partial<LiveServiceOptions> = {};
        if (row.id === "lookup-failed") {
          options.store = {
            ...supabasePlanStore(),
            account: () => Promise.reject(new Error("database down")),
          };
        }
        if (row.id === "address-busy") {
          options.limits = { upgradesPerMinuteByAddress: 2 };
        }
        const { port } = await startService(options);
        const open = (extra: { address?: string } = {}) =>
          Wire.open(port, {
            cookie: cookieFor(row.cookie),
            origin: row.origin === "none" ? undefined : row.origin,
            path: row.path,
            ...extra,
          });

        if (row.status !== 101) {
          if (row.id === "address-busy") {
            await open({ address: "203.0.113.9" });
            await open({ address: "203.0.113.9" });
          }
          const refused = await open({ address: "203.0.113.9" }).then(
            () => ({ status: 101 }),
            (error: { status: number }) => error,
          );
          expect(refused.status).toBe(row.status);
          return;
        }

        if (row.id === "too-many-connections") {
          for (let i = 0; i < 16; i += 1) await open();
        }
        const socket = await open();
        if (row.close === undefined) {
          // Accepted: a hello is answered with a welcome.
          socket.send({ t: "hello", v: 1, client: "game" });
          const welcome = await socket.next("welcome");
          expect(shapeProblems(shapes, "$ServerWelcome", welcome)).toEqual([]);
          return;
        }
        expect(await socket.closed()).toBe(row.close);
        const frames = row.frames ?? [];
        expect(socket.frames.length, `frames before the close: ${JSON.stringify(socket.frames)}`).toBe(frames.length);
        frames.forEach((spec, i) => {
          expect(shapeProblems(shapes, spec.shape, socket.frames[i]), `frame ${i}`).toEqual([]);
          expect(expectProblems(socket.frames[i], spec.expect ?? {}), `frame ${i} expect`).toEqual([]);
        });
      });
    }
  });

  describe("what each close code is", () => {
    const actions: Record<number, () => Promise<{ code: number; frameBefore?: string }>> = {
      // The service stops while a client is connected.
      1001: async () => {
        const { service, port } = await startService();
        const ann = await Wire.open(port, { cookie: users.ann!.cookie });
        ann.send({ t: "hello", v: 1, client: "web" });
        await ann.next("welcome");
        void service.stop();
        return { code: await ann.closed() };
      },
      // A frame before hello.
      1002: async () => {
        const { port } = await startService();
        const ann = await Wire.open(port, { cookie: users.ann!.cookie });
        ann.send({ t: "ping" });
        return { code: await ann.closed(), frameBefore: ann.frames.map((f) => `${f.t}:${f.code}`).join(",") };
      },
      // More than 200 frames in a second.
      1008: async () => {
        const { port } = await startService();
        const ann = await Wire.open(port, { cookie: users.ann!.cookie });
        ann.send({ t: "hello", v: 1, client: "web" });
        await ann.next("welcome");
        for (let i = 0; i < 400 && ann.closeCode === undefined; i += 1) ann.send({ t: "ping" });
        return { code: await ann.closed(), frameBefore: ann.frames.map((f) => `${f.t}:${f.code ?? ""}`).includes("error:rate") ? "error:rate" : "" };
      },
      // A message over the transport's limit.
      1009: async () => {
        const { port } = await startService();
        const ann = await Wire.open(port, { cookie: users.ann!.cookie });
        ann.send({ t: "hello", v: 1, client: "web" });
        await ann.next("welcome");
        ann.send({ t: "ops", doc: "x", base: 0, cid: "c", ops: [], pad: "x".repeat(700 * 1024) });
        return { code: await ann.closed() };
      },
      // A room that cannot be trusted any more has its members join again: here the lease lapsed and a save took it.
      1012: async () => {
        const { port } = await startService({ timing: { flushMs: 100, renewMs: 60_000, leaseMs: 300 } });
        const { designId, doc, plan } = await makeDesign("ann", "Close 1012");
        const ann = await annIn(port, doc);
        await new Promise((resolve) => setTimeout(resolve, 450));
        const saved = await designRoute.PUT(
          new Request(`http://localhost/api/library/designs/${designId}`, {
            method: "PUT",
            headers: { cookie: users.ann!.cookie },
            body: JSON.stringify({
              name: "Close 1012",
              icon: null,
              folderId: null,
              closed: false,
              favorite: false,
              order: null,
              communityPlanId: null,
              createdAt: "2026-10-14T10:00:00.000Z",
              updatedAt: new Date(Date.now() + 60_000).toISOString(),
              planUpdatedAt: new Date(Date.now() + 60_000).toISOString(),
              plan: { ...plan, notes: "From REST" },
            }),
          }),
          { params: Promise.resolve({ designId }) },
        );
        expect(saved.status).toBe(200);
        ann.send({ t: "ops", doc, base: 0, cid: "late", ops: [{ o: "patch", c: "tasks", id: "t1", f: { title: "Late" } }] });
        return { code: await ann.closed() };
      },
      // The account lookup failed: covered with the upgrade cases, where the socket never opens a session.
      1013: async () => {
        const { port } = await startService({
          store: { ...supabasePlanStore(), account: () => Promise.reject(new Error("down")) },
        });
        const ann = await Wire.open(port, { cookie: users.ann!.cookie });
        return { code: await ann.closed(), frameBefore: ann.frames.length === 0 ? "" : "frame" };
      },
      4001: async () => {
        const { port } = await startService();
        const socket = await Wire.open(port);
        return { code: await socket.closed(), frameBefore: socket.frames.map((f) => `${f.t}:${f.code}`).join(",") };
      },
      // No hello in time: the service waits 10 s, so its timer is shortened here.
      4002: async () => {
        const real = globalThis.setTimeout;
        const spy = vi.spyOn(globalThis, "setTimeout").mockImplementation(((fn: () => void, ms?: number, ...rest: unknown[]) =>
          real(fn, ms === 10_000 ? 50 : ms, ...rest)) as typeof setTimeout);
        try {
          const { port } = await startService();
          const ann = await Wire.open(port, { cookie: users.ann!.cookie });
          return { code: await ann.closed() };
        } finally {
          spy.mockRestore();
        }
      },
      4029: async () => {
        const { port } = await startService();
        for (let i = 0; i < 16; i += 1) await Wire.open(port, { cookie: users.ann!.cookie });
        const socket = await Wire.open(port, { cookie: users.ann!.cookie });
        return { code: await socket.closed(), frameBefore: socket.frames.map((f) => `${f.t}:${f.code}`).join(",") };
      },
    };
    const before: Record<number, string> = {
      1002: "error:version",
      1008: "error:rate",
      4001: "error:auth",
      4029: "error:rate",
      1012: "",
      1013: "",
    };

    for (const row of live.close) {
      it(`${row.code}: ${row.when}`, async () => {
        const action = actions[row.code];
        expect(action, `no runner for close code ${row.code}: add one to wire-live.test.ts`).toBeDefined();
        const result = await action!();
        expect(result.code).toBe(row.code);
        if (row.code in before && result.frameBefore !== undefined) {
          expect(result.frameBefore).toBe(before[row.code]);
        }
      });
    }

    it("has a runner for every close code and none for a code the contract does not name", () => {
      expect(Object.keys(actions).map(Number).sort()).toEqual(live.close.map((row) => row.code).sort());
    });
  });

  describe("the conversations", () => {
    it("game-edit: one game client from open to leave", async () => {
      const script = live.scripts.find((s) => s.id === "game-edit")!;
      const { port } = await startService();
      const { doc } = await makeDesign("ann", "Steel line", ["bob"]);
      const vars = { "ann.id": users.ann!.id, "bob.id": users.bob!.id, doc };
      const ann = await Wire.open(port, { cookie: users.ann!.cookie });
      for (const step of script.steps) {
        const label = `game-edit step ${script.steps.indexOf(step)}`;
        if (step.dir === "client") {
          if (step.via?.startsWith("leave")) {
            // ops-echo: the same batch again is acknowledged with its seq and no ops.
            const batch = script.steps.find((s) => s.via?.startsWith("ops"))!;
            playClient(ann, batch, vars, `${label} (the batch again)`);
            const again = await ann.next("ops");
            expect(again, "the batch sent again").toMatchObject({ cid: "c1", ops: [], seq: 1 });
          }
          playClient(ann, step, vars, label);
          continue;
        }
        checkServerFrame(step, await ann.next(typeOf(step.shape!)), vars, label);
      }
      // presence-unechoed: nothing came back for the presence frame.
      expect(ann.frames.filter((f) => f.t === "presence")).toEqual([]);
    });

    it("room-events: what the service sends without being asked", async () => {
      const script = live.scripts.find((s) => s.id === "room-events")!;
      const { port } = await startService();
      const mine = await makeDesign("ann", "Ann's plan", ["bob"]);
      const bobs = await makeDesign("bob", "Bob's plan");
      const vars = {
        "ann.id": users.ann!.id,
        "bob.id": users.bob!.id,
        "carol.id": users.carol!.id,
        doc: mine.doc,
        bobdoc: bobs.doc,
      };
      const ann = await annIn(port, mine.doc);
      const bob = await Wire.open(port, { cookie: users.bob!.cookie });
      bob.send({ t: "hello", v: 1, client: "web" });
      await bob.next("welcome");
      const steps = script.steps;
      const expectCause = (i: number, text: string) => expect(steps[i]!.cause, `room-events step ${i}`).toContain(text);

      expectCause(0, "bob joins");
      bob.send({ t: "join", doc: mine.doc });
      await bob.next("snapshot");
      checkServerFrame(steps[0]!, await ann.next("present"), vars, "present");

      expectCause(1, "presence");
      bob.send({ t: "presence", doc: mine.doc, cursor: { x: 1, y: 2 }, sel: ["n1"], view: { x: 0, y: 0, zoom: 1 } });
      checkServerFrame(steps[1]!, await ann.next("presence"), vars, "presence");

      expectCause(2, "ops");
      bob.send({
        t: "ops",
        doc: mine.doc,
        base: 0,
        cid: "b1",
        ops: [{ o: "patch", c: "tasks", id: "t1", f: { title: "Third" } }],
      });
      checkServerFrame(steps[2]!, await ann.next("ops"), vars, "ops");

      expectCause(3, "carol");
      await share("ann", mine.designId, "carol", true);
      checkServerFrame(steps[3]!, await ann.next("members"), vars, "members");

      expectCause(4, "shared with ann");
      await share("bob", bobs.designId, "ann", true);
      checkServerFrame(steps[4]!, await ann.next("shared"), vars, "shared");

      expectCause(5, "unshared");
      await share("bob", bobs.designId, "ann", false);
      checkServerFrame(steps[5]!, await ann.next("unshared"), vars, "unshared");

      // Kicked while in the plan: the room kicks her, and every socket of hers hears unshared.
      expectCause(6, "unshared while she is in it");
      await share("bob", bobs.designId, "ann", true);
      await ann.next("shared");
      ann.send({ t: "join", doc: bobs.doc });
      await ann.next("snapshot");
      await share("bob", bobs.designId, "ann", false);
      checkServerFrame(steps[6]!, await ann.next("kicked"), vars, "kicked (unshared)");
      await ann.next("unshared");

      // Kicked by a delete.
      expectCause(7, "deletes it");
      await share("bob", bobs.designId, "ann", true);
      await ann.next("shared");
      ann.send({ t: "join", doc: bobs.doc });
      await ann.next("snapshot");
      const deleted = await designRoute.DELETE(
        new Request(`http://localhost/api/library/designs/${bobs.designId}`, {
          method: "DELETE",
          headers: { cookie: users.bob!.cookie },
        }),
        { params: Promise.resolve({ designId: bobs.designId }) },
      );
      expect(deleted.status).toBe(200);
      checkServerFrame(steps[7]!, await ann.next("kicked"), vars, "kicked (deleted)");
    });

    it("refusals: error frames for a join and a batch", async () => {
      const script = live.scripts.find((s) => s.id === "refusals")!;
      const { port } = await startService();
      const mine = await makeDesign("ann", "Ann's plan");
      const strangers = await makeDesign("carol", "Carol's plan");
      const vars = {
        "ann.id": users.ann!.id,
        doc: mine.doc,
        strangerdoc: strangers.doc,
        missingdoc: docKey(users.ann!.id, "design-never-made"),
      };
      const ann = await annIn(port, mine.doc);
      for (const step of script.steps) {
        const label = `refusals step ${script.steps.indexOf(step)}`;
        if (step.dir === "client") {
          playClient(ann, step, vars, label);
          continue;
        }
        const frame = await ann.next(typeOf(step.shape!));
        checkServerFrame(step, frame, vars, label);
      }
    });

    it("hello-order: a frame before hello, and a hello for another version", async () => {
      const script = live.scripts.find((s) => s.id === "hello-order")!;
      const { port } = await startService();
      const mine = await makeDesign("ann", "Ann's plan");
      const vars = { "ann.id": users.ann!.id, doc: mine.doc };
      let socket = await Wire.open(port, { cookie: users.ann!.cookie });
      for (const step of script.steps) {
        const label = `hello-order step ${script.steps.indexOf(step)}`;
        if (step.dir === "client") {
          playClient(socket, step, vars, label);
          continue;
        }
        const frame = await socket.next(typeOf(step.shape!));
        checkServerFrame(step, frame, vars, label);
        if (step.close !== undefined) {
          expect(await socket.closed()).toBe(step.close);
          socket = await Wire.open(port, { cookie: users.ann!.cookie });
        }
      }
    });
  });

  describe("the rules of order", () => {
    // Each rule that names a script is checked in that script's run above or by the checks in it; a rule
    // that names a test no runner here knows would pass unchecked, so it fails instead.
    const known = new Set(["hello-order", "game-edit", "close", "connect"]);
    for (const rule of live.order) {
      it(`${rule.id}: names only runs that exist`, () => {
        for (const test of rule.tests ?? []) expect(known.has(test), `${rule.id} names ${test}`).toBe(true);
        expect(rule.tests !== undefined || rule.mod !== undefined, `${rule.id} says what checks it`).toBe(true);
      });
    }

    it("snapshot-first: a join is answered with a snapshot before any ops for that doc", async () => {
      const { port } = await startService();
      const mine = await makeDesign("ann", "Ann's plan", ["bob"]);
      const ann = await annIn(port, mine.doc);
      const bob = await Wire.open(port, { cookie: users.bob!.cookie });
      bob.send({ t: "hello", v: 1, client: "web" });
      await bob.next("welcome");
      ann.send({ t: "ops", doc: mine.doc, base: 0, cid: "a1", ops: [{ o: "patch", c: "tasks", id: "t1", f: { title: "A" } }] });
      await ann.next("ops");
      bob.send({ t: "join", doc: mine.doc });
      await until(() => bob.frames.some((f) => f.t === "snapshot"));
      expect(bob.frames[0]!.t).toBe("snapshot");
      expect(valueAt(bob.frames[0], "seq")).toBe(1);
    });
  });

  it("every frame an example plays back has its own shape", () => {
    for (const row of live.connect) {
      for (const frame of row.frames ?? []) {
        expect(shapeProblems(shapes, frame.shape, frame.example), `${row.id}`).toEqual([]);
      }
    }
    for (const script of live.scripts) {
      for (const step of script.steps) {
        if (step.dir === "server") {
          expect(shapeProblems(shapes, step.shape, step.example), `${script.id}`).toEqual([]);
        }
      }
    }
  });
});
