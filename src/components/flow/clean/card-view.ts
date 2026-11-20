/**
 * A recipe card's view, through the compiled core when the "card" area is on and the core is loaded
 * (src/lib/core/adopt.ts), else the website's own builders (`cleanCardView`). Both give the same view:
 * card-view.test.ts holds them equal on the shared card cases and on every card the website's tests draw.
 *
 * The core's path: the card's facts as the core's input (`cardInputOf`), one call (`cardThroughCore`: records as
 * JavaScript objects, the website's text widths called back), and the core's `CardView` read back into the website's
 * `CleanCardView` (`cleanViewOf`: names of tones and rules, the rail's own port objects; nothing is re-derived). A
 * card whose input, dials and font are what they were for its last view takes that view back without a call: a
 * remount, a render that changed nothing of it, a solve that left its numbers alone and a drag frame cost a
 * comparison. Nothing here runs per frame or over the board: one card, when React renders it.
 */
import { cardThroughCore } from "@/lib/core/card-wire";
import { coreFor } from "@/lib/core/adopt";
import type { CoreExports } from "@/lib/core/exports.generated";
import { coreInfo, type CoreTarget } from "@/lib/core/loader";
import type {
  CardInput,
  CardInputDials,
  CardView,
  CardViewChipView,
  CardViewFootView,
  CardViewPortView,
  TonesRole,
} from "@/lib/core/records.generated";
import { CARD_WORDS } from "@/lib/core/card-words.generated";
import type { PinRule } from "@/lib/model/pin-rule";
import type { RailPort } from "../node-verdict";
import { WEB_CARD_MEASURE, cardDials, cardInputOf } from "./card-input";
import {
  cleanCardView,
  type CleanCardFacts,
  type CleanCardView,
  type CleanChipView,
  type CleanFootView,
  type CleanPortView,
  type FootTone,
} from "./clean-model";
import type { LoopTone } from "./loops";
import { currentFontEpoch } from "./text-measure";

const RULE: Record<CardView["count"]["rule"], PinRule> = {
  ANY: "any",
  UP_TO: "upTo",
  EXACT: "exact",
};
const LOOP: Record<NonNullable<CardView["loop"]>, LoopTone> = {
  DEAD_LOOP: "dead",
  CLOG_LOCK: "clog",
};
const NAME_SIZE = [10, 9, 8];
const tone = (role: TonesRole) => role.toLowerCase() as FootTone;

function portView(p: CardViewPortView, nodeId: string, port: RailPort): CleanPortView {
  return {
    key: p.key,
    nodeId,
    handleId: p.handleId,
    side: p.output ? "output" : "input",
    port,
    number: p.number,
    unit: p.unit,
    name: p.name,
    title: p.title,
    zero: p.zero,
    gold: p.gold,
    rate: p.rate,
    numberSmall: p.numberSmall,
    nameSmall: p.nameSmall,
    nameClipped: p.nameClipped,
    wired: p.wired,
    choice: p.choice,
    resourceKey: p.resourceKey,
    forced: p.forced ? { number: p.forced.number, unit: p.forced.unit } : undefined,
    ...(p.spillTo !== undefined ? { spillTo: p.spillTo } : {}),
    ...(p.why !== undefined ? { why: tone(p.why) } : {}),
  };
}

function chipView(c: CardViewChipView): CleanChipView {
  return {
    id: c.id,
    label: c.label,
    value: c.value,
    icon: c.icon,
    reading: c.reading || undefined,
    powerCap: c.powerCap
      ? { running: c.powerCap.running, structure: c.powerCap.structure }
      : undefined,
    warn: c.warn || undefined,
    title: c.title,
    x: c.x,
    y: c.y,
    width: c.width,
  };
}

