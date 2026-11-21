import { z } from "zod";
import { constraintsSchema, playerSchema, resourceRef, tierName } from "./schemas";
import { THING_ID_PATTERN } from "./slim";

/**
 * Request and response shapes of the draft endpoints (plans/*, docs/agent-api.md). Strict like the rest of the API:
 * an unknown field is a bad_request.
 */

/** A draft id: `x` and 19 characters of the delivery alphabet (no 0, 1, i, l, o). */
export const PLAN_ID_PATTERN = /^x[a-hjkmnp-z2-9]{19}$/;
const planId = z.string().regex(PLAN_ID_PATTERN, "planId is not a draft id.");
/** Card and wire ids: `c<n>` and `w<n>` in a draft made here; an imported plan keeps its own ids (`THING_ID_PATTERN`). */
const cardId = z.string().regex(THING_ID_PATTERN, "not a card id.");
const wireId = z.string().regex(THING_ID_PATTERN, "not a wire id.");
const player = playerSchema.optional();

export const MAX_DRAFT_CARDS = 60;

export const plansCreateRequest = z
  .object({ name: z.string().trim().min(1).max(80), constraints: constraintsSchema.optional(), player })
  .strict();

/** A dial value: an option key (coils, tier parts) or a number (numeric settings). */
const dialValue = z.union([z.string().min(1).max(64), z.number()]);

export const cardRequest = z
  .object({
    recipeId: z.string().min(1).max(400),
    machine: z.string().trim().min(1).max(200).optional(),
    tier: tierName.optional(),
    dials: z.record(z.string().min(1).max(64), dialValue).optional(),
  })
  .strict();
export type CardRequest = z.infer<typeof cardRequest>;

export const plansAddCardsRequest = z
  .object({ planId, cards: z.array(cardRequest).min(1).max(MAX_DRAFT_CARDS), player })
  .strict();

export const plansRemoveCardsRequest = z
  .object({ planId, cardIds: z.array(cardId).min(1).max(MAX_DRAFT_CARDS), player })
  .strict();

export const plansSetTargetsRequest = z
  .object({
    planId,
    targets: z
      .array(z.object({ resource: resourceRef, rate: z.number().positive().max(1e12) }).strict())
      .max(16),
    player,
  })
  .strict();

const wireEnd = z.object({ cardId, resource: resourceRef }).strict();

export const plansConnectRequest = z
  .object({
    planId,
    from: wireEnd,
    to: z.object({ cardId, resource: resourceRef.optional() }).strict(),
    player,
  })
  .strict();

export const plansDisconnectRequest = z.object({ planId, wireId, player }).strict();

export const GET_FIELDS = ["cards", "wires", "targets"] as const;

export const plansGetRequest = z
  .object({
    planId,
    fields: z.array(z.enum(GET_FIELDS)).min(1).max(GET_FIELDS.length).optional(),
    /** Only these cards, and the wires that touch them. */
    cardIds: z.array(cardId).min(1).max(MAX_DRAFT_CARDS).optional(),
    player,
  })
  .strict();

export const SOLVE_FIELDS = ["targets", "unsupplied", "byproducts", "power", "warnings", "cards"] as const;

export const plansSolveRequest = z
  .object({ planId, fields: z.array(z.enum(SOLVE_FIELDS)).min(1).max(SOLVE_FIELDS.length).optional(), player })
  .strict();

// Responses.

const resource = { id: z.string(), kind: z.string(), name: z.string() };

export const draftCard = z
  .object({
    cardId: z.string(),
    recipeId: z.string(),
    name: z.string(),
    machine: z.object({ id: z.string(), name: z.string() }).strict(),
    tier: z.string(),
    dials: z.record(z.string(), z.union([z.string(), z.number()])).optional(),
    heat: z.number().optional(),
  })
  .strict();

export const draftWire = z
  .object({ wireId: z.string(), from: z.string(), to: z.string(), ...resource, manual: z.boolean().optional() })
  .strict();

export const draftTarget = z
  .object({ ...resource, rate: z.number(), cardId: z.string().optional() })
  .strict();

export const plansCreateResponse = z
  .object({
    dataset: z.string(),
    planId: z.string(),
    name: z.string(),
    expiresAt: z.string(),
    constraints: constraintsSchema.optional(),
  })
  .strict();

export const plansAddCardsResponse = z
  .object({
    planId: z.string(),
    cards: z.array(draftCard),
    wires: z.array(draftWire),
    cardCount: z.number().int(),
  })
  .strict();

export const plansRemoveCardsResponse = z
  .object({
    planId: z.string(),
    removed: z.array(z.string()),
    wiresRemoved: z.number().int(),
    cardCount: z.number().int(),
    targets: z.array(draftTarget),
  })
  .strict();

export const plansSetTargetsResponse = z.object({ planId: z.string(), targets: z.array(draftTarget) }).strict();

export const plansConnectResponse = z.object({ planId: z.string(), wire: draftWire, created: z.boolean() }).strict();

export const plansDisconnectResponse = z.object({ planId: z.string(), removed: z.string() }).strict();

export const plansGetResponse = z
  .object({
    dataset: z.string(),
    planId: z.string(),
    name: z.string(),
    expiresAt: z.string(),
    constraints: constraintsSchema.optional(),
    cardCount: z.number().int(),
    wireCount: z.number().int(),
    cards: z.array(draftCard).optional(),
    wires: z.array(draftWire).optional(),
    targets: z.array(draftTarget).optional(),
    truncated: z.boolean().optional(),
  })
  .strict();

const rateLine = z.object({ ...resource, perSecond: z.number(), free: z.boolean().optional() }).strict();

/**
 * One solved card as a row of `CARD_COLUMNS`: card id, recipe name, machine name, tier, machine count, what it takes
 * and what it makes (resource name to rate per second), then its dials when any are set. A row, not an object, so a
 * 60-card draft's answer stays under 12 KB.
 */
export const CARD_COLUMNS = ["cardId", "recipe", "machine", "tier", "count", "in", "out", "dials"] as const;

const rates = z.record(z.string(), z.number());
const dials = z.record(z.string(), z.union([z.string(), z.number()]));

export const solveCardLine = z.union([
  z.tuple([z.string(), z.string(), z.string(), z.string(), z.number(), rates, rates]),
  z.tuple([z.string(), z.string(), z.string(), z.string(), z.number(), rates, rates, dials]),
]);

export const plansSolveResponse = z
  .object({
    dataset: z.string(),
    planId: z.string(),
    solved: z.boolean(),
    targetsMet: z.boolean(),
    targets: z
      .array(
        z
          .object({ ...resource, rate: z.number(), made: z.number(), met: z.boolean(), cardId: z.string().optional() })
          .strict(),
      )
      .optional(),
    unsupplied: z.array(rateLine).optional(),
    byproducts: z.array(rateLine).optional(),
    euT: z.number().optional(),
    warnings: z.array(z.string()).optional(),
    cardColumns: z.array(z.string()).optional(),
    cards: z.array(solveCardLine).optional(),
    truncated: z.boolean().optional(),
  })
  .strict();
