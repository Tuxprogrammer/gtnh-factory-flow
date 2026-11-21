/**
 * A special card's own parts through the compiled core when the "card" area is on and the core is loaded (the recipe
 * card's switch, card-view.ts: the same flag, the same targets), else the website's own rules (`specialParts`,
 * special-parts.ts). Both give the same parts: special-view.test.ts holds them equal on the shared special cases and
 * on every special card the website's tests and the plan fixtures draw.
 *
 * The core's path: the card's facts and its kind's as the core's `SpecialInputCase`, one call
 * (`ViewExports.viewSpecialJsKept`: records as JavaScript objects, the website's widths called back and kept per font
 * epoch), and the core's `SpecialView` read back as the website's `SpecialParts` (nothing re-derived). A card whose
 * inputs, dials and font are what they were for its last parts takes them back without a call. Only for the kinds the
 * shared rules cover (`coveredKind`); nothing here runs per frame: one special card, when React renders it.
 */
import type { CoreExports } from "@/lib/core/exports.generated";
import type {
  CardInput,
  CardInputDials,
  CardView,
  SpecialInput,
  SpecialInputCase,
  SpecialView as SpecialViewRecord,
} from "@/lib/core/records.generated";
import { WEB_CARD_MEASURE, cardDials, cardInputOf } from "../card-input";
import { cardCore, cardTicketOf, chipView, footView, sameData, words } from "../card-view";
import type { CleanCardFacts, CleanCardView, CleanCountView } from "../clean-model";
import { currentFontEpoch } from "../text-measure";
import type { SpecialKind } from "./special-model";
import { coveredKind, specialParts, type SpecialFacts, type SpecialParts } from "./special-parts";

const KIND_OUT: Record<SpecialKind, SpecialInput["kind"]> = {
  shared: "SHARED",
  power: "POWER",
  "custom-rate": "CUSTOM_RATE",
  tank: "TANK",
  crop: "CROP",
};
const KIND_IN = Object.fromEntries(Object.entries(KIND_OUT).map(([k, v]) => [v, k])) as Record<
  SpecialInput["kind"],
  SpecialKind
>;
const RULE_IN: Record<CardView["count"]["rule"], CleanCountView["rule"]> = {
  ANY: "any",
  UP_TO: "upTo",
  EXACT: "exact",
};
const NAME_SIZE = [10, 9, 8];

/** A special card's kind and facts as the core's `SpecialInput`. */
export function specialInputOf(facts: SpecialFacts): SpecialInput {
  const h = facts.holder;
  const g = facts.generator;
  return {
    kind: KIND_OUT[facts.kind],
    ...(h
      ? {
          holder: {
            empty: h.empty,
            ...(h.dial
              ? {
                  dial: {
                    supply: h.dial.mode === "supply",
                    perSecond: h.dial.perSecond,
                    kind: h.dial.kind,
                    showMode: h.dial.showMode,
                    showRate: h.dial.showRate,
                  },
                }
              : {}),
          },
        }
      : {}),
    ...(g
      ? {
          generator: {
            name: g.name,
            ...(g.tier ? { tier: { label: g.tier.label, fixed: g.tier.fixed } } : {}),
            settings: g.settings.map((s) => ({
              id: s.id,
              label: s.label,
              kind: SETTING_KIND_OUT[s.kind],
              choice: s.choice,
              number: s.number,
              ...(s.unit !== undefined ? { unit: s.unit } : {}),
              on: s.on,
              enabled: s.enabled,
              pinned: s.pinned,
            })),
            readings: g.readings.map((r) => ({ label: r.label, value: r.value, pinned: r.pinned })),
            warnings: g.warnings,
          },
        }
      : {}),
  };
}

const SETTING_KIND_OUT = { choice: "CHOICE", number: "NUMBER", toggle: "TOGGLE" } as const;

