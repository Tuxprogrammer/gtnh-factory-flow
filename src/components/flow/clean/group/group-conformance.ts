import { viewEnvelope } from "@/test-support/view-corpus";
import { sumGroup, type GroupMember, type GroupSum } from "./group-sum";

/**
 * The group summary's shared cases (`conformance/view/group/cases.json`): the members the core's `GroupBuilder` takes
 * (`GroupInput`) and the sum the website's `sumGroup` made of them. No text goes through a measure, so the measure is
 * `none`.
 */

/** A member as the core takes it: `builds` is left out for a card that is not a machine. */
export interface GroupInputWire {
  members: Array<{
    host: string;
    builds?: number;
    euT: number;
    flows: Array<{ key: string; name: string; perSecond: number }>;
  }>;
}

export interface GroupCase {
  name: string;
  input: GroupInputWire;
  view: GroupSum;
}

const iron = (perSecond: number) => ({ key: "item:iron", name: "Iron Ingot", perSecond });
const water = (perSecond: number) => ({ key: "fluid:water", name: "Water", perSecond });
const gear = (perSecond: number) => ({ key: "item:gear", name: "Iron Gear", perSecond });
const power = (perSecond: number) => ({ key: "power", name: "EU", perSecond });
const machine = (
  host: string,
  builds: number,
  euT: number,
  flows: GroupMember["flows"] = [],
): GroupMember => ({ host, builds, euT, flows });
const flowsOf = (host: string, flows: GroupMember["flows"]): GroupMember => ({
  host,
  builds: null,
  euT: 0,
  flows,
});

const SPECS: Array<{ name: string; members: GroupMember[] }> = [
  { name: "empty group", members: [] },
  { name: "one machine, nothing moving", members: [machine("a", 1, 30)] },
  {
    name: "one machine with an input and an output",
    members: [machine("a", 2, 60, [iron(4), gear(-2)])],
  },
  {
    name: "a fractional count rounds up",
    members: [machine("a", 1.2, 10), machine("b", 0.4, 4)],
  },
  {
    name: "a count a hair over a whole number reads as the whole number",
    members: [machine("a", 3.0000000001, 0)],
  },
  {
    name: "a count a hair over its rounding step still rounds up",
    members: [machine("a", 3.000001, 0)],
  },
  {
    name: "a shared machine counts once for all the recipes on it",
    members: [
      machine("host", 0.75, 24),
      flowsOf("host", [iron(3), gear(-1)]),
      flowsOf("host", [iron(1), water(-1)]),
    ],
  },
  {
    name: "a host named twice as a machine is built once",
    members: [machine("host", 2, 20), machine("host", 5, 50)],
  },
  {
    name: "a card that is no machine adds flows and no machines",
    members: [flowsOf("drawer", [iron(-5)]), machine("a", 1, 8, [iron(5)])],
  },
  {
    name: "what one card makes the next one uses, and a balance reads as nothing",
    members: [
      machine("a", 1, 16, [iron(-6), gear(2)]),
      machine("b", 1, 16, [iron(6), gear(-2)]),
    ],
  },
  {
    name: "a net flow under the threshold reads as nothing",
    members: [machine("a", 1, 0, [iron(0.0000005)]), machine("b", 1, 0, [iron(0)])],
  },
  {
    name: "a net flow just over the threshold shows",
    members: [machine("a", 1, 0, [iron(0.000002)])],
  },
  {
    name: "inputs and outputs are busiest first",
    members: [
      machine("a", 1, 0, [iron(1), water(9), gear(-3), power(-7), { key: "item:dust", name: "Dust", perSecond: -12 }]),
    ],
  },
  {
    name: "equal flows keep the order the cards met them in",
    members: [machine("a", 1, 0, [iron(2), water(2), { key: "item:sand", name: "Sand", perSecond: 2 }])],
  },
  {
    name: "more outputs than inputs makes the longer list the row count",
    members: [machine("a", 1, 0, [iron(1), gear(-1), water(-2), power(-3)])],
  },
  {
    name: "a resource keeps the name the first card gave it",
    members: [
      machine("a", 1, 0, [{ key: "item:x", name: "First", perSecond: 1 }]),
      machine("b", 1, 0, [{ key: "item:x", name: "Second", perSecond: 1 }]),
    ],
  },
  {
    name: "a power producer sums negative EU/t",
    members: [machine("a", 1, 120), machine("gen", 2, -80)],
  },
  {
    name: "fractional flows add",
    members: [machine("a", 1, 0, [iron(0.25)]), machine("b", 1, 0, [iron(0.5), water(0.125)])],
  },
  {
    name: "a big group",
    members: Array.from({ length: 12 }, (_, i) =>
      machine(`m${i}`, i + 0.5, 8 * (i + 1), [
        { key: `item:r${i}`, name: `Resource ${i}`, perSecond: i % 2 === 0 ? i + 1 : -(i + 1) },
        iron(0.5),
      ]),
    ),
  },
];

function wire(members: readonly GroupMember[]): GroupInputWire {
  return {
    members: members.map((m) => ({
      host: m.host,
      ...(m.builds === null ? {} : { builds: m.builds }),
      euT: m.euT,
      flows: m.flows,
    })),
  };
}

export function groupCases(): GroupCase[] {
  return SPECS.map((spec) => ({ name: spec.name, input: wire(spec.members), view: sumGroup(spec.members) }));
}

/** The members of a stored case as the website's own sum takes them. */
export function groupMembersOf(input: GroupInputWire): GroupMember[] {
  return input.members.map((m) => ({ host: m.host, builds: m.builds ?? null, euT: m.euT, flows: m.flows }));
}

export function viewFiles(): Record<string, string> {
  return { "group/cases.json": viewEnvelope("group", "cases", "none", { cases: groupCases() }) };
}
