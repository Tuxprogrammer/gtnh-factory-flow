/**
 * The solver's input and answer as data that crosses between the website and the mod, for
 * `conformance/schema/solve-input.schema.json` and `solve-digest.schema.json`.
 *
 * The input is `SolveInput` (what the mod's `SolveInputJson` writes and the corpus holds); `solveInputSchema` is checked
 * against the type in `types.ts` at compile time, so the two cannot drift. The answer that crosses is not the engines'
 * own `Answer` (each side has its class) but the digest both write to compare them (`__fixtures__/digest.ts`, the mod's
 * `SolverCorpus.digest`): ports as `{nodeId, portIndex, input}`, sets sorted, the Up to read-out with every field. The
 * goldens and the Up to plans hold it. Nothing at run time reads these schemas: the solver does not validate its input.
 */
import { z } from "zod";

import type { SolveInput } from "./types";

const id = z.string();
const nat = z.number().int().min(0);

/** A port index map: JSON object keys are the decimal index. */
const indexRates = z.record(z.string().regex(/^\d+$/), z.number()) as unknown as z.ZodType<Record<number, number>>;

export const portInSchema = z.object({
  amount: z.number(),
  chance: z.number(),
  multiplier: z.number(),
  resource: z.number().int(),
  name: z.string(),
  free: z.boolean(),
});

export const machineInSchema = z.object({
  id,
  name: z.string(),
  durationTicks: z.number(),
  energyPerTick: z.number(),
  throughputFactor: z.number(),
  inputs: z.array(portInSchema),
  outputs: z.array(portInSchema),
  countFixed: z.boolean(),
  machineCount: z.number(),
  targetOutputRates: indexRates,
  targetInputRates: indexRates,
});

export const edgeInSchema = z.object({
  id,
  source: id,
  sourceOutput: nat,
  target: id,
  targetInput: nat,
});

export const poolInSchema = z.object({
  machines: z.array(id),
  capacity: z.number(),
  exact: z.boolean(),
  upTo: z.boolean().optional(),
});

export const drawerKindSchema = z.enum(["SOURCE", "PRODUCT", "BYPRODUCT", "TRASH", "BUFFER"]);
export const drawerRuleSchema = z.enum(["ANY", "AT_LEAST", "EXACTLY", "AT_MOST"]);

export const drawerLinkSchema = z.object({ node: id, port: nat });

export const drawerInSchema = z.object({
  id,
  label: z.string(),
  kind: drawerKindSchema,
  rule: drawerRuleSchema,
  rate: z.number(),
  links: z.array(drawerLinkSchema),
  takers: z.array(drawerLinkSchema).optional(),
});

export const portRefSchema = z.object({ node: id, port: nat, input: z.boolean() });

export const solveInputSchema = z.object({
  mode: z.literal("AUTO"),
  choice: z.array(portRefSchema).nullable().optional(),
  machines: z.array(machineInSchema),
  edges: z.array(edgeInSchema),
  pools: z.array(poolInSchema),
  drawers: z.array(drawerInSchema),
  extentPins: z.record(id, z.number()),
  spares: z.record(z.string(), z.record(z.string(), z.number())),
});

// -- the answer, as the digest both engines write ------------------------------------------------------------------

export const solverMessageSchema = z.enum([
  "NO_PIN",
  "EMPTY_GRAPH",
  "BALANCE_FAILED",
  "STAGE_FAILED",
  "VALIDATION_FAILED",
  "PIN_CONFLICT",
  "PIN_DROPPED",
  "PIN_COUNT",
  "PIN_TARGET",
  "PIN_EXTENT",
  "PIN_UP_TO",
  "SOLVER_UNSATISFIABLE",
  "SOLVER_BUDGET",
  "SOLVER_NOT_CONSERVING",
  "OVERSHOOTS_TARGET",
  "DRAWER_NOT_CONNECTED",
  "DRAWER_WIRED_IGNORED",
  "DRAWER_ONLY_UPPER_BOUNDS",
  "DRAWER_UNMET",
  "DRAWER_PORT_TAKEN",
  "DRAWER_LIMITED_BY",
  "RULE_ANY",
  "RULE_AT_LEAST",
  "RULE_EXACTLY",
  "RULE_AT_MOST",
  "LIMIT_PIN",
  "LIMIT_DRAWER",
  "LIMIT_GROUP",
  "WIRING_IMPORT",
  "WIRING_UNLINKED",
  "CHOICE_NO_LONGER_FITS",
  "CHOICE_NEEDS_MORE_GATES",
  "SHOWING_CLOSEST",
  "STOPPED_EARLY",
]);

