/**
 * A website `SolveInput` as the Java kernel reads it: every id a UUID (`withUuids`) and the mod's dump format
 * (`toDump`, what `SolveInputJson.read` and `load.ts` read). Used by the two exports both engines answer
 * (`parity/up-to-export.test.ts`, `tools/conformance/solver/export.capture.ts`). Node-only (crypto); never import this from app code.
 */
import type { Id, MachineIn, PortRef, SolveInput } from "../types";
import { compareId, nameUuidFromBytes } from "./load";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A hand-built plan's id as a UUID (`upto:<id>` hashed as Java's `UUID.nameUUIDFromBytes`); a UUID stays itself. */
export function uuidOf(id: Id): Id {
  return UUID.test(id) ? id : nameUuidFromBytes(Buffer.from(`upto:${id}`, "utf8"));
}

/** A spares key (`node:i:port`, or a leftover's bearers joined by "|") with its node ids renamed, its bearers sorted again by their new text as both engines sort them. */
function keyWith(key: string, rename: (id: Id) => Id): string {
  return key
    .split("|")
    .map((part) => {
      const io = part.lastIndexOf(":", part.lastIndexOf(":") - 1);
      return io < 0 ? part : `${rename(part.slice(0, io))}${part.slice(io)}`;
    })
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
    .join("|");
}

/** The plan with every id a UUID (machines, wires, pools, drawers and their links, pins, the stored choice, spares). */
export function withUuids(i: SolveInput): SolveInput {
  return renamed(i, uuidOf);
}

/**
 * The plan with its ids renamed in order: the k-th smallest (Java `UUID` order, `compareId`) becomes
 * `00000000-0000-4000-8000-<k as 12 hex digits>`, which sorts the same way, so the solve reads the plan as it was. A
 * test that makes random ids then writes the same case on every run. Every id must already be a UUID (`withUuids`).
 */
export function withOrderedIds(i: SolveInput): SolveInput {
  const ids = new Set<Id>();
  const add = (id: Id) => ids.add(id);
  for (const m of i.machines) add(m.id);
  for (const e of i.edges) [e.id, e.source, e.target].forEach(add);
  for (const p of i.pools) p.machines.forEach(add);
  for (const d of i.drawers) {
    add(d.id);
    for (const l of [...d.links, ...(d.takers ?? [])]) add(l.node);
  }
  for (const r of i.choice ?? []) add(r.node);
  Object.keys(i.extentPins).forEach(add);
  const keyNodes = (key: string) =>
    key.split("|").flatMap((part) => {
      const io = part.lastIndexOf(":", part.lastIndexOf(":") - 1);
      return io < 0 ? [] : [part.slice(0, io)];
    });
  for (const [k, bearers] of Object.entries(i.spares ?? {}))
    for (const key of [k, ...Object.keys(bearers)]) keyNodes(key).forEach(add);
  const order = [...ids].sort(compareId);
  const to = new Map(
    order.map((id, k) => [id, `00000000-0000-4000-8000-${k.toString(16).padStart(12, "0")}`]),
  );
  return renamed(i, (id) => to.get(id) ?? id);
}

function renamed(i: SolveInput, rename: (id: Id) => Id): SolveInput {
  const sparesKey = (key: string) => keyWith(key, rename);
  const ref = (r: PortRef): PortRef => ({ ...r, node: rename(r.node) });
  return {
    ...i,
    choice: i.choice ? i.choice.map(ref) : null,
    machines: i.machines.map((m) => ({ ...m, id: rename(m.id) })),
    edges: i.edges.map((e) => ({
      ...e,
      id: rename(e.id),
      source: rename(e.source),
      target: rename(e.target),
    })),
    pools: i.pools.map((p) => ({ ...p, machines: p.machines.map(rename) })),
    drawers: i.drawers.map((d) => ({
      ...d,
      id: rename(d.id),
      links: d.links.map((l) => ({ ...l, node: rename(l.node) })),
      ...(d.takers ? { takers: d.takers.map((l) => ({ ...l, node: rename(l.node) })) } : {}),
    })),
    extentPins: Object.fromEntries(Object.entries(i.extentPins).map(([k, v]) => [rename(k), v])),
    spares: Object.fromEntries(
      Object.entries(i.spares ?? {}).map(([k, bearers]) => [
        sparesKey(k),
        Object.fromEntries(Object.entries(bearers).map(([b, share]) => [sparesKey(b), share])),
      ]),
    ),
  };
}

const rates = (r: Record<number, number>) =>
  Object.fromEntries(Object.entries(r ?? {}).map(([k, v]) => [String(k), v]));

/** The mod's dump format (`SolveInputJson.write`). */
export function toDump(i: SolveInput): Record<string, unknown> {
  const machine = (m: MachineIn) => ({
    id: m.id,
    name: m.name,
    durationTicks: m.durationTicks,
    energyPerTick: m.energyPerTick,
    throughputFactor: m.throughputFactor,
    inputs: m.inputs.map((p) => ({ ...p })),
    outputs: m.outputs.map((p) => ({ ...p })),
    countFixed: m.countFixed,
    machineCount: m.machineCount,
    targetOutputRates: rates(m.targetOutputRates),
    targetInputRates: rates(m.targetInputRates),
  });
  return {
    mode: i.mode,
    ...(i.choice
      ? { choice: i.choice.map((r) => ({ node: r.node, port: r.port, input: r.input })) }
      : {}),
    machines: i.machines.map(machine),
    edges: i.edges.map((e) => ({ ...e })),
    pools: i.pools.map((p) => ({
      machines: [...p.machines],
      capacity: p.capacity,
      exact: p.exact,
      ...(p.upTo ? { upTo: true } : {}),
    })),
    drawers: i.drawers.map((d) => ({
      id: d.id,
      label: d.label,
      kind: d.kind,
      rule: d.rule,
      rate: d.rate,
      links: d.links.map((l) => ({ node: l.node, port: l.port })),
      ...(d.takers && d.takers.length > 0
        ? { takers: d.takers.map((l) => ({ node: l.node, port: l.port })) }
        : {}),
    })),
    extentPins: { ...i.extentPins },
    spares: JSON.parse(JSON.stringify(i.spares ?? {})) as unknown,
  };
}
