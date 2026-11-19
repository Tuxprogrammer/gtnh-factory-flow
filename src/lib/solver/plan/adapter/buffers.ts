import { getStorageRoles } from "../../../model/storage-role";
import type { FactoryEdge, FactoryProject, FactoryStorage } from "../../../model/types";
import { compareIds } from "../ids";

export interface BufferFreeGraph {
  edges: FactoryEdge[];
  storages: FactoryStorage[];
  /**
   * For each edge of `edges`, the ids of the project's own edges it stands
   * for (itself unless a buffer was joined away). A joined wire's flow is the
   * flow of every wire it covers.
   */
  covers: Map<string, string[]>;
  /** Buffers removed (a ratio buffer's shares are gone with it). */
  removed: string[];
  /** Buffer drawers wired to each other, each chain taken as one buffer (`mergeBufferChains`). */
  chains: BufferChain[];
}

/** Buffer drawers wired only to each other and to cards: the solver sees one buffer, the board keeps every drawer. */
export interface BufferChain {
  /** The drawer the solver sees for the whole chain (the first member by id). */
  drawerId: string;
  /** The player's wires between the chain's drawers, which the solver never sees. */
  links: FactoryEdge[];
  /** Each card wire of the chain (now on `drawerId`): the drawer it was drawn to, and whether it runs into it. */
  wires: Map<string, { drawer: string; into: boolean }>;
}

/**
 * Whether a buffer drawer (fed and drawn from) stays a buffer in plan mode (`docs/buffer-drawers.md`): every wire on
 * it runs to a card, none to another drawer. The solver then takes it as a BUFFER drawer. Any other buffer is joined
 * into the wires it stands for.
 */
export function keepsBuffer(storageId: string, edges: readonly FactoryEdge[], storageIds: ReadonlySet<string>): boolean {
  for (const edge of edges) {
    if (edge.source !== storageId && edge.target !== storageId) continue;
    const other = edge.source === storageId ? edge.target : edge.source;
    if (other === storageId || storageIds.has(other)) return false;
  }
  return true;
}

/**
 * A buffer drawer the solver cannot take as one (see `keepsBuffer`) is a junction: every wire into it is joined to
 * every wire out of it, one buffer at a time (a chain still joins its ends). The same rule as the conversion of a Build
 * plan (`convert.ts`), but remembering which of the player's wires each joined wire stands for, so their flows can be
 * shown.
 */
export function expandBuffers(project: FactoryProject): BufferFreeGraph {
  const covers = new Map<string, string[]>(project.edges.map((edge) => [edge.id, [edge.id]]));
  const removed: string[] = [];
  const roles = getStorageRoles({ ...project, poolMode: undefined });
  const merged = mergeBufferChains(project.edges, project.storages ?? [], roles);
  const { chains } = merged;
  const storages = merged.storages;
  const storageIds = new Set(storages.map((storage) => storage.id));
  const joins = storages.filter(
    (storage) =>
      roles.get(storage.id) === "buffer" && !keepsBuffer(storage.id, merged.edges, storageIds),
  );
  if (joins.length === 0) {
    return { edges: merged.edges, storages, covers, removed, chains };
  }

  let edges = merged.edges;
  const gone = new Set<string>();
  for (const storage of joins) {
    const feeders = edges.filter((e) => e.target === storage.id && e.source !== storage.id);
    const takers = edges.filter((e) => e.source === storage.id && e.target !== storage.id);
    edges = edges.filter((e) => e.source !== storage.id && e.target !== storage.id);
    for (const feeder of feeders) {
      for (const taker of takers) {
        const id = `${feeder.id}>${taker.id}`;
        const joined: FactoryEdge = {
          id,
          source: feeder.source,
          target: taker.target,
          sourceHandle: feeder.sourceHandle,
          targetHandle: taker.targetHandle,
          resourceKind: feeder.resourceKind,
          resourceId: feeder.resourceId,
        };
        covers.set(id, [...new Set([...(covers.get(feeder.id) ?? []), ...(covers.get(taker.id) ?? [])])]);
        edges.push(joined);
      }
    }
    gone.add(storage.id);
    removed.push(storage.id);
  }
  return {
    edges,
    storages: storages.filter((storage) => !gone.has(storage.id)),
    covers,
    removed,
    chains,
  };
}

/**
 * Buffer drawers wired to each other (a drawer feeding a drawer that feeds cards) are one buffer, as a single drawer
 * between the same cards would be: its makers' surplus is banked, never forced out (docs/up-to.md, "Buffers"). A chain
 * is taken whole only when its drawers are wired to nothing but each other and cards; one touching a source, product
 * or trash drawer is joined into wires as before, so a source feeding a buffer still covers its users' shortfall.
 * Every wire of the chain keeps its id: wires to cards move to the chain's first drawer, wires between its drawers
 * leave the solver's graph and get their flows back from `chainLinkFlows`.
 */
