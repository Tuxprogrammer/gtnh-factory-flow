import { z } from "zod";

/**
 * Request and response shapes of the agent API (docs/agent-api.md). Requests are strict: an unknown field is a
 * bad_request, so a misspelt option fails loudly instead of being ignored. Response schemas are what the committed
 * examples are checked against (agent-api-examples.test.ts).
 */

export const TIER_NAMES = [
  "ULV",
  "LV",
  "MV",
  "HV",
  "EV",
  "IV",
  "LuV",
  "ZPM",
  "UV",
  "UHV",
  "UEV",
  "UIV",
  "UMV",
  "UXV",
  "MAX",
] as const;
export const tierName = z.enum(TIER_NAMES);
export type TierName = z.infer<typeof tierName>;

export const playerSchema = z
  .object({ name: z.string().min(1).max(32), uuid: z.string().max(64).optional() })
  .strict();

const idString = z.string().min(1).max(200);

export const constraintsSchema = z
  .object({
    tierCap: tierName.optional(),
    heat: z.number().nonnegative().optional(),
    cleanroom: z.boolean().optional(),
    lowgrav: z.boolean().optional(),
    banned: z.array(idString).max(64).optional(),
    free: z.array(idString).max(64).optional(),
    /** Steam and primitive machines may be chosen without being named (also when tierCap is ULV). Default false. */
    steam: z.boolean().optional(),
  })
  .strict();
export type AgentConstraints = z.infer<typeof constraintsSchema>;

export const resourceKind = z.enum(["item", "fluid", "aspect"]);

/** A resource as a bare id (item first, then fluid, then aspect) or as `{id, kind}`. */
export const resourceRef = z.union([
  idString,
  z.object({ id: idString, kind: resourceKind.optional() }).strict(),
]);
export type ResourceRef = z.infer<typeof resourceRef>;

const limit = (max: number) => z.number().int().min(1).max(max).default(8);

export const resourcesSearchRequest = z
  .object({
    text: z.string().trim().min(1).max(100),
    kind: z.enum(["item", "fluid"]).optional(),
    limit: limit(25),
    player: playerSchema.optional(),
  })
  .strict();

export const recipesSearchRequest = z
  .object({
    makes: resourceRef.optional(),
    takes: resourceRef.optional(),
    text: z.string().trim().min(1).max(100).optional(),
    machine: z.string().trim().min(1).max(100).optional(),
    constraints: constraintsSchema.optional(),
    limit: limit(25),
    offset: z.number().int().min(0).max(1000).default(0),
    detail: z.boolean().default(false),
    player: playerSchema.optional(),
  })
  .strict()
  .refine((body) => body.makes !== undefined || body.takes !== undefined || body.text !== undefined, {
    message: "Give at least one of makes, takes or text.",
  });

export const recipesGetRequest = z
  .object({ recipeId: idString.max(400), player: playerSchema.optional() })
  .strict();

// Responses.

const named = { id: z.string(), kind: z.string(), name: z.string() };

export const resourceResult = z.object(named).strict();

export const resourcesSearchResponse = z
  .object({
    dataset: z.string(),
    results: z.array(resourceResult),
    total: z.number().int().nonnegative(),
    truncated: z.boolean().optional(),
  })
  .strict();

const alternative = z.object(named).strict();

export const recipeInput = z
  .object({
    ...named,
    amount: z.number(),
    consumed: z.boolean(),
    alternatives: z.array(alternative).optional(),
    moreAlternatives: z.number().int().optional(),
  })
  .strict();

export const recipeOutput = z
  .object({ ...named, amount: z.number(), chance: z.number().optional() })
  .strict();

export const compactRecipe = z
  .object({
    recipeId: z.string(),
    name: z.string(),
    machine: z.string(),
    minTier: z.string(),
    euT: z.number(),
    seconds: z.number(),
    heat: z.number().optional(),
    circuit: z.string().optional(),
    inputs: z.array(recipeInput),
    outputs: z.array(recipeOutput),
    // detail only
    map: z.string().optional(),
    machines: z.array(z.object({ id: z.string(), name: z.string(), kind: z.string() }).strict()).optional(),
  })
  .strict();

export const recipesSearchResponse = z
  .object({
    dataset: z.string(),
    results: z.array(compactRecipe),
    total: z.number().int().nonnegative(),
    offset: z.number().int().nonnegative(),
    more: z.boolean(),
    truncated: z.boolean().optional(),
    notEnforced: z.array(z.string()).optional(),
    /** Recipes left out because only steam or primitive machines run them (constraints.steam allows them). */
    steamOnly: z.number().int().positive().optional(),
  })
  .strict();

const dialOption = z
  .object({
    key: z.string(),
    label: z.string(),
    heat: z.number().optional(),
    parallels: z.number().optional(),
  })
  .strict();

export const machineDial = z
  .object({
    id: z.string(),
    label: z.string(),
    min: z.string(),
    default: z.string(),
    options: z.array(dialOption).optional(),
    numeric: z.object({ min: z.number(), max: z.number().optional(), step: z.number().optional() }).optional(),
  })
  .strict();

export const machineOption = z
  .object({
    id: z.string(),
    name: z.string(),
    kind: z.string(),
    minTier: z.string(),
    maxTier: z.string().optional(),
    tiers: z.array(z.string()).optional(),
    euT: z.number(),
    seconds: z.number(),
    parallels: z.number().optional(),
    perfectOverclock: z.boolean().optional(),
    heatOverclock: z.boolean().optional(),
    dials: z.array(machineDial),
  })
  .strict();

export const recipesGetResponse = z
  .object({
    dataset: z.string(),
    recipe: z
      .object({
        recipeId: z.string(),
        name: z.string(),
        map: z.string(),
        minTier: z.string(),
        euT: z.number(),
        seconds: z.number(),
        heat: z.number().optional(),
        circuit: z.string().optional(),
        inputs: z.array(recipeInput),
        outputs: z.array(recipeOutput),
        machines: z.array(machineOption),
      })
      .strict(),
  })
  .strict();

export const errorResponse = z
  .object({ error: z.object({ code: z.string(), message: z.string() }).strict() })
  .strict();
