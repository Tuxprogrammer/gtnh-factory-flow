import { makeResourceHandleId } from "@/components/flow/resource-handles";
import { dedupeEdgeWires, findDuplicateEdge } from "@/lib/model/edge-identity";
import { applyEdgeInputOverride } from "@/lib/model/edge-input-overrides";
import { isRecipeInputConsumed, makeResourceKey, resourceLabel, resourceMatchesInput } from "@/lib/model/resources";
import { listNodeSections, sectionHandleId } from "@/lib/model/shared-machine";
import type { FactoryEdge, FactoryProject, RecipeInput, RecipeOutput } from "@/lib/model/types";
import type { z } from "zod";
import type { Draft } from "./drafts";
import { AgentApiError } from "./errors";
import { nameOf } from "./format";
import type { draftWire } from "./plan-schemas";

/**
 * Wires in a draft, made by the site's own Auto-connect rule (the card button, `autoConnectNode` with
 * `buildCompatibleEdgesBetweenNodes` in src/store/factory-store.ts): every output slot of one card wires to every
 * consumed input slot of another that takes it (`resourceMatchesInput`: same kind, same id or one of an "any of"
 * slot's members), both ways, one wire per pair of port rows. A wire into an "any of" slot sets the slot to the wired
 * resource (`applyEdgeInputOverride`), as on the board. The store keeps that builder private to the module, so it is
 * mirrored here from the same exported parts; `wiring.test.ts` holds the two to the same wires.
 *
 * A wire the caller removed (`plans/disconnect`) is remembered in `draft.cut` and never made again automatically.
 */

export function cutKey(edge: Pick<FactoryEdge, "source" | "target" | "resourceKind" | "resourceId">): string {
  return `${edge.source}>${edge.target}>${makeResourceKey(edge.resourceKind, edge.resourceId)}`;
}

/** The site's rule: every compatible wire from `sourceId` to `targetId` (ids left blank for the caller). */
export function compatibleEdges(project: FactoryProject, sourceId: string, targetId: string): FactoryEdge[] {
  const sourceNode = project.nodes.find((node) => node.id === sourceId);
  const targetNode = project.nodes.find((node) => node.id === targetId);
  if (!sourceNode || !targetNode) {
    return [];
  }
  const edges: FactoryEdge[] = [];
  for (const source of listNodeSections(sourceNode)) {
    const sourceRecipe = project.recipes.find((recipe) => recipe.id === source.node.recipeId);
    if (!sourceRecipe) {
      continue;
    }
    for (const target of listNodeSections(targetNode)) {
      const targetRecipe = project.recipes.find((recipe) => recipe.id === target.node.recipeId);
      if (!targetRecipe) {
        continue;
      }
      sourceRecipe.outputs.forEach((output, outputIndex) => {
        targetRecipe.inputs.forEach((input, inputIndex) => {
          if (!isRecipeInputConsumed(input) || !resourceMatchesInput(output, input)) {
            return;
          }
          edges.push(edgeFor(sourceNode.id, targetNode.id, output, outputIndex, input, inputIndex, source.section, target.section));
        });
      });
    }
  }
  return dedupeEdgeWires(edges);
}

function edgeFor(
  source: string,
  target: string,
  output: RecipeOutput,
  outputIndex: number,
  input: RecipeInput,
  inputIndex: number,
  sourceSection = 0,
  targetSection = 0,
): FactoryEdge {
  return {
    id: "",
    source,
    target,
    sourceHandle: sectionHandleId(sourceSection, makeResourceHandleId("output", output, outputIndex)),
    targetHandle: sectionHandleId(targetSection, makeResourceHandleId("input", input, inputIndex)),
    resourceKind: output.kind,
    resourceId: output.id,
    label: resourceLabel(output),
  };
}

function nextWireId(draft: Draft): string {
  const id = `w${draft.nextWire}`;
  draft.nextWire += 1;
  return id;
}

function addEdges(draft: Draft, edges: FactoryEdge[]): FactoryEdge[] {
  let project = { ...draft.project, edges: [...draft.project.edges] };
  const added: FactoryEdge[] = [];
  for (const candidate of edges) {
    if (findDuplicateEdge(project.edges, candidate)) {
      continue;
    }
    const edge = { ...candidate, id: nextWireId(draft) };
    project.edges.push(edge);
    project = applyEdgeInputOverride(project, edge);
    added.push(edge);
  }
  draft.project = project;
  return added;
}

