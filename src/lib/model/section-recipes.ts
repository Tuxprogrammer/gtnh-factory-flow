import { renumberAfterSectionRemoved } from "@/lib/solver/plan/hygiene";
import { edgeSectionAt, sectionHandleId, splitSectionHandleId } from "./shared-machine";
import type { FactoryNode, FactoryProject } from "./types";

/**
 * A shared machine's extra section whose recipe the plan does not carry is dropped, with the wires on its ports;
 * later sections move down one, as when the player removes a section. Nothing can rebuild such a section: the plan
 * holds only the recipe's id, and a stored id is matched to the dataset by the recipe's content, which is gone. Kept,
 * it has no ports, so its wires point at handles the board never draws and the solver reads a card with no recipe.
 * Section 0 is the card's own recipe and is left alone. `cardIds` limits the repair to those cards.
 */
export function dropSectionsWithoutRecipes(
  project: FactoryProject,
  cardIds?: ReadonlySet<string>,
): FactoryProject {
  const held = new Set(project.recipes.map((recipe) => recipe.id));
  let next = project;
  for (const node of project.nodes) {
    if (cardIds && !cardIds.has(node.id)) continue;
    const extras = node.extraRecipes ?? [];
    // Highest first, so each removal leaves the lower section numbers as they were.
    for (let section = extras.length; section >= 1; section -= 1) {
      if (!held.has(extras[section - 1]!.recipeId)) {
        next = withoutSection(next, node.id, section);
      }
    }
  }
  if (next === project) return project;
  // A drawer whose only wires were on a dropped section has nothing left to hold.
  const linked = new Set(next.edges.flatMap((edge) => [edge.source, edge.target]));
  const wasLinked = new Set(project.edges.flatMap((edge) => [edge.source, edge.target]));
  const storages = next.storages?.filter(
    (storage) =>
      storage.poolSide !== undefined || linked.has(storage.id) || !wasLinked.has(storage.id),
  );
  return storages && storages.length !== next.storages?.length ? { ...next, storages } : next;
}

function withoutSection(project: FactoryProject, nodeId: string, section: number): FactoryProject {
  const renumber = (handleId: string | undefined): string | undefined => {
    if (!handleId) return handleId;
    const { section: at, handleId: bare } = splitSectionHandleId(handleId);
    return at > section && bare ? sectionHandleId(at - 1, bare) : handleId;
  };
  const edges = project.edges
    .filter(
      (edge) =>
        !(edge.source === nodeId && edgeSectionAt(edge, nodeId, "source") === section) &&
        !(edge.target === nodeId && edgeSectionAt(edge, nodeId, "target") === section),
    )
    .map((edge) =>
      edge.source === nodeId || edge.target === nodeId
        ? {
            ...edge,
            sourceHandle: edge.source === nodeId ? renumber(edge.sourceHandle) : edge.sourceHandle,
            targetHandle: edge.target === nodeId ? renumber(edge.targetHandle) : edge.targetHandle,
          }
        : edge,
    );
  const nodes = project.nodes.map((node): FactoryNode => {
    if (node.id !== nodeId) return node;
    const extraRecipes = (node.extraRecipes ?? []).filter((_extra, index) => index !== section - 1);
    const { extraRecipes: _old, ...rest } = node;
    void _old;
    return extraRecipes.length > 0 ? { ...rest, extraRecipes } : rest;
  });
  return renumberAfterSectionRemoved({ ...project, nodes, edges }, nodeId, section);
}
