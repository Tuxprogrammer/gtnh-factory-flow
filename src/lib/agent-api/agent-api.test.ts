import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  getDatasetRecipe,
  getDatasetResource,
  getDefaultDatasetVersionId,
  queryDatasetRecipes,
  queryDatasetResources,
} from "@/lib/server/dataset-query";
import { POST as getRecipePost, GET as getRecipeGet } from "@/app/api/agent/v1/recipes/get/route";
import { POST as searchRecipesPost } from "@/app/api/agent/v1/recipes/search/route";
import { POST as searchResourcesPost } from "@/app/api/agent/v1/resources/search/route";
import { materialOf, materialPattern, recycles } from "./recipes";
import { auditLine, MAX_REQUEST_BYTES } from "./route";
import { fitList, jsonBytes } from "./size";
import {
  errorResponse,
  recipesGetResponse,
  recipesSearchResponse,
  resourcesSearchResponse,
} from "./schemas";

vi.mock("@/lib/server/dataset-query", () => ({
  getDefaultDatasetVersionId: vi.fn(),
  getDatasetResource: vi.fn(),
  getDatasetRecipe: vi.fn(),
  queryDatasetResources: vi.fn(),
  queryDatasetRecipes: vi.fn(),
}));

const TOKEN = "test-token-0123456789";
const VERSION = "stable-test";

const RESOURCES: Record<string, { id: string; kind: "item" | "fluid"; displayName: string; recipeCount: number }> = {
  "item:dust_titanium": { id: "dust_titanium", kind: "item", displayName: "Titanium Dust", recipeCount: 3 },
  "item:dust_rutile": { id: "dust_rutile", kind: "item", displayName: "Rutile Dust", recipeCount: 2 },
  "fluid:water": { id: "water", kind: "fluid", displayName: "Water", recipeCount: 9 },
};

function handler(id: string, label: string, machineType: string, kind: "single" | "multiblock" = "single") {
  return { id, label, machineType, kind };
}

function recipe(
  id: string,
  options: {
    name?: string;
    tier?: string;
    outputs?: { id: string; chance?: number }[];
    inputs?: { id: string; consumed?: boolean; kind?: "item" | "fluid" }[];
    machineType?: string;
    handlers?: ReturnType<typeof handler>[];
    specialValue?: number;
  } = {},
) {
  const machineType = options.machineType ?? "Electrolyzer";
  return {
    id,
    name: options.name ?? id,
    recipeMap: machineType,
    machineType,
    minimumTier: options.tier ?? "LV",
    durationTicks: 200,
    eut: 30,
    specialValue: options.specialValue,
    machineHandlers: options.handlers ?? [handler(`h-${machineType}`, machineType, machineType)],
    inputs: (options.inputs ?? [{ id: "dust_rutile" }]).map((input) => ({
      kind: input.kind ?? "item",
      id: input.id,
      amount: 1,
      displayName: RESOURCES[`${input.kind ?? "item"}:${input.id}`]?.displayName,
      ...(input.consumed === false ? { consumed: false } : {}),
    })),
    outputs: (options.outputs ?? [{ id: "dust_titanium" }]).map((output) => ({
      kind: "item" as const,
      id: output.id,
      amount: 1,
      displayName: RESOURCES[`item:${output.id}`]?.displayName,
      ...(output.chance !== undefined ? { chance: output.chance } : {}),
    })),
    slots: [],
  };
}

function searchPage(recipes: ReturnType<typeof recipe>[], total = recipes.length) {
  return {
    recipes,
    total,
    recipeMaps: [...new Set(recipes.map((entry) => entry.recipeMap))],
    recipeMapCounts: recipes.reduce<Record<string, number>>((counts, entry) => {
      counts[entry.recipeMap] = (counts[entry.recipeMap] ?? 0) + 1;
      return counts;
    }, {}),
    recipeMapIcons: {},
    offset: 0,
    limit: 160,
    hasMore: false,
  };
}

function call(
  post: (request: Request) => Promise<Response>,
  body: unknown,
  token: string | null = TOKEN,
): Promise<Response> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (token !== null) {
    headers.Authorization = `Bearer ${token}`;
  }
  return post(
    new Request("http://localhost/api/agent/v1/x", {
      method: "POST",
      headers,
      body: typeof body === "string" ? body : JSON.stringify(body),
    }),
  );
}

