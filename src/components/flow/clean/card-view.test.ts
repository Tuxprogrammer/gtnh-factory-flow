import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { gunzipSync } from "node:zlib";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { setAdoptOverride, withAdopt } from "@/lib/core/adopt";
import type { CoreExports } from "@/lib/core/exports.generated";
import {
  core,
  loadCore,
  resetCoreForTests,
  wasmGcSupported,
  type CoreTarget,
} from "@/lib/core/loader";
import { cardThroughCore } from "@/lib/core/card-wire";
import type { CardView } from "@/lib/core/records.generated";
import { readViewCases } from "@/test-support/view-corpus";
import { WEB_CARD_MEASURE, cardInputOf } from "./card-input";
import { caseFacts, websiteCardView, withCaseDials, type CardCaseInput } from "./card-conformance";
import { decodeLine, replayFacts, withDials, type RecordedCard } from "./card-shadow";
import {
  CARD_TARGETS,
  cardCore,
  cardViewCounts,
  cardViewOf,
  cardViewWith,
  cleanViewOf,
  coreCardView,
  forgetCardViews,
  fillWaitingCardViews,
  resetCardViewCounts,
  sameData,
} from "./card-view";
import { cleanCardView } from "./clean-model";
import { resetMeasuredFont } from "./text-measure";

/**
 * The recipe card's switch (restructure/adoption.md, step two): with the "card" area on and the core loaded, a card's
 * view is the core's `CardView` read back as the website's `CleanCardView` (card-view.ts); otherwise the website's own
 * builders. The two must be the same view on every card: the shared cases' stored records read back, and every card the
 * website's tests draw (conformance/view-inputs) through the core with the website's widths, on both targets. A card
 * whose input did not change takes its last view back without a call.
 */
const root = process.cwd();
const present = existsSync(path.join(root, "public", "core", "manifest.json"));

function recorded(): RecordedCard[] {
  const file = path.join(root, "conformance/view-inputs/card-website-test-inputs.jsonl.gz");
  return gunzipSync(readFileSync(file))
    .toString("utf8")
    .split("\n")
    .filter(Boolean)
    .map((line) => decodeLine<RecordedCard>(line));
}

