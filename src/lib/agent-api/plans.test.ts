import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { z } from "zod";
import { POST as addCardsPost } from "@/app/api/agent/v1/plans/add-cards/route";
import { POST as connectPost } from "@/app/api/agent/v1/plans/connect/route";
import { POST as createPost, GET as createGet } from "@/app/api/agent/v1/plans/create/route";
import { POST as disconnectPost } from "@/app/api/agent/v1/plans/disconnect/route";
import { POST as getPost } from "@/app/api/agent/v1/plans/get/route";
import { POST as removeCardsPost } from "@/app/api/agent/v1/plans/remove-cards/route";
import { POST as setTargetsPost } from "@/app/api/agent/v1/plans/set-targets/route";
import { POST as solvePost } from "@/app/api/agent/v1/plans/solve/route";
import { factoryProjectSchema } from "@/lib/model/schemas";
import type { MachineHandler, Recipe } from "@/lib/model/types";
import { getDatasetRecipe, getDatasetResource, getDefaultDatasetVersionId } from "@/lib/server/dataset-query";
import { CORE_LOAD_MS, useCoreSolver } from "@/test-support/core-solve";
import { DRAFT_TTL_MS, DRAFTS_PER_DAY, getDraft, resetDrafts, setDraftClock } from "./drafts";
import {
  plansAddCardsResponse,
  plansConnectResponse,
  plansCreateResponse,
  plansDisconnectResponse,
  plansGetResponse,
  plansRemoveCardsResponse,
  plansSetTargetsResponse,
  plansSolveResponse,
} from "./plan-schemas";
import { errorResponse } from "./schemas";
import { RESPONSE_AIM_BYTES } from "./size";

vi.mock("@/lib/server/dataset-query", () => ({
  getDefaultDatasetVersionId: vi.fn(),
  getDatasetResource: vi.fn(),
  getDatasetRecipe: vi.fn(),
  queryDatasetResources: vi.fn(),
  queryDatasetRecipes: vi.fn(),
}));

const TOKEN = "test-token-0123456789";
const VERSION = "stable-test";

const NAMES: Record<string, string> = {
  "item:ore": "Titanium Ore",
  "item:dust": "Titanium Dust",
  "item:stone": "Stone Dust",
  "item:ingot": "Titanium Ingot",
  "fluid:nitrogen": "Nitrogen",
};

function handler(id: string, label: string, kind: MachineHandler["kind"], minimumTier: string, extra: Partial<MachineHandler> = {}): MachineHandler {
  return { id, label, kind, machineType: label, minimumTier, ...extra } as MachineHandler;
}

function slot(kind: "item" | "fluid", id: string, amount: number) {
  return { kind, id, amount, displayName: NAMES[`${kind}:${id}`] ?? id };
}

function recipe(id: string, fields: Partial<Recipe>): Recipe {
  return {
    id,
    name: id,
    machineType: "Mock Machine",
    minimumTier: "LV",
    durationTicks: 20,
    eut: 30,
    inputs: [],
    outputs: [],
    ...fields,
  } as Recipe;
}

const RECIPES: Record<string, Recipe> = {
  "r-dust": recipe("r-dust", {
    name: "Macerator: Titanium Dust",
    machineType: "Mock Macerator",
    inputs: [slot("item", "ore", 1)],
    outputs: [slot("item", "dust", 1), slot("item", "stone", 1)],
    machineHandlers: [
      handler("mock-macerator", "Mock Macerator", "single", "LV"),
      handler("mock-mega-macerator", "Mock Mega Macerator", "multiblock", "HV", {
        machineConfigControls: [
          {
            id: "mode",
            label: "Mode",
            minimumKey: "fast",
            tiers: [
              { key: "fast", label: "Fast", resource: slot("item", "ore", 1) },
              { key: "slow", label: "Slow", resource: slot("item", "ore", 1) },
            ],
          },
        ],
      }),
    ],
  }),
  "r-ingot": recipe("r-ingot", {
    name: "Furnace: Titanium Ingot",
    machineType: "Mock Furnace",
    inputs: [slot("item", "dust", 1), slot("fluid", "nitrogen", 1000)],
    outputs: [slot("item", "ingot", 1)],
    machineHandlers: [handler("mock-furnace", "Mock Furnace", "single", "LV")],
  }),
  "r-iv": recipe("r-iv", {
    name: "Fusion: Titanium Ingot",
    machineType: "Mock Fusion",
    minimumTier: "IV",
    inputs: [slot("item", "dust", 1)],
    outputs: [slot("item", "ingot", 2)],
    machineHandlers: [handler("mock-fusion", "Mock Fusion", "multiblock", "IV")],
  }),
};