/** The core's `SpecialView` as the website's parts: the same strings and numbers, names spelt as the website spells them. */
export function partsOfRecord(v: SpecialViewRecord): SpecialParts {
  if (v.sections.length > 0) throw new Error("a shared machine's sections are not read from the core yet");
  const c = v.count;
  return {
    kind: KIND_IN[v.kind],
    name: v.name,
    nameSize: NAME_SIZE[v.nameStep] ?? 8,
    nameTone: v.nameTone.toLowerCase() as SpecialParts["nameTone"],
    hasPicker: v.hasPicker,
    tier: v.tier ? { label: v.tier.label, fixed: v.tier.fixed, title: v.tier.title } : undefined,
    amps: v.amps ? { label: v.amps.label, value: v.amps.value, editable: v.amps.editable } : undefined,
    empty: v.empty,
    sections: [],
    power: v.power
      ? {
          number: v.power.number,
          unit: v.power.unit,
          name: v.power.name,
          stalled: v.power.stalled,
          title: v.power.title,
        }
      : undefined,
    sockets: v.sockets
      ? {
          take: v.sockets.take,
          give: v.sockets.give,
          takeHint: v.sockets.takeHint,
          giveHint: v.sockets.giveHint,
        }
      : undefined,
    gauge: v.gauge,
    count: c
      ? {
          text: c.text,
          unit: c.unit,
          value: c.value,
          pinned: c.pinned,
          rule: RULE_IN[c.rule],
          ruleWord: c.ruleWord,
          ruleFixed: c.ruleFixed,
          set: c.set,
          setText: c.setText,
          box: c.box,
          prompt: c.prompt,
          zero: c.zero,
          title: c.title,
        }
      : undefined,
    foot: v.foot ? footView(v.foot) : undefined,
    chips: v.chips.map((c) => ({
      chip: chipView(c.chip),
      ...(c.edit ? { edit: { text: c.edit.text, scale: c.edit.scale, notes: c.edit.notes } } : {}),
      off: c.off,
    })),
    stripHeight: v.stripHeight,
    warnings: v.warnings,
    cannotRun: v.cannotRun,
    unset: v.unset,
    pick: v.pick,
    cropKey: v.cropKey,
  };
}

/**
 * A special card's parts from the card view the core made last for it (`ticket`, from `cardTicketOf`): only the
 * kind's facts cross, the card is neither read nor made again. Undefined when the core no longer holds that card.
 */
export function specialThroughHeld(
  c: CoreExports,
  ticket: number,
  special: SpecialInput,
  fontState: number,
): SpecialViewRecord | undefined {
  if (!c.ViewExports.viewCardHeld(ticket, fontState)) return undefined;
  words(c);
  return c.ViewExports.viewSpecialJsHeld(
    ticket,
    special as unknown as Record<string, unknown>,
    WEB_CARD_MEASURE as unknown as Record<string, unknown>,
    fontState,
  ) as unknown as SpecialViewRecord;
}

/** A special card through the core `c`, its widths measured by `measure` and kept per `fontState`. */
export function specialThroughCore(
  c: CoreExports,
  input: SpecialInputCase,
  fontState?: number,
): SpecialViewRecord {
  words(c);
  const out =
    fontState === undefined
      ? c.ViewExports.viewSpecialJsSix(input)
      : c.ViewExports.viewSpecialJsKept(
          input,
          WEB_CARD_MEASURE as unknown as Record<string, unknown>,
          fontState,
        );
  return out as unknown as SpecialViewRecord;
}

/** A special card's last parts (two, as a card's: the board's and the Overview rail's can differ by `selected`). */
interface Slot {
  card: CardInput;
  special: SpecialInput;
  dials: CardInputDials;
  epoch: number;
  parts: SpecialParts;
}
const SLOTS_PER_CARD = 2;
const CARDS_KEPT = 1024;
const kept = new Map<string, Slot[]>();
const counts = { core: 0, kept: 0, ts: 0, first: 0, filled: 0, held: 0 };

/**
 * How many parts came from the core (`held`: of those, how many started from the card view the core held), from kept
 * parts and from the website's rules, how many first parts the website's rules drew while the core had none, and how
 * many of those the core made in idle time; for tests and probes.
 */
export function specialViewCounts(): Readonly<typeof counts> {
  return { ...counts };
}

/** Zeroes the counts and forgets every kept part and every card waiting for the core (tests). */
export function forgetSpecialViews(): void {
  for (const k of Object.keys(counts) as (keyof typeof counts)[]) counts[k] = 0;
  kept.clear();
  waiting.clear();
}

/**
 * A special card's own parts through the core `c`, kept per card: the switch's path, also for tests that hold it to
 * the website's rules. With `first`, the card's view: its parts start from the card the core made for it when the core
 * still holds it, and (unless `firstByRules` is false) a card the core has never drawn is drawn by the rules first.
 */
