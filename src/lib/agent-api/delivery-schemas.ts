import { z } from "zod";
import { PLAN_ID_PATTERN } from "./plan-schemas";
import { playerSchema } from "./schemas";
import { slimSnapshot, slimTarget } from "./slim";

/**
 * Request and response shapes of plans/deliver, plans/import, plans/diff and the read route GET /api/deliveries/{id}
 * (docs/agent-api.md). Strict like the rest of the API.
 */
const planId = z.string().regex(PLAN_ID_PATTERN, "planId is not a draft id.");

/** plans/import alone takes a body this big: it carries the player's open plan. */
export const IMPORT_MAX_BYTES = 512 * 1024;

export const plansDeliverRequest = z
  .object({
    planId,
    player: z.object({ name: z.string().min(1).max(32), uuid: z.string().min(1).max(64) }).strict(),
  })
  .strict();

export const plansDeliverResponse = z
  .object({
    name: z.string(),
    id: z.string(),
    code: z.string(),
    url: z.string().optional(),
    link: z.string().optional(),
    expiresAt: z.string(),
    cardCount: z.number().int(),
  })
  .strict();

export const plansImportRequest = z.object({ snapshot: slimSnapshot, player: playerSchema.optional() }).strict();

export const plansImportResponse = z
  .object({
    dataset: z.string(),
    planId: z.string(),
    name: z.string(),
    expiresAt: z.string(),
    cardCount: z.number().int(),
    wireCount: z.number().int(),
    warnings: z.array(z.string()),
  })
  .strict();

export const plansDiffRequest = z.object({ planId, player: playerSchema.optional() }).strict();

export const diffSummary = z
  .object({
    addedCards: z.number().int(),
    addedWires: z.number().int(),
    addedDrawers: z.number().int(),
    changedTargets: z.number().int(),
  })
  .strict();

export const plansDiffResponse = z
  .object({
    proposalId: z.string(),
    basePlanId: z.string(),
    expiresAt: z.string(),
    summary: diffSummary,
    text: z.string(),
  })
  .strict();

/**
 * A stored proposal: what to add to the plan `basePlanId`, in the plan's own format. Cards, drawers and wires are the
 * plan document's nodes, storages and edges; a wire's ends name the base plan's ids for things already there.
 * `targets` are pins to set (new or changed rates); `recipes` are the full bodies the added cards use.
 */
const looseObject = z.record(z.string(), z.unknown());

export const proposalPayload = z
  .object({
    version: z.literal(1),
    basePlanId: z.string(),
    summary: diffSummary,
    recipes: z.array(looseObject),
    cards: z.array(looseObject),
    drawers: z.array(looseObject),
    wires: z.array(looseObject),
    targets: z.array(slimTarget),
  })
  .strict();
export type ProposalPayload = z.infer<typeof proposalPayload>;

const readHead = {
  id: z.string(),
  name: z.string(),
  createdAt: z.string(),
  expiresAt: z.string(),
};

export const deliveryReadResponse = z.union([
  z.object({ ...readHead, kind: z.literal("delivery"), plan: looseObject }).strict(),
  z.object({ ...readHead, kind: z.literal("proposal"), basePlanId: z.string(), proposal: proposalPayload }).strict(),
]);
