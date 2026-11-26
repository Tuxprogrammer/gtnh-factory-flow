import { canonicalizeResourceHandleId } from "@/lib/model/edge-identity";
import type { FactoryEdge } from "@/lib/model/types";

/**
 * Channels: flat edges carrying one resource between the same two visible cards draw as ONE wire. A clean card's
 * wires dock anywhere on its rim, not at a port, so two wires there would be one line drawn twice. The first flat
 * edge is the representative and the rest are skipped; the drawn wire stands for all of them: its rate is their sum,
 * hovering it lights every member's ports, a checklist tick covers every member and deleting it deletes them all.
 * Handles stay out of the key on purpose: two slots of one material, or two recipes of a shared machine (each with
 * its own `r<n>:` port), between two cards are still one wire on the board.
 */
export interface WireChannels {
  /** Members that ride their representative's wire: nothing to draw. */
  skip: Set<string>;
  /** Representative id to the channel's summed rates, and whether it carries a fluid (its own scale). */
  totals: Map<string, { transferred: number; demand: number; fluid: boolean }>;
  /** Representative id to every flat edge id the drawn wire stands for, itself included. */
  idsByRepresentative: Map<string, string[]>;
  /** Representative id to every member's port keys (`<card>|<canonical handle>`), on ends that stand as themselves. */
  portKeysByRepresentative: Map<string, string[]>;
}

export function groupWireChannels(
  edges: readonly FactoryEdge[],
  representativeOf: (id: string) => string | undefined,
  transferredOf: (id: string) => number,
  demandOf: (edge: FactoryEdge) => number,
): WireChannels {
  const groups = new Map<string, FactoryEdge[]>();
  for (const edge of edges) {
    const sourceRep = representativeOf(edge.source);
    const targetRep = representativeOf(edge.target);
    if (!sourceRep || !targetRep || sourceRep === targetRep) {
      continue;
    }
    const key = [sourceRep, targetRep, edge.resourceKind, edge.resourceId].join("|");
    const group = groups.get(key);
    if (group) group.push(edge);
    else groups.set(key, [edge]);
  }
  const channels: WireChannels = {
    skip: new Set(),
    totals: new Map(),
    idsByRepresentative: new Map(),
    portKeysByRepresentative: new Map(),
  };
  for (const group of groups.values()) {
    if (group.length < 2) {
      continue;
    }
    const representativeId = group[0]!.id;
    channels.idsByRepresentative.set(
      representativeId,
      group.map((edge) => edge.id),
    );
    let transferred = 0;
    let demand = 0;
    const ports = new Set<string>();
    for (const edge of group) {
      transferred += transferredOf(edge.id);
      demand += demandOf(edge);
      if (edge.id !== representativeId) channels.skip.add(edge.id);
      const sourceHandle = canonicalizeResourceHandleId(edge.sourceHandle);
      const targetHandle = canonicalizeResourceHandleId(edge.targetHandle);
      // An end folded into a minimized board has no ports to light.
      if (sourceHandle && representativeOf(edge.source) === edge.source) ports.add(`${edge.source}|${sourceHandle}`);
      if (targetHandle && representativeOf(edge.target) === edge.target) ports.add(`${edge.target}|${targetHandle}`);
    }
    channels.totals.set(representativeId, {
      transferred,
      demand,
      fluid: group[0]!.resourceKind === "fluid",
    });
    channels.portKeysByRepresentative.set(representativeId, [...ports]);
  }
  return channels;
}

export type ShownWire = { id: string; data?: { bundle?: { edgeIds: string[] }; mergedEdgeIds?: string[] } };

/** Every flat edge a drawn wire stands for, for a checklist tick: its bundle, and each one's channel. */
export function checklistWireIds(shownEdges: readonly ShownWire[], id: string): string[] {
  const shown = (edgeId: string) => shownEdges.find((entry) => entry.id === edgeId);
  return (shown(id)?.data?.bundle?.edgeIds ?? [id]).flatMap(
    (member) => shown(member)?.data?.mergedEdgeIds ?? [member],
  );
}
