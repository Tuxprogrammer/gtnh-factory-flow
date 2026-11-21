import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { z } from "zod";
import { POST as addCardsPost } from "@/app/api/agent/v1/plans/add-cards/route";
import { POST as createPost } from "@/app/api/agent/v1/plans/create/route";
import { POST as expandPost } from "@/app/api/agent/v1/plans/expand-inputs/route";
import { POST as setTargetsPost } from "@/app/api/agent/v1/plans/set-targets/route";
import { getVoltageTierIndex } from "@/lib/model/tiers";
import type { MachineHandler, MachineTier, Recipe } from "@/lib/model/types";
import {
  getDatasetRecipe,
  getDatasetResource,
  getDefaultDatasetVersionId,
  queryDatasetRecipes,
  queryDatasetResources,
} from "@/lib/server/dataset-query";
import { CORE_LOAD_MS, useCoreSolver } from "@/test-support/core-solve";
import { getDraft, resetDrafts, setDraftClock } from "./drafts";
import { INPUTS_PER_CALL, mergeConstraints, TRIALS_PER_INPUT } from "./expand";
import { plansExpandInputsResponse } from "./expand-schemas";
import { plansAddCardsResponse, plansCreateResponse, plansSetTargetsResponse } from "./plan-schemas";
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
  "item:ingot": "Titanium Ingot",
  "item:slag": "Titanium Slag",
  "item:mg": "Magnesium Ingot",
  "item:chloride": "Magnesium Chloride Dust",
  "item:gadget": "Gadget",
  "fluid:nitrogen": "Nitrogen",
  "fluid:water": "Water",
  "fluid:tetra": "Titanium Tetrachloride",
};

function nameFor(kind: string, id: string): string | undefined {
  return NAMES[`${kind}:${id}`] ?? (/^part-\d+$/.test(id) ? `Part ${id.slice(5)}` : undefined);
}

function handler(label: string, minimumTier = "LV"): MachineHandler {
  return { id: label.toLowerCase().replace(/ /g, "-"), label, kind: "single", machineType: label, minimumTier } as MachineHandler;
}

function slot(kind: "item" | "fluid", id: string, amount: number) {
  return { kind, id, amount, displayName: nameFor(kind, id) ?? id };
}

function recipe(id: string, machine: string, inputs: ReturnType<typeof slot>[], outputs: ReturnType<typeof slot>[], fields: Partial<Recipe> = {}): Recipe {
  return {
    id,
    name: `${machine}: ${outputs[0]?.displayName}`,
    machineType: machine,
    minimumTier: "LV",
    durationTicks: 20,
    eut: 30,
    inputs,
    outputs,
    machineHandlers: [handler(machine, String(fields.minimumTier ?? "LV"))],
    ...fields,
  } as Recipe;
}

const RECIPES: Record<string, Recipe> = {};
function add(entry: Recipe): void {
  RECIPES[entry.id] = entry;
}

// The draft: a furnace smelting 2 dust and nitrogen into an ingot, with slag left over.
add(recipe("r-ingot", "Mock Furnace", [slot("item", "dust", 2), slot("fluid", "nitrogen", 1000)], [slot("item", "ingot", 1), slot("item", "slag", 1)]));
// What makes the dust.
add(recipe("r-slag", "Mock Sifter", [slot("item", "slag", 1)], [slot("item", "dust", 1)]));
add(recipe("r-ore", "Mock Macerator", [slot("item", "ore", 1)], [slot("item", "dust", 1)]));
// The same reaction on another machine, at the same power and time: tried once.
add(recipe("r-ore-dup", "Mock Big Macerator", [slot("item", "ore", 1)], [slot("item", "dust", 1)]));
add(recipe("r-ore-hot", "Mock Hot Macerator", [slot("item", "ore", 1)], [slot("item", "dust", 1)], { eut: 120 }));
add(recipe("r-ore-water", "Mock Washer", [slot("item", "ore", 1), slot("fluid", "water", 100)], [slot("item", "dust", 1)], { eut: 8 }));
// A steam machine only: left out unless steam is allowed; its steam then counts at 2 L per EU.
add(recipe("r-ore-steam", "Steam Grinder", [slot("item", "ore", 1)], [slot("item", "dust", 1)], { eut: 8 }));
add(recipe("r-chem", "Mock Reactor", [slot("fluid", "tetra", 100), slot("item", "mg", 1)], [slot("item", "dust", 1), slot("item", "chloride", 1)], { eut: 8 }));
add(recipe("r-loop", "Mock Doubler", [slot("item", "dust", 1), slot("item", "ore", 1)], [slot("item", "dust", 2)]));
add(recipe("r-reverse", "Mock Grinder", [slot("item", "ingot", 1)], [slot("item", "dust", 1)]));
add(recipe("r-iv", "Mock Fusion", [slot("item", "ore", 1)], [slot("item", "dust", 4)], { minimumTier: "IV" }));
add(recipe("r-banned", "Mock Banned", [slot("item", "ore", 1)], [slot("item", "dust", 3)]));
// A card that takes eight parts, each made by ten recipes from ore: the caps.
add(recipe("r-gadget", "Mock Assembler", Array.from({ length: 8 }, (_, n) => slot("item", `part-${n}`, 1)), [slot("item", "gadget", 1)]));
for (let part = 0; part < 8; part += 1) {
  for (let n = 0; n < 10; n += 1) {
    add(recipe(`r-p${part}-${n}`, `Mock Lathe ${n}`, [slot("item", "ore", 1)], [slot("item", `part-${part}`, 1 + n / 100)], { eut: 30 + 4 * n }));
  }
}

