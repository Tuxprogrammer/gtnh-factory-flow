// Loads the compiled core in Node, either target, and hands back its exported functions as one plain object.
//   wasm: core.wasm-runtime.js defines globalThis.TeaVM; TeaVM.wasmGC.load compiles <module>.wasm; .exports are the functions.
//   js:   <module>.js is an ES module whose named exports are the functions.
// The website's own loader (src/lib/core/loader.ts) does the same steps; this one is for Node tools and the conformance run.
import { readFileSync } from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import vm from "node:vm";
import { readModules } from "./modules.mjs";

export const TARGETS = {
  wasm: { dir: "wasm-gc", label: "WebAssembly GC" },
  js: { dir: "js", label: "JavaScript" },
};

/** The build's output directory for a target, under the directory the TeaVM plugin writes to (build/generated/teavm). */
export function targetDir(buildDir, target) {
  const t = TARGETS[target];
  if (!t) throw new Error(`unknown target ${target}`);
  return path.join(buildDir, t.dir);
}

/** What each module of the build exports on its own, by module name (the run checks the split against modules.txt). */
export const loadedModules = new Map();

/**
 * Every module of the build (jvm/web/modules.txt), loaded and merged into one object of exported functions: a run that
 * checks the core checks all of it. `modules` limits the load to the named ones. Each module is its own instance with its
 * own memory and statics, as in the browser.
 */
export async function loadTarget(buildDir, target, modules = readModules()) {
  const dir = targetDir(buildDir, target);
  const merged = {};
  const own = new Map();
  for (const m of modules) {
    let api;
    if (target === "wasm") {
      if (!globalThis.TeaVM?.wasmGC) {
        const runtime = path.join(dir, `${modules[0].name}.wasm-runtime.js`);
        vm.runInThisContext(readFileSync(runtime, "utf8"), { filename: runtime });
      }
      api = (await globalThis.TeaVM.wasmGC.load(readFileSync(path.join(dir, `${m.name}.wasm`)), { nodejs: true })).exports;
    } else {
      api = { ...(await import(pathToFileURL(path.join(dir, `${m.name}.js`)).href)) };
    }
    // A Wasm exports object may hide its names from Object.keys, so read them every way; `main` is TeaVM's entry point.
    const names = new Set(Object.getOwnPropertyNames(api));
    for (const k in api) names.add(k);
    names.delete("main");
    own.set(m.name, [...names].sort());
    for (const k of names) if (!(k in merged)) merged[k] = api[k];
  }
  loadedModules.set(target, own);
  return merged;
}
