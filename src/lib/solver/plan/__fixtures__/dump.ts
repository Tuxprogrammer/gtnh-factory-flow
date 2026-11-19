/**
 * A website `SolveInput` as the Java kernel reads it: every id a UUID (`withUuids`) and the mod's dump format
 * (`toDump`, what `SolveInputJson.read` and `load.ts` read). Used by the two exports both engines answer
 * (`parity/up-to-export.test.ts`, `website-export.ts`). Node-only (crypto); never import this from app code.
 */
import type { Id, MachineIn, PortRef, SolveInput } from "../types";
import { nameUuidFromBytes } from "./load";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A hand-built plan's id as a UUID (`upto:<id>` hashed as Java's `UUID.nameUUIDFromBytes`); a UUID stays itself. */
export function uuidOf(id: Id): Id {
  return UUID.test(id) ? id : nameUuidFromBytes(Buffer.from(`upto:${id}`, "utf8"));
}

/** A spares key (`node:i:port`, or several joined by commas) with its node ids made UUIDs. */
function sparesKey(key: string): string {
  return key
    .split(",")
    .map((part) => {
      const io = part.lastIndexOf(":", part.lastIndexOf(":") - 1);
      return io < 0 ? part : `${uuidOf(part.slice(0, io))}${part.slice(io)}`;
    })
    .join(",");
}

/** The plan with every id a UUID (machines, wires, pools, drawers and their links, pins, the stored choice, spares). */
export function withUuids(i: SolveInput): SolveInput {
  const ref = (r: PortRef): PortRef => ({ ...r, node: uuidOf(r.node) });
  return {
    ...i,
    choice: i.choice ? i.choice.map(ref) : null,
    machines: i.machines.map((m) => ({ ...m, id: uuidOf(m.id) })),
    edges: i.edges.map((e) => ({
      ...e,
      id: uuidOf(e.id),
      source: uuidOf(e.source),
      target: uuidOf(e.target),
    })),
    pools: i.pools.map((p) => ({ ...p, machines: p.machines.map(uuidOf) })),
    drawers: i.drawers.map((d) => ({
      ...d,
      id: uuidOf(d.id),
      links: d.links.map((l) => ({ ...l, node: uuidOf(l.node) })),
      ...(d.takers ? { takers: d.takers.map((l) => ({ ...l, node: uuidOf(l.node) })) } : {}),
    })),
    extentPins: Object.fromEntries(Object.entries(i.extentPins).map(([k, v]) => [uuidOf(k), v])),
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
