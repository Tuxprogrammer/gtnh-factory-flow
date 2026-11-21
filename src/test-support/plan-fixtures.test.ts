/**
 * The plan files in conformance/plans/: what the website's own reader makes of each, so the mod's plans are proven
 * readable here the way the schema test proves the website's are readable there (PlanSchemaTest on the JVM).
 *
 *   valid/     hand-made plans the schema accepts (one per thing it must let through)
 *   invalid/   hand-made plans it refuses, each by the Zod schema and by plan.schema.json
 *   website/   what serializeFactoryProject writes for some of the website's own plans (rewritten with
 *              GTNH_WRITE_PLANS=1 npx vitest run plan-fixtures, then node tools/conformance/manifest.mjs --write)
 *   mod/       what the mod writes (CloudPlans.write and PlanExport.project), rewritten by the mod's test with
 *              -PwritePlans; here they must pass the Zod schema and load through the website's importer
 */
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

import { parseFactoryProjectJson, serializeFactoryProject } from "@/lib/import-export/factory-json";
import { factoryProjectSchema } from "@/lib/model/schemas";

import { conformancePath, listConformance, readConformanceText } from "./conformance";

const repo = fileURLToPath(new URL("../../", import.meta.url));

/** The website plans kept in the tree, by file name, and the repository file each is made from. */
const WEBSITE_PLANS: Record<string, string> = {
  "biodiesel-demo.json": "examples/biodiesel-demo.json",
  "wooden-axe-categories.json": "src/lib/model/__fixtures__/wooden-axe-categories.json",
  "high-amperage-reactor.json": "src/lib/solver/__fixtures__/high-amperage-reactor.json",
  "titanium-line-chembath-slim.json": "src/components/flow/__fixtures__/titanium-line-chembath.json.slim.json",
  "old-build.json": "tools/parity/qa3/fx/old-build.json",
  "old-pool.json": "tools/parity/qa3/fx/old-pool.json",
  "old-solve.json": "tools/parity/qa3/fx/old-solve.json",
};

const written = (name: string) => serializeFactoryProject(parseFactoryProjectJson(readFileSync(path.join(repo, WEBSITE_PLANS[name]), "utf8")));

describe("conformance/plans", () => {
  for (const dir of ["valid", "website", "mod"]) {
    const files = listConformance("plans", dir);
    it(`plans/${dir} has plans`, () => {
      expect(files.length).toBeGreaterThan(0);
    });
    for (const file of files) {
      it(`plans/${dir}/${file} parses and loads`, () => {
        const text = readConformanceText("plans", dir, file);
        expect(factoryProjectSchema.safeParse(JSON.parse(text)).success).toBe(true);
        expect(() => parseFactoryProjectJson(text)).not.toThrow();
      });
    }
  }

  for (const file of listConformance("plans", "invalid")) {
    it(`plans/invalid/${file} is refused`, () => {
      expect(factoryProjectSchema.safeParse(JSON.parse(readConformanceText("plans", "invalid", file))).success).toBe(false);
    });
  }

  it("plans/website is exactly the list above and each is what the website writes today", () => {
    expect(listConformance("plans", "website")).toEqual(Object.keys(WEBSITE_PLANS).sort());
    for (const name of Object.keys(WEBSITE_PLANS)) {
      expect(readConformanceText("plans", "website", name) === written(name)).toBe(true);
    }
  });

  it.runIf(process.env.GTNH_WRITE_PLANS === "1")("rewrites plans/website", () => {
    for (const name of Object.keys(WEBSITE_PLANS)) writeFileSync(conformancePath("plans", "website", name), written(name));
  });
});