type Note = { message: z.infer<typeof solverMessageSchema>; args: Array<string | number | Note | Note[]> };

/** A note's arguments are text, numbers, or notes (a note inside a note, or a list of them). */
export const noteSchema: z.ZodType<Note> = z.lazy(() =>
  z.object({
    message: solverMessageSchema,
    args: z.array(z.union([z.string(), z.number(), noteSchema, z.array(noteSchema)])),
  }),
);

/** A port in the digest: not a `PortRef`, whose fields are `node`, `port` and `input`. */
export const digestPortSchema = z.object({ nodeId: id, portIndex: nat, input: z.boolean() });
export const digestExternalSchema = z.object({ port: digestPortSchema, ratePerSecond: z.number() });
export const digestKeySchema = z.object({ gateAnchors: z.array(digestPortSchema) });

export const digestShortfallSchema = z.object({
  drawer: id,
  rule: drawerRuleSchema,
  target: z.number(),
  reachable: z.number(),
  limitingNodes: z.array(id),
  limitingDrawers: z.array(id),
  limitingGroups: z.number().int(),
});

export const digestDrawersSchema = z.object({
  rates: z.record(id, z.number()),
  unmet: z.array(id),
  shortfalls: z.array(digestShortfallSchema),
  ports: z.record(id, z.array(digestExternalSchema)),
  banked: z.record(id, z.number()).optional(),
  /** Per banking buffer, what each maker port puts in beyond what its takers take; present exactly when `banked` is. */
  spills: z.record(id, z.array(digestExternalSchema)).optional(),
});

export const digestUpToSchema = z.object({
  node: id,
  machines: z.array(id),
  capacity: z.number(),
  reached: z.number(),
  reason: z.enum(["FULL", "BOTTLENECK", "STARVED", "CLOGGED", "UNUSED", "LIMITED"]).nullable(),
  port: digestPortSchema.nullable(),
  tied: z.array(digestPortSchema),
  wants: z.number().nullable(),
  upstream: z.array(id),
});

export const digestUpToLoopSchema = z.object({
  kind: z.enum(["DEAD_LOOP", "CLOG_LOCK"]),
  nodes: z.array(id),
  ports: z.array(digestPortSchema),
});

export const digestLeftoverSchema = z.object({
  key: z.string(),
  importing: z.boolean(),
  bearers: z.array(
    z.object({
      anchor: digestPortSchema,
      flows: z.array(digestExternalSchema),
      max: z.number(),
      share: z.number(),
      solverPick: z.boolean(),
    }),
  ),
  custom: z.boolean(),
});

export const digestSolutionSchema = z.object({
  extentsPerSecond: z.record(id, z.number()),
  machineCounts: z.record(id, z.number()),
  edgeFlowsPerSecond: z.record(id, z.number()),
  gatedSources: z.array(digestExternalSchema),
  gatedSinks: z.array(digestExternalSchema),
  terminalInputs: z.array(digestExternalSchema),
  terminalOutputs: z.array(digestExternalSchema),
  /** Absent in the one golden recorded before the field existed (platline-x10, a bench case). */
  unneeded: z.array(id).optional(),
  openGates: z.number().int(),
  externalQuantity: z.number(),
  totalInternalFlow: z.number(),
  notes: z.array(noteSchema),
  key: digestKeySchema,
  drawers: digestDrawersSchema,
  spares: z.array(digestLeftoverSchema),
  upTo: z.array(digestUpToSchema).optional(),
  upToLoops: z.array(digestUpToLoopSchema).optional(),
});

export const digestAlternativesSchema = z.object({
  chosen: digestKeySchema.nullable(),
  options: z.array(
    z.object({
      key: digestKeySchema,
      replaces: digestPortSchema,
      opens: digestPortSchema,
      externals: z.array(digestExternalSchema),
      rank: z.enum(["DEFAULT", "EQUALLY_VALID", "BUYS_MORE", "SELLS_MORE", "MOVES_MORE", "MOVES_LESS"]),
    }),
  ),
  complete: z.boolean(),
  notes: z.array(noteSchema),
});

/** What both engines write for a solved plan: `{solution, alternatives}`. */
export const solveDigestSchema = z.object({
  solution: digestSolutionSchema,
  alternatives: digestAlternativesSchema,
});

// -- the input and its type must say the same thing -----------------------------------------------------------------

type Same<A, B> = [A] extends [B] ? ([B] extends [A] ? true : false) : false;
const same = <T extends true>(): T | undefined => undefined;
same<Same<z.infer<typeof solveInputSchema>, SolveInput>>();
