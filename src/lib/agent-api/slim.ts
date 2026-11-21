import { z } from "zod";
import type { FactoryProject } from "@/lib/model/types";

/**
 * The SLIM SNAPSHOT: a plan as the in-game panel and the site send it to the assistant (`plans/import`), without
 * recipe bodies or runtime tables. Recipes are named by id and rebuilt from the dataset on import. The mod mirrors
 * this shape in Java, so it stays small and every field has one meaning (docs/agent-api.md, "The slim snapshot").
 *
 * What it carries per card is what the planner lets a player set on it that the solve reads: recipe (and the extra
 * recipes of a shared machine), machine, tier, dials, a pinned machine count, and the board it sits on. Anything else
 * on a card (oredict picks, power and hatch choices, colours) is not carried and takes the planner's default.
 */
export const SLIM_SNAPSHOT_VERSION = 1;

/** A card, drawer, wire or board id kept from the player's plan. */
export const THING_ID_PATTERN = /^[A-Za-z0-9_][A-Za-z0-9_.:-]{0,99}$/;

const thingId = z.string().regex(THING_ID_PATTERN, "not an id this API accepts.");
const position = z.object({ x: z.number().finite(), y: z.number().finite() }).strict();
const resource = z
  .object({ id: z.string().min(1).max(400), kind: z.enum(["item", "fluid", "aspect", "power"]) })
  .strict();
const port = z.string().min(1).max(600);

export const slimCard = z
  .object({
    id: thingId,
    recipeId: z.string().min(1).max(400),
    /** The machine handler id, only when it is not the recipe's default machine. */
    machine: z.string().min(1).max(200).optional(),
    tier: z.string().min(1).max(16),
    /** Dial id to option key: `heatingCoil` is the coil, the rest are the machine's tier parts. */
    dials: z.record(z.string().min(1).max(64), z.string().min(1).max(64)).optional(),
    /** The machine count the player pinned; absent means the solver chooses it. */
    count: z.number().finite().nonnegative().optional(),
    /** The pinned count is Exactly, not Up to. */
    exact: z.literal(true).optional(),
    /** A shared machine's further recipes, in order (sections 1..n). */
    sections: z.array(z.string().min(1).max(400)).min(1).max(16).optional(),
    position,
    /** The board (pocket) the card sits on; absent is the root board. */
    board: thingId.optional(),
  })
  .strict();

export const slimDrawer = z
  .object({
    id: thingId,
    resource,
    /** A drain's mode when it is not `product`: `byproduct` or `trash`. */
    mode: z.enum(["byproduct", "trash"]).optional(),
    position,
    board: thingId.optional(),
  })
  .strict();

export const slimWire = z
  .object({
    id: thingId,
    from: thingId,
    to: thingId,
    resource,
    /** The slot handles at each end, as the plan stores them (`output:item:<id>:<slot>`), when the plan names them. */
    fromPort: port.optional(),
    toPort: port.optional(),
  })
  .strict();

/** A rate pinned on one card's slot: plan mode's target. `port` is the slot handle (`output:item:<id>`). */
export const slimTarget = z
  .object({ card: thingId, port, rate: z.number().finite().positive() })
  .strict();

export const slimBoard = z
  .object({ id: thingId, name: z.string().max(200), parent: thingId.optional(), position })
  .strict();

export const slimSnapshot = z
  .object({
    version: z.literal(SLIM_SNAPSHOT_VERSION),
    plan: z.object({ id: z.string().min(1).max(200), name: z.string().max(200) }).strict(),
    boards: z.array(slimBoard).max(500).optional(),
    cards: z.array(slimCard).max(2000),
    drawers: z.array(slimDrawer).max(2000),
    wires: z.array(slimWire).max(8000),
    targets: z.array(slimTarget).max(500),
  })
  .strict();

export type SlimSnapshot = z.infer<typeof slimSnapshot>;
export type SlimCard = z.infer<typeof slimCard>;
export type SlimDrawer = z.infer<typeof slimDrawer>;
export type SlimWire = z.infer<typeof slimWire>;
export type SlimTarget = z.infer<typeof slimTarget>;

/** The slim snapshot of a plan. Pure; the same function the panel's Java mirror must match field for field. */
export function toSlimSnapshot(project: FactoryProject): SlimSnapshot {
  const cards: SlimCard[] = project.nodes.map((node) => {
    const dials: Record<string, string> = { ...node.machineConfigTiers };
    if (node.coilTier) {
      dials.heatingCoil = node.coilTier;
    }
    const sections = (node.extraRecipes ?? []).map((section) => section.recipeId);
    return {
      id: node.id,
      recipeId: node.recipeId,
      ...(node.machineHandlerId ? { machine: node.machineHandlerId } : {}),
      tier: String(node.overclockTier),
      ...(Object.keys(dials).length ? { dials } : {}),
      ...(node.solvePin !== undefined ? { count: node.solvePin } : {}),
      ...(node.solvePin !== undefined && node.solvePinExact ? { exact: true as const } : {}),
      ...(sections.length ? { sections } : {}),
      position: { x: node.position.x, y: node.position.y },
      ...(node.pocketId ? { board: node.pocketId } : {}),
    };
  });
  const targets: SlimTarget[] = project.nodes.flatMap((node) =>
    Object.entries(node.portRates ?? {})
      .filter(([, rate]) => Number.isFinite(rate) && rate > 0)
      // Card order, then each card's ports in code-unit order, so both sides write the same list.
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
      .map(([port, rate]) => ({ card: node.id, port, rate })),
  );
  const drawers: SlimDrawer[] = (project.storages ?? []).map((storage) => ({
    id: storage.id,
    resource: { id: storage.resourceId, kind: storage.kind },
    ...(storage.drainMode === "byproduct" || storage.drainMode === "trash" ? { mode: storage.drainMode } : {}),
    position: { x: storage.position.x, y: storage.position.y },
    ...(storage.pocketId ? { board: storage.pocketId } : {}),
  }));
  const wires: SlimWire[] = project.edges.map((edge) => ({
    id: edge.id,
    from: edge.source,
    to: edge.target,
    resource: { id: edge.resourceId, kind: edge.resourceKind },
    ...(edge.sourceHandle ? { fromPort: edge.sourceHandle } : {}),
    ...(edge.targetHandle ? { toPort: edge.targetHandle } : {}),
  }));
  const boards = (project.pockets ?? []).map((pocket) => ({
    id: pocket.id,
    name: pocket.name,
    ...(pocket.parentPocketId ? { parent: pocket.parentPocketId } : {}),
    position: { x: pocket.position.x, y: pocket.position.y },
  }));
  return {
    version: SLIM_SNAPSHOT_VERSION,
    plan: { id: project.id, name: project.name },
    ...(boards.length ? { boards } : {}),
    cards,
    drawers,
    wires,
    targets,
  };
}