/** Auto-connect for newly added cards: each against every card in the draft, both ways. Returns the wires made. */
export function autoConnect(draft: Draft, newCardIds: string[]): FactoryEdge[] {
  const candidates: FactoryEdge[] = [];
  // Each pair once: a new card against every older card and every new card added before it.
  const later = new Set(newCardIds);
  for (const cardId of newCardIds) {
    later.delete(cardId);
    for (const other of draft.project.nodes) {
      if (other.id === cardId || later.has(other.id)) {
        continue;
      }
      candidates.push(
        ...compatibleEdges(draft.project, other.id, cardId),
        ...compatibleEdges(draft.project, cardId, other.id),
      );
    }
  }
  return addEdges(
    draft,
    candidates.filter((edge) => !draft.cut.has(cutKey(edge))),
  );
}

export interface WireRequest {
  fromCard: string;
  from: { kind: string; id: string };
  toCard: string;
  to?: { kind: string; id: string };
}

function cardRecipe(project: FactoryProject, cardId: string) {
  const node = project.nodes.find((entry) => entry.id === cardId);
  if (!node) {
    throw new AgentApiError("bad_request", `No card ${cardId} in this draft.`);
  }
  return { node, recipe: project.recipes.find((recipe) => recipe.id === node.recipeId) };
}

/**
 * One wire the caller asks for: from a card's output slot of the resource to the other card's consumed input slot
 * that takes it (`to.resource` picks the slot when several could). Returns the wire and whether it is new; an
 * existing identical wire is returned as it is.
 */
export function connect(draft: Draft, request: WireRequest): { edge: FactoryEdge; created: boolean } {
  if (request.fromCard === request.toCard) {
    throw new AgentApiError("bad_request", "A wire joins two different cards.");
  }
  const from = cardRecipe(draft.project, request.fromCard);
  const to = cardRecipe(draft.project, request.toCard);
  const outputIndex = from.recipe?.outputs.findIndex(
    (output) => output.kind === request.from.kind && output.id === request.from.id,
  ) ?? -1;
  if (!from.recipe || outputIndex === -1) {
    throw new AgentApiError("bad_request", `Card ${request.fromCard} does not make ${request.from.id}.`);
  }
  const output = from.recipe.outputs[outputIndex]!;
  const inputIndex = to.recipe?.inputs.findIndex(
    (input) =>
      isRecipeInputConsumed(input) &&
      resourceMatchesInput(output, input) &&
      (!request.to ||
        (input.kind === request.to.kind &&
          (input.id === request.to.id || Boolean(input.alternatives?.some((entry) => entry.id === request.to!.id))))),
  ) ?? -1;
  if (!to.recipe || inputIndex === -1) {
    throw new AgentApiError(
      "bad_request",
      `Card ${request.toCard} has no input slot that uses up ${nameOf(output)}.`,
    );
  }
  const candidate = edgeFor(from.node.id, to.node.id, output, outputIndex, to.recipe.inputs[inputIndex]!, inputIndex);
  const existing = findDuplicateEdge(draft.project.edges, candidate);
  if (existing) {
    return { edge: existing, created: false };
  }
  draft.cut.delete(cutKey(candidate));
  const [edge] = addEdges(draft, [candidate]);
  draft.manual.add(edge!.id);
  return { edge: edge!, created: true };
}

/** Removes one wire and remembers it so automatic wiring leaves that pair alone. */
export function disconnect(draft: Draft, wireId: string): void {
  const edge = draft.project.edges.find((entry) => entry.id === wireId);
  if (!edge) {
    throw new AgentApiError("bad_request", `No wire ${wireId} in this draft.`);
  }
  draft.cut.add(cutKey(edge));
  draft.manual.delete(wireId);
  draft.project = { ...draft.project, edges: draft.project.edges.filter((entry) => entry.id !== wireId) };
}

export function describeWire(draft: Draft, edge: FactoryEdge): z.infer<typeof draftWire> {
  const source = draft.project.nodes.find((node) => node.id === edge.source);
  const recipe = draft.project.recipes.find((entry) => entry.id === source?.recipeId);
  const output = recipe?.outputs.find((entry) => entry.kind === edge.resourceKind && entry.id === edge.resourceId);
  return {
    wireId: edge.id,
    from: edge.source,
    to: edge.target,
    id: edge.resourceId,
    kind: edge.resourceKind,
    name: output ? nameOf(output) : (edge.label ?? edge.resourceId),
    ...(draft.manual.has(edge.id) ? { manual: true } : {}),
  };
}