function footView(f: CardViewFootView): CleanFootView {
  const facts = f.facts;
  return {
    tone: tone(f.tone),
    word: f.word,
    say: f.say,
    frac: f.frac,
    fill: f.fill,
    ghost: f.ghost ? { left: f.ghost.left, width: f.ghost.width } : undefined,
    facts: {
      reason: (facts.reason ?? null) as CleanFootView["facts"]["reason"],
      loop: facts.loop ? LOOP[facts.loop] : undefined,
      rule: RULE[facts.rule],
      running: facts.running,
      capacity: facts.capacity,
      wants: facts.wants,
      resource: facts.resource,
      tied: facts.tied,
      upstream: facts.upstream,
      forced: facts.forced,
      sentOut: facts.sentOut,
      turbine: facts.turbine
        ? ({ ...facts.turbine, tier: facts.turbineTier } as CleanFootView["facts"]["turbine"])
        : undefined,
    },
  };
}

/**
 * The core's `CardView` as the website's `CleanCardView`: the same strings and numbers, the record's tone and rule
 * names as the website spells them, and the objects the website keeps beside the view (each row's rail port, the
 * programmed circuit) from the facts the view was made of. The menu rows and the frame's tone are the record's own
 * (the website draws its menu from `cardRecipeRows` and its frame from the foot).
 */
export function cleanViewOf(
  view: CardView,
  facts: Pick<CleanCardFacts, "rails" | "circuit">,
): CleanCardView {
  const { rails } = facts;
  return {
    nodeId: view.nodeId,
    name: view.name,
    nameSize: NAME_SIZE[view.nameStep] ?? 8,
    hasPicker: view.hasPicker,
    tier: view.tier
      ? { label: view.tier.label, fixed: view.tier.fixed, title: view.tier.title }
      : undefined,
    amps: view.amps
      ? { label: view.amps.label, value: view.amps.value, editable: view.amps.editable }
      : undefined,
    inputs: view.inputs.map((p, i) => portView(p, view.nodeId, rails.inputs[i]!)),
    outputs: view.outputs.map((p, i) => portView(p, view.nodeId, rails.outputs[i]!)),
    power: view.power
      ? {
          number: view.power.number,
          unit: view.power.unit,
          name: view.power.name,
          stalled: view.power.stalled,
          title: view.power.title,
        }
      : undefined,
    art: view.art,
    glanceArt: view.glanceArt,
    glanceProduct: view.glanceProduct
      ? { image: `url(${view.glanceProduct.icon})`, fluid: view.glanceProduct.fluid }
      : undefined,
    count: {
      text: view.count.text,
      unit: view.count.unit,
      value: view.count.value,
      pinned: view.count.pinned,
      rule: RULE[view.count.rule],
      ruleWord: view.count.ruleWord,
      ruleFixed: view.count.ruleFixed,
      set: view.count.set,
      setText: view.count.setText,
      box: view.count.box,
      prompt: view.count.prompt,
      zero: view.count.zero,
      title: view.count.title,
    },
    foot: view.foot ? footView(view.foot) : undefined,
    loop: view.loop ? LOOP[view.loop] : undefined,
    glance: view.glance
      ? { tone: tone(view.glance.tone), fill: view.glance.fill, word: view.glance.word }
      : undefined,
    circuit: view.circuit ? facts.circuit : undefined,
    chips: view.chips.map(chipView),
    stripHeight: view.stripHeight,
    selected: view.selected,
    cannotRun: view.cannotRun,
    unset: view.unset,
  };
}

/** Plain data equal key for key (JSON values: objects, arrays, strings, numbers, booleans; undefined is no key). */
export function sameData(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (typeof a !== "object" || typeof b !== "object" || a === null || b === null) {
    return typeof a === "number" && typeof b === "number" && Number.isNaN(a) && Number.isNaN(b);
  }
  if (Array.isArray(a)) {
    if (!Array.isArray(b) || a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) if (!sameData(a[i], b[i])) return false;
    return true;
  }
  if (Array.isArray(b)) return false;
  const x = a as Record<string, unknown>;
  const y = b as Record<string, unknown>;
  let n = 0;
  for (const k in x) {
    if (x[k] === undefined) continue;
    n++;
    if (!sameData(x[k], y[k])) return false;
  }
  for (const k in y) if (y[k] !== undefined) n--;
  return n === 0;
}

