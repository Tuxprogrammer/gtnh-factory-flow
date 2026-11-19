// Loads the compiled core in Node, either target, and hands back its exported functions as one plain object.
//   wasm: core.wasm-runtime.js defines globalThis.TeaVM; TeaVM.wasmGC.load compiles core.wasm; .exports are the functions.
//   js:   core.js is an ES module whose named exports are the functions.
// The website's own loader (a later item) does the same two steps; this one is for Node tools and the conformance run.
import { readFileSync } from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import vm from "node:vm";

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

export async function loadTarget(buildDir, target) {
  const dir = targetDir(buildDir, target);
  if (target === "wasm") {
    const runtime = path.join(dir, "core.wasm-runtime.js");
    vm.runInThisContext(readFileSync(runtime, "utf8"), { filename: runtime });
    const loaded = await globalThis.TeaVM.wasmGC.load(readFileSync(path.join(dir, "core.wasm")), { nodejs: true });
    return loaded.exports;
  }
  const mod = await import(pathToFileURL(path.join(dir, "core.js")).href);
  return { ...mod };
}
