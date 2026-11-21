import { spawn, type ChildProcess } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { WebSocket } from "ws";
import { fill, readWire, type LiveFrameStep, type RestStep } from "./wire-server";
import { expectProblems, shapeProblems, valueAt } from "./wire-shape";

/**
 * The mod's development stand-ins (jvm/mod/tools/dev/mock-library.mjs, which carries mock-live.mjs) against the wire
 * contract, the way the real routes are tested in src/lib/server/wire-rest.test.ts and src/live/wire-live.test.ts: the
 * same steps over real HTTP and a real WebSocket, with the same status, shape and expected values. A mock that drifts
 * from the site fails here. What a mock does not do is listed in the step's `mock.skip` with the reason, never left out.
 */

const wire = readWire();
const MOCK = path.join(process.cwd(), "jvm", "mod", "tools", "dev", "mock-library.mjs");

let child: ChildProcess | undefined;
let stateDir = "";
let base = "";
let wsBase = "";
const vars: Record<string, unknown> = {};
const tokens: Record<string, string> = {};

function skipReason(step: RestStep): string | undefined {
  if (step.mock?.skip) return step.mock.skip;
  if (step.server?.skip) return `not run on a mock either: ${step.server.skip}`;
  if (step.server?.dataset) return "the mock has no datasets";
  if (step.before?.length) return "needs the real service's own state";
  if (step.prime) return "needs a rate limit";
  return undefined;
}

async function register(name: string): Promise<void> {
  const res = await fetch(`${base}/api/community/auth/register`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ username: name, password: "password1" }),
  });
  expect(res.status, `registering ${name}`).toBe(201);
  const cookie = /gtnh_session=([^;]+)/.exec(res.headers.get("set-cookie") ?? "")?.[1];
  expect(cookie).toBeTruthy();
  tokens[name] = cookie!;
  vars[`${name}.token`] = cookie!;
  vars[`${name}.id`] = cookie!.split(".")[0]!;
}

beforeAll(async () => {
  stateDir = mkdtempSync(path.join(tmpdir(), "gtnh-wire-mock-"));
  child = spawn(process.execPath, [MOCK, "0"], {
    env: { ...process.env, MOCK_STATE_DIR: stateDir },
    stdio: ["ignore", "pipe", "pipe"],
  });
  const port = await new Promise<number>((resolve, reject) => {
    let out = "";
    const timer = setTimeout(() => reject(new Error(`the mock did not start: ${out}`)), 10_000);
    child!.stdout!.on("data", (chunk: Buffer) => {
      out += chunk.toString();
      const hit = /http:\/\/127\.0\.0\.1:(\d+)/.exec(out);
      if (hit) {
        clearTimeout(timer);
        resolve(Number(hit[1]));
      }
    });
    child!.once("exit", (code) => reject(new Error(`the mock exited ${code}: ${out}`)));
  });
  base = `http://127.0.0.1:${port}`;
  wsBase = `ws://127.0.0.1:${port}`;
  for (const name of wire.rest.accounts) await register(name);
  vars["dataset.id"] = "none";
  vars.plan = wire.plans.plan;
  vars.emptyPlan = wire.plans.emptyPlan;
}, 30_000);

afterAll(() => {
  child?.kill();
  if (stateDir) rmSync(stateDir, { recursive: true, force: true });
});

describe("the mock community site against the wire contract: REST", () => {
  for (const raw of wire.rest.steps) {
    const step = raw as RestStep;
    const name = `${step.id}: ${step.request.method} ${step.request.path.split("?")[0]} -> ${step.status}`;
    const skip = skipReason(step);
    if (skip) {
      it.skip(`${name} (${skip})`, () => undefined);
      continue;
    }
    it(name, async () => {
      const req = fill(step.request, vars);
      const headers: Record<string, string> = {};
      if (step.as && req.auth === "session") headers.cookie = `gtnh_session=${tokens[step.as]}`;
      if (req.body !== undefined) headers["content-type"] = "application/json";
      const res = await fetch(`${base}${req.path}`, {
        method: req.method,
        headers,
        body: req.body === undefined ? undefined : JSON.stringify(req.body),
      });
      const text = await res.text();
      const json = text ? JSON.parse(text) : undefined;
      expect(res.status, `${step.id} status (body ${text})`).toBe(step.status);
      expect(shapeProblems(wire.shapes, step.shape, json), `${step.id} shape`).toEqual([]);
      expect(expectProblems(json, fill(step.serverExpect ?? step.expect ?? {}, vars)), `${step.id} expect`).toEqual([]);
      for (const [header, spec] of Object.entries(step.headers ?? {})) {
        expect(res.headers.get(header) ?? "", `${step.id} ${header}`).toMatch(new RegExp(spec.re));
      }
      for (const [variable, at] of Object.entries(step.capture ?? {})) {
        vars[variable] = (json as Record<string, unknown>)[at];
        expect(typeof vars[variable]).toBe("string");
      }
    });
  }

  it("says why for every step it does not run, and runs most of them", () => {
    const steps = wire.rest.steps as Array<RestStep>;
    const skipped = steps.filter((s) => skipReason(s));
    for (const s of skipped) expect(skipReason(s)!.length, s.id).toBeGreaterThan(5);
    expect(steps.length - skipped.length).toBeGreaterThan(steps.length * 0.5);
  });
});

