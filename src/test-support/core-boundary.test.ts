import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { applyAllowList, scanBoundary, type AllowedFile } from "./core-boundary";

/**
 * Files under (or reached from) src/lib/model, solver, machines and power that may still
 * break the boundary. One entry per file, each with the reason and what retires it. A new
 * entry needs a reason a reviewer accepts; the aim is an empty list.
 */
const ALLOWED: AllowedFile[] = [
  {
    file: "src/lib/solver/lp-engine.ts",
    reason:
      "Loads the HiGHS WebAssembly solver with fetch. Retires when solving goes through the shared core.",
  },
  {
    file: "src/lib/solver/plan/parity/scenario-kit.ts",
    leaf: true,
    reason:
      "A test harness, imported only by the parity scenarios and the parity tools: it renders the card's rail model, so it reaches the board view model and the store. Retires when it moves out of the solver directory.",
  },
];

const SRC = path.resolve(__dirname, "..");

describe("the shared-core boundary", () => {
  it("keeps React, the DOM, browser storage, Next, Zustand and src/lib/format out of the plan code", () => {
    const { violations, visited } = scanBoundary(SRC, ALLOWED);
    // The scan must actually be walking the code, not an empty tree.
    expect(visited).toContain("src/lib/model/resources.ts");
    expect(visited).toContain("src/lib/solver/overclock.ts");
    expect(visited.length).toBeGreaterThan(100);
    const { remaining, stale } = applyAllowList(violations, ALLOWED, visited);
    expect(remaining.map((v) => `${v.file}: ${v.kind} ${v.what}${v.via ? ` (via ${v.via})` : ""}`)).toEqual([]);
    expect(stale).toEqual([]);
  });

  it("only allows files that exist and say why", () => {
    for (const entry of ALLOWED) {
      expect(fs.existsSync(path.resolve(SRC, "..", entry.file)), entry.file).toBe(true);
      expect(entry.reason.length).toBeGreaterThan(20);
    }
  });
});

describe("the boundary scan itself", () => {
  const dirs: string[] = [];
  afterEach(() => {
    for (const d of dirs.splice(0)) fs.rmSync(d, { recursive: true, force: true });
  });

  /** A throwaway `src` tree: files maps a path under src to its text. */
  function tree(files: Record<string, string>): string {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), "core-boundary-"));
    dirs.push(root);
    const src = path.join(root, "src");
    for (const [name, text] of Object.entries(files)) {
      const full = path.join(src, name);
      fs.mkdirSync(path.dirname(full), { recursive: true });
      fs.writeFileSync(full, text);
    }
    return src;
  }

  const kinds = (src: string) =>
    scanBoundary(src)
      .violations.map((v) => `${v.kind}:${v.what}`)
      .sort();

  it("passes a clean tree", () => {
    const src = tree({
      "lib/model/a.ts": 'import { b } from "./b";\nexport const a = b + 1;\n',
      "lib/model/b.ts": 'import { z } from "zod";\nexport const b = z ? 1 : 2;\n',
    });
    expect(kinds(src)).toEqual([]);
  });

  it("fails on a React import, a browser global, a Zustand store and src/lib/format", () => {
    const src = tree({
      "lib/model/a.ts": 'import { useState } from "react";\nexport const a = useState;\n',
      "lib/solver/b.ts": "export const b = () => localStorage.getItem('x');\n",
      "lib/machines/c.ts": 'import { useFactoryStore } from "@/store/factory-store";\nexport const c = useFactoryStore;\n',
      "store/factory-store.ts": "export const useFactoryStore = 1;\n",
      "lib/power/d.ts": 'import { formatCompact } from "@/lib/format/numbers";\nexport const d = formatCompact;\n',
      "lib/format/numbers.ts": "export const formatCompact = 1;\n",
    });
    expect(kinds(src)).toEqual([
      "global:localStorage",
      "import:react",
      "import:src/store (Zustand)",
      "layer:src/lib/format",
    ]);
  });

  it("follows imports out of the four directories", () => {
    const src = tree({
      "lib/model/a.ts": 'import { b } from "@/components/flow/helper";\nexport const a = b;\n',
      "components/flow/helper.ts": "export const b = typeof window === 'undefined' ? 0 : 1;\n",
    });
    expect(kinds(src)).toEqual(["global:window"]);
  });

  it("does not count a type-only import, a type position or a local with a browser name", () => {
    const src = tree({
      "lib/model/a.ts": [
        'import type { ReactNode } from "react";',
        'import { type FC } from "react";',
        "export type T = { el: HTMLElement; n: ReactNode; f: FC };",
        "export function f(window: number) { return window + 1; }",
        "export const o = { document: 1, fetch: 2 };",
        "",
      ].join("\n"),
    });
    expect(kinds(src)).toEqual([]);
  });

  it("stops at a leaf entry and does not follow its imports", () => {
    const files = {
      "lib/solver/kit.ts": 'import { v } from "@/components/view";\nexport const k = v;\n',
      "components/view.ts": "export const v = typeof window === 'undefined' ? 0 : 1;\n",
    };
    const src = tree(files);
    expect(scanBoundary(src).violations.length).toBe(1);
    const allow = [{ file: "src/lib/solver/kit.ts", reason: "a harness", leaf: true }];
    const { violations, visited } = scanBoundary(src, allow);
    expect(violations).toEqual([]);
    expect(applyAllowList(violations, allow, visited).stale).toEqual([]);
    expect(applyAllowList([], [{ ...allow[0], file: "src/lib/solver/gone.ts" }], visited).stale.length).toBe(1);
  });

  it("applies the allow-list per file and reports an entry that matches nothing", () => {
    const src = tree({ "lib/solver/loader.ts": "export const l = () => fetch('x');\n" });
    const { violations, visited } = scanBoundary(src);
    const hit = applyAllowList(violations, [{ file: "src/lib/solver/loader.ts", reason: "test" }], visited);
    expect(hit.remaining).toEqual([]);
    expect(hit.stale).toEqual([]);
    const miss = applyAllowList(violations, [{ file: "src/lib/solver/other.ts", reason: "test" }], visited);
    expect(miss.remaining.length).toBe(1);
    expect(miss.stale.length).toBe(1);
  });
});
