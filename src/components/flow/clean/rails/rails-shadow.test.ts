import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import {
  core,
  loadCore,
  resetCoreForTests,
  wasmGcSupported,
  type CoreTarget,
} from "@/lib/core/loader";
import { railsThroughCore } from "@/lib/core/rails-wire";
import type { NodeRailsInput } from "@/lib/core/records.generated";
import { parseFactoryProjectJson } from "@/lib/import-export/factory-json";
import type { FactoryProject } from "@/lib/model/types";
import { getPoolProject } from "@/lib/solver/pool-mode";
import { useCoreSolver } from "@/test-support/core-solve";
import { calculatePlanBoardThroughput } from "@/lib/solver/plan/adapter/open";
import { buildRailPorts, deriveNodeVerdict } from "../../node-verdict";
import { railsInputOf, railsViewOf } from "./rails-conformance";

/**
 * The rails shadow: for every card of every reference plan, solved the way a landing plan is, what the website's
 * `deriveNodeVerdict` and `buildRailPorts` decide and what the compiled core's `NodeRails` makes of the same facts
 * (`railsInputOf`, through `railsThroughCore`, on both targets) must agree, row for row. Also the count of verdicts the
 * plan boards reach: the core answers `balanced`, `demand-set`, `off`, `no-recipe` and `unwired`; any other verdict on
 * a plan board is the host's own ladder and is counted here, so a plan board that reaches one shows.
 *
 * A difference the two are known to have is listed in KNOWN with the side that is right; any other fails.
 */
const root = process.cwd();
const present = existsSync(path.join(root, "public", "core", "manifest.json"));

const KNOWN: Record<string, string> = {};

const CORE_VERDICTS = new Set(["off", "no-recipe", "unwired", "balanced", "demand-set"]);

const DIRS = [
  "conformance/plans/website",
  "conformance/plans/mod",
  "conformance/plans/valid",
  "src/components/flow/__fixtures__",
  "jvm/mod/src/test/resources/factory-flow",
  "examples",
];

function plans(): { name: string; project: FactoryProject }[] {
  const out: { name: string; project: FactoryProject }[] = [];
  for (const dir of DIRS) {
    const full = path.join(root, dir);
    if (!existsSync(full)) continue;
    for (const f of readdirSync(full)
      .filter((f) => f.endsWith(".json"))
      .sort()) {
      try {
        out.push({
          name: `${dir}/${f}`,
          project: parseFactoryProjectJson(readFileSync(path.join(full, f), "utf8")),
        });
      } catch {
        // Not a plan this build reads (a slim fixture, a part of one).
      }
    }
  }
  return out;
}

const targets: CoreTarget[] = wasmGcSupported() ? ["wasm-gc", "js"] : ["js"];

describe("rails shadow: the website's rows and verdict and the compiled core's agree", () => {
  it("has the core when CI requires it", () => {
    if (process.env.GTNH_CORE_REQUIRED === "1") expect(present).toBe(true);
  });

  for (const target of targets) {
    describe.skipIf(!present)(`on the ${target} target`, () => {
      beforeAll(async () => {
        resetCoreForTests();
        await loadCore({ target });
        // The books the rails read are the plan solve's: on the core, as every board's are.
        await useCoreSolver(target);
      }, 120_000);
      afterAll(() => resetCoreForTests());

      it("over every card of every reference plan", () => {
        const verdicts = new Map<string, number>();
        const kinds = new Map<string, { count: number; first: string }>();
        let cards = 0;
        let rows = 0;
        for (const { name, project: raw } of plans()) {
          const result = calculatePlanBoardThroughput(raw);
          const project = getPoolProject(raw);
          for (const node of project.nodes) {
            const recipe = project.recipes.find((r) => r.id === node.recipeId);
            if (!recipe) continue;
            cards++;
            const verdict = deriveNodeVerdict(project, result, node.id);
            const rails = buildRailPorts(project, result, node.id, recipe, verdict);
            verdicts.set(verdict.kind, (verdicts.get(verdict.kind) ?? 0) + 1);
            const input = railsInputOf(
              project,
              result,
              node.id,
              recipe,
            ) as unknown as NodeRailsInput;
            const made = railsThroughCore(core().ViewExports, input);
            const website = JSON.parse(
              JSON.stringify(railsViewOf(verdict, rails, recipe, project.solveMode === true)),
            );
            rows += rails.inputs.length + rails.outputs.length;
            const a = JSON.stringify(website);
            const b = JSON.stringify(made);
            if (a !== b) {
              const kind = `${verdict.kind}`;
              const k = kinds.get(kind);
              if (k) k.count++;
              else
                kinds.set(kind, {
                  count: 1,
                  first: `${name} ${node.id}\n  site ${a.slice(0, 600)}\n  core ${b.slice(0, 600)}`,
                });
            }
          }
        }
        console.log(
          `[rails shadow] ${target}: ${cards} cards, ${rows} rows, verdicts ${JSON.stringify(Object.fromEntries(verdicts))}`,
        );
        const unknown = [...kinds]
          .filter(([k]) => !(k in KNOWN))
          .map(([k, v]) => `${k} (${v.count}; first ${v.first})`);
        expect(unknown).toEqual([]);
        // No plan board reaches the host's own ladder: every card's verdict is one the core answers.
        expect([...verdicts.keys()].filter((k) => !CORE_VERDICTS.has(k))).toEqual([]);
        expect(cards).toBeGreaterThan(250);
        expect(rows).toBeGreaterThan(900);
      }, 300_000);
    });
  }
});