/** A socket with every frame kept, in order, and the close code once it closes. */
class Wire {
  readonly frames: Array<Record<string, unknown> & { t: string }> = [];
  closeCode?: number;

  private constructor(readonly ws: WebSocket) {
    ws.on("message", (data) => this.frames.push(JSON.parse(data.toString())));
    ws.on("close", (code) => {
      this.closeCode = code;
    });
  }

  static open(cookie?: string, pathName = "/live"): Promise<Wire> {
    const ws = new WebSocket(`${wsBase}${pathName}`, { headers: cookie ? { cookie } : {} });
    const w = new Wire(ws);
    return new Promise((resolve, reject) => {
      ws.once("open", () => resolve(w));
      ws.once("error", () => undefined);
      ws.once("unexpected-response", (_req, res) =>
        reject(Object.assign(new Error(`HTTP ${res.statusCode}`), { status: res.statusCode })),
      );
    });
  }

  send(frame: unknown): void {
    this.ws.send(JSON.stringify(frame));
  }

  async until(what: string, test: () => boolean): Promise<void> {
    const end = Date.now() + 5_000;
    while (Date.now() < end) {
      if (test()) return;
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    throw new Error(`timed out waiting for ${what}; heard ${JSON.stringify(this.frames)}`);
  }

  async next(type: string, from: number): Promise<Record<string, unknown>> {
    await this.until(`a ${type} frame`, () => this.frames.slice(from).some((f) => f.t === type));
    return this.frames.slice(from).find((f) => f.t === type)!;
  }
}

describe("the mock live service against the wire contract", () => {
  const opened: Wire[] = [];
  afterAll(() => opened.forEach((w) => w.ws.close()));

  // The one row of the connect table a mock can answer: a session it does not know is accepted, told so, closed 4001.
  for (const row of wire.live.connect.filter((c) => c.id === "no-cookie")) {
    it(`connect ${row.id}: ${row.serverDoes}`, async () => {
      const w = await Wire.open();
      opened.push(w);
      await w.until("the close", () => w.closeCode !== undefined);
      expect(w.closeCode).toBe(row.close);
      for (const f of row.frames ?? []) {
        expect(shapeProblems(wire.shapes, f.shape, w.frames[0])).toEqual([]);
        expect(expectProblems(w.frames[0], f.expect ?? {})).toEqual([]);
      }
    });
  }

  it("connect wrong-path: a bare 404", async () => {
    await expect(Wire.open(`gtnh_session=${tokens.ann}`, "/live/other")).rejects.toMatchObject({ status: 404 });
  });

  /** The doc the scripts use: ann's design, made through the mock's REST the way the contract's put-create does. */
  async function makeDoc(): Promise<void> {
    const put = wire.rest.steps.find((s) => s.id === "put-create")!;
    const req = fill(put.request, vars);
    const res = await fetch(`${base}${req.path}`, {
      method: "PUT",
      headers: { "content-type": "application/json", cookie: `gtnh_session=${tokens.ann}` },
      // Later than the tombstone an earlier script's room left, or the save is answered "behind" and nothing is written.
      body: JSON.stringify({ ...(req.body as object), updatedAt: new Date().toISOString(), planUpdatedAt: new Date().toISOString() }),
    });
    expect(res.status).toBe(200);
    // The script's editor: bob is on the plan, as the REST steps leave it for the live scripts.
    const add = await fetch(`${base}/api/library/designs/design-0001/members/${vars["bob.id"]}`, {
      method: "PUT",
      headers: { "content-type": "application/json", cookie: `gtnh_session=${tokens.ann}` },
      body: "{}",
    });
    expect(add.status, await add.text()).toBe(200);
  }

  /** Plays a script's steps against a mock: client steps are sent, each server step must arrive with its shape. */
  async function play(id: string, w: Wire): Promise<void> {
    const script = wire.live.scripts.find((s) => s.id === id)!;
    const live: Record<string, unknown> = {
      ...vars,
      doc: `${vars["ann.id"]}:design-0001`,
      bobdoc: `${vars["bob.id"]}:design-0002`,
      strangerdoc: `${vars["bob.id"]}:design-0003`,
      missingdoc: `${vars["ann.id"]}:design-9999`,
    };
    for (const step of script.steps as LiveFrameStep[]) {
      if (step.dir === "client") {
        const from = w.frames.length;
        w.send(clientFrame(step, live));
        (w as unknown as { mark: number }).mark = from;
        continue;
      }
      const type = (fill(step.example, live) as { t: string }).t;
      if (type === "pong") {
        await w.until("a pong", () => w.frames.some((f) => f.t === "pong"));
        continue;
      }
      await w.until(`a ${type} frame`, () => w.frames.some((f) => f.t === type));
      const frame = w.frames.find((f) => f.t === type)!;
      w.frames.splice(w.frames.indexOf(frame), 1);
      expect(shapeProblems(wire.shapes, step.shape, frame), `${id}: ${type} shape`).toEqual([]);
      for (const [key, want] of Object.entries(step.expect ?? {})) {
        expect(valueAt(frame, key), `${id}: ${type} ${key}`).toEqual(fill(want, live));
      }
      // 'heard' paths start with the frame's type; the rest are conclusions the mod draws, not frame fields.
      for (const [key, want] of Object.entries(step.heard ?? {})) {
        const [head, ...rest] = key.split(".");
        if (head !== type) continue;
        expect(valueAt(frame, rest.join(".")), `${id}: ${key}`).toEqual(fill(want, live));
      }
    }
  }

  /** The frame a client step sends: its explicit `frame`, else the one its `via` and `expect` describe. */
  function clientFrame(step: LiveFrameStep, live: Record<string, unknown>): unknown {
    if (step.frame) return fill(step.frame, live);
    const via = step.via ?? "";
    const [kind, rest] = via.split(/:(.*)/s);
    const doc = rest === undefined ? undefined : (fill(rest, live) as string);
    switch (kind) {
      case "open":
        return {
          t: "hello",
          v: 1,
          client: "game",
          clientVersion: "mock-test",
          game: { server: "srv1", mcName: "Alex" },
        };
      case "join":
        return { t: "join", doc };
      case "leave":
        return { t: "leave", doc };
      case "ping":
        return { t: "ping" };
      case "presence":
        return { t: "presence", doc, cursor: { x: 4, y: 8 }, sel: ["n1"], view: { x: 0, y: 0, zoom: 1 } };
      case "ops": {
        const e = fill(step.expect ?? {}, live) as Record<string, unknown>;
        return {
          t: "ops",
          doc,
          base: e.base ?? 0,
          cid: e.cid,
          ops: [{ o: "patch", c: "tasks", id: e["ops.0.id"], f: { title: e["ops.0.f.title"] } }],
        };
      }
      default:
        throw new Error(`no way to send ${via}`);
    }
  }

  for (const id of ["game-edit", "refusals"]) {
    it(`script ${id}`, async () => {
      await makeDoc();
      const w = await Wire.open(`gtnh_session=${tokens.ann}`);
      opened.push(w);
      if (id === "refusals") {
        // The script starts with the client in the room already.
        w.send(clientFrame({ dir: "client", via: "open" }, {}));
        await w.next("welcome", 0);
        w.send({ t: "join", doc: `${vars["ann.id"]}:design-0001` });
        await w.next("snapshot", 0);
        w.frames.length = 0;
      }
      try {
        await play(id, w);
      } finally {
        // The room is empty before the next script saves the plan again: a plan with a live room refuses a save.
        w.ws.close();
        await w.until("the close", () => w.closeCode !== undefined);
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
    });
  }
});
