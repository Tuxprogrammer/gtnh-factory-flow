import { readFileSync } from "node:fs";
import path from "node:path";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { z } from "zod";
import { POST as addCardsPost } from "@/app/api/agent/v1/plans/add-cards/route";
import { POST as createPost } from "@/app/api/agent/v1/plans/create/route";
import { POST as deliverPost } from "@/app/api/agent/v1/plans/deliver/route";
import { POST as diffPost } from "@/app/api/agent/v1/plans/diff/route";
import { POST as disconnectPost } from "@/app/api/agent/v1/plans/disconnect/route";
import { POST as importPost } from "@/app/api/agent/v1/plans/import/route";
import { POST as removeCardsPost } from "@/app/api/agent/v1/plans/remove-cards/route";
import { POST as setTargetsPost } from "@/app/api/agent/v1/plans/set-targets/route";
import { POST as solvePost } from "@/app/api/agent/v1/plans/solve/route";
import { GET as readGet } from "@/app/api/deliveries/[id]/route";
import { decodePlanCode } from "@/lib/import-export/plan-code";
import { factoryProjectSchema } from "@/lib/model/schemas";
import { PROJECT_SCHEMA_VERSION, type FactoryProject, type MachineHandler, type Recipe } from "@/lib/model/types";
import { getDatasetRecipe, getDatasetResource, getDefaultDatasetVersionId } from "@/lib/server/dataset-query";
import { resetDbBreaker, getCommunityDb } from "@/lib/server/community";
import { useInProcessLocalDb } from "@/lib/server/local-db/client";
import { openLocalDatabase, type LocalDatabase } from "@/lib/server/local-db/database";
import { resetMemoryTokens } from "@/lib/server/memory-rate";
import { CORE_LOAD_MS, useCoreSolver } from "@/test-support/core-solve";
import { DELIVERIES_PER_DAY, DELIVERY_ID_PATTERN, DELIVERY_TABLE, DELIVERY_TTL_MS, newDeliveryId } from "./deliveries";
import {
  deliveryReadResponse,
  plansDeliverResponse,
  plansDiffResponse,
  plansImportResponse,
} from "./delivery-schemas";
import { getDraft, resetDrafts, setDraftClock } from "./drafts";
import { plansCreateResponse, plansAddCardsResponse, plansSolveResponse } from "./plan-schemas";
import { errorResponse } from "./schemas";
import { slimSnapshot, toSlimSnapshot, type SlimSnapshot } from "./slim";

/**
 * plans/deliver, plans/import, plans/diff and GET /api/deliveries/{id}, against the local database the suite opens
 * and a small mocked dataset.
 */

vi.mock("@/lib/server/dataset-query", () => ({
  getDefaultDatasetVersionId: vi.fn(),
  getDatasetResource: vi.fn(),
  getDatasetRecipe: vi.fn(),
  queryDatasetResources: vi.fn(),
  queryDatasetRecipes: vi.fn(),
}));

const TOKEN = "test-token-0123456789";
const VERSION = "stable-test";
const PLAYER = { name: "Steve", uuid: "0f8e2d4c-1111-2222-3333-444455556666" };

const NAMES: Record<string, string> = {
  "item:ore": "Titanium Ore",
  "item:dust": "Titanium Dust",
  "item:stone": "Stone Dust",
  "item:ingot": "Titanium Ingot",
  "item:plate": "Titanium Plate",
  "fluid:nitrogen": "Nitrogen",
};

function slot(kind: "item" | "fluid", id: string, amount: number) {
  return { kind, id, amount, displayName: NAMES[`${kind}:${id}`] ?? id };
}

function recipe(id: string, machine: string, inputs: ReturnType<typeof slot>[], outputs: ReturnType<typeof slot>[]): Recipe {
  return {
    id,
    name: `${machine}: ${outputs[0]!.displayName}`,
    machineType: machine,
    minimumTier: "LV",
    durationTicks: 20,
    eut: 30,
    inputs,
    outputs,
    machineHandlers: [{ id: `mock-${id}`, label: machine, kind: "single", machineType: machine, minimumTier: "LV" } as MachineHandler],
  } as Recipe;
}

