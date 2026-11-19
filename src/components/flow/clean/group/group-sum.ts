/**
 * The group summary's sum, over members a host has already read from its plan (docs/groups.md, "What the summary
 * says"). It is the web side of the core's `GroupBuilder` (`com.gtnhplanner.core.view.group`) and the shared cases
 * (`conformance/view/group/cases.json`) hold the two together: a machine card counts once however many members name
 * its host, its count rounds up, EU/t adds, and a resource's net flow is what is used minus what is made, busiest
 * first.
 */

const EPS = 1e-6;

/** One card of the group. `builds` is null for a card that is not a machine. */
export interface GroupMember {
  host: string;
  builds: number | null;
  euT: number;
  flows: { key: string; name: string; perSecond: number }[];
}

export interface GroupSumFlow {
  key: string;
  name: string;
  perSecond: number;
}

export interface GroupSum {
  machines: number;
  euT: number;
  inputs: GroupSumFlow[];
  outputs: GroupSumFlow[];
  rows: number;
}

export function sumGroup(members: readonly GroupMember[]): GroupSum {
  let machines = 0;
  let euT = 0;
  const hosts = new Set<string>();
  const net = new Map<string, { name: string; value: number }>();
  for (const member of members) {
    if (member.builds !== null && !hosts.has(member.host)) {
      hosts.add(member.host);
      machines += Math.ceil(member.builds - 1e-9);
    }
    euT += member.euT;
    for (const flow of member.flows) {
      const entry = net.get(flow.key);
      if (entry) entry.value += flow.perSecond;
      else net.set(flow.key, { name: flow.name, value: flow.perSecond });
    }
  }
  const inputs: GroupSumFlow[] = [];
  const outputs: GroupSumFlow[] = [];
  for (const [key, entry] of net) {
    if (Math.abs(entry.value) <= EPS) continue;
    (entry.value > 0 ? inputs : outputs).push({
      key,
      name: entry.name,
      perSecond: Math.abs(entry.value),
    });
  }
  // Busiest first; ties keep the order the members met them in (the sort is stable).
  const busiest = (a: GroupSumFlow, b: GroupSumFlow) => b.perSecond - a.perSecond;
  inputs.sort(busiest);
  outputs.sort(busiest);
  return { machines, euT, inputs, outputs, rows: Math.max(1, inputs.length, outputs.length) };
}
