/**
 * A plan the game made, sent to `plans/import` with its cards' recipes as content only (the game cannot know the
 * dataset's recipe ids), against the pack dataset: the cards come back as the dataset's recipes, solve, take a new
 * card's wire, and the proposal names them by the game's own ids. Runs wherever a dataset is installed
 * (public/datasets/gtnh); without one it is skipped.
 */
import fs from "node:fs";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import type { z } from "zod";
import { POST as addCardsPost } from "@/app/api/agent/v1/plans/add-cards/route";
import { POST as diffPost } from "@/app/api/agent/v1/plans/diff/route";
import { POST as getPost } from "@/app/api/agent/v1/plans/get/route";
import { POST as importPost } from "@/app/api/agent/v1/plans/import/route";
import { POST as solvePost } from "@/app/api/agent/v1/plans/solve/route";
import { POST as searchPost } from "@/app/api/agent/v1/recipes/search/route";
import { GET as readGet } from "@/app/api/deliveries/[id]/route";
import type { Recipe } from "@/lib/model/types";
import { getDatasetRecipe, getDefaultDatasetVersionId } from "@/lib/server/dataset-query";
import { useInProcessLocalDb } from "@/lib/server/local-db/client";
import { openLocalDatabase, type LocalDatabase } from "@/lib/server/local-db/database";
import { useCoreSolver } from "@/test-support/core-solve";
import { deliveryReadResponse, plansDiffResponse, plansImportResponse } from "./delivery-schemas";
import { plansAddCardsResponse, plansGetResponse, plansSolveResponse } from "./plan-schemas";
import { recipesSearchResponse } from "./schemas";
import type { SlimRecipeContent, SlimSnapshot } from "./slim";

const HAS_DATASET = fs.existsSync(path.resolve("public/datasets/gtnh/datasets.manifest.json"));
const TOKEN = "test-token-0123456789";
const TITANIUM_INGOT = "gregtech:gt.metaitem.01@11028";
const TITANIUM_PLATE = "gregtech:gt.metaitem.01@17028";

/** A recipe as the game's panel describes it: what it does, no dataset id. */
function contentOf(recipe: Recipe): SlimRecipeContent {
  return {
    name: recipe.name,
    machineType: recipe.machineType,
    ...(recipe.source?.recipeMap ? { recipeMap: recipe.source.recipeMap } : {}),
    inputs: recipe.inputs.map((input) => ({
      kind: input.kind,
      id: input.id,
      amount: input.amount,
      ...(input.consumed === false ? { consumed: false as const } : {}),
    })),
    outputs: recipe.outputs.map((output) => ({ kind: output.kind, id: output.id, amount: output.amount })),
    durationTicks: recipe.durationTicks,
    eut: recipe.eut,
  };
}

async function call<S extends z.ZodType>(post: (request: Request) => Promise<Response>, body: unknown, schema: S) {
  const response = await post(
    new Request("http://localhost/api/agent/v1/x", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${TOKEN}` },
      body: JSON.stringify(body),
    }),
  );
  const json = await response.json();
  expect(response.status, JSON.stringify(json)).toBe(200);
  return schema.parse(json) as z.infer<S>;
}

async function firstRecipe(makes: string, machine: RegExp): Promise<Recipe> {
  const found = await call(searchPost, { makes: { id: makes, kind: "item" }, limit: 25 }, recipesSearchResponse);
  const pick = found.results.find((entry) => machine.test(entry.machine));
  expect(pick, JSON.stringify(found.results.map((entry) => entry.machine))).toBeDefined();
  return (await getDatasetRecipe((await getDefaultDatasetVersionId())!, pick!.recipeId))!;
}

describe.skipIf(!HAS_DATASET)("a game plan's cards found again by their content", () => {
  let local: LocalDatabase;
  beforeAll(async () => {
    vi.stubEnv("LOCAL_DB", "1");
    vi.stubEnv("AGENT_API_TOKEN", TOKEN);
    local = await openLocalDatabase();
    useInProcessLocalDb(local.handle);
  });
  afterAll(async () => {
    useInProcessLocalDb(undefined);
    await local.close();
    vi.unstubAllEnvs();
  });

  it("imports, solves, takes a new card's wire and proposes it to the game's card ids", { timeout: 120_000 }, async () => {
    await useCoreSolver();
    vi.spyOn(console, "info").mockImplementation(() => {});
    const bender = await firstRecipe(TITANIUM_PLATE, /bend/i);
    const plateCard = "3f0c1d2e-0000-4000-8000-00000000a001";
    const snapshot: SlimSnapshot = {
      version: 1,
      plan: { id: "game-plan-titanium", name: "Titanium plates" },
      cards: [
        {
          id: plateCard,
          recipeId: `gtnhplanner:${plateCard}`,
          tier: "LV",
          recipe: contentOf(bender),
          position: { x: 0, y: 0 },
        },
      ],
      drawers: [],
      wires: [],
      targets: [{ card: plateCard, port: `output:item:${encodeURIComponent(TITANIUM_PLATE)}`, rate: 1 }],
    };
    const imported = await call(importPost, { snapshot }, plansImportResponse);
    expect(imported.warnings).toEqual([]);
    const got = await call(getPost, { planId: imported.planId }, plansGetResponse);
    expect(got.cards).toEqual([expect.objectContaining({ cardId: plateCard, recipeId: bender.id })]);

    const solved = await call(solvePost, { planId: imported.planId }, plansSolveResponse);
    expect(solved.targetsMet).toBe(true);
    expect(solved.cards?.map((line) => line[0])).toContain(plateCard);

    // A card that makes titanium ingots feeds the game's card.
    const ingots = await call(searchPost, { makes: { id: TITANIUM_INGOT, kind: "item" }, limit: 5 }, recipesSearchResponse);
    const added = await call(
      addCardsPost,
      { planId: imported.planId, cards: [{ recipeId: ingots.results[0]!.recipeId }] },
      plansAddCardsResponse,
    );
    const newCard = added.cards[0]!.cardId;
    expect(added.wires).toEqual([expect.objectContaining({ from: newCard, to: plateCard, id: TITANIUM_INGOT })]);

    const diff = await call(diffPost, { planId: imported.planId }, plansDiffResponse);
    expect(diff.basePlanId).toBe("game-plan-titanium");
    const response = await readGet(new Request(`http://localhost/api/deliveries/${diff.proposalId}`), {
      params: Promise.resolve({ id: diff.proposalId }),
    });
    const body = deliveryReadResponse.parse(await response.json());
    if (body.kind !== "proposal") throw new Error("not a proposal");
    expect(body.proposal.cards.map((card) => card.id)).toEqual([newCard]);
    expect(body.proposal.wires).toEqual([
      expect.objectContaining({
        source: newCard,
        target: plateCard,
        resourceId: TITANIUM_INGOT,
        targetHandle: expect.stringMatching(/^input:item:/),
      }),
    ]);
  });
});
