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
import { normalizeLoadedProject } from "@/lib/model/project-normalize";
import { machineReadout } from "@/lib/machines/readout";
import { normalizeHatchInput } from "@/lib/solver/hatch-input";
import { DRAFT_TTL_MS, DRAFTS_PER_DAY, getDraft, resetDrafts, setDraftClock } from "./drafts";
import { solveProject } from "./solve";
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

/** The steam age: a recipe steam and electric machines share (in the dataset's order), and one only bricks run. */
RECIPES["r-bronze"] = recipe("r-bronze", {
  name: "Alloy Smelter: Bronze Ingot",
  machineType: "Alloy Smelter",
  minimumTier: "ULV",
  eut: 8,
  inputs: [slot("item", "dust", 3)],
  outputs: [slot("item", "ingot", 4)],
  machineHandlers: [
    handler("alloy-smelter", "Alloy Smelter", "single", "ULV", { availableTiers: ["LV", "MV", "HV"] }),
    // The steam machines' tiers read the recipe's EU, not anything they are built at.
    handler("steam-alloy-smelter", "Steam Alloy Smelter", "single", "LuV"),
    handler("steam-fuser", "Steam Fuser", "multiblock", "LuV"),
    handler("zyngen", "Zyngen", "multiblock", "ULV"),
  ],
});
RECIPES["r-smelt"] = recipe("r-smelt", {
  name: "Furnace: Titanium Ingot",
  machineType: "Furnace",
  minimumTier: "NONE" as Recipe["minimumTier"],
  eut: 0,
  inputs: [slot("item", "dust", 1)],
  outputs: [slot("item", "ingot", 1)],
  machineHandlers: [
    handler("steam-hearth", "Steam Hearth", "multiblock", "NONE"),
    handler("multi-smelter", "Multi Smelter", "multiblock", "NONE"),
  ],
});
// A GT++ "tier x 4 parallels" multiblock on a recipe that draws most of its tier's voltage: one amp runs one parallel.
RECIPES["r-bath"] = recipe("r-bath", {
  name: "Industrial Chemical Bath: Titanium Ingot",
  machineType: "Industrial Chemical Bath",
  minimumTier: "HV",
  eut: 480,
  durationTicks: 200,
  inputs: [slot("item", "dust", 1)],
  outputs: [slot("item", "ingot", 1)],
  machineHandlers: [handler("chem-bath", "Industrial Chemical Bath", "multiblock", "HV")],
});
RECIPES["r-cauldron"] = recipe("r-cauldron", {
  name: "Cauldron: Titanium Dust",
  machineType: "Cauldron",
  minimumTier: "ULV",
  eut: 0,
  inputs: [slot("item", "ore", 1)],
  outputs: [slot("item", "dust", 1)],
});
RECIPES["r-bbf"] = recipe("r-bbf", {
  name: "Bricked Blast Furnace: Steel Ingot",
  machineType: "Bricked Blast Furnace",
  minimumTier: "ULV",
  eut: 0,
  inputs: [slot("item", "dust", 1)],
  outputs: [slot("item", "ingot", 1)],
});

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

