/**
 * Test support for the layout adoption's shadow run (restructure/adoption.md): the website's router and arrange tests
 * run their scenarios through the TypeScript and through the compiled core (`layout-core.ts`), and every answer must
 * be the same. The core is built by tools/copy-core.mjs; a checkout with only Node has none and the core half skips,
 * unless CI requires it (GTNH_CORE_REQUIRED=1).
 */
import { existsSync } from "node:fs";
import path from "node:path";
import { expect } from "vitest";
import type { CoreExports } from "@/lib/core/exports.generated";
import { loadCore, resetCoreForTests, type CoreTarget } from "@/lib/core/loader";
import type { GridObstacle, GridRoutedEdge, GridRouteRequest } from "./grid-edge-router";
import { IncrementalRouter, type IncrementalStats, type RouterSnapshot } from "./incremental-router";
import { CoreBoardRouter } from "./layout-core";
import type { RouterTuning } from "./router-tuning";

export type LayoutExports = CoreExports["LayoutExports"];

const present = existsSync(path.join(process.cwd(), "public", "core", "manifest.json"));
const required = process.env.GTNH_CORE_REQUIRED === "1";

/**
 * The core's layout exports on each target, loaded one after the other (the loader keeps one instance; an exports
 * object stays usable after the loader forgets it). Leaves the loader holding the default target. Empty when no core
 * is built and none is required.
 */
export async function layoutTargets(): Promise<Array<[CoreTarget, LayoutExports]>> {
  if (!present && !required) return [];
  const out: Array<[CoreTarget, LayoutExports]> = [];
  for (const target of ["wasm-gc", "js"] as const) {
    resetCoreForTests();
    out.push([target, (await loadCore({ target })).LayoutExports]);
  }
  resetCoreForTests();
  await loadCore();
  return out;
}

/** Points by edge id, as JSON text: what the board draws. */
export function drawn(routes: Map<string, GridRoutedEdge> | Iterable<{ edgeId: string; points: unknown }>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const r of routes instanceof Map ? routes.values() : routes) out[r.edgeId] = JSON.stringify(r.points);
  return out;
}

/** A snapshot as plain JSON, keys with no value dropped: how route-memory.ts stores it. */
function plain(snapshot: RouterSnapshot | undefined): unknown {
  return snapshot === undefined ? undefined : JSON.parse(JSON.stringify(snapshot));
}

/**
 * `IncrementalRouter` that also routes every board through the core and fails the test on any difference: routes,
 * the incremental stats, and the stored state. Answers with the TypeScript's routes, so a test's own expectations
 * (widths, vertices) read the TypeScript as before.
 */
export class ShadowRouter extends IncrementalRouter {
  private readonly core: CoreBoardRouter;

  constructor(layout: LayoutExports) {
    super();
    this.core = new CoreBoardRouter(layout);
  }

  override solve(
    obstacles: GridObstacle[],
    requests: GridRouteRequest[],
    tuning: RouterTuning,
    options: { thorough: boolean; stats?: IncrementalStats },
  ): Map<string, GridRoutedEdge> {
    const coreStats = {} as IncrementalStats;
    const got = this.core.solve(obstacles, requests, tuning, { thorough: options.thorough, stats: coreStats });
    const tsStats = options.stats ?? ({} as IncrementalStats);
    const want = super.solve(obstacles, requests, tuning, { thorough: options.thorough, stats: tsStats });
    expect(drawn(got), "core routes").toEqual(drawn(want));
    expect(
      { fresh: coreStats.fresh, routed: coreStats.routed, kept: coreStats.kept, crossings: coreStats.crossings },
      "core stats",
    ).toEqual({ fresh: tsStats.fresh, routed: tsStats.routed, kept: tsStats.kept, crossings: tsStats.crossings });
    expect(plain(this.core.snapshot()), "core snapshot").toEqual(plain(super.snapshot()));
    return want;
  }

  override restore(snapshot: RouterSnapshot) {
    super.restore(snapshot);
    this.core.restore(snapshot);
  }

  override reset() {
    super.reset();
    this.core.reset();
  }
}
