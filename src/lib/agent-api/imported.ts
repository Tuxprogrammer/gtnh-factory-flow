import type { Draft } from "./drafts";
import { MAX_DRAFT_CARDS } from "./plan-schemas";
import type { SlimSnapshot } from "./slim";

/**
 * What a draft made by `plans/import` remembers about the plan it was made from: the snapshot as imported (for
 * `plans/diff`), the plan's id, the cards whose recipes the dataset lacks, and the snapshot's own port-rate pins, so
 * the draft's target handling (`applyTargets`) leaves them where the player put them.
 *
 * Kept beside the draft, not in it, on one global like the draft store (a module reload keeps it). A draft that was
 * not imported has none of this and behaves exactly as before.
 */
export const MAX_IMPORTED_CARDS = 200;

export interface ImportedBase {
  basePlanId: string;
  /** `toSlimSnapshot` of the draft right after import: what diff compares against. */
  base: SlimSnapshot;
  /** Card ids whose recipe (or a shared section's recipe) the dataset does not have. */
  opaque: Set<string>;
  /** Snapshot pins that are not one of the draft's targets, by card id: kept as they were. */
  pins: Map<string, Record<string, number>>;
  /** For each target the snapshot pinned, the card that carried it (`kind:id` to card id). */
  carriers: Map<string, string>;
}

const KEY = Symbol.for("gtnh-factory-flow.agent-api.imported");
type GlobalWithImports = typeof globalThis & { [KEY]?: WeakMap<Draft, ImportedBase> };

function imports(): WeakMap<Draft, ImportedBase> {
  const holder = globalThis as GlobalWithImports;
  holder[KEY] ??= new WeakMap();
  return holder[KEY];
}

export function setImportedBase(draft: Draft, base: ImportedBase): void {
  imports().set(draft, base);
}

export function importedBase(draft: Draft): ImportedBase | undefined {
  return imports().get(draft);
}

/** 60 cards for a draft made here; an imported plan may hold up to 200. */
export function draftCardCap(draft: Draft): number {
  return imports().has(draft) ? MAX_IMPORTED_CARDS : MAX_DRAFT_CARDS;
}

/** The snapshot's card for a target it pinned, while that card is still in the draft. */
export function importedCarrier(draft: Draft, resourceKey: string): string | undefined {
  const cardId = imports().get(draft)?.carriers.get(resourceKey);
  return cardId && draft.project.nodes.some((node) => node.id === cardId) ? cardId : undefined;
}

/** The snapshot's other pins on one card. */
export function importedPins(draft: Draft, cardId: string): Record<string, number> | undefined {
  return imports().get(draft)?.pins.get(cardId);
}
