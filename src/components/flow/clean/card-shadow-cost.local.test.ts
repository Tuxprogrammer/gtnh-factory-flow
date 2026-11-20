/**
 * What one card's view costs on each path, for the switch item to decide per card or per board (a scratch harness, not
 * in the suite): the website's `cleanCardView`, the writer of the core's input, and the compiled core per card and per
 * board, with the website's widths called back for each string and with the built-in six px (no call back), on both
 * targets. Also a planted check that the comparison sees a difference when the widths differ.
 *
 *   npx vitest run src/components/flow/clean/card-shadow-cost.local.test.ts --config vitest.local.config.ts
 */
import { readFileSync } from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { gunzipSync } from "node:zlib";
import { describe, expect, it } from "vitest";
import { core, coreInfo, loadCore, resetCoreForTests, type CoreTarget } from "@/lib/core/loader";
import { WEB_CARD_MEASURE, cardInputOf } from "./card-input";
import { cardViewJson, type CardCaseInput } from "./card-conformance";
import { decodeLine, differences, replayFacts, withDials, type RecordedCard } from "./card-shadow";
import { cleanCardView } from "./clean-model";

const root = process.cwd();
const lines = gunzipSync(
  readFileSync(path.join(root, "conformance/view-inputs/card-website-test-inputs.jsonl.gz")),
)
  .toString("utf8")
  .split("\n")
  .filter(Boolean)
  .map((l) => decodeLine<RecordedCard>(l));

function median(xs: number[]): number {
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.floor(s.length / 2)]!;
}

/** Microseconds per card: the median of `runs` timed passes over every card, after `warm` untimed ones. */
function perCard(fn: () => void, cards: number, runs = 9, warm = 3): number {
  for (let i = 0; i < warm; i++) fn();
  const times: number[] = [];
  for (let i = 0; i < runs; i++) {
    const t0 = performance.now();
    fn();
    times.push(performance.now() - t0);
  }
  return Math.round(((median(times) * 1000) / cards) * 100) / 100;
}

const prepared = lines.map((line) => {
  const facts = replayFacts(line);
  const input = withDials(line.dials, () => cardInputOf(facts));
  return { line, facts, input, json: JSON.stringify({ dials: line.dials, card: input }) };
});

/** The cards grouped by their dials (a batch call takes one set), each group's inputs as one array's text. */
const boards = [...Map.groupBy(prepared, (p) => JSON.stringify(p.line.dials))].map(
  ([dials, cards]) => ({
    dials,
    cardsJson: JSON.stringify(cards.map((c) => c.input)),
    count: cards.length,
  }),
);

describe("card view cost", () => {
  it("in TypeScript", () => {
    const n = prepared.length;
    const ts = perCard(() => {
      for (const p of prepared) withDials(p.line.dials, () => cleanCardView(p.facts));
    }, n);
    const writer = perCard(() => {
      for (const p of prepared) JSON.stringify(withDials(p.line.dials, () => cardInputOf(p.facts)));
    }, n);
    console.log(
      `[card cost] ${n} cards; TypeScript cleanCardView ${ts} us a card; input writer + JSON ${writer} us a card`,
    );
  });

  for (const target of ["wasm-gc", "js"] as CoreTarget[]) {
    it(`through the core on ${target}`, async () => {
      resetCoreForTests();
      await loadCore({ target });
      const words = (await import(
        /* @vite-ignore */ pathToFileURL(path.join(root, "tools/web-core/view.mjs")).href
      )) as {
        cardWords(): Record<string, string>;
      };
      const v = core().ViewExports;
      v.viewCardWords(JSON.stringify(words.cardWords()));
      const n = prepared.length;
      let calls = 0;
      const counting = {
        width: (style: string, text: string) => (calls++, WEB_CARD_MEASURE.width(style, text)),
      };
      for (const p of prepared) JSON.parse(v.viewCardMeasured(p.json, counting));
      const callsPerCard = Math.round((calls / n) * 10) / 10;
      const oneMeasured = perCard(() => {
        for (const p of prepared) JSON.parse(v.viewCardMeasured(p.json, WEB_CARD_MEASURE));
      }, n);
      const oneSix = perCard(() => {
        for (const p of prepared) JSON.parse(v.viewCard(p.json));
      }, n);
      const boardMeasured = perCard(() => {
        for (const b of boards) JSON.parse(v.viewCards(b.dials, b.cardsJson, WEB_CARD_MEASURE));
      }, n);
      const boardSix = perCard(() => {
        for (const b of boards) JSON.parse(v.viewCardsSix(b.dials, b.cardsJson));
      }, n);
      const callOnly = perCard(() => {
        for (const p of prepared) v.viewCard(p.json);
      }, n);
      const outs = prepared.map((p) => v.viewCard(p.json));
      const parseOut = perCard(() => {
        for (const o of outs) JSON.parse(o);
      }, n);
      const stringifyIn = perCard(() => {
        for (const p of prepared) JSON.stringify({ dials: p.line.dials, card: p.input });
      }, n);
      console.log(
        `[card cost] ${target}: six px without parsing the answer ${callOnly} us a card; JSON.parse of the answer ${parseOut} us; JSON.stringify of the input ${stringifyIn} us`,
      );
      const noop = perCard(() => {
        for (let i = 0; i < n; i++) v.viewCardWords("{}");
      }, n);
      v.viewCardWords(JSON.stringify(words.cardWords()));
      const bytesIn = Math.round(prepared.reduce((s, p) => s + p.json.length, 0) / n);
      const bytesOut = Math.round(prepared.reduce((s, p) => s + v.viewCard(p.json).length, 0) / n);
      console.log(
        `[card cost] ${target} (${coreInfo().target}): per card ${oneMeasured} us measured by call back (${callsPerCard} widths a card), ${oneSix} us six px; per board (${boards.length} boards) ${boardMeasured} us a card measured, ${boardSix} us six px; an empty call ${noop} us; ${bytesIn} bytes in, ${bytesOut} out a card`,
      );

      // Planted: widths three times the website's must show as differences, so a clean run is not an empty one.
      const wide = {
        width: (style: string, text: string) => 3 * WEB_CARD_MEASURE.width(style, text),
      };
      let differ = 0;
      for (const p of prepared) {
        const shown = cardViewJson(
          withDials(p.line.dials, () => cleanCardView(p.facts)),
          {
            dials: p.line.dials,
            card: p.input,
          } as unknown as CardCaseInput,
        );
        const made = JSON.parse(v.viewCardMeasured(p.json, wide)) as { view: unknown };
        if (differences(shown, made.view).length) differ++;
      }
      console.log(`[card cost] ${target}: planted wide widths differ on ${differ} of ${n} cards`);
      expect(differ).toBeGreaterThan(n / 4);
      resetCoreForTests();
    }, 600_000);
  }
});
