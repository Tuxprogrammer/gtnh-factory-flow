/**
 * The card shadow's inputs and comparison (restructure adoption step one): a card's facts as `useCleanCardModel` hands
 * them to `cleanCardView`, recorded from the website's own tests (tools/audits/card-test-inputs) and replayed through
 * the website's builders and through the compiled core (`ViewExports`) by card-shadow.test.ts.
 *
 * Node only (tests and the recorder); never import this from app code.
 */
import type { CardInputDials } from "@/lib/core/records.generated";
import {
  setActivePowerDisplayUnit,
  setActiveRateUnit,
  type PowerDisplayUnit,
  type RateUnit,
} from "@/lib/format/rate-unit";
import { cardDials, cardInputOf } from "./card-input";
import type { CleanCardFacts } from "./clean-model";

/** One recorded card: the board's dials and the card's facts, its names of other cards as a map. */
export interface RecordedCard {
  dials: CardInputDials;
  facts: Omit<CleanCardFacts, "cardName"> & { cardNames: Record<string, string> };
}

const cardOf = (nodeId: string | undefined) => nodeId?.split("#r")[0];

/**
 * A card's facts as a recorded line: the plan's read-outs cut to what `cleanCardView` reads of this card (its forced
 * flows and spills, its Up to read-out, the rings it is in, the leftovers with a place on it, whether nothing sets it),
 * so a line stays small on a big board and replays to the same view.
 */
export function recordCard(facts: CleanCardFacts): RecordedCard {
  const id = facts.projectNode.id;
  const plan = facts.plan;
  const trimmed = plan
    ? {
        ...plan,
        forced: (plan.forced ?? []).filter((f) => f.nodeId === id),
        spills: (plan.spills ?? []).filter((s) => s.nodeId === id),
        upTo: plan.upTo?.[id] ? { [id]: plan.upTo[id] } : {},
        loops: (plan.loops ?? []).filter((l) => l.cards.includes(id)),
        leftovers: (plan.leftovers ?? []).filter((l) =>
          l.bearers.some((b) => cardOf(b.nodeId) === id),
        ),
        unanchored: (plan.unanchored ?? []).filter((n) => n === id),
        unneeded: (plan.unneeded ?? []).filter((n) => n === id),
        notes: [],
        terminals: [],
      }
    : plan;
  const { cardName: _names, ...rest } = facts;
  void _names;
  return {
    dials: cardDials(facts.averageDraw),
    facts: {
      ...rest,
      plan: trimmed as CleanCardFacts["plan"],
      cardNames: cardInputOf(facts).cardNames,
    },
  };
}

/** A recorded card's facts as `cleanCardView` takes them. */
export function replayFacts(line: RecordedCard): CleanCardFacts {
  const { cardNames, ...rest } = line.facts;
  return { ...rest, cardName: (id) => cardNames[id] };
}

const RATE_OUT: Record<CardInputDials["rate"], RateUnit> = {
  TICK: "tick",
  SECOND: "second",
  MINUTE: "minute",
  HOUR: "hour",
  EU: "eu",
};

/** Runs `fn` under a recorded card's dials, and puts the defaults back after. */
export function withDials<T>(dials: CardInputDials, fn: () => T): T {
  setActiveRateUnit(RATE_OUT[dials.rate]);
  setActivePowerDisplayUnit(dials.power as PowerDisplayUnit);
  try {
    return fn();
  } finally {
    setActiveRateUnit("second");
    setActivePowerDisplayUnit("eu");
  }
}

/** JSON with the non-finite numbers JSON lacks spelt as marked strings, and read back. */
const NON_FINITE = "\u0000number:";
export function encodeLine(value: unknown): string {
  return JSON.stringify(value, (_key, v) =>
    typeof v === "number" && !Number.isFinite(v) ? `${NON_FINITE}${String(v)}` : v,
  );
}
export function decodeLine<T>(text: string): T {
  return JSON.parse(text, (_key, v) =>
    typeof v === "string" && v.startsWith(NON_FINITE) ? Number(v.slice(NON_FINITE.length)) : v,
  ) as T;
}

/**
 * Every place two JSON values differ, as `path: got for want`; a key in a different order is its own entry. Paths
 * index arrays by position, so a difference in one row reads `view.inputs[1].number`.
 */
export function differences(
  want: unknown,
  got: unknown,
  where = "view",
  out: string[] = [],
): string[] {
  if (JSON.stringify(want) === JSON.stringify(got)) return out;
  if (Array.isArray(want) && Array.isArray(got)) {
    if (want.length !== got.length) out.push(`${where}: ${got.length} items for ${want.length}`);
    for (let i = 0; i < Math.min(want.length, got.length); i++)
      differences(want[i], got[i], `${where}[${i}]`, out);
    return out;
  }
  if (
    want &&
    got &&
    typeof want === "object" &&
    typeof got === "object" &&
    !Array.isArray(want) &&
    !Array.isArray(got)
  ) {
    const w = want as Record<string, unknown>;
    const g = got as Record<string, unknown>;
    for (const k of new Set([...Object.keys(w), ...Object.keys(g)]))
      differences(w[k], g[k], `${where}.${k}`, out);
    const wk = Object.keys(w).filter((k) => k in g);
    const gk = Object.keys(g).filter((k) => k in w);
    if (JSON.stringify(wk) !== JSON.stringify(gk))
      out.push(`${where}: key order ${gk.join(",")} for ${wk.join(",")}`);
    return out;
  }
  out.push(`${where}: ${JSON.stringify(got)} for ${JSON.stringify(want)}`);
  return out;
}

/** A JSON value with every object's keys sorted: for comparing records whose key order means nothing (an input). */
export function sortedKeys(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortedKeys);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((k) => [k, sortedKeys((value as Record<string, unknown>)[k])]),
    );
  }
  return value;
}

/** A difference's path with the row positions taken out: what one finding is, over every card it shows on. */
export function differenceKind(difference: string): string {
  return difference.split(":")[0]!.replace(/\[\d+\]/g, "[]");
}