describe("card view switch", () => {
  beforeEach(() => {
    resetCardViewCounts();
    forgetCardViews();
  });

  it("reads every shared case's stored record back as the website's view", () => {
    const { cases } = readViewCases<CardCaseInput, CardView>("card", "cases");
    for (const c of cases) {
      const facts = withCaseDials(c.input, () => caseFacts(c.input));
      expect(cleanViewOf(c.view, facts), c.name).toEqual(websiteCardView(c.input));
    }
    expect(cases.length).toBeGreaterThanOrEqual(99);
  });

  it("compares plain data key for key, an undefined value as no key", () => {
    expect(sameData({ a: 1, b: [1, { c: "x" }] }, { b: [1, { c: "x" }], a: 1 })).toBe(true);
    expect(sameData({ a: 1, b: undefined }, { a: 1 })).toBe(true);
    expect(sameData({ a: 1 }, { a: 1, b: 2 })).toBe(false);
    expect(sameData([1, 2], [1, 2, 3])).toBe(false);
    expect(sameData({ a: [] }, { a: {} })).toBe(false);
    expect(sameData(0, -0)).toBe(true);
    expect(sameData(Number.NaN, Number.NaN)).toBe(true);
    expect(sameData("1", 1)).toBe(false);
  });

  it("draws with the builders while the area is off or the core is not there", () => {
    const line = recorded()[0]!;
    const facts = replayFacts(line);
    withAdopt({ card: false }, () => {
      expect(withDials(line.dials, () => cardViewOf(facts))).toEqual(
        withDials(line.dials, () => cleanCardView(facts)),
      );
    });
    expect(cardViewCounts()).toEqual({ core: 0, kept: 0, ts: 1, first: 0, filled: 0 });
  });

  it("draws with the builders when the core fails on a card, and says so once", () => {
    const line = recorded()[0]!;
    const facts = replayFacts(line);
    const want = withDials(line.dials, () => cleanCardView(facts));
    const broken = {
      ViewExports: {
        viewCardWords: () => 0,
        viewCardJsKept: () => {
          throw new Error("planted");
        },
      },
    } as unknown as CoreExports;
    const quiet = console.error;
    const said: unknown[] = [];
    console.error = (...args: unknown[]) => void said.push(args);
    try {
      const draw = () => withDials(line.dials, () => cardViewWith(broken, facts));
      expect(draw()).toEqual(want);
      withDials(line.dials, () => fillWaitingCardViews());
      // The core made no view of it: the builders keep drawing it.
      expect(draw()).toEqual(want);
    } finally {
      console.error = quiet;
    }
    expect(said.length).toBeLessThanOrEqual(1);
    expect(cardViewCounts()).toMatchObject({ core: 0, filled: 0, first: 2 });
  });

  const targets: CoreTarget[] = wasmGcSupported() ? ["wasm-gc", "js"] : ["js"];
  for (const target of targets) {
    describe.skipIf(!present)(`through the core on the ${target} target`, () => {
      beforeAll(async () => {
        resetCoreForTests();
        await loadCore({ target });
      });
      afterAll(() => {
        resetCoreForTests();
        setAdoptOverride("card", undefined);
      });

      it("gives the builders' view on every card the website's tests draw", () => {
        const lines = recorded();
        let rows = 0;
        for (const [i, line] of lines.entries()) {
          const facts = replayFacts(line);
          const { want, got } = withDials(line.dials, () => ({
            want: cleanCardView(facts),
            got: coreCardView(core(), facts),
          }));
          expect(got, `recorded ${i} (${line.facts.name})`).toEqual(want);
          // The rows carry the rail's own port objects, as the builders' do.
          got.inputs.forEach((p, k) => expect(p.port).toBe(facts.rails.inputs[k]));
          rows += got.inputs.length + got.outputs.length;
        }
        expect(lines.length).toBeGreaterThan(700);
        expect(rows).toBeGreaterThan(1000);
        // The recordings hold a card once per render: a card drawn again with the same input is taken back.
        const { core: called, kept } = cardViewCounts();
        expect(called + kept).toBe(lines.length);
        expect(called).toBeGreaterThan(400);
      }, 300_000);

      it("takes a card's last view back while its input, dials and font are the same", () => {
        const line = recorded().find((l) => l.facts.rails.inputs.length > 0)!;
        const view = (facts = replayFacts(line), dials = line.dials) =>
          withDials(dials, () => coreCardView(core(), facts));
        const first = view();
        expect(cardViewCounts()).toMatchObject({ core: 1, kept: 0, ts: 0 });
        // An equal card made afresh (a remount, a new render, a solve that left it alone): no call.
        expect(view()).toEqual(first);
        expect(cardViewCounts().kept).toBe(1);
        // A dial repaints it.
        const minute = { ...line.dials, rate: line.dials.rate === "MINUTE" ? "SECOND" : "MINUTE" } as const;
        view(undefined, minute);
        expect(cardViewCounts().core).toBe(2);
        // So does a face that loaded late (every width so far is dropped).
        resetMeasuredFont();
        view();
        expect(cardViewCounts().core).toBe(3);
        // Selected and not: two views kept per card (the board's card and the Overview rail's line).
        const selected = { ...replayFacts(line), selected: !line.facts.selected };
        view(selected);
        view();
        view(selected);
        expect(cardViewCounts()).toMatchObject({ core: 4, kept: 3, ts: 0 });
        // A changed rate on one row is a new input.
        const facts = replayFacts(line);
        const rails = {
          ...facts.rails,
          inputs: facts.rails.inputs.map((p, k) =>
            k === 0 ? { ...p, currentPerSecond: p.currentPerSecond + 1 } : p,
          ),
        };
        expect(view({ ...facts, rails }).inputs[0]!.number).not.toBe(first.inputs[0]!.number);
        expect(cardViewCounts().core).toBe(5);
      });

      it("keeps the widths it measured while the font state stays, and measures again under a new one", () => {
        const line = recorded().find((l) => l.facts.rails.inputs.length > 0)!;
        const input = { dials: line.dials, card: withDials(line.dials, () => cardInputOf(replayFacts(line))) };
        let asked = 0;
        const narrow = { width: (s: string, t: string) => (asked++, WEB_CARD_MEASURE.width(s, t)) };
        const wide = { width: (s: string, t: string) => (asked++, 3 * WEB_CARD_MEASURE.width(s, t)) };
        const card = (measure: typeof narrow, state?: number) =>
          withDials(line.dials, () => cardThroughCore(core().ViewExports, input, measure, state));
        const first = card(narrow, 1_000_001);
        const measured = asked;
        expect(measured).toBeGreaterThan(5);
        // Same state: every width is the kept one, none is asked.
        expect(card(wide, 1_000_001)).toEqual(first);
        expect(asked).toBe(measured);
        // A new state: measured again, as a card with no kept widths is.
        const again = card(wide, 1_000_002);
        expect(asked).toBeGreaterThan(measured);
        expect(again).toEqual(card(wide));
        expect(again).not.toEqual(first);
      });

      it(`draws through the core on this target only when it is one of CARD_TARGETS`, () => {
        withAdopt({ card: true }, () => expect(cardCore()).toBe(CARD_TARGETS.includes(target) ? core() : null));
        withAdopt({ card: false }, () => expect(cardCore()).toBeNull());
      });

      it.skipIf(!CARD_TARGETS.includes(target))("is the switch's path when the area is on: a card's first view by the builders, the core's from then on", () => {
        const line = recorded()[1]!;
        const facts = replayFacts(line);
        const want = withDials(line.dials, () => cleanCardView(facts));
        withAdopt({ card: true }, () => {
          const draw = (f = facts) => withDials(line.dials, () => cardViewOf(f));
          expect(draw()).toEqual(want);
          expect(cardViewCounts()).toEqual({ core: 0, kept: 0, ts: 0, first: 1, filled: 0 });
          // Idle time: the core makes the waiting card's view, and the next draw takes it.
          withDials(line.dials, () => fillWaitingCardViews());
          expect(cardViewCounts().filled).toBe(1);
          expect(draw()).toEqual(want);
          expect(cardViewCounts()).toMatchObject({ core: 0, kept: 1, first: 1 });
          // A changed card is the core's at once.
          const changed = { ...facts, selected: !facts.selected };
          expect(draw(changed)).toEqual(withDials(line.dials, () => cleanCardView(changed)));
          expect(cardViewCounts()).toMatchObject({ core: 1, first: 1 });
        });
      });

      it.skipIf(!CARD_TARGETS.includes(target))("drops a waiting card whose font changed before idle time", () => {
        const line = recorded()[2]!;
        withAdopt({ card: true }, () => {
          withDials(line.dials, () => cardViewOf(replayFacts(line)));
          resetMeasuredFont();
          fillWaitingCardViews();
        });
        expect(cardViewCounts()).toMatchObject({ first: 1, filled: 0 });
      });
    });
  }
});
