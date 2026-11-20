import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { gunzipSync } from "node:zlib";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import {
  core,
  loadCore,
  resetCoreForTests,
  wasmGcSupported,
  type CoreTarget,
} from "@/lib/core/loader";
import type { CardInput, CardInputCase } from "@/lib/core/records.generated";
import { readViewCases } from "@/test-support/view-corpus";
import { WEB_CARD_MEASURE, cardInputOf } from "./card-input";
import { caseFacts, cardViewJson, withCaseDials, type CardCaseInput } from "./card-conformance";
import {
  decodeLine,
  differenceKind,
  differences,
  replayFacts,
  sortedKeys,
  withDials,
  type RecordedCard,
} from "./card-shadow";
import { cleanCardView } from "./clean-model";

/**
 * The card shadow (restructure/adoption.md, step one): what the website's TypeScript shows on a recipe card and what
 * the compiled core's `CardViews` makes of the same facts (`ViewExports`, on both targets) must agree, text, numbers,
 * tones, order and rows. No production path calls the core. Inputs:
 *
 * - the shared card cases (conformance/view/card/cases.json): the core over each stored input gives the stored view,
 *   and the input the website writes from its own facts for the case (`cardInputOf`) is the stored input;
 * - every card the website's own tests draw (conformance/view-inputs/card-website-test-inputs.jsonl.gz, recorded by
 *   tools/audits/card-test-inputs/record.mjs): the facts replayed through `cleanCardView` and, written as the core's
 *   input, through the core with the website's own text widths (`WEB_CARD_MEASURE`).
 *
 * A difference the core and the website are known to have is listed in KNOWN with the side that is right; any other
 * difference fails.
 */
const root = process.cwd();
const present = existsSync(path.join(root, "public", "core", "manifest.json"));

/**
 * Differences between the two, by kind (`differenceKind`: the path with row positions taken out), with the reason and
 * which side is right. Empty: the two agree on every input.
 */
const KNOWN: Record<string, string> = {};

function recorded(): RecordedCard[] {
  const file = path.join(root, "conformance/view-inputs/card-website-test-inputs.jsonl.gz");
  return gunzipSync(readFileSync(file))
    .toString("utf8")
    .split("\n")
    .filter(Boolean)
    .map((line) => decodeLine<RecordedCard>(line));
}

async function words(): Promise<Record<string, string>> {
  const url = pathToFileURL(path.join(root, "tools/web-core/view.mjs")).href;
  const mod = (await import(/* @vite-ignore */ url)) as { cardWords(): Record<string, string> };
  return mod.cardWords();
}

interface Tally {
  cards: number;
  /** What the compared views held, so a pass is not an empty comparison. */
  covered?: Record<string, number>;
  kinds: Map<string, { count: number; first: string; card: string }>;
}

function note(tally: Tally, card: string, found: string[]): void {
  for (const d of found) {
    const kind = differenceKind(d);
    const k = tally.kinds.get(kind);
    if (k) k.count++;
    else tally.kinds.set(kind, { count: 1, first: d, card });
  }
}

function unknown(tally: Tally): string[] {
  return [...tally.kinds]
    .filter(([kind]) => !(kind in KNOWN))
    .map(([kind, k]) => `${kind} (${k.count} cards; first ${k.card}: ${k.first})`);
}

const targets: CoreTarget[] = wasmGcSupported() ? ["wasm-gc", "js"] : ["js"];

describe("card shadow: the website's card and the compiled core's agree", () => {
  it("has the core when CI requires it", () => {
    if (process.env.GTNH_CORE_REQUIRED === "1") expect(present).toBe(true);
  });

  for (const target of targets) {
    describe.skipIf(!present)(`on the ${target} target`, () => {
      beforeAll(async () => {
        resetCoreForTests();
        await loadCore({ target });
        const table = await words();
        expect(core().ViewExports.viewCardWords(JSON.stringify(table))).toBe(
          Object.keys(table).length,
        );
      });
      afterAll(() => resetCoreForTests());

      it("over the shared cases: the stored view, and the website writes the stored input", () => {
        const { cases } = readViewCases<CardCaseInput, unknown>("card", "cases");
        const tally: Tally = { cards: 0, kinds: new Map() };
        for (const c of cases) {
          tally.cards++;
          const made = JSON.parse(core().ViewExports.viewCard(JSON.stringify(c.input))) as {
            view: unknown;
          };
          note(tally, c.name, differences(c.view, made.view));
          const written = withCaseDials(c.input, () =>
            cardInputOf(caseFacts(c.input), c.input.card.menu),
          );
          // The input's key order means nothing (the case builders spread their edits in): compare the values.
          note(tally, c.name, differences(sortedKeys(c.input.card), sortedKeys(written), "input"));
        }
        expect(unknown(tally)).toEqual([]);
        expect(tally.cards).toBeGreaterThanOrEqual(99);
      });

      it("over every card the website's own tests draw", () => {
        const lines = recorded();
        const tally: Tally = { cards: 0, kinds: new Map() };
        for (const [i, line] of lines.entries()) {
          tally.cards++;
          const name = `recorded ${i} (${line.facts.name})`;
          const facts = replayFacts(line);
          const { website, input } = withDials(line.dials, () => ({
            website: cleanCardView(facts),
            input: cardInputOf(facts),
          }));
          const shown = cardViewJson(website, {
            dials: line.dials,
            card: input,
          } as unknown as CardCaseInput);
          const caseJson: CardInputCase = { dials: line.dials, card: input as CardInput };
          let made: { view: unknown; geometry: { anchors: unknown[] } };
          try {
            made = JSON.parse(
              withDials(line.dials, () =>
                core().ViewExports.viewCardMeasured(JSON.stringify(caseJson), WEB_CARD_MEASURE),
              ),
            );
          } catch (e) {
            note(tally, name, [`core: threw ${String(e).slice(0, 200)}`]);
            continue;
          }
          note(tally, name, differences(shown, made.view));
          const v = made.view as Record<string, unknown> & {
            inputs: unknown[];
            outputs: unknown[];
            chips: unknown[];
          };
          const covered = (tally.covered ??= {});
          const count = (key: string, n = 1) => (covered[key] = (covered[key] ?? 0) + n);
          count("port rows", v.inputs.length + v.outputs.length);
          if (v.power) count("power rows");
          if (v.foot) count("foot bars");
          if (v.chips.length) count("strips");
          if (v.tier) count("tier chips");
          if (v.amps) count("amps chips");
          if (v.glance) count("glance bars");
          if (v.circuit) count("circuits");
          if ((v.count as { rule: string }).rule === "UP_TO") count("Up to counts");
          if ((v.count as { rule: string }).rule === "EXACT") count("Exactly counts");
          const rows = website.inputs.length + website.outputs.length;
          if (made.geometry.anchors.length !== rows)
            note(tally, name, [`geometry.anchors: ${made.geometry.anchors.length} for ${rows}`]);
        }
        const summary = [...tally.kinds].map(
          ([kind, k]) => `${kind}: ${k.count} (${KNOWN[kind] ? "known" : "NEW"})`,
        );
        console.log(
          `[card shadow] ${target}: ${tally.cards} recorded cards (${JSON.stringify(tally.covered)}), ${tally.kinds.size} kinds of difference${summary.length ? `\n  ${summary.join("\n  ")}` : ""}`,
        );
        expect(unknown(tally)).toEqual([]);
        expect(tally.cards).toBeGreaterThan(700);
        expect(tally.covered?.["port rows"]).toBeGreaterThan(1000);
        expect(tally.covered?.["foot bars"]).toBeGreaterThan(50);
      }, 300_000);
    });
  }
});
