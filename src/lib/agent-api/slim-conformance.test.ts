import fs from "node:fs";
import path from "node:path";
import { describe, expect, it, vi } from "vitest";
import { POST as importPost } from "@/app/api/agent/v1/plans/import/route";
import { listConformance, readConformanceJson } from "@/test-support/conformance";
import { plansImportRequest, plansImportResponse } from "./delivery-schemas";
import { getDraft } from "./drafts";
import { checkSnapshot } from "./handoff";
import { THING_ID_PATTERN, type SlimSnapshot } from "./slim";

/**
 * The game's Ask Dave panel sends the open plan as a slim snapshot. conformance/slim/mod holds what the mod's own code
 * makes of its demo plans (SlimSnapshotTest in jvm/mod); each must pass the validation `plans/import` runs on a request
 * before it reads the dataset, so the two sides cannot drift.
 */
describe("the mod's slim snapshots", () => {
  const files = listConformance("slim", "mod").filter((name) => name.endsWith(".json"));

  it("are there", () => {
    expect(files.length).toBeGreaterThanOrEqual(3);
  });

  for (const file of files) {
    it(`${file} is a plans/import request the site accepts`, () => {
      const parsed = plansImportRequest.safeParse({ snapshot: readConformanceJson("slim", "mod", file) });
      expect(parsed.success ? "" : JSON.stringify(parsed.error.issues.slice(0, 5))).toBe("");
      if (!parsed.success) return;
      expect(() => checkSnapshot(parsed.data.snapshot)).not.toThrow();
      const snapshot = parsed.data.snapshot;
      expect(snapshot.cards.length).toBeGreaterThan(0);
      expect(snapshot.wires.length).toBeGreaterThan(0);
      // The game names cards and drawers by its board ids, which a proposal's wires name back.
      for (const thing of [...snapshot.cards, ...snapshot.drawers]) expect(thing.id).toMatch(THING_ID_PATTERN);
      // Within the bridge's attachment limit with room to spare.
      expect(JSON.stringify(snapshot).length).toBeLessThan(256 * 1024);
    });
  }
});

const HAS_DATASET = fs.existsSync(path.resolve("public/datasets/gtnh/datasets.manifest.json"));
const TOKEN = "test-token-0123456789";

/**
 * Game cards the pack dataset has no recipe for: the biodiesel demo's recipes are the demo's own (a fish catcher, demo
 * items and fluids), not the pack's. Its demo methane source (card-6) makes methane from nothing on no recipe map, which
 * an Underground Fluid recipe also does, so it is matched to that.
 */
const NOT_IN_PACK: Record<string, string[]> = {
  "biodiesel-demo.json": ["card-1", "card-2", "card-3", "card-4", "card-5", "card-7"],
};

type Slot = { kind: string; id: string; amount: number; consumed?: boolean };
/** Each used resource's total: the dataset lists an output of 80 as stacks of 64 and 16, the game's card as one. */
const used = (slots: Slot[]) => {
  const totals = new Map<string, number>();
  for (const slot of slots.filter((entry) => entry.consumed !== false)) {
    totals.set(`${slot.kind}:${slot.id}`, (totals.get(`${slot.kind}:${slot.id}`) ?? 0) + slot.amount);
  }
  return [...totals].map(([key, amount]) => `${key}:${amount}`).sort();
};

/**
 * On the pack dataset, every game card in the fixtures is found again by the content the mod sends with it, so the
 * assistant can read and solve the player's own cards. The fixtures' recipes are the demo plans' (the pack's own
 * speed); the instance's dataset carries the server's recipe speed, so their times differ and nothing else does.
 */
describe.skipIf(!HAS_DATASET)("the mod's game cards on the pack dataset", () => {
  const files = listConformance("slim", "mod").filter((name) => name.endsWith(".json"));
  for (const file of files) {
    it(`${file}: each game card the pack has resolves to its dataset recipe`, { timeout: 120_000 }, async () => {
      vi.stubEnv("AGENT_API_TOKEN", TOKEN);
      vi.spyOn(console, "info").mockImplementation(() => {});
      const snapshot = readConformanceJson("slim", "mod", file) as SlimSnapshot;
      const response = await importPost(
        new Request("http://localhost/x", {
          method: "POST",
          headers: { "Content-Type": "application/json", Authorization: `Bearer ${TOKEN}` },
          body: JSON.stringify({ snapshot }),
        }),
      );
      const imported = plansImportResponse.parse(await response.json());
      const draft = getDraft(imported.planId);
      const unresolved = draft.project.nodes
        .filter((node) =>
          [node.recipeId, ...(node.extraRecipes ?? []).map((section) => section.recipeId)].some((id) =>
            id.startsWith("gtnhplanner:"),
          ),
        )
        .map((node) => node.id);
      expect(unresolved).toEqual(NOT_IN_PACK[file] ?? []);
      for (const card of snapshot.cards) {
        if (unresolved.includes(card.id) || file === "biodiesel-demo.json") continue;
        const node = draft.project.nodes.find((entry) => entry.id === card.id)!;
        const pairs = [
          [card.recipe, node.recipeId],
          ...(card.sectionRecipes ?? []).map((content, k) => [content, node.extraRecipes![k]!.recipeId] as const),
        ] as const;
        for (const [content, recipeId] of pairs) {
          const recipe = draft.project.recipes.find((entry) => entry.id === recipeId)!;
          expect({ card: card.id, inputs: used(content!.inputs), outputs: used(content!.outputs), eut: content!.eut }).toEqual(
            { card: card.id, inputs: used(recipe.inputs), outputs: used(recipe.outputs), eut: recipe.eut },
          );
        }
      }
      vi.unstubAllEnvs();
    });
  }
});