/** A 60-step chain for the size and time check: step n turns part n into part n+1. */
for (let n = 0; n < 60; n += 1) {
  RECIPES[`r-step-${n}`] = recipe(`r-step-${n}`, {
    name: `Assembler: Titanium Part ${n + 1}`,
    machineType: "Mock Assembler",
    inputs: [slot("item", `part-${n}`, 1), slot("fluid", "nitrogen", 144)],
    outputs: [slot("item", `part-${n + 1}`, 1), ...(n % 6 === 0 ? [slot("item", `scrap-${n}`, 1)] : [])],
    machineHandlers: [handler("mock-assembler", "Mock Assembler", "single", "LV")],
  });
}

function request(body: unknown, auth = true): Request {
  return new Request("http://localhost/api/agent/v1/plans", {
    method: "POST",
    headers: { "Content-Type": "application/json", ...(auth ? { Authorization: `Bearer ${TOKEN}` } : {}) },
    body: JSON.stringify(body),
  });
}

type Post = (request: Request) => Promise<Response>;

async function call<S extends z.ZodType>(post: Post, body: unknown, schema: S): Promise<z.infer<S>> {
  const response = await post(request(body));
  const json = await response.json();
  expect(response.status, JSON.stringify(json)).toBe(200);
  return schema.parse(json);
}

async function fails(post: Post, body: unknown, code: string, status: number): Promise<string> {
  const response = await post(request(body));
  const json = errorResponse.parse(await response.json());
  expect(json.error.code, json.error.message).toBe(code);
  expect(response.status).toBe(status);
  return json.error.message;
}

let now = Date.parse("2026-11-21T12:00:00.000Z");

beforeEach(() => {
  vi.stubEnv("AGENT_API_TOKEN", TOKEN);
  vi.spyOn(console, "info").mockImplementation(() => {});
  vi.spyOn(console, "error").mockImplementation(() => {});
  now = Date.parse("2026-11-21T12:00:00.000Z");
  setDraftClock(() => now);
  resetDrafts();
  vi.mocked(getDefaultDatasetVersionId).mockResolvedValue(VERSION);
  vi.mocked(getDatasetRecipe).mockImplementation(async (_version, id) => structuredClone(RECIPES[id]));
  vi.mocked(getDatasetResource).mockImplementation(async (_version, kind, id) => {
    const name = NAMES[`${kind}:${id}`] ?? (/^(part|scrap)-\d+$/.test(id) ? `Titanium ${id}` : undefined);
    return name ? ({ id, kind, displayName: name } as never) : undefined;
  });
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
  setDraftClock();
});

async function create(constraints?: object): Promise<string> {
  const body = await call(createPost, { name: "Titanium", ...(constraints ? { constraints } : {}) }, plansCreateResponse);
  return body.planId;
}

