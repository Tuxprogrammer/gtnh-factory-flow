import { estimateRecipeCardHeight } from "@/components/flow/virtual/card-shell";
import { BOARD_GRID, RECIPE_NODE_WIDTH, snapPositionToGrid } from "@/lib/board-grid";
import type { FactoryProject } from "@/lib/model/types";

/**
 * Card positions for a draft: a layered layout, flow left to right. A card's column is the longest chain of wires
 * feeding it (wires that close a loop are not counted), cards in a column stack top to bottom in card order, each at
 * the height it is first drawn (`estimateRecipeCardHeight`), on the board grid.
 *
 * The board's own Arrange (`arrangeBoard` in src/lib/board-arrange.ts) is pure and runs on the server, but it takes
 * about 0.3 s for 10 cards, 2.3 s for 30 and 11.6 s for 60 on the build box, too slow to run after every call; this
 * runs in well under a millisecond. The player can press Arrange on the delivered plan.
 */

const COLUMN_GAP = BOARD_GRID * 8;
const ROW_GAP = BOARD_GRID * 2;
const ORIGIN = { x: BOARD_GRID * 5, y: BOARD_GRID * 5 };

function columns(project: FactoryProject): Map<string, number> {
  const ids = project.nodes.map((node) => node.id);
  const feeders = new Map<string, string[]>(ids.map((id) => [id, []]));
  for (const edge of project.edges) {
    if (edge.source !== edge.target && feeders.has(edge.source) && feeders.has(edge.target)) {
      feeders.get(edge.target)!.push(edge.source);
    }
  }
  const column = new Map<string, number>();
  const visiting = new Set<string>();
  const depth = (id: string): number => {
    const known = column.get(id);
    if (known !== undefined) {
      return known;
    }
    visiting.add(id);
    let best = 0;
    for (const feeder of feeders.get(id) ?? []) {
      if (!visiting.has(feeder)) {
        best = Math.max(best, depth(feeder) + 1);
      }
    }
    visiting.delete(id);
    column.set(id, best);
    return best;
  };
  for (const id of ids) {
    depth(id);
  }
  return column;
}

export function arrangeDraft(project: FactoryProject): FactoryProject {
  const recipes = new Map(project.recipes.map((recipe) => [recipe.id, recipe]));
  const column = columns(project);
  const nextY = new Map<number, number>();
  return {
    ...project,
    nodes: project.nodes.map((node) => {
      const at = column.get(node.id) ?? 0;
      const recipe = recipes.get(node.recipeId);
      const height = recipe ? estimateRecipeCardHeight(recipe, recipe.eut > 0) : BOARD_GRID * 8;
      const y = nextY.get(at) ?? ORIGIN.y;
      nextY.set(at, y + height + ROW_GAP);
      return { ...node, position: snapPositionToGrid({ x: ORIGIN.x + at * (RECIPE_NODE_WIDTH + COLUMN_GAP), y }) };
    }),
  };
}
