import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { gunzipSync } from "node:zlib";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { setAdoptOverride, withAdopt } from "@/lib/core/adopt";
import type { CoreExports } from "@/lib/core/exports.generated";
import { core, loadCore, resetCoreForTests, wasmGcSupported, type CoreTarget } from "@/lib/core/loader";
import type { SpecialView as SpecialViewRecord } from "@/lib/core/records.generated";
import { readViewCases } from "@/test-support/view-corpus";
import { cardDials, cardInputOf } from "../card-input";
import { caseFacts, withCaseDials } from "../card-conformance";
import {
  decodeLine,
  differences,
  replayFacts,
  sortedKeys,
  withDials,
  type RecordedCard,
} from "../card-shadow";
import { CARD_TARGETS } from "../card-view";
import { cleanCardView } from "../clean-model";
import { resetMeasuredFont } from "../text-measure";
import {
  caseSpecialFacts,
  specialPartsJson,
  websiteSpecialParts,
  type SpecialCaseInput,
} from "./special-conformance";
import { specialParts, type SpecialFacts } from "./special-parts";
import {
  coreSpecialParts,
  forgetSpecialViews,
  partsOfRecord,
  specialInputOf,
  specialPartsOf,
  specialPartsWith,
  specialThroughCore,
  specialViewCounts,
} from "./special-view";

/**
 * The special cards' shadow and switch (restructure/adoption.md): a special card's own parts through the compiled core
 * (`SpecialViews`, special-view.ts) are the website's own rules' (`specialParts`) on the shared special cases and on
 * every special card the website's tests and the plan fixtures draw (conformance/view-inputs), on both targets; with the
 * "card" area on and the core loaded, the board's special cards of the kinds the rules cover take the core's parts.
 */
const root = process.cwd();
const present = existsSync(path.join(root, "public", "core", "manifest.json"));

type RecordedSpecial = RecordedCard & { special: SpecialFacts };

function recorded(): RecordedSpecial[] {
  const file = path.join(root, "conformance/view-inputs/special-website-test-inputs.jsonl.gz");
  return gunzipSync(readFileSync(file))
    .toString("utf8")
    .split("\n")
    .filter(Boolean)
    .map((line) => decodeLine<RecordedSpecial>(line));
}

/** The website's parts of a recorded special card, under its dials. */
function websiteParts(line: RecordedSpecial) {
  const facts = replayFacts(line);
  return withDials(line.dials, () => specialParts(cleanCardView(facts), line.special));
}