describe("plans/create and the draft store", () => {
  it("creates a draft with a random id that lives 24 hours", async () => {
    const created = await call(createPost, { name: "Titanium", constraints: { tierCap: "HV" }, player: { name: "Steve" } }, plansCreateResponse);
    expect(created.planId).toMatch(/^x[a-hjkmnp-z2-9]{19}$/);
    expect(created).toMatchObject({ dataset: VERSION, name: "Titanium", constraints: { tierCap: "HV" } });
    expect(Date.parse(created.expiresAt) - now).toBe(DRAFT_TTL_MS);
    expect(await create()).not.toBe(created.planId);
  });

  it("answers plan_not_found for an unknown id and bad_request for a malformed one", async () => {
    await fails(getPost, { planId: "xaaaaaaaaaaaaaaaaaaa" }, "plan_not_found", 404);
    await fails(getPost, { planId: "nope" }, "bad_request", 400);
    expect((await createGet(request({}))).status).toBe(400);
  });

  it("expires a draft 24 hours after its last change, and a change keeps it", async () => {
    const planId = await create();
    now += DRAFT_TTL_MS - 1000;
    await call(addCardsPost, { planId, cards: [{ recipeId: "r-ingot" }] }, plansAddCardsResponse);
    now += DRAFT_TTL_MS - 1000;
    await call(getPost, { planId }, plansGetResponse);
    now += 1000;
    await fails(getPost, { planId }, "plan_not_found", 404);
    await fails(solvePost, { planId }, "plan_not_found", 404);
  });

  it("creates at most 300 drafts a day (UTC), then answers rate_limited until the next day", async () => {
    for (let n = 0; n < DRAFTS_PER_DAY; n += 1) {
      await create();
    }
    await fails(createPost, { name: "One too many" }, "rate_limited", 429);
    now = Date.parse("2026-11-22T00:00:01.000Z");
    await create();
  });

  it("is off without the token and refuses a wrong one", async () => {
    vi.stubEnv("AGENT_API_TOKEN", "");
    expect((await createPost(request({ name: "x" }))).status).toBe(404);
    vi.stubEnv("AGENT_API_TOKEN", TOKEN);
    expect((await createPost(request({ name: "x" }, false))).status).toBe(401);
  });
});

describe("plans/add-cards", () => {
  it("picks the first multiblock under the cap, else the single block, at the lowest tier", async () => {
    const open = await create();
    const free = await call(addCardsPost, { planId: open, cards: [{ recipeId: "r-dust" }] }, plansAddCardsResponse);
    expect(free.cards[0]).toMatchObject({
      cardId: "c1",
      name: "Macerator: Titanium Dust",
      machine: { id: "mock-mega-macerator", name: "Mock Mega Macerator" },
      tier: "HV",
    });
    const capped = await create({ tierCap: "MV" });
    const single = await call(addCardsPost, { planId: capped, cards: [{ recipeId: "r-dust" }] }, plansAddCardsResponse);
    expect(single.cards[0]).toMatchObject({ machine: { id: "mock-macerator" }, tier: "LV" });
    const node = getDraft(capped).project.nodes[0]!;
    expect(node).toMatchObject({ recipeId: "r-dust", overclockTier: "LV", machineCount: 1, enabled: true });
    expect(node.machineHandlerId).toBeUndefined();
  });

  it("takes a machine by name or id, a tier and nested dials", async () => {
    const planId = await create();
    const added = await call(
      addCardsPost,
      {
        planId,
        cards: [
          { recipeId: "r-dust", machine: "mock macerator", tier: "MV" },
          { recipeId: "r-dust", machine: "mock-mega-macerator", tier: "EV", dials: { mode: "slow" } },
        ],
      },
      plansAddCardsResponse,
    );
    expect(added.cards.map((card) => [card.machine.id, card.tier, card.dials])).toEqual([
      ["mock-macerator", "MV", undefined],
      ["mock-mega-macerator", "EV", { mode: "slow" }],
    ]);
    expect(getDraft(planId).project.nodes[1]).toMatchObject({
      machineHandlerId: "mock-mega-macerator",
      machineConfigTiers: { mode: "slow" },
    });
  });

  it("refuses what the recipe cannot do, naming the card, and changes nothing", async () => {
    const planId = await create({ tierCap: "HV" });
    const card = (fields: object) => ({ planId, cards: [{ recipeId: "r-ingot" }, { recipeId: "r-dust", ...fields }] });
    expect(await fails(addCardsPost, card({ tier: "ULV" }), "tier_too_low", 422)).toMatch(/^cards\[1\]: .*LV/);
    expect(await fails(addCardsPost, { planId, cards: [{ recipeId: "r-iv" }] }, "tier_too_low", 422)).toMatch(/IV/);
    await fails(addCardsPost, card({ tier: "EV" }), "bad_request", 400);
    expect(await fails(addCardsPost, card({ machine: "Oven" }), "bad_request", 400)).toMatch(/Mock Macerator/);
    expect(
      await fails(addCardsPost, card({ machine: "mock-mega-macerator", dials: { speed: "fast" } }), "bad_request", 400),
    ).toMatch(/"speed"/);
    await fails(addCardsPost, card({ machine: "mock-mega-macerator", dials: { mode: "warp" } }), "bad_request", 400);
    await fails(addCardsPost, card({ mode: "slow" }), "bad_request", 400);
    expect(await fails(addCardsPost, card({ recipeId: "r-none" }), "unknown_recipe", 404)).toMatch(/^cards\[1\]/);
    expect(getDraft(planId).project.nodes).toHaveLength(0);
  });

  it("holds at most 60 cards", async () => {
    const planId = await create();
    const cards = (n: number) => Array.from({ length: n }, () => ({ recipeId: "r-ingot" }));
    await call(addCardsPost, { planId, cards: cards(59) }, plansAddCardsResponse);
    await fails(addCardsPost, { planId, cards: cards(2) }, "plan_too_big", 413);
    const last = await call(addCardsPost, { planId, cards: cards(1) }, plansAddCardsResponse);
    expect(last.cardCount).toBe(60);
  });
});

