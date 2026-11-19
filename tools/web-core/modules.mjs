// The web core's modules, read from jvm/web/modules.txt (the file the Gradle build reads too). One module is one TeaVM
// compilation per target: the loader fetches a module on first use, the declarations are generated per module and the
// conformance run loads them all. A module's files are `<name>.wasm` (WebAssembly GC) and `<name>.js` (JavaScript);
// every module shares the one runtime script `<first module>.wasm-runtime.js`.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
export const MODULES_FILE = path.join(root, "jvm", "web", "modules.txt");

/** [{ name, main, exports: ["*"] | ["SolverExports", ...] }] in file order; [] when the checkout has no jvm tree. */
export function readModules(file = MODULES_FILE) {
  if (!fs.existsSync(file)) return [];
  const modules = fs
    .readFileSync(file, "utf8")
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith("#"))
    .map((l) => {
      const cols = l.split(/\s+/);
      if (cols.length < 3) throw new Error(`${file}: '${l}' is not '<module> <main class> <exports...>'`);
      return { name: cols[0], main: cols[1], exports: cols.slice(2) };
    });
  if (new Set(modules.map((m) => m.name)).size !== modules.length) throw new Error(`${file}: a module name is repeated`);
  if (modules.filter((m) => m.exports.includes("*")).length !== 1)
    throw new Error(`${file}: exactly one module takes the rest of the exports classes (*)`);
  return modules;
}

/** The module that holds an exports class (a name like "SolverExports"): its own, or the one that takes the rest. */
export function moduleOfNamespace(modules, namespace) {
  return (modules.find((m) => m.exports.includes(namespace)) ?? modules.find((m) => m.exports.includes("*")))?.name;
}

/** The files a module makes, relative to the TeaVM output directory (and to public/core before hashing). */
export function moduleFiles(modules, module) {
  return { wasm: `wasm-gc/${module}.wasm`, js: `js/${module}.js` };
}

/** The runtime script every module's WebAssembly build shares. */
export function runtimeFile(modules) {
  if (modules.length === 0) throw new Error(`${MODULES_FILE} is missing`);
  return `wasm-gc/${modules[0].name}.wasm-runtime.js`;
}

/** Every file a complete build holds, relative to the TeaVM output directory. */
export function buildFiles(modules) {
  if (modules.length === 0) return [];
  return [runtimeFile(modules), ...modules.flatMap((m) => Object.values(moduleFiles(modules, m.name)))];
}