/** A card's last views (two: the board's card and the Overview rail's line can differ by `selected`). */
interface Slot {
  input: CardInput;
  dials: CardInputDials;
  epoch: number;
  view: CardView;
}
const SLOTS_PER_CARD = 2;
/** Cards kept, least recently drawn dropped first: the largest boards hold a few thousand cards. */
const CARDS_KEPT = 4096;
const kept = new Map<string, Slot[]>();

function lookup(
  id: string,
  input: CardInput,
  dials: CardInputDials,
  epoch: number,
): CardView | undefined {
  const slots = kept.get(id);
  if (!slots) return undefined;
  for (let i = 0; i < slots.length; i++) {
    const s = slots[i]!;
    if (s.epoch !== epoch || !sameData(s.dials, dials) || !sameData(s.input, input)) continue;
    if (i > 0) slots.unshift(...slots.splice(i, 1));
    // Most recently drawn last in the map's order.
    kept.delete(id);
    kept.set(id, slots);
    return s.view;
  }
  return undefined;
}

function keep(id: string, slot: Slot): void {
  const slots = kept.get(id) ?? [];
  slots.unshift(slot);
  if (slots.length > SLOTS_PER_CARD) slots.length = SLOTS_PER_CARD;
  kept.delete(id);
  kept.set(id, slots);
  if (kept.size > CARDS_KEPT) kept.delete(kept.keys().next().value!);
}

/** The core instances the card's words were handed to (once per loaded core). */
const worded = new WeakSet<object>();

const counts = { core: 0, kept: 0, ts: 0, first: 0, filled: 0 };

/**
 * How many views came from the core, from a kept view and from the website's builders (the area off, the core not
 * there or failing), how many first views the builders drew while the core had none, and how many of those the core
 * made in idle time; for tests and probes.
 */
export function cardViewCounts(): Readonly<typeof counts> {
  return { ...counts };
}

/** Zeroes the counts. */
export function resetCardViewCounts(): void {
  for (const k of Object.keys(counts) as (keyof typeof counts)[]) counts[k] = 0;
}

/** Forgets every kept view and every card waiting for the core (tests). */
export function forgetCardViews(): void {
  kept.clear();
  waiting.clear();
}

function words(c: CoreExports): void {
  if (worded.has(c)) return;
  c.ViewExports.viewCardWords(JSON.stringify(CARD_WORDS));
  worded.add(c);
}

function viewThroughCore(
  c: CoreExports,
  input: CardInput,
  dials: CardInputDials,
  epoch: number,
): CardView {
  words(c);
  const view = cardThroughCore(c.ViewExports, { dials, card: input }, WEB_CARD_MEASURE, epoch).view;
  keep(input.nodeId, { input, dials, epoch, view });
  return view;
}

/**
 * The view through the core `c`, kept per card: the switch's path, also for tests that hold it to the builders. With
 * `firstByBuilders`, a card the core has never drawn is drawn by the builders (the same view) and the core makes its
 * view in idle time (`fillWaitingCardViews`): opening a plan draws every card once, and that first pass stays at the
 * builders' cost. Every later view of the card is the core's.
 */
export function coreCardView(
  c: CoreExports,
  facts: CleanCardFacts,
  { firstByBuilders = false }: { firstByBuilders?: boolean } = {},
): CleanCardView {
  const dials = cardDials(facts.averageDraw);
  const epoch = currentFontEpoch();
  if (firstByBuilders && !kept.has(facts.projectNode.id)) {
    // The input is written in idle time too: the facts are this render's and nothing changes them.
    counts.first++;
    wait(c, { facts, dials, epoch });
    return cleanCardView(facts);
  }
  const input = cardInputOf(facts);
  let view = lookup(input.nodeId, input, dials, epoch);
  if (view) {
    counts.kept++;
  } else {
    view = viewThroughCore(c, input, dials, epoch);
    counts.core++;
  }
  return cleanViewOf(view, facts);
}

