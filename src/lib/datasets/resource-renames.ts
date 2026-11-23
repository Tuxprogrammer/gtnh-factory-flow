/**
 * Items the game renamed between pack versions, applied to a saved plan.
 *
 * When a pack moves an item to a new id (IC2's plantball became a GregTech meta item), the mod that moved it
 * registers a Postea replacement and the game converts old stacks in worlds. The exporter reads that table from the
 * running game and the dataset publishes it (`resourceRenamesPath`, rows `{ from, kind, to }`; the pipeline's
 * `tools/dataset-pipeline/scripts/resource-renames.mjs` keeps the same rule). A plan saved before the move names the
 * old id: its recipes are matched to the dataset through the table, and its wires, drawers and saved keys follow.
 *
 * `from` ending in `@*` is every meta of that item, and a row for the exact meta wins over it; `to` ending in `@*`
 * keeps the stack's own meta. Only items are renamed: the game registers no renames for fluids.
 */
import type { FactoryProject, Recipe, RecipeInput, RecipeOutput } from "@/lib/model/types";

export interface ResourceRenameTable {
  schemaVersion: 1;
  renames: Array<{ from: string; kind: string; to: string }>;
}

/** from id -> to id. */
export type ResourceRenames = ReadonlyMap<string, string>;

export const NO_RENAMES: ResourceRenames = new Map();

export function resourceRenameLookup(table: ResourceRenameTable | undefined): ResourceRenames {
  const rows = Array.isArray(table?.renames) ? table.renames : [];
  return new Map(
    rows
      .filter((row) => row.kind === "item" && typeof row.from === "string" && typeof row.to === "string")
      .map((row) => [row.from, row.to] as const),
  );
}

function withMeta(name: string, meta: number): string {
  return meta === 0 ? name : `${name}@${meta}`;
}

/** The id the game turns `id` into, or `id` itself. */
export function renameResourceId(renames: ResourceRenames, id: string, kind = "item"): string {
  if (kind !== "item" || renames.size === 0) return id;
  const at = id.lastIndexOf("@");
  const hasMeta = at > 0 && /^\d+$/.test(id.slice(at + 1));
  const name = hasMeta ? id.slice(0, at) : id;
  const meta = hasMeta ? Number(id.slice(at + 1)) : 0;
  const to = renames.get(id) ?? renames.get(`${name}@*`);
  if (!to) return id;
  return to.endsWith("@*") ? withMeta(to.slice(0, -2), meta) : to;
}

function renameSlot<T extends RecipeInput | RecipeOutput>(renames: ResourceRenames, slot: T): T {
  const id = renameResourceId(renames, slot.id, slot.kind);
  return id === slot.id ? slot : { ...slot, id };
}

/** A stored recipe as the renamed game would write it, for matching by content. */
export function withRenamedResources(renames: ResourceRenames, recipe: Recipe): Recipe {
  if (renames.size === 0) return recipe;
  return {
    ...recipe,
    inputs: recipe.inputs.map((slot) => renameSlot(renames, slot)),
    outputs: recipe.outputs.map((slot) => renameSlot(renames, slot)),
  };
}

type Face = Pick<RecipeInput, "displayName" | "iconPath" | "dominantColor">;
const FACE_KEYS = ["displayName", "iconPath", "dominantColor", "iconAtlas"] as const;

/**
 * `project` with every renamed item moved to its new id outside the recipe bodies: wire resources and handle ids
 * (handles embed the encoded id), drawers, slot picks, saved leftover and pool keys, hidden and favourite resource
 * keys, the plan's icon, and the bodies of `ownRecipeIds` (recipes the dataset has none of: the card runs the plan's
 * saved copy, and the game turns its items into the new ones as well). A dataset recipe body is never rewritten, and
 * an old id a card on the board still takes or makes through one is kept everywhere: its wires must keep meeting it.
 * The in-game planner's own data (`gtnhplanner`) is opaque here. An object renamed this way takes the new item's
 * name and picture from the recipes that name it. Unchanged parts keep their identity.
 */
