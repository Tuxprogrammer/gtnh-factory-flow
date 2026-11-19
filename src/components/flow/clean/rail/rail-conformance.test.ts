import { describe, expect, it } from "vitest";
import { listConformance, readConformanceJson, readConformanceText } from "@/test-support/conformance";
import { mockPlans } from "@/lib/solver/plan/__fixtures__/mock-extras";
import { peakCount } from "@/lib/model/peak-count";
import { noticeList, NOTICE_WORDS, type NoticeInput } from "./notice-core";
import {
  mathVectors,
  mockRailInput,
  noticeCases,
  railCases,
  viewFiles,
  type NoticeCase,
  type RailCase,
} from "./rail-conformance";
import { dialDivisor, dialSuffix, dialValue, railViewOf, type RailInput } from "./rail-core";
import {
  allAt,
  evenShares,
  nudged,
  parseRateInput,
  sameShares,
  shareAtX,
  steppedRate,
  wheelStep,
} from "./rail-math";

/**
 * conformance/view/ holds what the rail and the notices bar print for plans, as input records and views. The website's
 * own code must give each stored view from its stored input (the core view module checks the same files from Java), and
 * the files must be what the code makes now (regenerate with `npx tsx tools/conformance/view/generate.ts`, then
 * `node tools/conformance/manifest.mjs --write`).
 */
const railFile = readConformanceJson("view", "rail-cases.json") as RailCase[];
const noticeFile = readConformanceJson("view", "notice-cases.json") as NoticeCase[];
const mathFile = readConformanceJson("view", "rail-math.json") as ReturnType<typeof mathVectors>;

describe("view conformance files", () => {
  it("are what the code makes now", () => {
    const made = viewFiles();
    expect(listConformance("view")).toEqual(Object.keys(made).sort());
    for (const [name, text] of Object.entries(made)) {
      expect(readConformanceText("view", name) === text, `${name} differs: regenerate it`).toBe(true);
    }
  });
});

describe("the rail's cases", () => {
  it("has a case for every mock plan at every view", () => {
    for (const mock of mockPlans) {
      expect(railFile.filter((c) => c.name.startsWith(`${mock.name}: `))).toHaveLength(6);
    }
  });

  it.each(railFile.map((c) => [c.name, c] as const))("%s: the stored input gives the stored view", (_n, c) => {
    expect(JSON.parse(JSON.stringify(railViewOf(c.input)))).toEqual(c.view);
  });

  it("reads each mock plan through the adapters into the stored input", () => {
    for (const mock of mockPlans) {
      const stored = railFile.find((c) => c.name === `${mock.name}: peak eu`)!;
      const made = mockRailInput(mock, stored.input.view) satisfies RailInput;
      expect(JSON.parse(JSON.stringify(made))).toEqual(stored.input);
    }
  });

  it("shows no view with a trailing undefined: every optional is absent, not null", () => {
    expect(JSON.stringify(railFile)).not.toContain("null");
  });
});

describe("the notices' cases", () => {
  it.each(noticeFile.map((c) => [c.name, c] as const))("%s: the stored input gives the stored list", (_n, c) => {
    expect(JSON.parse(JSON.stringify(noticeList(c.input as NoticeInput)))).toEqual(c.notices);
  });

  it("names only keys the word table has", () => {
    const keys = new Set<string>();
    for (const c of noticeFile) {
      for (const n of c.notices) {
        keys.add(n.key);
        if (n.tag) keys.add(n.tag);
      }
    }
    for (const key of keys) expect(NOTICE_WORDS[key], key).toBeDefined();
  });

  it("covers every notice key the word table says outside the holders", () => {
    const used = new Set(noticeFile.flatMap((c) => c.notices.map((n) => n.key)));
    const missing = Object.keys(NOTICE_WORDS).filter(
      (k) => !used.has(k) && !k.includes(".holder.") && !k.endsWith(".tag") && k !== "gtnhplanner.notice.and",
    );
    expect(missing).toEqual([]);
  });
});

describe("the arithmetic vectors", () => {
  const none = (n: number | null) => (n === null ? undefined : n);
  it("parse a rate box", () => {
    for (const v of mathFile.parseRateInput) expect(none(v.value), v.text).toBe(parseRateInput(v.text));
  });
  it("nudge, split and press", () => {
    for (const v of mathFile.nudged) expect(nudged(v.shares, v.index, v.step)).toEqual(v.out);
    for (const v of mathFile.evenShares) expect(evenShares(v.count)).toEqual(v.out);
    for (const v of mathFile.allAt) expect(allAt(v.count, v.index)).toEqual(v.out);
    for (const v of mathFile.shareAtX) expect(shareAtX(v.x, v.left, v.width)).toBe(v.out);
    for (const v of mathFile.sameShares) expect(sameShares(v.a, v.b)).toBe(v.out);
  });
  it("step the wheel", () => {
    for (const v of mathFile.wheelStep) expect(wheelStep(v.ctrl, v.shift)).toBe(v.out);
    for (const v of mathFile.steppedRate) {
      expect(steppedRate(v.shown, v.direction as 1 | -1, v.ctrl, v.shift)).toBe(v.out);
    }
  });
  it("count peak machines", () => {
    for (const v of mathFile.peakCount) expect(peakCount(v.solved, none(v.set), v.keepSolved)).toBe(v.out);
  });
  it("read the power dial", () => {
    for (const v of mathFile.dial) {
      const tier = v.ownTier ?? undefined;
      expect(none(v.divisor)).toBe(dialDivisor(v.unit, tier));
      expect(dialValue(v.euT, v.unit, tier)).toBe(v.value);
      expect(dialSuffix(v.unit, tier)).toBe(v.suffix);
    }
  });
});
