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
import { cardCore, chipView, footView, sameData, words } from "../card-view";
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
  };
}

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
    })),
    stripHeight: v.stripHeight,
    warnings: v.warnings,
    cannotRun: v.cannotRun,
    unset: v.unset,
    pick: v.pick,
    cropKey: v.cropKey,
  };
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
const counts = { core: 0, kept: 0, ts: 0 };

/** How many parts came from the core, from kept parts and from the website's rules; for tests and probes. */
export function specialViewCounts(): Readonly<typeof counts> {
  return { ...counts };
}

/** Zeroes the counts and forgets every kept part (tests). */
export function forgetSpecialViews(): void {
  for (const k of Object.keys(counts) as (keyof typeof counts)[]) counts[k] = 0;
  kept.clear();
}

/**
 * A special card's own parts through the core `c`, kept per card: the switch's path, also for tests that hold it to
 * the website's rules.
 */
export function coreSpecialParts(
  c: CoreExports,
  cardFacts: CleanCardFacts,
  facts: SpecialFacts,
): SpecialParts {
  const card = cardInputOf(cardFacts);
  const special = specialInputOf(facts);
  const dials = cardDials(cardFacts.averageDraw);
  const epoch = currentFontEpoch();
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
  const parts = partsOfRecord(specialThroughCore(c, { dials, card, special }, epoch));
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
      return coreSpecialParts(c, cardFacts, facts);
    } catch (error) {
      said(error);
    }
  }
  counts.ts++;
  return specialParts(base, facts);
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