export function coreSpecialParts(
  c: CoreExports,
  cardFacts: CleanCardFacts,
  facts: SpecialFacts,
  first?: { base: CleanCardView; firstByRules?: boolean },
): SpecialParts {
  const dials = cardDials(cardFacts.averageDraw);
  const epoch = currentFontEpoch();
  if (first && first.firstByRules !== false && !kept.has(cardFacts.projectNode.id)) {
    // A card the core has never drawn: the website's rules draw it now, and the core makes its parts in idle time, so
    // opening a plan stays at the rules' cost (the recipe card's first view does the same, card-view.ts).
    counts.first++;
    wait(c, { cardFacts, facts, dials, epoch });
    return specialParts(first.base, facts);
  }
  const card = cardInputOf(cardFacts);
  const special = specialInputOf(facts);
  const id = card.nodeId;
  const slots = kept.get(id) ?? [];
  for (const s of slots) {
    if (
      s.epoch === epoch &&
      sameData(s.dials, dials) &&
      sameData(s.special, special) &&
      sameData(s.card, card)
    ) {
      counts.kept++;
      return s.parts;
    }
  }
  // From the card the core just made for this view when it still holds it, else the card made again on the way.
  const ticket = first ? cardTicketOf(first.base) : undefined;
  const fromHeld = ticket !== undefined ? specialThroughHeld(c, ticket, special, epoch) : undefined;
  if (fromHeld) counts.held++;
  const parts = partsOfRecord(fromHeld ?? specialThroughCore(c, { dials, card, special }, epoch));
  counts.core++;
  slots.unshift({ card, special, dials, epoch, parts });
  if (slots.length > SLOTS_PER_CARD) slots.length = SLOTS_PER_CARD;
  kept.delete(id);
  kept.set(id, slots);
  if (kept.size > CARDS_KEPT) kept.delete(kept.keys().next().value!);
  return parts;
}

/**
 * A special card's own parts: the core's when the "card" area is on, the core is loaded and the shared rules cover the
 * kind, else (and when the core fails on it) the website's rules over the card's view.
 */
export function specialPartsOf(
  cardFacts: CleanCardFacts,
  facts: SpecialFacts,
  base: CleanCardView,
): SpecialParts {
  specialFactsListener?.(cardFacts, facts);
  return specialPartsWith(coveredKind(facts.kind) ? cardCore() : null, cardFacts, facts, base);
}

/** `specialPartsOf` through the core `c` when there is one. */
export function specialPartsWith(
  c: CoreExports | null,
  cardFacts: CleanCardFacts,
  facts: SpecialFacts,
  base: CleanCardView,
): SpecialParts {
  if (c) {
    try {
      return coreSpecialParts(c, cardFacts, facts, { base });
    } catch (error) {
      said(error);
    }
  }
  counts.ts++;
  return specialParts(base, facts);
}

/** Special cards drawn first by the website's rules, waiting for the core (the latest facts per card). */
interface Waiting {
  cardFacts: CleanCardFacts;
  facts: SpecialFacts;
  dials: CardInputDials;
  epoch: number;
}
const waiting = new Map<string, Waiting>();
let waitingCore: CoreExports | undefined;
const FILL_SLICE_MS = 8;
let fillScheduled = false;

function wait(c: CoreExports, entry: Waiting): void {
  waiting.delete(entry.cardFacts.projectNode.id);
  waiting.set(entry.cardFacts.projectNode.id, entry);
  waitingCore = c;
  if (fillScheduled) return;
  fillScheduled = true;
  const idle = (
    globalThis as { requestIdleCallback?: (cb: (d: IdleDeadline) => void) => number }
  ).requestIdleCallback;
  const run = (deadline?: IdleDeadline) => {
    fillScheduled = false;
    const budget = deadline ? Math.min(FILL_SLICE_MS, deadline.timeRemaining() - 1) : FILL_SLICE_MS;
    const more = fillWaitingSpecialViews(Math.max(1, budget));
    if (more && waitingCore) wait(waitingCore, waiting.values().next().value!);
  };
  if (idle) idle(run);
  else setTimeout(run, 50);
}

/**
 * Makes the waiting special cards' parts through the core for at most `budgetMs` (idle time; tests call it directly).
 * A card whose font changed since it waited is dropped: its next draw is new parts anyway. Answers whether cards still
 * wait.
 */
export function fillWaitingSpecialViews(budgetMs = Number.POSITIVE_INFINITY): boolean {
  const c = waitingCore;
  if (!c) return false;
  const until = performance.now() + budgetMs;
  for (const [id, w] of waiting) {
    if (performance.now() >= until) return true;
    waiting.delete(id);
    if (w.epoch !== currentFontEpoch() || kept.has(id)) continue;
    try {
      const card = cardInputOf(w.cardFacts);
      const special = specialInputOf(w.facts);
      const parts = partsOfRecord(specialThroughCore(c, { dials: w.dials, card, special }, w.epoch));
      kept.set(id, [{ card, special, dials: w.dials, epoch: w.epoch, parts }]);
      counts.filled++;
    } catch (error) {
      said(error);
    }
  }
  return false;
}

let failed = false;
function said(error: unknown): void {
  if (!failed)
    console.error("special card: the core failed on a card; the website's rules draw it", error);
  failed = true;
}

let specialFactsListener: ((card: CleanCardFacts, facts: SpecialFacts) => void) | undefined;

/** Hears every special card's facts (the shadow's recorder, tools/audits/card-test-inputs, only; the app never sets it). */
export function setSpecialFactsListener(
  listener: ((card: CleanCardFacts, facts: SpecialFacts) => void) | undefined,
): void {
  specialFactsListener = listener;
}