function tierIndex(tier: string): number {
  return getVoltageTierIndex(tier as Exclude<MachineTier, "DEMO">);
}

/** The site's recipe query, as far as the search reads it: recipes that make the resource, under the tier cap. */
function recipePage(request: { clauses?: { role: string; kind: string; id: string }[]; maxTier?: string }) {
  const makes = request.clauses?.find((clause) => clause.role === "makes");
  const recipes = Object.values(RECIPES).filter(
    (entry) =>
      makes &&
      entry.outputs.some((output) => output.kind === makes.kind && output.id === makes.id) &&
      (!request.maxTier || request.maxTier === "all" || tierIndex(String(entry.minimumTier)) <= tierIndex(request.maxTier)),
  );
  return {
    recipes,
    total: recipes.length,
    recipeMaps: [...new Set(recipes.map((entry) => entry.machineType))],
    recipeMapCounts: recipes.reduce<Record<string, number>>((counts, entry) => {
      counts[entry.machineType] = (counts[entry.machineType] ?? 0) + 1;
      return counts;
    }, {}),
    recipeMapIcons: {},
    offset: 0,
    limit: 160,
    hasMore: false,
  };
}

function request(body: unknown): Request {
  return new Request("http://localhost/api/agent/v1/plans", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${TOKEN}` },
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

const expand = (body: object) => call(expandPost, body, plansExpandInputsResponse);

beforeEach(() => {
  vi.stubEnv("AGENT_API_TOKEN", TOKEN);
  vi.spyOn(console, "info").mockImplementation(() => {});
  vi.spyOn(console, "error").mockImplementation(() => {});
  setDraftClock(() => Date.parse("2026-11-21T12:00:00.000Z"));
  resetDrafts();
  vi.mocked(getDefaultDatasetVersionId).mockResolvedValue(VERSION);
  vi.mocked(getDatasetRecipe).mockImplementation(async (_version, id) => structuredClone(RECIPES[id]));
  vi.mocked(getDatasetResource).mockImplementation(async (_version, kind, id) => {
    const name = nameFor(kind, id);
    return name ? ({ id, kind, displayName: name } as never) : undefined;
  });
  vi.mocked(queryDatasetRecipes).mockImplementation(async (_version, query) => recipePage(query as never) as never);
  vi.mocked(queryDatasetResources).mockImplementation(async (_version, query) => {
    const word = query.query.split(" ")[0]!.toLowerCase();
    const resources = Object.entries(NAMES)
      .filter(([key, name]) => key.startsWith("item:") && name.toLowerCase().includes(word))
      .map(([key, name]) => ({ id: key.slice(5), kind: "item", displayName: name }));
    return { resources, total: resources.length } as never;
  });
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
  setDraftClock();
});

async function ingotDraft(
  constraints: object = { free: ["nitrogen"], tierCap: "MV", banned: ["Mock Banned"] },
  cardTier = "LV",
): Promise<string> {
  const { planId } = await call(createPost, { name: "Titanium", constraints, cardTier }, plansCreateResponse);
  await call(addCardsPost, { planId, cards: [{ recipeId: "r-ingot" }] }, plansAddCardsResponse);
  await call(setTargetsPost, { planId, targets: [{ resource: "ingot", rate: 1 }] }, plansSetTargetsResponse);
  return planId;
}

describe("mergeConstraints", () => {
  it("takes the lower tier cap and heat and joins the lists", () => {
    expect(
      mergeConstraints({ tierCap: "EV", heat: 3000, banned: ["a"], free: ["x"] }, { tierCap: "MV", heat: 4500, banned: ["b"] }),
    ).toEqual({ tierCap: "MV", heat: 3000, banned: ["a", "b"], free: ["x"] });
    expect(mergeConstraints(undefined, { tierCap: "HV" })).toEqual({ tierCap: "HV" });
    expect(mergeConstraints({ steam: false }, { steam: true })).toEqual({ steam: true });
    expect(mergeConstraints({ steam: true }, { tierCap: "EV" })).toEqual({ tierCap: "EV", steam: true });
  });
});

describe("plans/expand-inputs", () => {
  beforeAll(() => useCoreSolver(), CORE_LOAD_MS);

  it("ranks by trying each candidate: fewer and more basic new raws, then EU per item; free inputs are accepted", async () => {
    const planId = await ingotDraft();
    const before = JSON.stringify(getDraft(planId).project);
    const answer = await expand({ planId });
    expect(JSON.stringify(getDraft(planId).project)).toBe(before);
    expect(getDraft(planId).nextCard).toBe(2);

    expect(answer.accepted).toEqual([{ id: "nitrogen", kind: "fluid", name: "Nitrogen", perSecond: 1000 }]);
    expect(answer.more).toBe(false);
    expect(answer.inputs).toHaveLength(1);
    const dust = answer.inputs[0]!;
    expect(dust).toMatchObject({ id: "dust", kind: "item", name: "Titanium Dust", perSecond: 2 });
    // The loop (takes dust), the reverse of the furnace card (grinds the ingot back) and the same reaction as r-ore on
    // another machine are skipped, and so is the recipe only a steam machine runs.
    expect(dust.skipped).toBe(4);
    expect(dust.steamOnly).toBe(1);
    expect(dust.candidates.map((candidate) => candidate.recipeId)).toEqual(["r-slag", "r-ore", "r-ore-hot"]);

    const [slag, ore, hot] = dust.candidates;
    expect(slag!.newRaws).toEqual([]);
    expect(slag!.usesByproducts).toEqual(["Titanium Slag"]);
    expect(slag!.reason).toMatch(/Uses the draft's spare Titanium Slag/);
    expect(slag!.reason).toMatch(/Covers only 50% of the need/);

    expect(ore).toMatchObject({ machine: "Mock Macerator", tier: "LV", machines: 2, euPerItem: 600, euT: 30, seconds: 1 });
    expect(ore!.newRaws).toEqual([{ id: "ore", kind: "item", name: "Titanium Ore", perSecond: 2 }]);
    expect(ore!.reason).toBe("2/s from 2 Mock Macerator at LV, 600 EU per item. Brings in Titanium Ore (ore).");
    expect(hot!.euPerItem).toBe(2400);
  });

  it("puts candidates that bring in more raws, or made ones, after; constraints filter and the call's join the draft's", async () => {
    const planId = await ingotDraft({ free: ["nitrogen"], tierCap: "EV", banned: ["Mock Banned"] });
    const answer = await expand({ planId, constraints: { tierCap: "MV", banned: ["r-slag", "Mock Hot Macerator"] } });
    expect(queryDatasetRecipes).toHaveBeenCalledWith(VERSION, expect.objectContaining({ maxTier: "MV" }));
    const ids = answer.inputs[0]!.candidates.map((candidate) => candidate.recipeId);
    expect(ids).toEqual(["r-ore", "r-ore-water", "r-chem"]);
    const chem = answer.inputs[0]!.candidates[2]!;
    expect(chem.newRaws.map((raw) => raw.name).sort()).toEqual(["Magnesium Ingot", "Titanium Tetrachloride"]);
    expect(chem.reason).toMatch(/\(made\)/);
    expect(chem.reason).toMatch(/Leaves spare Magnesium Chloride Dust\./);
    expect(answer.inputs[0]!.candidates[1]!.reason).toMatch(/Brings in Water \(raw\) and Titanium Ore \(ore\)\./);
  });

  it("offers steam machines when steam is allowed, their steam counted at 2 L per EU", async () => {
    const planId = await ingotDraft();
    const answer = await expand({ planId, constraints: { steam: true } });
    const dust = answer.inputs[0]!;
    expect(dust.steamOnly).toBeUndefined();
    const steam = dust.candidates.find((candidate) => candidate.recipeId === "r-ore-steam")!;
    expect(steam.machine).toBe("Steam Grinder");
    expect(steam.steamPerSecond).toBeGreaterThan(0);
    // 2 dust a second: EU per item = (L/s / 2) / 2.
    expect(steam.euPerItem).toBeCloseTo(steam.steamPerSecond! / 4, 2);
    expect(steam.reason).toMatch(/Burns [\d.]+ L\/s of steam \(counted at 2 L per EU\)\./);
    const electric = dust.candidates.find((candidate) => candidate.recipeId === "r-ore");
    expect(electric?.steamPerSecond).toBeUndefined();
  });

  it("gives candidates the draft's card tier", async () => {
    const planId = await ingotDraft({ free: ["nitrogen"], tierCap: "EV", banned: ["Mock Banned"] }, "HV");
    const answer = await expand({ planId, resources: ["dust"] });
    expect(answer.inputs[0]!.candidates.find((candidate) => candidate.recipeId === "r-ore")!.tier).toBe("HV");
    // The call's lower cap holds the card tier down.
    const capped = await expand({ planId, resources: ["dust"], constraints: { tierCap: "MV" } });
    expect(capped.inputs[0]!.candidates.find((candidate) => candidate.recipeId === "r-ore")!.tier).toBe("MV");
  });

  it("never expands a free resource, and answers a named input the draft does not need with a note", async () => {
    const planId = await ingotDraft();
    const freeDust = await expand({ planId, constraints: { free: ["dust"] } });
    expect(freeDust.inputs).toEqual([]);
    expect(freeDust.accepted.map((entry) => entry.id).sort()).toEqual(["dust", "nitrogen"]);

    const named = await expand({ planId, resources: ["ingot", "dust"], max: 1 });
    expect(named.inputs.map((entry) => entry.id)).toEqual(["ingot", "dust"]);
    expect(named.inputs[0]!.note).toMatch(/does not need it/);
    expect(named.inputs[1]!.candidates).toHaveLength(1);
  });

  it("refuses a draft with no target", async () => {
    const { planId } = await call(createPost, { name: "Empty" }, plansCreateResponse);
    const response = await expandPost(request({ planId }));
    expect(errorResponse.parse(await response.json()).error.code).toBe("bad_request");
  });

  it("caps the work: six trials per input, six inputs per call, the rest by resources; under 12 KB", async () => {
    const { planId } = await call(createPost, { name: "Gadget" }, plansCreateResponse);
    await call(addCardsPost, { planId, cards: [{ recipeId: "r-gadget" }] }, plansAddCardsResponse);
    await call(setTargetsPost, { planId, targets: [{ resource: "gadget", rate: 1 }] }, plansSetTargetsResponse);
    vi.mocked(getDatasetRecipe).mockClear();
    const started = performance.now();
    const response = await expandPost(request({ planId }));
    const text = await response.text();
    console.warn(`expand 8 inputs: ${Math.round(performance.now() - started)} ms, ${Buffer.byteLength(text)} bytes`);
    const answer = plansExpandInputsResponse.parse(JSON.parse(text));
    expect(Buffer.byteLength(text)).toBeLessThanOrEqual(RESPONSE_AIM_BYTES);
    expect(answer.inputs.length).toBeLessThanOrEqual(INPUTS_PER_CALL);
    expect(answer.more).toBe(true);
    expect(answer.inputs.length + answer.remaining!.length).toBe(8);
    const loaded = vi.mocked(getDatasetRecipe).mock.calls.filter(([, id]) => id.startsWith("r-p0-"));
    expect(loaded).toHaveLength(TRIALS_PER_INPUT);
    const first = answer.inputs[0]!;
    expect(first.found).toBe(10);
    // All bring in the same ore; the cheapest lathe first.
    expect(first.candidates.map((candidate) => candidate.machine)).toEqual(["Mock Lathe 0", "Mock Lathe 1", "Mock Lathe 2"]);

    const rest = await expand({ planId, resources: answer.remaining!.map((entry) => entry.id) });
    expect(rest.inputs.map((entry) => entry.id)).toEqual(answer.remaining!.map((entry) => entry.id));
    expect(rest.more).toBe(false);
  });
});
