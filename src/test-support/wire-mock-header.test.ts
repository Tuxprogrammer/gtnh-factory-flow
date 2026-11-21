import { spawn, type ChildProcess } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { WebSocket } from "ws";

/**
 * The mod's development stand-in as a site behind a guarding proxy (conformance/wire/README.md, "A guarded site"):
 * with MOCK_REQUIRE_HEADER set, a request without the header, REST or the WebSocket upgrade, is answered 302 to a
 * sign-in page and nothing is served; with it, the mock answers as it always does. The mod's own tests of the header
 * run against Java stand-ins; this pins that the node one behaves the same way.
 */

const MOCK = path.join(process.cwd(), "jvm", "mod", "tools", "dev", "mock-library.mjs");
const NAME = "X-Test-Access";
const VALUE = "placeholder-value";

let child: ChildProcess | undefined;
let stateDir = "";
let output = "";
let base = "";
let wsBase = "";

beforeAll(async () => {
  stateDir = mkdtempSync(path.join(tmpdir(), "gtnh-wire-mock-header-"));
  child = spawn(process.execPath, [MOCK, "0"], {
    env: { ...process.env, MOCK_STATE_DIR: stateDir, MOCK_REQUIRE_HEADER: `${NAME}=${VALUE}` },
    stdio: ["ignore", "pipe", "pipe"],
  });
  const port = await new Promise<number>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`the mock did not start: ${output}`)), 10_000);
    child!.stdout!.on("data", (chunk: Buffer) => {
      output += chunk.toString();
      const hit = /http:\/\/127\.0\.0\.1:(\d+)/.exec(output);
      if (hit) {
        clearTimeout(timer);
        resolve(Number(hit[1]));
      }
    });
    child!.once("exit", (code) => reject(new Error(`the mock exited ${code}: ${output}`)));
  });
  base = `http://127.0.0.1:${port}`;
  wsBase = `ws://127.0.0.1:${port}`;
}, 30_000);

afterAll(() => {
  child?.kill();
  if (stateDir) rmSync(stateDir, { recursive: true, force: true });
});

function upgrade(headers: Record<string, string>): Promise<string> {
  return new Promise((resolve) => {
    const ws = new WebSocket(`${wsBase}/live`, { headers, followRedirects: false });
    ws.once("open", () => {
      ws.close();
      resolve("open");
    });
    ws.once("unexpected-response", (_req, res) => {
      res.resume();
      resolve(`${res.statusCode} ${res.headers.location ?? ""}`);
    });
    ws.once("error", () => resolve("error"));
  });
}

describe("a mock behind a guarding proxy", () => {
  it("answers 302 to a sign-in page without the header, on any route", async () => {
    for (const route of ["/api/community/auth/me", "/api/library", "/live/health", "/api/community/plans"]) {
      const res = await fetch(`${base}${route}`, { redirect: "manual" });
      expect(res.status, route).toBe(302);
      expect(res.headers.get("location"), route).toMatch(/^\/sign-in/);
    }
    const post = await fetch(`${base}/api/community/auth/register`, {
      method: "POST",
      redirect: "manual",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ username: "someone", password: "password1" }),
    });
    expect(post.status).toBe(302);
  });

  it("answers 302 to a wrong value as to none", async () => {
    const res = await fetch(`${base}/api/library`, { redirect: "manual", headers: { [NAME]: "wrong" } });
    expect(res.status).toBe(302);
  });

  it("serves as usual with the header", async () => {
    const res = await fetch(`${base}/api/community/auth/register`, {
      method: "POST",
      redirect: "manual",
      headers: { "content-type": "application/json", [NAME]: VALUE },
      body: JSON.stringify({ username: "someone", password: "password1" }),
    });
    expect(res.status).toBe(201);
    const cookie = /gtnh_session=([^;]+)/.exec(res.headers.get("set-cookie") ?? "")?.[1];
    expect(cookie).toBeTruthy();
    const library = await fetch(`${base}/api/library`, {
      redirect: "manual",
      headers: { [NAME]: VALUE, cookie: `gtnh_session=${cookie}` },
    });
    expect(library.status).toBe(200);
  });

  it("answers the WebSocket upgrade 302 without the header, and takes it with", async () => {
    expect(await upgrade({})).toMatch(/^302 \/sign-in/);
    expect(await upgrade({ [NAME]: VALUE })).toBe("open");
  });

  it("never prints the value", () => {
    expect(output).not.toContain(VALUE);
  });
});