export function withRenamedProjectResources(
  project: FactoryProject,
  renames: ResourceRenames,
  ownRecipeIds: ReadonlySet<string> = new Set(),
): FactoryProject {
  if (renames.size === 0) return project;
  const onBoard = new Set<string>();
  for (const node of project.nodes) {
    onBoard.add(node.recipeId);
    for (const extra of node.extraRecipes ?? []) onBoard.add(extra.recipeId);
  }
  const used = new Set<string>();
  const faces = new Map<string, Face>();
  for (const recipe of project.recipes) {
    for (const slot of [...recipe.inputs, ...recipe.outputs]) {
      if (slot.kind !== "item") continue;
      if (onBoard.has(recipe.id) && !ownRecipeIds.has(recipe.id)) used.add(slot.id);
      if (!faces.has(slot.id) && slot.displayName) faces.set(slot.id, slot);
    }
  }
  const renameOf = (id: string): string | undefined => {
    if (used.has(id)) return undefined;
    const to = renameResourceId(renames, id);
    return to === id ? undefined : to;
  };
  // Handles and port keys carry `item:<encodeURIComponent(id)>`, ended by `:`, `|`, U+001F or the string's end.
  const ENCODED = /(^|[:|\u001f])item:([^:|\u001f]+)(?=$|[:|\u001f])/g;
  const renameString = (text: string): string => {
    const whole = renameOf(text);
    if (whole) return whole;
    if (text.startsWith("item:")) {
      const keyed = renameOf(text.slice(5));
      if (keyed) return `item:${keyed}`;
    }
    if (!text.includes("item:")) return text;
    return text.replace(ENCODED, (match, lead: string, encoded: string) => {
      let id: string;
      try {
        id = decodeURIComponent(encoded);
      } catch {
        return match;
      }
      const to = renameOf(id);
      return to ? `${lead}item:${encodeURIComponent(to)}` : match;
    });
  };
  const walk = (node: unknown): unknown => {
    if (typeof node === "string") return renameString(node);
    if (Array.isArray(node)) {
      let out: unknown[] | undefined;
      node.forEach((item: unknown, index) => {
        const next = walk(item);
        if (next !== item) {
          out ??= node.slice();
          out[index] = next;
        }
      });
      return out ?? node;
    }
    if (node && typeof node === "object") {
      const record = node as Record<string, unknown>;
      let out: Record<string, unknown> | undefined;
      for (const [key, item] of Object.entries(record)) {
        if (key === "gtnhplanner") continue;
        const nextKey = renameString(key);
        const next = walk(item);
        if (next !== item || nextKey !== key) {
          out ??= { ...record };
          if (nextKey !== key) delete out[key];
          out[nextKey] = next;
        }
      }
      if (out) {
        // A resource that moved wears the new item's face.
        for (const idKey of ["id", "resourceId"]) {
          const before = record[idKey];
          const after = out[idKey];
          if (typeof before !== "string" || typeof after !== "string" || before === after) continue;
          const face = faces.get(after);
          for (const faceKey of FACE_KEYS) {
            if (!(faceKey in out)) continue;
            const value = face?.[faceKey as keyof Face];
            if (value === undefined) delete out[faceKey];
            else out[faceKey] = value;
          }
          if (face?.displayName && typeof out.label === "string") out.label = face.displayName;
        }
      }
      return out ?? node;
    }
    return node;
  };
  const { recipes, ...rest } = project;
  const next = walk(rest) as Omit<FactoryProject, "recipes">;
  let nextRecipes = recipes;
  if (ownRecipeIds.size > 0) {
    const mapped = recipes.map((recipe) => (ownRecipeIds.has(recipe.id) ? (walk(recipe) as Recipe) : recipe));
    if (mapped.some((recipe, index) => recipe !== recipes[index])) nextRecipes = mapped;
  }
  return next === rest && nextRecipes === recipes ? project : { ...next, recipes: nextRecipes };
}