describe("special card switch", () => {
  beforeEach(() => forgetSpecialViews());

  it("reads every shared case's stored record back as the website's parts", () => {
    const { cases } = readViewCases<SpecialCaseInput, SpecialViewRecord>("special", "cases");
    for (const c of cases) {
      expect(partsOfRecord(c.view), c.name).toEqual(websiteSpecialParts(c.input));
    }
  });

  it("writes each case's special facts as the stored input, and each case's card as its stored card", () => {
    const { cases } = readViewCases<SpecialCaseInput, unknown>("special", "cases");
    for (const c of cases) {
      expect(specialInputOf(caseSpecialFacts(c.input)), c.name).toEqual(c.input.special);
      const card = withCaseDials(c.input, () => cardInputOf(caseFacts(c.input), c.input.card.menu));
      expect(differences(sortedKeys(c.input.card), sortedKeys(card), "card"), c.name).toEqual([]);
    }
  });

  it("records holder cards from the plan fixtures and the tests: both holders, empty and holding", () => {
    const lines = recorded();
    const kinds = new Set(lines.map((l) => `${l.special.kind}:${l.special.holder?.empty}`));
    expect([...kinds].sort()).toEqual([
      "custom-rate:false",
      "custom-rate:true",
      "tank:false",
      "tank:true",
    ]);
    expect(lines.length).toBeGreaterThanOrEqual(25);
  });

  it("draws with the website's rules while the area is off or the core is not there", () => {
    const line = recorded()[0]!;
    withAdopt({ card: false }, () => {
      const facts = replayFacts(line);
      expect(
        withDials(line.dials, () => specialPartsOf(facts, line.special, cleanCardView(facts))),
      ).toEqual(websiteParts(line));
    });
    expect(specialViewCounts()).toEqual({ core: 0, kept: 0, ts: 1 });
  });

  it("draws with the website's rules when the core fails on a card, and says so once", () => {
    const line = recorded()[0]!;
    const broken = {
      ViewExports: {
        viewCardWords: () => 0,
        viewSpecialJsKept: () => {
          throw new Error("planted");
        },
      },
    } as unknown as CoreExports;
    const quiet = console.error;
    const said: unknown[] = [];
    console.error = (...args: unknown[]) => void said.push(args);
    try {
      const facts = replayFacts(line);
      const draw = () =>
        withDials(line.dials, () => specialPartsWith(broken, facts, line.special, cleanCardView(facts)));
      expect(draw()).toEqual(websiteParts(line));
      expect(draw()).toEqual(websiteParts(line));
    } finally {
      console.error = quiet;
    }
    expect(said.length).toBeLessThanOrEqual(1);
    expect(specialViewCounts()).toMatchObject({ core: 0, ts: 2 });
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

      it("makes every shared case's stored parts, six px a character", () => {
        const { cases } = readViewCases<SpecialCaseInput, SpecialViewRecord>("special", "cases");
        for (const c of cases) {
          const got = specialThroughCore(core(), c.input);
          // Key for key and in the record's key order: the objects JSON.parse makes of the record's JSON.
          expect(JSON.stringify(got), c.name).toBe(JSON.stringify(c.view));
        }
        expect(cases.length).toBeGreaterThanOrEqual(25);
      });

      it("gives the website's parts on every special card the website's tests and the fixtures draw", () => {
        const lines = recorded();
        for (const [i, line] of lines.entries()) {
          const facts = replayFacts(line);
          const want = websiteParts(line);
          const got = withDials(line.dials, () => coreSpecialParts(core(), facts, line.special));
          expect(differences(specialPartsJson(want), specialPartsJson(got), "parts"), `recorded ${i}`).toEqual(
            [],
          );
          expect(got, `recorded ${i}`).toEqual(want);
        }
        const { core: called, kept } = specialViewCounts();
        expect(called + kept).toBe(lines.length);
      }, 120_000);

      it("takes a card's last parts back while its inputs, dials and font are the same", () => {
        const line = recorded().find((l) => l.special.holder?.dial && !l.special.holder.empty)!;
        const parts = (facts = replayFacts(line), special = line.special, dials = line.dials) =>
          withDials(dials, () => coreSpecialParts(core(), facts, special));
        const first = parts();
        expect(specialViewCounts()).toMatchObject({ core: 1, kept: 0 });
        expect(parts()).toEqual(first);
        expect(specialViewCounts().kept).toBe(1);
        // The board's rate unit repaints the dial's rate.
        const minute = { ...line.dials, rate: line.dials.rate === "MINUTE" ? "SECOND" : "MINUTE" } as const;
        const repainted = parts(undefined, undefined, minute);
        expect(specialViewCounts().core).toBe(2);
        expect(repainted.chips.find((c) => c.chip.id === "rate")?.chip.label).not.toBe(
          first.chips.find((c) => c.chip.id === "rate")?.chip.label,
        );
        // So does a face that loaded late, and a turned dial.
        resetMeasuredFont();
        parts();
        expect(specialViewCounts().core).toBe(3);
        const dial = line.special.holder!.dial!;
        parts(undefined, {
          ...line.special,
          holder: { ...line.special.holder!, dial: { ...dial, perSecond: dial.perSecond + 1 } },
        });
        expect(specialViewCounts().core).toBe(4);
        // The card's dials are what the input carries.
        expect(cardDials(replayFacts(line).averageDraw)).toEqual(cardDials(line.facts.averageDraw));
      });

      it.skipIf(!CARD_TARGETS.includes(target))(
        "is the switch's path when the area is on, for the kinds the shared rules cover",
        () => {
          const line = recorded()[0]!;
          const facts = replayFacts(line);
          withAdopt({ card: true }, () => {
            const got = withDials(line.dials, () =>
              specialPartsOf(facts, line.special, cleanCardView(facts)),
            );
            expect(got).toEqual(websiteParts(line));
            expect(specialViewCounts()).toMatchObject({ core: 1, ts: 0 });
          });
        },
      );
    });
  }
});