const RECIPES: Record<string, Recipe> = {
  "r-dust": recipe("r-dust", "Mock Macerator", [slot("item", "ore", 1)], [slot("item", "dust", 1), slot("item", "stone", 1)]),
  "r-ingot": recipe("r-ingot", "Mock Furnace", [slot("item", "dust", 1), slot("fluid", "nitrogen", 1000)], [slot("item", "ingot", 1)]),
  "r-plate": recipe("r-plate", "Mock Bender", [slot("item", "ingot", 1)], [slot("item", "plate", 1)]),
};

function request(url: string, body: unknown, auth = true): Request {
  return new Request(url, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...(auth ? { Authorization: `Bearer ${TOKEN}` } : {}) },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

type Post = (request: Request) => Promise<Response>;

async function call<S extends z.ZodType>(post: Post, body: unknown, schema: S): Promise<z.infer<S>> {
  const response = await post(request("http://localhost/api/agent/v1/plans", body));
  const json = await response.json();
  expect(response.status, JSON.stringify(json)).toBe(200);
  return schema.parse(json);
}

async function fails(post: Post, body: unknown, code: string, status: number): Promise<string> {
  const response = await post(request("http://localhost/api/agent/v1/plans", body));
  const json = errorResponse.parse(await response.json());
  expect(json.error.code, json.error.message).toBe(code);
  expect(response.status).toBe(status);
  return json.error.message;
}

async function read(id: string, address = "198.51.100.7"): Promise<Response> {
  return readGet(new Request(`http://localhost/api/deliveries/${id}`, { headers: { "x-client-ip": address } }), {
    params: Promise.resolve({ id }),
  });
}

let local: LocalDatabase;
let now = Date.parse("2026-11-21T12:00:00.000Z");

beforeAll(async () => {
  vi.stubEnv("LOCAL_DB", "1");
  local = await openLocalDatabase();
  useInProcessLocalDb(local.handle);
});

afterAll(async () => {
  useInProcessLocalDb(undefined);
  await local.close();
});

beforeEach(async () => {
  vi.stubEnv("LOCAL_DB", "1");
  vi.stubEnv("AGENT_API_TOKEN", TOKEN);
  vi.stubEnv("NEXT_PUBLIC_SITE_URL", "");
  vi.spyOn(console, "info").mockImplementation(() => {});
  vi.spyOn(console, "error").mockImplementation(() => {});
  now = Date.parse("2026-11-21T12:00:00.000Z");
  setDraftClock(() => now);
  resetDrafts();
  resetDbBreaker();
  resetMemoryTokens();
  await local.db.exec(`delete from ${DELIVERY_TABLE}`);
  vi.mocked(getDefaultDatasetVersionId).mockResolvedValue(VERSION);
  vi.mocked(getDatasetRecipe).mockImplementation(async (_version, id) => structuredClone(RECIPES[id]));
  vi.mocked(getDatasetResource).mockImplementation(async (_version, kind, id) => {
    const name = NAMES[`${kind}:${id}`];
    return name ? ({ id, kind, displayName: name } as never) : undefined;
  });
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
  setDraftClock();
});

async function draftWith(recipeIds: string[], target?: { resource: string; rate: number }): Promise<string> {
  const { planId } = await call(createPost, { name: "Titanium" }, plansCreateResponse);
  await call(addCardsPost, { planId, cards: recipeIds.map((recipeId) => ({ recipeId })) }, plansAddCardsResponse);
  if (target) {
    const response = await setTargetsPost(request("http://localhost/x", { planId, targets: [target] }));
    expect(response.status).toBe(200);
  }
  return planId;
}

async function readJson(id: string) {
  const response = await read(id);
  expect(response.status).toBe(200);
  expect(response.headers.get("cache-control")).toBe("no-store");
  return deliveryReadResponse.parse(await response.json());
}

/** A player's plan as the site holds it: ids of its own, two cards on a board, a drawer, a pin. */
function playerPlan(): FactoryProject {
  return factoryProjectSchema.parse({
    schemaVersion: PROJECT_SCHEMA_VERSION,
    id: "6c1f0a7e-player-plan",
    name: "My titanium",
    planMode: true,
    recipes: [RECIPES["r-dust"], RECIPES["r-ingot"]],
    pockets: [{ id: "pocket-a", name: "Smelting", position: { x: 0, y: 0 } }],
    nodes: [
      {
        id: "node-7f3a",
        recipeId: "r-dust",
        machineCount: 2,
        parallel: 1,
        overclockTier: "MV",
        machineConfigTiers: { casing: "hv" },
        coilTier: "cupronickel",
        solvePin: 2,
        solvePinExact: true,
        enabled: true,
        position: { x: 100, y: 40 },
      },
      {
        id: "c4",
        recipeId: "r-ingot",
        machineCount: 1,
        parallel: 1,
        overclockTier: "LV",
        portRates: { "output:item:ingot": 0.5 },
        enabled: true,
        pocketId: "pocket-a",
        position: { x: 500, y: 40 },
      },
    ],
    storages: [{ id: "drawer-1", kind: "item", resourceId: "stone", drainMode: "trash", position: { x: 500, y: 300 } }],
    edges: [
      {
        id: "edge-1",
        source: "node-7f3a",
        target: "c4",
        sourceHandle: "output:item:dust:0",
        targetHandle: "input:item:dust:0",
        resourceKind: "item",
        resourceId: "dust",
      },
      { id: "w2", source: "node-7f3a", target: "drawer-1", resourceKind: "item", resourceId: "stone" },
    ],
    fuelProfiles: [],
  } as never) as FactoryProject;
}

describe("plans/deliver and GET /api/deliveries/{id}", () => {
  it("stores the draft as a new plan, under a d id, whose code decodes to the same plan", async () => {
    const planId = await draftWith(["r-dust", "r-ingot"], { resource: "ingot", rate: 1 });
    const delivered = await call(deliverPost, { planId, player: PLAYER }, plansDeliverResponse);
    expect(delivered.id).toMatch(DELIVERY_ID_PATTERN);
    expect(delivered.id[0]).toBe("d");
    expect(delivered.name).toBe("Titanium");
    expect(delivered.url).toBeUndefined();
    expect(delivered.expiresAt).toBe(new Date(now + DELIVERY_TTL_MS).toISOString());

    const body = await readJson(delivered.id);
    expect(body.kind).toBe("delivery");
    if (body.kind !== "delivery") throw new Error("not a delivery");
    const plan = factoryProjectSchema.parse(body.plan) as FactoryProject;
    expect(plan.id).not.toBe(planId);
    expect(plan.nodes.map((node) => node.recipeId)).toEqual(["r-dust", "r-ingot"]);
    expect(plan.recipes.map((entry) => entry.id).sort()).toEqual(["r-dust", "r-ingot"]);
    expect(plan.nodes[1]!.portRates).toEqual({ "output:item:ingot": 1 });
    expect(await decodePlanCode(delivered.code)).toEqual(plan);
  });

  it("gives a link only when the site knows its address", async () => {
    vi.stubEnv("NEXT_PUBLIC_SITE_URL", "https://planner.test/");
    const planId = await draftWith(["r-dust"]);
    const delivered = await call(deliverPost, { planId, player: PLAYER }, plansDeliverResponse);
    expect(delivered.url).toBe(`https://planner.test/#p=${delivered.code}`);
  });

  it("never serves the player's name or uuid, and stores them for audit", async () => {
    const planId = await draftWith(["r-dust"]);
    const { id } = await call(deliverPost, { planId, player: PLAYER }, plansDeliverResponse);
    const response = await read(id);
    const text = await response.text();
    expect(text).not.toContain(PLAYER.name);
    expect(text).not.toContain(PLAYER.uuid);
    expect(Object.keys(JSON.parse(text)).sort()).toEqual(["createdAt", "expiresAt", "id", "kind", "name", "plan"]);
    const row = await local.db.query<{ player_name: string; player_uuid: string }>(
      `select player_name, player_uuid from ${DELIVERY_TABLE} where id = $1`,
      [id],
    );
    expect(row.rows[0]).toEqual({ player_name: PLAYER.name, player_uuid: PLAYER.uuid });
  });

  it("needs the player with a uuid, and a draft with cards", async () => {
    const planId = await draftWith(["r-dust"]);
    await fails(deliverPost, { planId, player: { name: "Steve" } }, "bad_request", 400);
    const { planId: empty } = await call(createPost, { name: "Empty" }, plansCreateResponse);
    await fails(deliverPost, { planId: empty, player: PLAYER }, "bad_request", 400);
    await fails(deliverPost, { planId: "x23456789abcdefghjkm", player: PLAYER }, "plan_not_found", 404);
  });

  it("delivers at most 100 a day (UTC), counting again the next day", async () => {
    const planId = await draftWith(["r-dust"]);
    const rows = Array.from({ length: DELIVERIES_PER_DAY }, () => ({
      id: newDeliveryId("delivery"),
      kind: "delivery",
      name: "Old",
      payload: {},
      created_at: new Date(now - 60_000).toISOString(),
      expires_at: new Date(now + DELIVERY_TTL_MS).toISOString(),
    }));
    const { error } = await getCommunityDb().from(DELIVERY_TABLE).insert(rows);
    expect(error).toBeNull();
    await fails(deliverPost, { planId, player: PLAYER }, "rate_limited", 429);
    now = Date.parse("2026-11-22T00:00:01.000Z");
    await call(deliverPost, { planId, player: PLAYER }, plansDeliverResponse);
  });

  it("answers 404 for a bad id, an unknown id and an expired one, and only GET", async () => {
    const planId = await draftWith(["r-dust"]);
    const { id } = await call(deliverPost, { planId, player: PLAYER }, plansDeliverResponse);
    for (const wrong of ["d123", "x23456789abcdefgh", `${id}a`, id.toUpperCase(), "d0000000000000000"[0] + "o".repeat(15), newDeliveryId("delivery"), newDeliveryId("proposal")]) {
      const response = await read(wrong);
      expect(response.status, wrong).toBe(404);
      expect(await response.json()).toEqual({ error: "Plan not found." });
    }
    now += DELIVERY_TTL_MS - 1;
    expect((await read(id)).status).toBe(200);
    now += 1;
    expect((await read(id)).status).toBe(404);
  });

  it("still serves stored plans with the agent API switched off", async () => {
    const planId = await draftWith(["r-dust"]);
    const { id } = await call(deliverPost, { planId, player: PLAYER }, plansDeliverResponse);
    vi.stubEnv("AGENT_API_TOKEN", "");
    expect((await deliverPost(request("http://localhost/x", { planId, player: PLAYER }))).status).toBe(404);
    expect((await read(id)).status).toBe(200);
  });

  it("limits reads per address", async () => {
    const id = newDeliveryId("delivery");
    for (let i = 0; i < 60; i += 1) {
      expect((await read(id, "203.0.113.9")).status).toBe(404);
    }
    expect((await read(id, "203.0.113.9")).status).toBe(429);
    expect((await read(id, "203.0.113.10")).status).toBe(404);
  });
});

describe("slim snapshots", () => {
  it("makes the documented shape and survives an import exactly", async () => {
    const snapshot = toSlimSnapshot(playerPlan());
    slimSnapshot.parse(snapshot);
    expect(snapshot).toEqual({
      version: 1,
      plan: { id: "6c1f0a7e-player-plan", name: "My titanium" },
      boards: [{ id: "pocket-a", name: "Smelting", position: { x: 0, y: 0 } }],
      cards: [
        {
          id: "node-7f3a",
          recipeId: "r-dust",
          tier: "MV",
          dials: { casing: "hv", heatingCoil: "cupronickel" },
          count: 2,
          exact: true,
          position: { x: 100, y: 40 },
        },
        { id: "c4", recipeId: "r-ingot", tier: "LV", position: { x: 500, y: 40 }, board: "pocket-a" },
      ],
      drawers: [{ id: "drawer-1", resource: { id: "stone", kind: "item" }, mode: "trash", position: { x: 500, y: 300 } }],
      wires: [
        {
          id: "edge-1",
          from: "node-7f3a",
          to: "c4",
          resource: { id: "dust", kind: "item" },
          fromPort: "output:item:dust:0",
          toPort: "input:item:dust:0",
        },
        { id: "w2", from: "node-7f3a", to: "drawer-1", resource: { id: "stone", kind: "item" } },
      ],
      targets: [{ card: "c4", port: "output:item:ingot", rate: 0.5 }],
    });

    const imported = await call(importPost, { snapshot, player: PLAYER }, plansImportResponse);
    expect(imported).toMatchObject({ cardCount: 2, wireCount: 2, warnings: [], name: "My titanium" });
    const draft = getDraft(imported.planId);
    expect(toSlimSnapshot(draft.project)).toEqual({ ...snapshot, plan: { ...snapshot.plan, id: draft.id } });
    expect(draft.targets).toEqual([{ id: "ingot", kind: "item", name: "Titanium Ingot", rate: 0.5 }]);
    factoryProjectSchema.parse(draft.project);
  });

  it("keeps a card whose recipe the dataset lacks, says so, and still solves", { timeout: CORE_LOAD_MS }, async () => {
    await useCoreSolver();
    const snapshot = toSlimSnapshot(playerPlan());
    snapshot.cards[0]!.recipeId = "r-gone";
    const imported = await call(importPost, { snapshot }, plansImportResponse);
    expect(imported.warnings).toHaveLength(1);
    expect(imported.warnings[0]).toContain("node-7f3a");
    expect(imported.warnings[0]).toContain("r-gone");
    const draft = getDraft(imported.planId);
    expect(draft.project.nodes[0]!.recipeId).toBe("r-gone");
    expect(draft.project.recipes.map((entry) => entry.id)).toEqual(["r-ingot"]);
    await call(solvePost, { planId: imported.planId }, plansSolveResponse);
  });

  it("refuses broken references, and plans over 200 cards", async () => {
    const snapshot = toSlimSnapshot(playerPlan());
    const broken = structuredClone(snapshot);
    broken.wires[0]!.to = "nowhere";
    expect(await fails(importPost, { snapshot: broken }, "bad_request", 400)).toContain("nowhere");
    const twice = structuredClone(snapshot);
    twice.drawers[0]!.id = "c4";
    await fails(importPost, { snapshot: twice }, "bad_request", 400);
    const big: SlimSnapshot = { ...snapshot, boards: undefined, wires: [], targets: [], drawers: [] };
    big.cards = Array.from({ length: 201 }, (_, n) => ({ id: `c${n + 1}`, recipeId: "r-dust", tier: "LV", position: { x: n, y: 0 } }));
    await fails(importPost, { snapshot: big }, "plan_too_big", 413);
    big.cards = big.cards.slice(0, 200);
    const fine = await call(importPost, { snapshot: big }, plansImportResponse);
    expect(fine.cardCount).toBe(200);
    // An imported draft may grow past 60 cards, never past 200.
    await fails(addCardsPost, { planId: fine.planId, cards: [{ recipeId: "r-ingot" }] }, "plan_too_big", 413);
  });

  it("takes a body up to 512 KB on import only", async () => {
    const snapshot = toSlimSnapshot(playerPlan());
    const padded = { snapshot: { ...snapshot, plan: { ...snapshot.plan, name: "x".repeat(150) } } };
    const filler = JSON.stringify(padded).replace('"targets"', `${" ".repeat(300 * 1024)}"targets"`);
    const response = await importPost(request("http://localhost/x", filler));
    expect(response.status).toBe(200);
    const tooBig = JSON.stringify(padded).replace('"targets"', `${" ".repeat(513 * 1024)}"targets"`);
    expect((await importPost(request("http://localhost/x", tooBig))).status).toBe(400);
    const planId = await draftWith(["r-dust"]);
    const elsewhere = JSON.stringify({ planId, player: PLAYER }).replace("}", `${" ".repeat(70 * 1024)}}`);
    expect((await deliverPost(request("http://localhost/x", elsewhere))).status).toBe(400);
  });
});

describe("plans/diff", () => {
  async function importPlayerPlan(): Promise<string> {
    const imported = await call(importPost, { snapshot: toSlimSnapshot(playerPlan()) }, plansImportResponse);
    return imported.planId;
  }

  it("stores what the draft adds as a proposal the GET serves", async () => {
    const planId = await importPlayerPlan();
    const added = await call(addCardsPost, { planId, cards: [{ recipeId: "r-plate" }] }, plansAddCardsResponse);
    expect(added.cards[0]!.cardId).toBe("c5");
    expect(added.wires).toHaveLength(1);
    const setTargets = await setTargetsPost(
      request("http://localhost/x", {
        planId,
        targets: [
          { resource: "ingot", rate: 0.5 },
          { resource: "plate", rate: 0.25 },
        ],
      }),
    );
    expect(setTargets.status).toBe(200);

    const diff = await call(diffPost, { planId, player: PLAYER }, plansDiffResponse);
    expect(diff.proposalId).toMatch(/^p[a-hjkmnp-z2-9]{15}$/);
    expect(diff.basePlanId).toBe("6c1f0a7e-player-plan");
    expect(diff.summary).toEqual({ addedCards: 1, addedWires: 1, addedDrawers: 0, changedTargets: 1 });
    expect(diff.text).toContain("Mock Bender: Titanium Plate c5");

    const body = await readJson(diff.proposalId);
    if (body.kind !== "proposal") throw new Error("not a proposal");
    expect(body.basePlanId).toBe("6c1f0a7e-player-plan");
    expect(JSON.stringify(body)).not.toContain(PLAYER.uuid);
    const proposal = body.proposal;
    expect(proposal.cards.map((card) => card.id)).toEqual(["c5"]);
    expect((proposal.cards[0] as { position: { x: number } }).position.x).toBeGreaterThan(500);
    expect(proposal.recipes.map((entry) => entry.id)).toEqual(["r-plate"]);
    expect(proposal.wires).toEqual([
      expect.objectContaining({ source: "c4", target: "c5", resourceId: "ingot" }),
    ]);
    expect(proposal.targets).toEqual([{ card: "c5", port: "output:item:plate", rate: 0.25 }]);
  });

  it("refuses a draft that removed or changed what the player had", async () => {
    const planId = await importPlayerPlan();
    await call(addCardsPost, { planId, cards: [{ recipeId: "r-plate" }] }, plansAddCardsResponse);
    const disconnected = await disconnectPost(request("http://localhost/x", { planId, wireId: "edge-1" }));
    expect(disconnected.status).toBe(200);
    expect(await fails(diffPost, { planId }, "bad_request", 400)).toContain("removed wire edge-1");

    const second = await importPlayerPlan();
    expect((await removeCardsPost(request("http://localhost/x", { planId: second, cardIds: ["node-7f3a"] }))).status).toBe(200);
    expect(await fails(diffPost, { planId: second }, "bad_request", 400)).toContain("removed card node-7f3a");

    const third = await importPlayerPlan();
    expect((await setTargetsPost(request("http://localhost/x", { planId: third, targets: [] }))).status).toBe(200);
    expect(await fails(diffPost, { planId: third }, "bad_request", 400)).toContain("removed the rate pinned on card c4");
  });

  it("refuses a draft that was not imported, and one that adds nothing", async () => {
    const planId = await draftWith(["r-dust"]);
    expect(await fails(diffPost, { planId }, "bad_request", 400)).toContain("plans/import");
    const imported = await importPlayerPlan();
    expect(await fails(diffPost, { planId: imported }, "bad_request", 400)).toContain("adds nothing");
  });
});

describe("read route examples", () => {
  const DIR = path.join(process.cwd(), "docs", "agent-api", "examples");
  it.each(["delivery.json", "proposal.json"])("deliveries/%s matches the read route's schema", (file) => {
    const example = JSON.parse(readFileSync(path.join(DIR, "deliveries", file), "utf8")) as { status: number; response: unknown };
    expect(example.status).toBe(200);
    deliveryReadResponse.parse(example.response);
  });
});
