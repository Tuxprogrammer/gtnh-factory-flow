import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { gunzipSync } from "node:zlib";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { core, loadCore, resetCoreForTests } from "@/lib/core/loader";
import { decodeLine, replayFacts, withDials, type RecordedCard } from "./card-shadow";
import { coreCardView } from "./card-view";
import { cleanCardView } from "./clean-model";

/**
 * A plan card's power row is what the solve runs: one machine's draw times the solved count (or the count the
 * player set), never the count stored on the node, which in a plan is only where the solve started. The same cards
 * with the stored count changed must print the same power, in the website's builders and in the core's card view.
 */
const root = process.cwd();
const present = existsSync(path.join(root, "public", "core", "manifest.json"));

function powered(): RecordedCard[] {
  const file = path.join(root, "conformance/view-inputs/card-website-test-inputs.jsonl.gz");
  return gunzipSync(readFileSync(file))
    .toString("utf8")
    .split("\n")
    .filter(Boolean)
    .map((line) => decodeLine<RecordedCard>(line))
    .filter((line) => line.facts.powerReport !== undefined && line.facts.result !== undefined);
}

function withStoredCount(line: RecordedCard, machineCount: number) {
  const facts = replayFacts(line);
  return { ...facts, projectNode: { ...facts.projectNode, machineCount } };
}

describe("a plan card's power ignores the stored machine count", () => {
  it("prints the same power row whatever count the node stores", () => {
    const lines = powered();
    expect(lines.length).toBeGreaterThan(0);
    for (const line of lines) {
      const power = (count: number) =>
        withDials(line.dials, () => cleanCardView(withStoredCount(line, count)).power);
      expect(power(7)).toEqual(power(1));
      expect(power(0.25)).toEqual(power(1));
    }
  });

  describe.skipIf(!present)("through the core", () => {
    beforeAll(async () => {
      resetCoreForTests();
      await loadCore({ target: "js" });
    });
    afterAll(() => resetCoreForTests());

    it("prints the same power row whatever count the node stores", () => {
      for (const line of powered()) {
        const power = (count: number) =>
          withDials(line.dials, () => coreCardView(core(), withStoredCount(line, count)).power);
        expect(power(7)).toEqual(power(1));
      }
    });
  });
});
