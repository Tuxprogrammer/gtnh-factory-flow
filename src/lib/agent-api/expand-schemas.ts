import { z } from "zod";
import { PLAN_ID_PATTERN } from "./plan-schemas";
import { constraintsSchema, playerSchema, recipeInput, recipeOutput, resourceRef } from "./schemas";

/** Request and response of plans/expand-inputs (docs/agent-api.md). Strict like the rest of the API. */

/** Candidates per input, at most. */
export const MAX_EXPAND_CANDIDATES = 3;

export const plansExpandInputsRequest = z
  .object({
    planId: z.string().regex(PLAN_ID_PATTERN, "planId is not a draft id."),
    /** Only these inputs, in this order (a page of `remaining` from an earlier answer). */
    resources: z.array(resourceRef).min(1).max(16).optional(),
    constraints: constraintsSchema.optional(),
    max: z.number().int().min(1).max(MAX_EXPAND_CANDIDATES).default(MAX_EXPAND_CANDIDATES),
    player: playerSchema.optional(),
  })
  .strict();

const resource = { id: z.string(), kind: z.string(), name: z.string() };
const rateLine = z.object({ ...resource, perSecond: z.number() }).strict();

export const expandCandidate = z
  .object({
    recipeId: z.string(),
    name: z.string(),
    machine: z.string(),
    tier: z.string(),
    euT: z.number(),
    seconds: z.number(),
    inputs: z.array(recipeInput),
    outputs: z.array(recipeOutput),
    /** One plain sentence: what it costs and what it brings in. */
    reason: z.string(),
    /** Inputs the draft did not need before and would need from outside with this card added, per second. */
    newRaws: z.array(rateLine),
    /** Inputs it takes that the draft already makes and nothing uses: a good sign. */
    usesByproducts: z.array(z.string()).optional(),
    /** Machines the solve needs for this card (fractional). */
    machines: z.number(),
    /** EU the draft spends more per unit made (per L for a fluid), with this card added; steam at 2 L per EU. */
    euPerItem: z.number(),
    /** Litres of steam a second the card burns at the solved count, for a steam machine. */
    steamPerSecond: z.number().optional(),
    /** False when the trial solve could not use the card; it is ranked last. */
    works: z.boolean().optional(),
  })
  .strict();

export const expandedInput = z
  .object({
    ...resource,
    perSecond: z.number(),
    /** Raw to a player (an ore form, an ore's dust, water, an air gas): fine to leave unsupplied or mark `free`. */
    raw: z.boolean().optional(),
    /** Recipes found that make it, before constraints and skips. */
    found: z.number().int(),
    candidates: z.array(expandCandidate),
    /** Recipes left out: they take what they make, undo a card in the draft, are in it already, or cannot be built. */
    skipped: z.number().int().optional(),
    /** Of `skipped`: recipes only steam or primitive machines run (constraints.steam allows them). */
    steamOnly: z.number().int().positive().optional(),
    note: z.string().optional(),
  })
  .strict();

export const plansExpandInputsResponse = z
  .object({
    dataset: z.string(),
    planId: z.string(),
    inputs: z.array(expandedInput),
    /** Unsupplied inputs the constraints mark `free`: raw on purpose, never expanded. */
    accepted: z.array(rateLine),
    /** More unsupplied inputs than one answer covers: ask again with `resources` set to these. */
    more: z.boolean(),
    remaining: z.array(z.object(resource).strict()).optional(),
    notEnforced: z.array(z.string()).optional(),
    truncated: z.boolean().optional(),
  })
  .strict();