describe("wires", () => {
  it("wires by resource automatically, and a disconnected pair stays cut", async () => {
    const planId = await create({ tierCap: "MV" });
    const first = await call(addCardsPost, { planId, cards: [{ recipeId: "r-dust" }, { recipeId: "r-ingot" }] }, plansAddCardsResponse);
    expect(first.wires).toEqual([
      { wireId: "w1", from: "c1", to: "c2", id: "dust", kind: "item", name: "Titanium Dust" },
    ]);
    await call(disconnectPost, { planId, wireId: "w1" }, plansDisconnectResponse);
    await fails(disconnectPost, { planId, wireId: "w1" }, "bad_request", 400);
    const second = await call(addCardsPost, { planId, cards: [{ recipeId: "r-ingot" }] }, plansAddCardsResponse);
    expect(second.wires.map((wire) => [wire.from, wire.to])).toEqual([["c1", "c3"]]);

    const joined = await call(
      connectPost,
      { planId, from: { cardId: "c1", resource: "dust" }, to: { cardId: "c2" } },
      plansConnectResponse,
    );
    expect(joined).toMatchObject({ created: true, wire: { from: "c1", to: "c2", name: "Titanium Dust", manual: true } });
    const again = await call(
      connectPost,
      { planId, from: { cardId: "c1", resource: { id: "dust", kind: "item" } }, to: { cardId: "c2", resource: "dust" } },
      plansConnectResponse,
    );
    expect(again).toMatchObject({ created: false, wire: { wireId: joined.wire.wireId } });
    await fails(connectPost, { planId, from: { cardId: "c2", resource: "dust" }, to: { cardId: "c1" } }, "bad_request", 400);
    await fails(connectPost, { planId, from: { cardId: "c1", resource: "stone" }, to: { cardId: "c2" } }, "bad_request", 400);
    await fails(connectPost, { planId, from: { cardId: "c1", resource: "nope" }, to: { cardId: "c2" } }, "unknown_resource", 404);
  });

  it("removes cards with their wires and keeps targets for the next maker", async () => {
    const planId = await create({ tierCap: "MV" });
    await call(addCardsPost, { planId, cards: [{ recipeId: "r-dust" }, { recipeId: "r-ingot" }] }, plansAddCardsResponse);
    await call(setTargetsPost, { planId, targets: [{ resource: "ingot", rate: 1 }] }, plansSetTargetsResponse);
    const removed = await call(removeCardsPost, { planId, cardIds: ["c2"] }, plansRemoveCardsResponse);
    expect(removed).toMatchObject({ removed: ["c2"], wiresRemoved: 1, cardCount: 1 });
    expect(removed.targets).toEqual([{ id: "ingot", kind: "item", name: "Titanium Ingot", rate: 1 }]);
    expect(getDraft(planId).project.recipes.map((entry) => entry.id)).toEqual(["r-dust"]);
    await fails(removeCardsPost, { planId, cardIds: ["c2"] }, "bad_request", 400);
    const back = await call(addCardsPost, { planId, cards: [{ recipeId: "r-ingot" }] }, plansAddCardsResponse);
    expect(back.cards[0]!.cardId).toBe("c3");
    const got = await call(getPost, { planId }, plansGetResponse);
    expect(got.targets).toEqual([{ id: "ingot", kind: "item", name: "Titanium Ingot", rate: 1, cardId: "c3" }]);
  });
});