/** Cards drawn first by the builders, waiting for the core to make their views (the latest input per card). */
interface Waiting {
  facts: CleanCardFacts;
  dials: CardInputDials;
  epoch: number;
}
const waiting = new Map<string, Waiting>();
let waitingCore: CoreExports | undefined;
const FILL_SLICE_MS = 8;
let fillScheduled = false;

function wait(c: CoreExports, entry: Waiting): void {
  waiting.delete(entry.facts.projectNode.id);
  waiting.set(entry.facts.projectNode.id, entry);
  waitingCore = c;
  if (fillScheduled) return;
  fillScheduled = true;
  const idle = (
    globalThis as {
      requestIdleCallback?: (cb: (d: IdleDeadline) => void) => number;
    }
  ).requestIdleCallback;
  const run = (deadline?: IdleDeadline) => {
    fillScheduled = false;
    // At most a few cards a slice, and only in idle time (no timeout that would force a slice into a busy frame):
    // the cards waiting already show the builders' view, so nothing is late.
    const budget = deadline ? Math.min(FILL_SLICE_MS, deadline.timeRemaining() - 1) : FILL_SLICE_MS;
    const more = fillWaitingCardViews(Math.max(1, budget));
    if (more && waitingCore) wait(waitingCore, waiting.values().next().value!);
  };
  if (idle) idle(run);
  else setTimeout(run, 50);
}

/**
 * Makes the waiting cards' views through the core for at most `budgetMs` (idle time; tests call it directly). A card
 * whose font changed since it waited is dropped: its next draw is a new view anyway. Answers whether cards still wait.
 */
export function fillWaitingCardViews(budgetMs = Number.POSITIVE_INFINITY): boolean {
  const c = waitingCore;
  if (!c) return false;
  const until = performance.now() + budgetMs;
  for (const [id, w] of waiting) {
    if (performance.now() >= until) return true;
    waiting.delete(id);
    if (w.epoch !== currentFontEpoch() || kept.has(id)) continue;
    try {
      viewThroughCore(c, cardInputOf(w.facts), w.dials, w.epoch);
      counts.filled++;
    } catch (error) {
      // The card keeps the builders' view, and the core is asked again only when it changes.
      said(error);
    }
  }
  return false;
}

/**
 * The targets a card is drawn through the core on. WebAssembly GC: a changed card costs the board a few milliseconds
 * more than the builders on a dial or a solve, well under a frame (docs/view-card.md, "The switch"). The JavaScript
 * build runs the rules at two to four times the builders' cost, so there the builders draw every card.
 */
export const CARD_TARGETS: readonly CoreTarget[] = ["wasm-gc"];

/** The core for the board's cards: the "card" area on, the `core` module loaded on one of `CARD_TARGETS`. */
export function cardCore(): CoreExports | null {
  const c = coreFor("card");
  if (!c) return null;
  const info = coreInfo();
  const target = info?.modules.core?.target ?? info?.target;
  return target !== undefined && CARD_TARGETS.includes(target) ? c : null;
}

/** A card's view: the core's when the "card" area is on and the core is loaded, else the website's builders'. */
export function cardViewOf(facts: CleanCardFacts): CleanCardView {
  return cardViewWith(cardCore(), facts);
}

/** A card's view through the core `c` when there is one, else (and when the core fails on it) the builders'. */
export function cardViewWith(c: CoreExports | null, facts: CleanCardFacts): CleanCardView {
  if (c) {
    try {
      return coreCardView(c, facts, { firstByBuilders: true });
    } catch (error) {
      said(error);
    }
  }
  counts.ts++;
  return cleanCardView(facts);
}

/** A card the core cannot draw is drawn by the builders, and said once: the switch's test should have caught it. */
let failed = false;
function said(error: unknown): void {
  if (!failed)
    console.error("card view: the core failed on a card; the website's builders draw it", error);
  failed = true;
}
