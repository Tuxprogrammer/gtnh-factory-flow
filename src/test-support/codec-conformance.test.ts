import { createHash } from "node:crypto";
import { writeFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { parseFactoryProjectJson } from "@/lib/import-export/factory-json";
import { factoryProjectSchema } from "@/lib/model/schemas";
import {
  conformancePath,
  listConformance,
  readConformanceJson,
  readConformanceText,
} from "./conformance";

/**
 * The website's side of the shared plan codec (core-codec's PlanJson, CodecTest on the JVM): a plan's text read and
 * written again is what JSON.stringify writes, for the hand-written cases and every committed plan, and the plans the
 * mod writes name each wire's port as the card's recipe names it, so every wire lands on the website. Rewrite
 * conformance/codec/plan-text.json with GTNH_WRITE_GOLDEN=1.
 */

type PlanText = {
  about: string;
  cases: { name: string; text: string; written: string }[];
  plans: { path: string; sha256: string }[];
};

const PLAN_DIRS = ["plans/valid", "plans/website", "plans/mod"];

function compact(text: string): string {
  return JSON.stringify(JSON.parse(text));
}

function sha256(text: string): string {
  return createHash("sha256").update(text, "utf8").digest("hex");
}

function golden(previous: PlanText): string {
  const out: PlanText = {
    about: previous.about,
    cases: previous.cases.map((c) => ({ name: c.name, text: c.text, written: compact(c.text) })),
    plans: PLAN_DIRS.flatMap((dir) =>
      listConformance(dir).map((f) => ({
        path: `${dir}/${f}`,
        sha256: sha256(compact(readConformanceText(dir, f))),
      })),
    ),
  };
  return `${JSON.stringify(out, null, 2)}\n`;
}

describe("plan text, as both sides write it", () => {
  const file = readConformanceJson("codec", "plan-text.json") as PlanText;

  it("is what plan-text.json says", () => {
    const text = golden(file);
    if (process.env.GTNH_WRITE_GOLDEN)
      writeFileSync(conformancePath("codec", "plan-text.json"), text);
    expect(text).toBe(readConformanceText("codec", "plan-text.json"));
  });

  it("covers every committed plan", () => {
    expect(file.plans.length).toBeGreaterThanOrEqual(20);
    for (const c of file.cases) expect(compact(c.text), c.name).toBe(c.written);
  });
});

type Slot = { id?: string; alternatives?: { id?: string }[] };
type Recipe = { id: string; inputs?: Slot[]; outputs?: Slot[] };
type PlanNode = {
  id: string;
  recipeId: string;
  extraRecipes?: { recipeId: string }[];
  recipeInputOverrides?: Record<string, Slot>;
};
type Plan = {
  recipes: Recipe[];
  nodes: PlanNode[];
  edges: {
    id: string;
    source: string;
    target: string;
    sourceHandle?: string;
    targetHandle?: string;
  }[];
};

/** The ids a recipe section's side names: each slot's own and its alternatives (and the card's input picks). */
function sideIds(recipe: Recipe | undefined, node: PlanNode, output: boolean): Set<string> {
  const ids = new Set<string>();
  for (const slot of (output ? recipe?.outputs : recipe?.inputs) ?? []) {
    if (slot.id) ids.add(slot.id);
    for (const a of slot.alternatives ?? []) if (a.id) ids.add(a.id);
  }
  if (!output)
    for (const o of Object.values(node.recipeInputOverrides ?? {})) if (o.id) ids.add(o.id);
  return ids;
}

/** The section and resource id a handle names (`r<n>:side:kind:id[:slot]`), or null. */
function parseHandle(handle: string | undefined): { section: number; id: string } | null {
  if (!handle) return null;
  const m = /^r(\d+):(.*)$/.exec(handle);
  const bare = m ? m[2] : handle;
  const parts = bare.split(":");
  if (parts.length < 3) return null;
  return { section: m ? Number(m[1]) : 0, id: decodeURIComponent(parts[2]) };
}

describe("plans the mod writes to files, links and posts", () => {
  // export-* are PlanExport's. cloud-* are the cloud map's, which still names a drawer's wire by the drawer's own key.
  for (const f of listConformance("plans/mod").filter((name) => name.startsWith("export-"))) {
    it(`${f}: every wire names a port its card's recipe has`, () => {
      const plan = readConformanceJson("plans/mod", f) as Plan;
      const recipes = new Map(plan.recipes.map((r) => [r.id, r]));
      const nodes = new Map(plan.nodes.map((n) => [n.id, n]));
      const unresolved: string[] = [];
      for (const e of plan.edges) {
        for (const [end, handle, output] of [
          [e.source, e.sourceHandle, true],
          [e.target, e.targetHandle, false],
        ] as const) {
          const node = nodes.get(end);
          if (!node) continue;
          const h = parseHandle(handle);
          const recipeId =
            h && h.section > 0 ? node.extraRecipes?.[h.section - 1]?.recipeId : node.recipeId;
          if (!h || !sideIds(recipeId ? recipes.get(recipeId) : undefined, node, output).has(h.id))
            unresolved.push(`${e.id} ${handle}`);
        }
      }
      expect(unresolved).toEqual([]);
    });
  }
});

describe("round-trip plans (the mod's PlanRoundTripTest reads the same files)", () => {
  for (const f of listConformance("codec/round-trip")) {
    it(`${f} is a plan the website opens`, () => {
      const text = readConformanceText("codec/round-trip", f);
      expect(factoryProjectSchema.safeParse(JSON.parse(text)).success).toBe(true);
      expect(() => parseFactoryProjectJson(text)).not.toThrow();
    });
  }
});