describe("plans/set-targets and plans/get", () => {
  it("puts a target on the card that makes it as a port rate", async () => {
    const planId = await create({ tierCap: "MV" });
    await call(addCardsPost, { planId, cards: [{ recipeId: "r-dust" }, { recipeId: "r-ingot" }] }, plansAddCardsResponse);
    await fails(setTargetsPost, { planId, targets: [{ resource: "ore", rate: 1 }] }, "no_recipe", 422);
    await fails(setTargetsPost, { planId, targets: [{ resource: "ingot", rate: 1 }, { resource: "ingot", rate: 2 }] }, "bad_request", 400);
    await fails(setTargetsPost, { planId, targets: [{ resource: "ingot", rate: 0 }] }, "bad_request", 400);
    const set = await call(setTargetsPost, { planId, targets: [{ resource: "ingot", rate: 2 }] }, plansSetTargetsResponse);
    expect(set.targets).toEqual([{ id: "ingot", kind: "item", name: "Titanium Ingot", rate: 2, cardId: "c2" }]);
    expect(getDraft(planId).project.nodes.map((node) => node.portRates)).toEqual([undefined, { "output:item:ingot": 2 }]);
    await call(setTargetsPost, { planId, targets: [] }, plansSetTargetsResponse);
    expect(getDraft(planId).project.nodes.every((node) => node.portRates === undefined)).toBe(true);
  });

  it("holds a real plan: the site's plan schema takes it, in plan mode, cards laid out apart", async () => {
    const planId = await create({ tierCap: "MV" });
    await call(addCardsPost, { planId, cards: [{ recipeId: "r-dust" }, { recipeId: "r-ingot" }] }, plansAddCardsResponse);
    const got = await call(getPost, { planId }, plansGetResponse);
    expect(got.cards!.map((card) => card.cardId)).toEqual(["c1", "c2"]);
    expect(got.wires).toHaveLength(1);
    const project = getDraft(planId).project;
    expect(project).toMatchObject({ planMode: true, solveMode: true, name: "Titanium" });
    const parsed = factoryProjectSchema.safeParse(project);
    expect(parsed.success, JSON.stringify(parsed.error?.issues)).toBe(true);
    const [left, right] = project.nodes;
    expect(right!.position.x).toBeGreaterThan(left!.position.x);
  });
});

