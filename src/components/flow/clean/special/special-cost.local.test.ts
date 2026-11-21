/**
 * What a changed special card's own parts cost through the compiled core (a scratch harness, not in the suite): the
 * special call that makes the card's view again on the way (`viewSpecialJsKept`, the whole case crossing), against
 * the one that starts from the card view the core made for the card (`viewSpecialJsHeld`, only the kind's facts
 * crossing), beside the card's own call (`viewCardJsKept`) and the website's own rules, over every recorded special
 * card (conformance/view-inputs), six interleaved runs on each target.
 *
 *   npx vitest run src/components/flow/clean/special/special-cost.local.test.ts --config vitest.local.config.ts
 */
import { readFileSync } from "node:fs";
import path from "node:path";
import { gunzipSync } from "node:zlib";
import { describe, expect, it } from "vitest";
import { core, loadCore, resetCoreForTests, type CoreTarget } from "@/lib/core/loader";
import { WEB_CARD_MEASURE, cardDials, cardInputOf } from "../card-input";
import { decodeLine, replayFacts, withDials, type RecordedCard } from "../card-shadow";
import { words } from "../card-view";
import { cleanCardView } from "../clean-model";
import { currentFontEpoch } from "../text-measure";
import { specialParts, type SpecialFacts } from "./special-parts";
import { specialInputOf, specialThroughCore, specialThroughHeld } from "./special-view";

type RecordedSpecial = RecordedCard & { special: SpecialFacts };
const lines = gunzipSync(
  readFileSync(path.join(process.cwd(), "conformance/view-inputs/special-website-test-inputs.jsonl.gz")),
)
  .toString("utf8")
  .split("\n")
  .filter(Boolean)
  .map((l) => decodeLine<RecordedSpecial>(l));

function median(xs: number[]): number {
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.floor(s.length / 2)]!;
}

/** Microseconds per card of one timed pass. */
function pass(fn: () => void, cards: number): number {
  const t0 = performance.now();
  fn();
  return ((performance.now() - t0) * 1000) / cards;
}

const M = WEB_CARD_MEASURE as unknown as Record<string, unknown>;

describe("special card cost", () => {
  for (const target of ["wasm-gc", "js"] as CoreTarget[]) {
    it(`on ${target}`, async () => {
      resetCoreForTests();
      await loadCore({ target });
      const c = core();
      words(c);
      const epoch = currentFontEpoch();
      const prepared = lines.map((line) => {
        const facts = replayFacts(line);
        const dials = cardDials(facts.averageDraw);
        const card = withDials(line.dials, () => cardInputOf(facts));
        const special = specialInputOf(line.special);
        return { line, facts, dials, card, special, ticket: 0 };
      });
      const n = prepared.length;
      const cardCall = () => {
        for (const p of prepared) {
          c.ViewExports.viewCardJsKept({ dials: p.dials, card: p.card }, M, epoch);
          p.ticket = c.ViewExports.viewCardTicket();
        }
      };
      const before = () => {
        for (const p of prepared) specialThroughCore(c, { dials: p.dials, card: p.card, special: p.special }, epoch);
      };
      const after = () => {
        for (const p of prepared) {
          if (!specialThroughHeld(c, p.ticket, p.special, epoch)) throw new Error("not held");
        }
      };
      const rules = () => {
        for (const p of prepared) withDials(p.line.dials, () => specialParts(cleanCardView(p.facts), p.line.special));
      };
      // Warm every path, then six interleaved runs of nine passes each.
      for (let i = 0; i < 5; i++) {
        cardCall();
        before();
        after();
        rules();
      }
      const runs: Record<string, number[]> = { card: [], before: [], after: [], rules: [] };
      for (let r = 0; r < 6; r++) {
        const per: Record<string, number[]> = { card: [], before: [], after: [], rules: [] };
        for (let i = 0; i < 9; i++) {
          per.card!.push(pass(cardCall, n));
          per.before!.push(pass(before, n));
          per.after!.push(pass(after, n));
          per.rules!.push(pass(rules, n));
        }
        for (const k of Object.keys(per)) runs[k]!.push(median(per[k]!));
      }
      const show = (xs: number[]) =>
        `${median(xs).toFixed(1)} (${Math.min(...xs).toFixed(1)}-${Math.max(...xs).toFixed(1)})`;
      console.log(
        `[special cost] ${target}, ${n} special cards, us a card, median (range) of six runs: ` +
          `card call ${show(runs.card!)}; special before (card made again) ${show(runs.before!)}; ` +
          `special after (held card) ${show(runs.after!)}; website rules incl. card builders ${show(runs.rules!)}`,
      );
      expect(n).toBeGreaterThan(0);
    }, 300_000);
  }
});