async function create(constraints?: object, extra: object = {}): Promise<string> {
  const body = await call(createPost, { name: "Titanium", ...(constraints ? { constraints } : {}), ...extra }, plansCreateResponse);
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
    expect(single.cards[0]).toMatchObject({ machine: { id: "mock-macerator" }, tier: "MV" });
    const node = getDraft(capped).project.nodes[0]!;
    expect(node).toMatchObject({ recipeId: "r-dust", overclockTier: "MV", machineCount: 1, enabled: true });
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

describe("steam, primitive machines and the card tier", () => {
  beforeAll(() => useCoreSolver(), CORE_LOAD_MS);

  const added = async (planId: string, card: object) =>
    (await call(addCardsPost, { planId, cards: [{ recipeId: "r-bronze", ...card }] }, plansAddCardsResponse)).cards[0]!;

  it("never defaults to a steam machine, and with steam allowed or a ULV cap offers them as before", async () => {
    // The dataset puts the Steam Fuser before the Zyngen; at EV the Zyngen is the electric multiblock.
    expect(await added(await create({ tierCap: "EV" }), {})).toMatchObject({ machine: { id: "zyngen" }, tier: "EV" });
    expect(await added(await create(), {})).toMatchObject({ machine: { id: "zyngen" }, tier: "ULV" });
    // Steam counts as ULV whatever its tier reads (LuV here), and keeps that tier: it does not overclock.
    expect(await added(await create({ tierCap: "EV", steam: true }), {})).toMatchObject({ machine: { id: "steam-fuser" }, tier: "LuV" });
    expect(await added(await create({ tierCap: "ULV" }), {})).toMatchObject({ machine: { id: "steam-fuser" } });
    // The Multi Smelter reads NONE like the steam furnaces, and is electric.
    const smelt = await create({ tierCap: "EV" });
    const smelter = (await call(addCardsPost, { planId: smelt, cards: [{ recipeId: "r-smelt" }] }, plansAddCardsResponse)).cards[0]!;
    expect(smelter.machine.id).toBe("multi-smelter");
    // Named, a steam machine is the player's choice.
    const named = await create({ tierCap: "EV" });
    expect(await added(named, { machine: "Steam Alloy Smelter" })).toMatchObject({ machine: { id: "steam-alloy-smelter" }, tier: "LuV" });
    expect(await fails(addCardsPost, { planId: named, cards: [{ recipeId: "r-bronze", machine: "steam-fuser", tier: "EV" }] }, "bad_request", 400)).toMatch(/burns steam/);
  });

  it("leaves a recipe only primitive machines run to a request that names one", async () => {
    const planId = await create({ tierCap: "EV" });
    expect(await fails(addCardsPost, { planId, cards: [{ recipeId: "r-bbf" }] }, "bad_request", 400)).toMatch(/Bricked Blast Furnace.*constraints\.steam/);
    const card = (await call(addCardsPost, { planId, cards: [{ recipeId: "r-bbf", machine: "Bricked Blast Furnace" }] }, plansAddCardsResponse)).cards[0]!;
    expect(card).toMatchObject({ machine: { name: "Bricked Blast Furnace" }, tier: "ULV" });
    const open = await create({ steam: true });
    expect((await call(addCardsPost, { planId: open, cards: [{ recipeId: "r-bbf" }] }, plansAddCardsResponse)).cards[0]!.tier).toBe("ULV");
  });

  it("puts a card at the highest tier at or below cardTier the machine is built at, never below the recipe's", async () => {
    const ev = await create({ tierCap: "IV" }, { cardTier: "EV" });
    expect(await added(ev, {})).toMatchObject({ machine: { id: "zyngen" }, tier: "EV" });
    // The single block is built at LV to HV only.
    expect(await added(ev, { machine: "Alloy Smelter" })).toMatchObject({ tier: "HV" });
    // A recipe above the card tier but within the cap runs at its own minimum.
    const iv = (await call(addCardsPost, { planId: ev, cards: [{ recipeId: "r-iv" }] }, plansAddCardsResponse)).cards[0]!;
    expect(iv.tier).toBe("IV");
    // A machine that draws no EU stays at its own tier.
    const cauldron = (await call(addCardsPost, { planId: ev, cards: [{ recipeId: "r-cauldron" }] }, plansAddCardsResponse)).cards[0]!;
    expect(cauldron.tier).toBe("ULV");
    const low = await create({ tierCap: "EV" }, { cardTier: "LV" });
    expect(await added(low, {})).toMatchObject({ tier: "LV" });
    expect(await fails(createPost, { name: "x", constraints: { tierCap: "HV" }, cardTier: "EV" }, "bad_request", 400)).toMatch(/cardTier/);
    const got = await call(getPost, { planId: ev, fields: ["targets"] }, plansGetResponse);
    expect(got).toMatchObject({ cardTier: "EV", constraints: { tierCap: "IV" } });
  });

  it("echoes the card tier it applies, and keeps cards from going below minTier", async () => {
    // No cardTier: the cap is the card tier, and create says so.
    const capped = await call(createPost, { name: "x", constraints: { tierCap: "IV" } }, plansCreateResponse);
    expect(capped.cardTier).toBe("IV");
    expect(await added(capped.planId, {})).toMatchObject({ machine: { id: "zyngen" }, tier: "IV" });
    const uncapped = await call(createPost, { name: "x" }, plansCreateResponse);
    expect(uncapped.cardTier).toBeUndefined();

    const ev = await call(createPost, { name: "x", constraints: { tierCap: "IV" }, cardTier: "EV", minTier: "EV" }, plansCreateResponse);
    expect(ev).toMatchObject({ cardTier: "EV", minTier: "EV" });
    expect(await added(ev.planId, {})).toMatchObject({ tier: "EV" });
    expect(await fails(addCardsPost, { planId: ev.planId, cards: [{ recipeId: "r-bronze", tier: "HV" }] }, "bad_request", 400)).toMatch(/HV is below the draft's minTier EV/);
    // The single block is built up to HV only: nothing at or above EV.
    expect(await fails(addCardsPost, { planId: ev.planId, cards: [{ recipeId: "r-bronze", machine: "Alloy Smelter" }] }, "bad_request", 400)).toMatch(/minTier EV/);
    // A machine that draws no EU keeps its own tier.
    expect((await call(addCardsPost, { planId: ev.planId, cards: [{ recipeId: "r-cauldron" }] }, plansAddCardsResponse)).cards[0]!.tier).toBe("ULV");
    expect(await call(getPost, { planId: ev.planId, fields: ["targets"] }, plansGetResponse)).toMatchObject({ cardTier: "EV", minTier: "EV" });

    // With no card tier a card takes the lowest tier that runs it, raised to minTier.
    const floor = await create(undefined, { minTier: "MV" });
    expect(await added(floor, {})).toMatchObject({ machine: { id: "zyngen" }, tier: "MV" });

    expect(await fails(createPost, { name: "x", constraints: { tierCap: "IV" }, cardTier: "HV", minTier: "EV" }, "bad_request", 400)).toMatch(/minTier EV is above the cardTier HV/);
    expect(await fails(createPost, { name: "x", constraints: { tierCap: "HV" }, minTier: "EV" }, "bad_request", 400)).toMatch(/minTier EV is above the tierCap HV/);
  });

  it("solves a card at its tier as the planner shows it once the plan is opened", async () => {
    const planId = await create({ tierCap: "EV" });
    await call(addCardsPost, { planId, cards: [{ recipeId: "r-dust" }] }, plansAddCardsResponse);
    await call(setTargetsPost, { planId, targets: [{ resource: "dust", rate: 4 }] }, plansSetTargetsResponse);
    const project = getDraft(planId).project;
    expect(project.nodes[0]).toMatchObject({ overclockTier: "EV" });
    const draft = await solveProject(project);
    // The planner's load funnel gives the multiblock its EV hatch; the numbers must not move.
    const opened = normalizeLoadedProject(structuredClone(project));
    expect(opened.nodes[0]).toMatchObject({ hatchVoltageTier: "EV" });
    const shown = await solveProject(opened);
    expect(shown.totalEuT).toBeCloseTo(draft.totalEuT, 6);
    expect(shown.nodes.c1!.theoreticalMachinesRequired).toBeCloseTo(draft.nodes.c1!.theoreticalMachinesRequired, 6);
    // At EV the card overclocks past its HV minimum, so it needs fewer machines than at HV.
    const hv = await create({ tierCap: "HV" });
    await call(addCardsPost, { planId: hv, cards: [{ recipeId: "r-dust" }] }, plansAddCardsResponse);
    await call(setTargetsPost, { planId: hv, targets: [{ resource: "dust", rate: 4 }] }, plansSetTargetsResponse);
    const slower = await solveProject(getDraft(hv).project);
    expect(draft.nodes.c1!.theoreticalMachinesRequired).toBeLessThan(slower.nodes.c1!.theoreticalMachinesRequired);
  });

  it("gives a multiblock card the hatch the board gives it, whose amps raise the parallel tier", async () => {
    const planId = await create({ tierCap: "HV" });
    await call(addCardsPost, { planId, cards: [{ recipeId: "r-bath" }] }, plansAddCardsResponse);
    await call(setTargetsPost, { planId, targets: [{ resource: "ingot", rate: 2 }] }, plansSetTargetsResponse);
    const project = getDraft(planId).project;
    const card = project.nodes[0]!;
    // The board's new-card rule (whole amps for full parallels) at the card's tier: 19 A of HV, read as regular
    // hatches (9,728 / 2 = 4,864 EU/t summed, the IV ordinal), so 5 x 4 = 20 parallels, which the 19 A can feed.
    const board = normalizeHatchInput(RECIPES["r-bath"]!, { ...card, hatchAmps: undefined, powerEuT: undefined, hatchVoltageTier: "HV" });
    expect(card).toMatchObject({ overclockTier: "HV", hatchVoltageTier: "HV", hatchAmps: board.hatchAmps, powerEuT: board.powerEuT });
    expect(card.hatchAmps).toBe(19);
    expect((machineReadout(RECIPES["r-bath"]!, card).expected as { parallels: number }).parallels).toBe(20);
    // The delivered plan opens with the same hatch, and solves to the same machines.
    const draft = await solveProject(project);
    const opened = normalizeLoadedProject(structuredClone(project));
    expect(opened.nodes[0]).toMatchObject({ hatchVoltageTier: "HV", hatchAmps: 19 });
    const shown = await solveProject(opened);
    expect(shown.nodes.c1!.theoreticalMachinesRequired).toBeCloseTo(draft.nodes.c1!.theoreticalMachinesRequired, 6);
    expect(shown.totalEuT).toBeCloseTo(draft.totalEuT, 6);
    // One amp of HV feeds one parallel: twenty times the machines.
    const single = structuredClone(project);
    single.nodes[0] = { ...card, hatchAmps: 1, powerEuT: 512 };
    const oneAmp = await solveProject(single);
    expect(oneAmp.nodes.c1!.theoreticalMachinesRequired / draft.nodes.c1!.theoreticalMachinesRequired).toBeCloseTo(20, 6);
  });

  it("says in the solve what steam its steam machines burn", async () => {
    const planId = await create({ steam: true, tierCap: "EV" });
    await call(addCardsPost, { planId, cards: [{ recipeId: "r-bronze" }] }, plansAddCardsResponse);
    await call(setTargetsPost, { planId, targets: [{ resource: "ingot", rate: 1 }] }, plansSetTargetsResponse);
    const solved = await call(solvePost, { planId, fields: ["warnings", "power"] }, plansSolveResponse);
    expect(solved.euT).toBe(0);
    expect(solved.warnings!.join(" ")).toMatch(/Steam machines burn [\d.]+ L\/s of steam, which is not in unsupplied or euT: c1\./);
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

  it("keeps the recipes of a shared machine's other sections when another card goes", async () => {
    const planId = await create({ tierCap: "MV" });
    await call(addCardsPost, { planId, cards: [{ recipeId: "r-dust" }, { recipeId: "r-ingot" }] }, plansAddCardsResponse);
    const draft = getDraft(planId);
    draft.project = {
      ...draft.project,
      nodes: draft.project.nodes.map((node) =>
        node.id === "c1" ? { ...node, extraRecipes: [{ recipeId: "r-ingot" }] } : node,
      ),
    };
    await call(removeCardsPost, { planId, cardIds: ["c2"] }, plansRemoveCardsResponse);
    expect(getDraft(planId).project.recipes.map((entry) => entry.id).sort()).toEqual(["r-dust", "r-ingot"]);
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
    const planId = await create({ tierCap: "MV", free: ["ore"] }, { cardTier: "LV" });
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
