/**
 * What the two server-side runs of the wire contract (src/lib/server/wire-rest.test.ts, src/live/wire-live.test.ts)
 * share: the contract's files, the `{var}` fill, and the step's fields as types.
 */
import { readConformanceJson } from "./conformance";
import type { Shapes } from "./wire-shape";

export interface RestStep {
  id: string;
  client: string;
  args: unknown[];
  as: string | null;
  request: {
    method: string;
    path: string;
    auth: "session" | "none";
    body?: unknown;
    bodyShape?: unknown;
    bodyExpect?: Record<string, unknown>;
  };
  status: number;
  shape: string;
  example?: unknown;
  expect?: Record<string, unknown>;
  serverExpect?: Record<string, unknown>;
  headers?: Record<string, { example: string; re: string }>;
  result: Record<string, unknown>;
  capture?: Record<string, string>;
  before?: string[];
  prime?: number;
  joins?: string;
  server?: { skip?: string; feedback?: boolean; dataset?: boolean };
  /** The dev stand-in's run: `skip` is the reason it does not play this step. */
  mock?: { skip?: string };
  note?: string;
}

export interface LiveFrameStep {
  dir: "client" | "server";
  via?: string;
  frame?: unknown;
  shape?: string;
  expect?: Record<string, unknown>;
  example?: unknown;
  heard?: Record<string, unknown>;
  cause?: string;
  close?: number;
  note?: string;
}

export interface LiveConnectCase {
  id: string;
  cookie: "valid" | "none" | "forged" | "unknown-account";
  origin: string;
  path?: string;
  serverDoes: string;
  status: number;
  frames?: Array<{ shape: string; example: unknown; expect?: Record<string, unknown> }>;
  close?: number;
  headers?: Record<string, string>;
  mod: { state: string; reason?: string; retries?: string };
  server?: { skip?: string };
}

export interface LiveContract {
  path: string;
  connect: LiveConnectCase[];
  close: Array<{
    code: number;
    sentBy: string;
    when: string;
    mod: { state: string; reason: string; retries: string };
    finding?: string;
  }>;
  scripts: Array<{ id: string; about: string; steps: LiveFrameStep[]; mod?: { skip?: string } }>;
  order: Array<{ id: string; rule: string; tests?: string[]; mod?: string }>;
}

export interface WireContract {
  shapes: Shapes;
  rest: { accounts: string[]; steps: RestStep[] };
  plans: { plan: unknown; emptyPlan: unknown };
  live: LiveContract;
}

export function readWire(): WireContract {
  return {
    shapes: readConformanceJson("wire", "shapes.json") as Shapes,
    rest: readConformanceJson("wire", "rest.json") as WireContract["rest"],
    plans: readConformanceJson("wire", "plan.json") as WireContract["plans"],
    live: readConformanceJson("wire", "live.json") as LiveContract,
  };
}

/**
 * Fills the `{name}` variables in a contract value. A string that is exactly one variable becomes that variable's value
 * (an object stays an object); a variable inside a longer string is written into it. An unknown variable throws, so a
 * step cannot run with a hole in it.
 */
export function fill<T>(value: T, vars: Record<string, unknown>): T {
  if (typeof value === "string") {
    const whole = /^\{([A-Za-z0-9_.]+)\}$/.exec(value);
    if (whole) {
      if (!(whole[1]! in vars)) throw new Error(`no value for {${whole[1]}}`);
      return vars[whole[1]!] as T;
    }
    return value.replace(/\{([A-Za-z0-9_.]+)\}/g, (_all, name: string) => {
      if (!(name in vars)) throw new Error(`no value for {${name}}`);
      return String(vars[name]);
    }) as T;
  }
  if (Array.isArray(value)) return value.map((item) => fill(item, vars)) as T;
  if (typeof value === "object" && value !== null) {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, fill(v, vars)])) as T;
  }
  return value;
}
