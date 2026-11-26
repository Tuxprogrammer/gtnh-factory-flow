import { describe, expect, it } from "vitest";
import { createNewProject } from "@/examples";
import type { FactoryNode, FactoryPocket, FactoryProject, FactoryStorage } from "@/lib/model/types";
import { cardsDeleteTakes, deleteAsks, deleteQuestion } from "./delete-ask";

function card(id: string, pocketId?: string): FactoryNode {
  return {
    id,
    recipeId: "r",
    machineCount: 1,
    parallel: 1,
    overclockTier: "LV",
    enabled: true,
    position: { x: 0, y: 0 },
    ...(pocketId ? { pocketId } : {}),
  };
}

function plan(): FactoryProject {
  return {
    ...createNewProject(),
    nodes: [card("a"), card("b"), card("c", "board"), card("d", "inner")],
    storages: [{ id: "drawer", kind: "item", resourceId: "x" } as FactoryStorage],
    pockets: [
      { id: "board", name: "Board" } as FactoryPocket,
      { id: "inner", name: "Inner", parentPocketId: "board" } as FactoryPocket,
    ],
  };
}

describe("a delete asks first when it takes more than one card", () => {
  it("counts cards only: one card or a drawer goes at once", () => {
    expect(cardsDeleteTakes(plan(), ["a"])).toBe(1);
    expect(cardsDeleteTakes(plan(), ["a", "drawer"])).toBe(1);
    expect(cardsDeleteTakes(plan(), ["drawer"])).toBe(0);
    expect(deleteAsks(1)).toBe(false);
    expect(cardsDeleteTakes(plan(), ["a", "b"])).toBe(2);
    expect(deleteAsks(2)).toBe(true);
    expect(deleteQuestion(2)).toBe("Delete 2 cards?");
  });

  it("counts the cards a board takes with it, boards inside it too", () => {
    expect(cardsDeleteTakes(plan(), ["inner"])).toBe(1);
    expect(cardsDeleteTakes(plan(), ["board"])).toBe(2);
  });
});