function mergeBufferChains(
  edges: FactoryEdge[],
  storages: FactoryStorage[],
  roles: ReadonlyMap<string, string>,
): { edges: FactoryEdge[]; storages: FactoryStorage[]; chains: BufferChain[] } {
  const ids = new Set(storages.map((storage) => storage.id));
  const parent = new Map<string, string>();
  const find = (id: string): string => {
    let root = id;
    while (parent.get(root) !== root) root = parent.get(root)!;
    parent.set(id, root);
    return root;
  };
  for (const storage of storages)
    if (roles.get(storage.id) === "buffer") parent.set(storage.id, storage.id);
  // A chain wired to any drawer that is not one of its buffers is no chain here.
  const spoiled = new Set<string>();
  for (const edge of edges) {
    const from = parent.has(edge.source);
    const to = parent.has(edge.target);
    if (from && to && edge.source !== edge.target) parent.set(find(edge.source), find(edge.target));
  }
  for (const edge of edges) {
    if (parent.has(edge.source) && ids.has(edge.target) && !parent.has(edge.target))
      spoiled.add(find(edge.source));
    if (parent.has(edge.target) && ids.has(edge.source) && !parent.has(edge.source))
      spoiled.add(find(edge.target));
  }
  const members = new Map<string, string[]>();
  for (const id of parent.keys()) {
    const root = find(id);
    if (!spoiled.has(root)) members.set(root, [...(members.get(root) ?? []), id]);
  }
  const chainOf = new Map<string, BufferChain>();
  for (const list of members.values()) {
    if (list.length < 2) continue;
    const chain: BufferChain = {
      drawerId: [...list].sort(compareIds)[0]!,
      links: [],
      wires: new Map(),
    };
    for (const id of list) chainOf.set(id, chain);
  }
  if (chainOf.size === 0) return { edges, storages, chains: [] };

  const rewired: FactoryEdge[] = [];
  for (const edge of edges) {
    const from = chainOf.get(edge.source);
    const to = chainOf.get(edge.target);
    if (from && from === to) {
      from.links.push(edge);
    } else if (from) {
      from.wires.set(edge.id, { drawer: edge.source, into: false });
      rewired.push({ ...edge, source: from.drawerId });
    } else if (to) {
      to.wires.set(edge.id, { drawer: edge.target, into: true });
      rewired.push({ ...edge, target: to.drawerId });
    } else {
      rewired.push(edge);
    }
  }
  return {
    edges: rewired,
    storages: storages.filter(
      (storage) => (chainOf.get(storage.id)?.drawerId ?? storage.id) === storage.id,
    ),
    chains: [...new Set(chainOf.values())],
  };
}

/**
 * What each wire between a chain's drawers carries, from what its card wires carry: a drawer at the end of the chain
 * passes its own surplus (what its makers put in beyond what its users take) along its one link, and so on inward.
 * A link closing a loop of drawers has no single answer and reads 0.
 */
export function chainLinkFlows(
  chain: BufferChain,
  flowOf: (edgeId: string) => number,
): Map<string, number> {
  const balance = new Map<string, number>();
  for (const [edgeId, { drawer, into }] of chain.wires) {
    balance.set(drawer, (balance.get(drawer) ?? 0) + (into ? 1 : -1) * flowOf(edgeId));
  }
  const flows = new Map<string, number>();
  const open = [...chain.links].sort((a, b) => compareIds(a.id, b.id));
  for (;;) {
    const degree = new Map<string, number>();
    for (const link of open) {
      degree.set(link.source, (degree.get(link.source) ?? 0) + 1);
      degree.set(link.target, (degree.get(link.target) ?? 0) + 1);
    }
    const index = open.findIndex(
      (link) => degree.get(link.source) === 1 || degree.get(link.target) === 1,
    );
    if (index < 0) break;
    const link = open.splice(index, 1)[0]!;
    const leafIsSource = degree.get(link.source) === 1;
    const leaf = leafIsSource ? link.source : link.target;
    const surplus = balance.get(leaf) ?? 0;
    const flow = Math.max(0, leafIsSource ? surplus : -surplus);
    flows.set(link.id, flow);
    balance.set(link.source, (balance.get(link.source) ?? 0) - flow);
    balance.set(link.target, (balance.get(link.target) ?? 0) + flow);
  }
  for (const link of open) flows.set(link.id, 0);
  return flows;
}