describe("plans/solve", () => {
  beforeAll(() => useCoreSolver(), CORE_LOAD_MS);

  it("solves a small chain: targets, raws still needed, byproducts, power, one line per card", async () => {
    const planId = await create({ tierCap: "MV", free: ["ore"] });
    await call(addCardsPost, { planId, cards: [{ recipeId: "r-dust" }, { recipeId: "r-ingot" }] }, plansAddCardsResponse);
    const unset = await call(solvePost, { planId }, plansSolveResponse);
    expect(unset.targetsMet).toBe(false);
    expect(unset.warnings!.join(" ")).toMatch(/plans\/set-targets/);

    await call(setTargetsPost, { planId, targets: [{ resource: "ingot", rate: 2 }] }, plansSetTargetsResponse);
    const solved = await call(solvePost, { planId }, plansSolveResponse);
    expect(solved).toMatchObject({ solved: true, targetsMet: true });
    expect(solved.targets).toEqual([
      { id: "ingot", kind: "item", name: "Titanium Ingot", rate: 2, made: 2, met: true, cardId: "c2" },
    ]);
    expect(solved.unsupplied).toEqual([
      { id: "nitrogen", kind: "fluid", name: "Nitrogen", perSecond: 2000 },
      { id: "ore", kind: "item", name: "Titanium Ore", perSecond: 2, free: true },
    ]);
    expect(solved.byproducts).toEqual([{ id: "stone", kind: "item", name: "Stone Dust", perSecond: 2 }]);
    expect(solved.euT).toBe(120);
    expect(solved.cardColumns).toEqual(["cardId", "recipe", "machine", "tier", "count", "in", "out", "dials"]);
    expect(solved.cards).toEqual([
      ["c1", "Macerator: Titanium Dust", "Mock Macerator", "LV", 2, { "Titanium Ore": 2 }, { "Titanium Dust": 2, "Stone Dust": 2 }],
      ["c2", "Furnace: Titanium Ingot", "Mock Furnace", "LV", 2, { "Titanium Dust": 2, Nitrogen: 2000 }, { "Titanium Ingot": 2 }],
    ]);

    const trimmed = await call(solvePost, { planId, fields: ["unsupplied"] }, plansSolveResponse);
    expect(Object.keys(trimmed).sort()).toEqual(["dataset", "planId", "solved", "targetsMet", "unsupplied"]);
  });

  it("ends a card's row with its dials when any are set", async () => {
    const planId = await create();
    await call(addCardsPost, { planId, cards: [{ recipeId: "r-dust", dials: { mode: "slow" } }] }, plansAddCardsResponse);
    const solved = await call(solvePost, { planId, fields: ["cards"] }, plansSolveResponse);
    expect(solved.cards![0]![2]).toBe("Mock Mega Macerator");
    expect(solved.cards![0]![7]).toEqual({ mode: "slow" });
  });

  it("answers a 60-card draft in under 12 KB and 5 s", async () => {
    const planId = await create();
    const cards = Array.from({ length: 60 }, (_, n) => ({ recipeId: `r-step-${n}` }));
    let started = performance.now();
    const added = await call(addCardsPost, { planId, cards }, plansAddCardsResponse);
    console.warn(`add 60 cards: ${Math.round(performance.now() - started)} ms`);
    expect(added.wires).toHaveLength(59);
    await call(setTargetsPost, { planId, targets: [{ resource: "part-60", rate: 1 }] }, plansSetTargetsResponse);
    started = performance.now();
    const response = await solvePost(request({ planId }));
    const text = await response.text();
    const ms = performance.now() - started;
    const solved = plansSolveResponse.parse(JSON.parse(text));
    expect(solved.targetsMet).toBe(true);
    expect(solved.cards).toHaveLength(60);
    expect(Buffer.byteLength(text)).toBeLessThanOrEqual(RESPONSE_AIM_BYTES);
    console.warn(`solve 60 cards: ${Math.round(ms)} ms, ${Buffer.byteLength(text)} bytes`);
    expect(ms).toBeLessThan(5000);
    const got = await getPost(request({ planId }));
    const gotText = await got.text();
    console.warn(`get 60 cards: ${Buffer.byteLength(gotText)} bytes, truncated ${plansGetResponse.parse(JSON.parse(gotText)).truncated}`);
    expect(Buffer.byteLength(gotText)).toBeLessThanOrEqual(RESPONSE_AIM_BYTES);
  });
});
