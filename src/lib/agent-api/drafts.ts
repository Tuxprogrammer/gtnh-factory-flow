import { randomInt } from "node:crypto";
import { createNewProject } from "@/examples/empty-project";
import type { FactoryProject, ResourceKind } from "@/lib/model/types";
import { AgentApiError } from "./errors";
import type { AgentConstraints, TierName } from "./schemas";

/**
 * The draft store: plans the assistant builds, held in the server process's memory (one site process on the
 * instance; a restart drops them and the assistant starts over). A draft is kept 24 hours after its last change, holds
 * at most 60 cards, and at most 300 are created a day (UTC). Nothing here reads or writes accounts, designs, the
 * library or the community hub.
 *
 * The store sits on one global so a module reload (dev server, a route bundle evaluated twice) keeps the drafts.
 */
export const DRAFT_TTL_MS = 24 * 60 * 60 * 1000;
export const DRAFTS_PER_DAY = 300;

export interface DraftTarget {
  id: string;
  kind: ResourceKind;
  name: string;
  rate: number;
}

export interface Draft {
  id: string;
  name: string;
  dataset: string;
  constraints?: AgentConstraints;
  /** The tier cards default to (plans/create's cardTier); absent, they default to the tier cap (cards.ts). */
  cardTier?: TierName;
  /** No card runs below this tier (plans/create's minTier), where the card has a voltage tier. */
  minTier?: TierName;
  createdAt: number;
  touchedAt: number;
  /** A real plan in the site's model (plan mode), card ids `c<n>`, wire ids `w<n>`. */
  project: FactoryProject;
  /** The targets as asked; each is carried by a port rate on the card that makes it (`applyTargets`). */
  targets: DraftTarget[];
  /** Wires the caller removed (`source>target>kind:id`): automatic wiring never makes them again. */
  cut: Set<string>;
  /** Wires made by plans/connect rather than by automatic wiring. */
  manual: Set<string>;
  nextCard: number;
  nextWire: number;
}

interface DraftStore {
  drafts: Map<string, Draft>;
  day: string;
  createdToday: number;
}

const STORE_KEY = Symbol.for("gtnh-factory-flow.agent-api.drafts");
type GlobalWithStore = typeof globalThis & { [STORE_KEY]?: DraftStore };

function store(): DraftStore {
  const holder = globalThis as GlobalWithStore;
  holder[STORE_KEY] ??= { drafts: new Map(), day: "", createdToday: 0 };
  return holder[STORE_KEY];
}

let clock: () => number = () => Date.now();

/** Tests move time; `undefined` puts the real clock back. */
export function setDraftClock(next?: () => number): void {
  clock = next ?? (() => Date.now());
}

export function draftNow(): number {
  return clock();
}

/** Tests start from an empty store. */
export function resetDrafts(): void {
  const current = store();
  current.drafts.clear();
  current.day = "";
  current.createdToday = 0;
}

const ID_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

/** `x` + 19 random characters: about 94 bits, never guessed. */
function newDraftId(): string {
  let id = "x";
  for (let i = 0; i < 19; i += 1) {
    id += ID_ALPHABET[randomInt(ID_ALPHABET.length)];
  }
  return id;
}

function sweep(now: number): void {
  for (const [id, draft] of store().drafts) {
    if (now - draft.touchedAt >= DRAFT_TTL_MS) {
      store().drafts.delete(id);
    }
  }
}

export function createDraft(fields: {
  name: string;
  dataset: string;
  constraints?: AgentConstraints;
  cardTier?: TierName;
  minTier?: TierName;
}): Draft {
  const now = clock();
  const current = store();
  const day = new Date(now).toISOString().slice(0, 10);
  if (current.day !== day) {
    current.day = day;
    current.createdToday = 0;
  }
  if (current.createdToday >= DRAFTS_PER_DAY) {
    throw new AgentApiError("rate_limited", `At most ${DRAFTS_PER_DAY} drafts a day; the count starts again at 00:00 UTC.`);
  }
  sweep(now);
  let id = newDraftId();
  while (current.drafts.has(id)) {
    id = newDraftId();
  }
  const blank = createNewProject();
  const stamp = new Date(now).toISOString();
  const draft: Draft = {
    id,
    name: fields.name,
    dataset: fields.dataset,
    ...(fields.constraints ? { constraints: fields.constraints } : {}),
    ...(fields.cardTier ? { cardTier: fields.cardTier } : {}),
    ...(fields.minTier ? { minTier: fields.minTier } : {}),
    createdAt: now,
    touchedAt: now,
    project: {
      ...blank,
      id,
      name: fields.name,
      notes: undefined,
      metadata: { source: "agent-api", createdAt: stamp, updatedAt: stamp },
    },
    targets: [],
    cut: new Set(),
    manual: new Set(),
    nextCard: 1,
    nextWire: 1,
  };
  current.createdToday += 1;
  current.drafts.set(id, draft);
  return draft;
}

/** The live draft, or plan_not_found for an unknown or expired id. */
export function getDraft(id: string): Draft {
  const now = clock();
  const draft = store().drafts.get(id);
  if (!draft || now - draft.touchedAt >= DRAFT_TTL_MS) {
    if (draft) {
      store().drafts.delete(id);
    }
    throw new AgentApiError("plan_not_found", `No draft "${id}": it never existed or expired 24 hours after its last change.`);
  }
  return draft;
}

/** A change keeps the draft another 24 hours and stamps the plan. */
export function touchDraft(draft: Draft): void {
  const now = clock();
  draft.touchedAt = now;
  draft.project = {
    ...draft.project,
    metadata: { ...draft.project.metadata, updatedAt: new Date(now).toISOString() },
  };
}

export function draftExpiresAt(draft: Draft): string {
  return new Date(draft.touchedAt + DRAFT_TTL_MS).toISOString();
}