async function errorCode(response: Response): Promise<string> {
  const body = errorResponse.parse(await response.json());
  return body.error.code;
}

let logs: string[];

beforeEach(() => {
  vi.stubEnv("AGENT_API_TOKEN", TOKEN);
  logs = [];
  vi.spyOn(console, "info").mockImplementation((line: string) => {
    logs.push(String(line));
  });
  vi.mocked(getDefaultDatasetVersionId).mockResolvedValue(VERSION);
  vi.mocked(getDatasetResource).mockImplementation(async (_version, kind, id) => {
    return RESOURCES[`${kind}:${id}`] as never;
  });
  vi.mocked(getDatasetRecipe).mockResolvedValue(undefined);
  vi.mocked(queryDatasetRecipes).mockResolvedValue(searchPage([]) as never);
  vi.mocked(queryDatasetResources).mockResolvedValue({
    resources: [RESOURCES["item:dust_titanium"]],
    total: 1,
  } as never);
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe("agent API auth", () => {
  it("answers a bare 404 on every route when no token is configured", async () => {
    vi.stubEnv("AGENT_API_TOKEN", "");
    for (const post of [searchResourcesPost, searchRecipesPost, getRecipePost]) {
      const response = await call(post, { text: "titanium" });
      expect(response.status).toBe(404);
      expect(await response.text()).toBe("Not Found");
    }
    expect((await getRecipeGet(new Request("http://localhost/x"))).status).toBe(404);
    expect(queryDatasetResources).not.toHaveBeenCalled();
  });

  it("answers 401 unauthorized on a missing or wrong token", async () => {
    const missing = await call(searchResourcesPost, { text: "titanium" }, null);
    expect(missing.status).toBe(401);
    expect(await errorCode(missing)).toBe("unauthorized");
    const wrong = await call(searchResourcesPost, { text: "titanium" }, "nope");
    expect(wrong.status).toBe(401);
    const prefix = await call(searchResourcesPost, { text: "titanium" }, TOKEN.slice(0, -1));
    expect(prefix.status).toBe(401);
    expect(queryDatasetResources).not.toHaveBeenCalled();
  });

  it("answers with the right token, and other methods are refused after auth", async () => {
    const response = await call(searchResourcesPost, { text: "titanium" });
    expect(response.status).toBe(200);
    expect((await getRecipeGet(new Request("http://localhost/x"))).status).toBe(401);
    const get = await getRecipeGet(
      new Request("http://localhost/x", { headers: { Authorization: `Bearer ${TOKEN}` } }),
    );
    expect(get.status).toBe(400);
    expect(await errorCode(get)).toBe("bad_request");
  });

  it("logs route and player name, never the token or the body", async () => {
    await call(searchResourcesPost, { text: "secret words", player: { name: "Steve\n[x]", uuid: "u-1" } });
    expect(logs).toHaveLength(1);
    expect(logs[0]).toMatch(/^\[agent-api\] route=resources\/search player=Stevex outcome=200 ms=\d+ bytes=\d+$/);
    expect(logs[0]).not.toContain(TOKEN);
    expect(logs[0]).not.toContain("secret");
    expect(auditLine("r", undefined, "bad_request", 1.4)).toBe("[agent-api] route=r player=- outcome=bad_request ms=1");
  });
});

describe("agent API requests", () => {
  it("refuses bad JSON, unknown fields, missing fields and oversized bodies", async () => {
    expect(await errorCode(await call(searchResourcesPost, "{not json"))).toBe("bad_request");
    expect(await errorCode(await call(searchResourcesPost, { text: "a", colour: 1 }))).toBe("bad_request");
    expect(await errorCode(await call(searchResourcesPost, {}))).toBe("bad_request");
    expect(await errorCode(await call(searchResourcesPost, { text: "a", limit: 26 }))).toBe("bad_request");
    expect(await errorCode(await call(searchRecipesPost, { limit: 3 }))).toBe("bad_request");
    expect(
      await errorCode(await call(searchRecipesPost, { text: "a", constraints: { tierCap: "XV" } })),
    ).toBe("bad_request");
    const big = await call(searchResourcesPost, { text: "a", pad: "x".repeat(MAX_REQUEST_BYTES) });
    expect(big.status).toBe(400);
    expect((await errorResponse.parse(await big.json())).error.message).toMatch(/over 64 KB/);
  });

  it("answers unknown_resource, unknown_recipe and dataset_unavailable with their statuses", async () => {
    const resource = await call(searchRecipesPost, { makes: "no_such_thing" });
    expect(resource.status).toBe(404);
    expect(await errorCode(resource)).toBe("unknown_resource");

    const missing = await call(getRecipePost, { recipeId: "nope" });
    expect(missing.status).toBe(404);
    expect(await errorCode(missing)).toBe("unknown_recipe");

    vi.mocked(getDefaultDatasetVersionId).mockResolvedValue(undefined);
    const none = await call(searchResourcesPost, { text: "titanium" });
    expect(none.status).toBe(503);
    expect(await errorCode(none)).toBe("dataset_unavailable");

    vi.mocked(getDefaultDatasetVersionId).mockResolvedValue(VERSION);
    vi.mocked(queryDatasetResources).mockRejectedValue(Object.assign(new Error("gone"), { code: "ENOENT" }));
    expect(await errorCode(await call(searchResourcesPost, { text: "titanium" }))).toBe("dataset_unavailable");
  });

  it("answers internal, without the error's text, when endpoint code throws", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    vi.mocked(queryDatasetResources).mockRejectedValue(new Error("private detail"));
    const response = await call(searchResourcesPost, { text: "titanium" });
    expect(response.status).toBe(500);
    const body = errorResponse.parse(await response.json());
    expect(body.error.code).toBe("internal");
    expect(body.error.message).not.toContain("private");
  });
});

describe("resources/search", () => {
  it("returns ids with names and passes kind and limit", async () => {
    const response = await call(searchResourcesPost, { text: "titanium", kind: "item", limit: 3 });
    const body = resourcesSearchResponse.parse(await response.json());
    expect(body).toEqual({
      dataset: VERSION,
      results: [{ id: "dust_titanium", kind: "item", name: "Titanium Dust" }],
      total: 1,
    });
    expect(queryDatasetResources).toHaveBeenCalledWith(VERSION, {
      query: "titanium",
      offset: 0,
      limit: 3,
      kind: "item",
    });
  });
});

describe("recipes/search", () => {
  it("puts the main product first, then byproducts, and resolves a bare id", async () => {
    vi.mocked(queryDatasetRecipes).mockResolvedValue(
      searchPage([
        recipe("byproduct", { outputs: [{ id: "dust_rutile" }, { id: "dust_titanium" }] }),
        recipe("chance", { outputs: [{ id: "dust_titanium", chance: 0.5 }] }),
        recipe("main-hv", { tier: "HV" }),
        recipe("main-lv", { tier: "LV" }),
      ]) as never,
    );
    const response = await call(searchRecipesPost, { makes: "dust_titanium" });
    const body = recipesSearchResponse.parse(await response.json());
    expect(body.results.map((entry) => entry.recipeId)).toEqual(["main-lv", "main-hv", "chance", "byproduct"]);
    expect(body.results[2]!.outputs[0]).toEqual({
      id: "dust_titanium",
      kind: "item",
      name: "Titanium Dust",
      amount: 1,
      chance: 0.5,
    });
    expect(body.results[0]).toMatchObject({ machine: "Electrolyzer", minTier: "LV", euT: 30, seconds: 10 });
    expect(body.results[0]!.machines).toBeUndefined();
    expect(queryDatasetRecipes).toHaveBeenCalledWith(
      VERSION,
      expect.objectContaining({
        clauses: [{ role: "makes", kind: "item", id: "dust_titanium" }],
        allMaps: true,
        maxTier: "all",
      }),
    );
  });

  it("puts recipes that produce the material before ones that recycle it from another form", async () => {
    vi.mocked(queryDatasetRecipes).mockResolvedValue(
      searchPage([
        { ...recipe("macerate-ingot"), inputs: [{ kind: "item", id: "ingot_titanium", amount: 1, displayName: "Titanium Ingot" }] },
        { ...recipe("tetrachloride", { tier: "HV" }), inputs: [{ kind: "item", id: "ticl4", amount: 1, displayName: "Titaniumtetrachloride Cell" }] },
      ] as never) as never,
    );
    const body = recipesSearchResponse.parse(await (await call(searchRecipesPost, { makes: "dust_titanium" })).json());
    expect(body.results.map((entry) => entry.recipeId)).toEqual(["tetrachloride", "macerate-ingot"]);
    expect(materialOf("Small Pile of Titanium Dust")).toBe("titanium");
    expect(materialOf("Molten Titanium")).toBe("titanium");
    expect(materialOf("Water")).toBeUndefined();

    const titanium = { id: "dust_titanium", kind: "item" as const, name: "Titanium Dust" };
    const pattern = materialPattern(materialOf(titanium.name));
    const named = (machineType: string, ...names: string[]) => ({
      ...recipe("x", { machineType }),
      inputs: names.map((displayName, index) => ({ kind: "item" as const, id: `i${index}`, amount: 1, displayName })),
    }) as never;
    expect(recycles(named("Macerator", "Missile Body T2"), pattern, titanium)).toBe(true);
    expect(recycles(named("Replicator", "UU-Matter"), pattern, titanium)).toBe(true);
    expect(recycles(named("Crafting", "Ore Dictionary: dustSmallTitanium", "Ore Dictionary: craftingToolHardHammer"), pattern, titanium)).toBe(true);
    expect(recycles(named("Forge Hammer", "Ore Dictionary: crushedCentrifugedTitanium"), pattern, titanium)).toBe(false);
    expect(recycles(named("Centrifuge", "Impure Pile of Titanium Dust"), pattern, titanium)).toBe(false);
    expect(recycles(named("Macerator", "Rutile Ore"), pattern, titanium)).toBe(false);
    expect(recycles(named("Centrifuge", "TPV-Alloy Dust"), pattern, titanium)).toBe(false);
  });

  it("puts recipes that consume a taken resource before ones that only hold it", async () => {
    vi.mocked(queryDatasetRecipes).mockResolvedValue(
      searchPage([
        recipe("holds", { inputs: [{ id: "dust_rutile", consumed: false }] }),
        recipe("eats", { tier: "MV" }),
      ]) as never,
    );
    const body = recipesSearchResponse.parse(
      await (await call(searchRecipesPost, { takes: { id: "dust_rutile", kind: "item" } })).json(),
    );
    expect(body.results.map((entry) => entry.recipeId)).toEqual(["eats", "holds"]);
    expect(body.results[1]!.inputs[0]!.consumed).toBe(false);
  });

  it("filters by heat and banned machines, passes tierCap, and reports what it does not enforce", async () => {
    vi.mocked(queryDatasetRecipes).mockResolvedValue(
      searchPage([
        recipe("hot", {
          machineType: "Blast Furnace",
          specialValue: 5000,
          handlers: [handler("ebf", "Electric Blast Furnace", "Blast Furnace", "multiblock")],
        }),
        recipe("warm", {
          machineType: "Blast Furnace",
          specialValue: 1500,
          handlers: [handler("ebf", "Electric Blast Furnace", "Blast Furnace", "multiblock")],
        }),
        recipe("two-machines", {
          handlers: [handler("a", "Electrolyzer", "Electrolyzer"), handler("b", "Industrial Electrolyzer", "x", "multiblock")],
        }),
        recipe("banned-map", { machineType: "Centrifuge" }),
      ]) as never,
    );
    const body = recipesSearchResponse.parse(
      await (
        await call(searchRecipesPost, {
          makes: "dust_titanium",
          constraints: { tierCap: "HV", heat: 2000, banned: ["A", "centrifuge"], cleanroom: true, free: ["water"] },
        })
      ).json(),
    );
    expect(body.results.map((entry) => [entry.recipeId, entry.machine, entry.heat])).toEqual([
      ["warm", "Electric Blast Furnace", 1500],
      ["two-machines", "Industrial Electrolyzer", undefined],
    ]);
    expect(body.notEnforced).toEqual(["cleanroom", "free"]);
    expect(queryDatasetRecipes).toHaveBeenCalledWith(VERSION, expect.objectContaining({ maxTier: "HV" }));
  });

  it("keeps to the limit, pages with offset and adds machines in detail form", async () => {
    vi.mocked(queryDatasetRecipes).mockResolvedValue(
      searchPage(Array.from({ length: 12 }, (_, index) => recipe(`r${String(index).padStart(2, "0")}`))) as never,
    );
    const first = recipesSearchResponse.parse(await (await call(searchRecipesPost, { makes: "dust_titanium" })).json());
    expect(first.results).toHaveLength(8);
    expect(first.more).toBe(true);
    const second = recipesSearchResponse.parse(
      await (await call(searchRecipesPost, { makes: "dust_titanium", offset: 8, detail: true })).json(),
    );
    expect(second.results.map((entry) => entry.recipeId)).toEqual(["r08", "r09", "r10", "r11"]);
    expect(second.more).toBe(false);
    expect(second.results[0]!.machines).toEqual([{ id: "h-Electrolyzer", name: "Electrolyzer", kind: "single" }]);
    expect(second.results[0]!.map).toBe("Electrolyzer");
  });

  it("asks map by map when a resource search has more matches than one window", async () => {
    vi.mocked(queryDatasetRecipes).mockImplementation(async (_version, request) => {
      if (request.allMaps) {
        throw new Error("a large search asks map by map only");
      }
      const recipeMap = request.recipeMap ?? "A";
      return {
        ...searchPage([recipe(`best-of-${recipeMap}`, { machineType: recipeMap })]),
        recipeMaps: ["A", "B"],
        recipeMapCounts: { A: 400, B: 100 },
      } as never;
    });
    const body = recipesSearchResponse.parse(await (await call(searchRecipesPost, { makes: "dust_titanium" })).json());
    expect(body.results.map((entry) => entry.recipeId)).toEqual(["best-of-A", "best-of-B"]);
    expect(body.total).toBe(500);
    expect(body.more).toBe(true);
  });
});

describe("recipes/get", () => {
  it("returns the full form with each machine and its numbers", async () => {
    vi.mocked(getDatasetRecipe).mockResolvedValue(
      recipe("r1", {
        handlers: [handler("a", "Electrolyzer", "Electrolyzer"), handler("b", "Industrial Electrolyzer", "Electrolyzer", "multiblock")],
      }) as never,
    );
    const response = await call(getRecipePost, { recipeId: "r1" });
    expect(response.status).toBe(200);
    const body = recipesGetResponse.parse(await response.json());
    expect(body.recipe).toMatchObject({ recipeId: "r1", map: "Electrolyzer", minTier: "LV", euT: 30, seconds: 10 });
    expect(body.recipe.machines.map((machine) => [machine.id, machine.name, machine.kind])).toEqual([
      ["a", "Electrolyzer", "single"],
      ["b", "Industrial Electrolyzer", "multiblock"],
    ]);
    expect(getDatasetRecipe).toHaveBeenCalledWith(VERSION, "r1");
  });
});

describe("response size", () => {
  it("cuts a list from its end to fit and says so", () => {
    const items = Array.from({ length: 50 }, (_, index) => ({ index, pad: "x".repeat(100) }));
    const whole = fitList(items, (kept, truncated) => ({ kept, truncated }), 100_000);
    expect(whole.kept).toHaveLength(50);
    expect(whole.truncated).toBe(false);
    const cut = fitList(items, (kept, truncated) => ({ kept, truncated }), 1_000);
    expect(cut.truncated).toBe(true);
    expect(jsonBytes(cut)).toBeLessThanOrEqual(1_000);
    expect(jsonBytes({ kept: items.slice(0, cut.kept.length + 1), truncated: true })).toBeGreaterThan(1_000);
    expect(cut.kept.at(-1)!.index).toBe(cut.kept.length - 1);
    const one = fitList(items.slice(0, 1), (kept, truncated) => ({ kept, truncated }), 10);
    expect(one.kept).toHaveLength(1);
  });
});
