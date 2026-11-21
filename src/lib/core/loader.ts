// Loads the shared Java core compiled for the web (jvm/web, TeaVM) and hands back its exports.
//
// One module for every place the website runs code: the browser main thread, a Worker (classic or module), a Node
// server route and vitest. It loads once, asynchronously, with `loadCore()`; after that `core()` is synchronous and
// every call into the core is a plain function call, with no promise and no postMessage.
//
//   await loadCore();
//   core().BaseExports.numberTextOf(0.1);       // "0.1"
//
// The core is split into modules (jvm/web/modules.txt), each its own file that is fetched when first used:
//   core     the card side: base, machines, power, layout (what a page needs to draw and compute cards)
//   solver   the LP solver with ojAlgo (what the solve Worker needs; no page loads it on the main thread)
// `loadCore()` loads "core"; `loadCore({ modules: ["solver"] })` loads the solver, in the Worker that runs it. Every
// loaded module's namespaces sit on the one object `core()` returns, so a caller never knows where a function lives;
// a namespace of a module that is not loaded throws CoreNotLoadedError naming the module, so a missed load fails loudly.
//
// Which build runs: WebAssembly GC where the engine has it (Chrome and Edge 119, Firefox 120, Safari 18.2), otherwise
// the JavaScript build of the same sources. The wasm file is tried first and any failure to compile or start it
// (an engine without GC or without the exception handling the build uses) falls back to the JavaScript build; a
// target asked for with `options.target` is never swapped. Both give the same answers (the conformance run in
// tools/web-core proves it on every change).
//
// Where the files come from: tools/copy-core.mjs puts them in public/core/ (manifest.json, wasm-gc/, js/), every file
// under a name that carries its content hash. A browser fetches them from `<baseUrl>/core/`; Node reads the same
// directory. Without them the core is "unavailable": loadCore() rejects with CoreUnavailableError and the site keeps
// working for everything that does not need it.
import { CORE_MODULES, type CoreExports, type CoreModuleName } from "./exports.generated";

export type { CoreModuleName };
export type CoreTarget = "wasm-gc" | "js";
export type CoreState = "idle" | "loading" | "ready" | "unavailable";

export interface LoadOptions {
  /** Force a target. Without it WebAssembly GC is used where supported and the JavaScript build is the fallback. */
  target?: CoreTarget;
  /** The modules to load (default: just "core", the card side). Ones already loaded are not loaded again. */
  modules?: readonly CoreModuleName[];
  /** Browser: where the core files are served, a path or URL ending in "/" (default "/core/"). */
  baseUrl?: string;
  /** Node: the directory holding manifest.json (default `<cwd>/public/core`, or GTNH_CORE_PUBLIC_DIR). */
  dir?: string;
}

export interface CoreInfo {
  /** The target of the first module that loaded. */
  target: CoreTarget;
  /** The build's content hash (manifest.json). */
  version: string;
  /** Milliseconds from the call of loadCore to the exports being usable (the first module's). */
  loadMs: number;
  /** Why the WebAssembly build was not used, when the JavaScript build runs although Wasm was wanted. */
  fellBack?: string;
  /** Each loaded module's own target and load time. */
  modules: Partial<Record<CoreModuleName, { target: CoreTarget; loadMs: number }>>;
}

export class CoreUnavailableError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = "CoreUnavailableError";
  }
}

export class CoreNotLoadedError extends Error {
  constructor(module?: string) {
    super(
      module
        ? `The ${module} module of the core is not loaded: await loadCore({ modules: ["${module}"] }) first`
        : "The core is not loaded: await loadCore() first",
    );
    this.name = "CoreNotLoadedError";
  }
}

/** The export convention's version this loader speaks; every module's webCoreExportVersion() must answer it. */
export const EXPECTED_EXPORT_VERSION = 1;

/** The module a plain `loadCore()` loads: the card side, which every page that uses the core needs. */
export const DEFAULT_MODULES: readonly CoreModuleName[] = ["core"];

interface Manifest {
  format: number;
  version: string;
  /** The wasm runtime script every module shares. */
  runtime: string;
  modules: Record<string, { wasm: string; js: string }>;
  files: { path: string; bytes: number }[];
}

const MANIFEST_FORMAT = 2;

interface ModuleState {
  state: CoreState;
  promise: Promise<void> | null;
}

interface Shared {
  /** One object holds every loaded module's exports; a namespace of a module not loaded yet throws on access. */
  exports: CoreExports | null;
  info: CoreInfo | null;
  modules: Record<string, ModuleState>;
  /** The manifest and where its files are read from, kept so every module of one session comes from one build. */
  source: Promise<Source> | null;
}

// On globalThis, not in the module: a dev server that re-evaluates this module, or a bundle that holds two copies of
// it, must not load a second instance of a multi-megabyte module.
const SHARED_KEY = Symbol.for("gtnh.core.loader");
function shared(): Shared {
  const g = globalThis as unknown as Record<symbol, Shared | undefined>;
  return (g[SHARED_KEY] ??= { exports: null, info: null, modules: {}, source: null });
}

function moduleEntry(name: string): ModuleState {
  const s = shared();
  return (s.modules[name] ??= { state: "idle", promise: null });
}

/** The state of one module: "idle" until something asks for it. */
export function moduleState(name: CoreModuleName): CoreState {
  return shared().modules[name]?.state ?? "idle";
}

/**
 * The state of the core as a whole: "loading" while any module loads, else "ready" when any module is loaded, else
 * "unavailable" when a load failed, else "idle".
 */
export function coreState(): CoreState {
  const states = Object.values(shared().modules).map((m) => m.state);
  if (states.includes("loading")) return "loading";
  if (states.includes("ready")) return "ready";
  if (states.includes("unavailable")) return "unavailable";
  return "idle";
}

export function coreInfo(): CoreInfo | null {
  return shared().info;
}

/** The loaded core (every module loaded so far), or null before the first module has finished. */
export function coreIfLoaded(): CoreExports | null {
  return shared().exports;
}

/** The loaded core. Throws CoreNotLoadedError before loadCore has finished, so a missed `await` fails loudly. */
export function core(): CoreExports {
  const c = shared().exports;
  if (!c) throw new CoreNotLoadedError();
  return c;
}

/** For tests: forget the loaded core so the next loadCore loads again. */
export function resetCoreForTests(): void {
  const s = shared();
  s.exports = null;
  s.info = null;
  s.modules = {};
  s.source = null;
}

/** An empty struct type: validates only on an engine with the WebAssembly GC proposal. */
const GC_PROBE = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 95, 1, 120, 0]);

export function wasmGcSupported(): boolean {
  try {
    return (
      typeof WebAssembly === "object" &&
      typeof WebAssembly.validate === "function" &&
      WebAssembly.validate(GC_PROBE)
    );
  } catch {
    return false;
  }
}

function inNode(): boolean {
  const p = (globalThis as { process?: { versions?: { node?: string } } }).process;
  return !!p?.versions?.node && typeof (globalThis as { window?: unknown }).window === "undefined";
}

/**
 * `import()` of a file the bundler cannot know: the core's own files are plain ES modules that must be loaded as they
 * are, from a URL or path decided at run time (and Node's own modules, which only the Node path asks for).
 */
const nativeImport = (specifier: string): Promise<Record<string, unknown>> =>
  import(
    /* webpackIgnore: true */ /* turbopackIgnore: true */ /* @vite-ignore */ specifier
  ) as Promise<Record<string, unknown>>;

interface TeaVMGlobal {
  wasmGC: {
    load(
      source: string | Uint8Array,
      options?: Record<string, unknown>,
    ): Promise<{ exports: unknown }>;
  };
}

interface Source {
  manifest: Manifest;
  /** The runtime script of the wasm build, loaded (it defines globalThis.TeaVM). */
  runtime(): Promise<void>;
  /** What TeaVM.wasmGC.load takes: bytes in Node, a URL in a browser (compiled while it streams). */
  wasm(module: string): Promise<string | Uint8Array>;
  wasmOptions: Record<string, unknown>;
  /** The JavaScript build's exports. */
  js(module: string): Promise<Record<string, unknown>>;
}

function checkManifest(manifest: Manifest, where: string): Manifest {
  if (manifest.format !== MANIFEST_FORMAT || !manifest.modules || !manifest.runtime)
    throw new CoreUnavailableError(
      `the core manifest at ${where} is format ${String(manifest.format)}, this site reads format ${MANIFEST_FORMAT}: rebuild it (tools/copy-core.mjs)`,
    );
  return manifest;
}

async function nodeSource(options: LoadOptions): Promise<Source> {
  const fs = (await nativeImport(
    "node:fs/promises",
  )) as unknown as typeof import("node:fs/promises");
  const path = (await nativeImport("node:path")) as unknown as typeof import("node:path");
  const url = (await nativeImport("node:url")) as unknown as typeof import("node:url");
  const proc = (
    globalThis as unknown as { process: { cwd(): string; env: Record<string, string | undefined> } }
  ).process;
  const dir =
    options.dir ?? proc.env.GTNH_CORE_PUBLIC_DIR ?? path.join(proc.cwd(), "public", "core");
  let manifest: Manifest;
  try {
    manifest = JSON.parse(await fs.readFile(path.join(dir, "manifest.json"), "utf8")) as Manifest;
  } catch (cause) {
    throw new CoreUnavailableError(
      `no core in ${dir} (tools/copy-core.mjs puts it there; it needs a core build, see jvm/web/README.md)`,
      { cause },
    );
  }
  checkManifest(manifest, dir);
  const file = (name: string) => path.join(dir, name);
  const filesOf = (name: string) => moduleFiles(manifest, name);
  return {
    manifest,
    runtime: async () => {
      await nativeImport(url.pathToFileURL(file(manifest.runtime)).href);
    },
    wasm: async (name) => new Uint8Array(await fs.readFile(file(filesOf(name).wasm))),
    wasmOptions: { nodejs: true },
    js: (name) => nativeImport(url.pathToFileURL(file(filesOf(name).js)).href),
  };
}

function moduleFiles(manifest: Manifest, name: string): { wasm: string; js: string } {
  const files = manifest.modules[name];
  if (!files)
    throw new CoreUnavailableError(
      `the core build has no ${name} module (it has ${Object.keys(manifest.modules).join(", ")}): rebuild it (tools/copy-core.mjs)`,
    );
  return files;
}

async function browserSource(options: LoadOptions): Promise<Source> {
  const here =
    (globalThis as { location?: { href: string } }).location?.href ?? "http://localhost/";
  const base = new URL(options.baseUrl ?? "/core/", here);
  let manifest: Manifest;
  try {
    const response = await fetch(new URL("manifest.json", base), { cache: "no-cache" });
    if (!response.ok) throw new Error(`manifest.json: HTTP ${response.status}`);
    manifest = (await response.json()) as Manifest;
  } catch (cause) {
    throw new CoreUnavailableError(`the core is not deployed at ${base.href}`, { cause });
  }
  checkManifest(manifest, base.href);
  // Every file's name carries its content hash, so no version query is needed and the file is cached for good.
  const at = (name: string) => new URL(name, base).href;
  return {
    manifest,
    // A module import runs the runtime script the same way on the main thread and in a classic or module worker, with
    // no <script> tag, no importScripts and no eval; the script only sets globalThis.TeaVM.
    runtime: async () => {
      await nativeImport(at(manifest.runtime));
    },
    wasm: async (name) => at(moduleFiles(manifest, name).wasm),
    wasmOptions: {},
    js: (name) => nativeImport(at(moduleFiles(manifest, name).js)),
  };
}

async function startWasm(source: Source, name: string): Promise<Record<string, unknown>> {
  await source.runtime();
  const teavm = (globalThis as unknown as { TeaVM?: TeaVMGlobal }).TeaVM;
  if (!teavm?.wasmGC) throw new Error("the wasm runtime did not define TeaVM.wasmGC");
  const loaded = await teavm.wasmGC.load(await source.wasm(name), source.wasmOptions);
  return loaded.exports as Record<string, unknown>;
}

function checkVersion(
  exports: Record<string, unknown>,
  name: string,
  target: CoreTarget,
): Record<string, unknown> {
  const version = (exports as { webCoreExportVersion?: () => number }).webCoreExportVersion?.();
  if (version !== EXPECTED_EXPORT_VERSION) {
    throw new CoreUnavailableError(
      `the ${name} module of the ${target} core answers export version ${String(version)}, this site expects ${EXPECTED_EXPORT_VERSION}: rebuild it (tools/copy-core.mjs)`,
    );
  }
  return exports;
}

/** The names a module exports. A Wasm exports object may hide them from Object.keys, so read them every way. */
function exportNames(exports: object): string[] {
  const names = new Set(Object.getOwnPropertyNames(exports));
  for (const k in exports) names.add(k);
  names.delete("main");
  return [...names];
}

/** The one object `core()` returns. A name of a module that is not loaded yet answers with a clear error, not undefined. */
function mergedExports(): CoreExports {
  const s = shared();
  if (s.exports) return s.exports;
  const target: Record<string, unknown> = {};
  for (const [name, spec] of Object.entries(CORE_MODULES as Record<string, { namespaces: readonly string[]; top: readonly string[] }>)) {
    for (const key of [...spec.namespaces, ...spec.top])
      Object.defineProperty(target, key, {
        configurable: true,
        enumerable: false,
        get() {
          throw new CoreNotLoadedError(name);
        },
      });
  }
  s.exports = target as unknown as CoreExports;
  return s.exports;
}

function addExports(exports: Record<string, unknown>): void {
  const target = mergedExports() as unknown as Record<string, unknown>;
  for (const key of exportNames(exports)) {
    const present = Object.getOwnPropertyDescriptor(target, key);
    // Every module answers webCoreExportVersion: the first one's stays.
    if (present && "value" in present) continue;
    Object.defineProperty(target, key, {
      configurable: true,
      enumerable: true,
      writable: false,
      value: exports[key],
    });
  }
}

async function loadModule(name: string, options: LoadOptions): Promise<void> {
  const started = performance.now();
  const s = shared();
  s.source ??= (
    options.dir !== undefined || inNode() ? nodeSource(options) : browserSource(options)
  ).catch((e: unknown) => {
    s.source = null;
    throw e;
  });
  const source = await s.source;
  const finish = (exports: Record<string, unknown>, target: CoreTarget, fellBack?: string) => {
    addExports(exports);
    const loadMs = performance.now() - started;
    const info = (s.info ??= {
      target,
      version: source.manifest.version,
      loadMs,
      modules: {},
      ...(fellBack ? { fellBack } : {}),
    });
    info.modules[name as CoreModuleName] = { target, loadMs };
  };
  let fellBack: string | undefined;
  if (options.target !== "js") {
    if (wasmGcSupported()) {
      try {
        finish(checkVersion(await startWasm(source, name), name, "wasm-gc"), "wasm-gc");
        return;
      } catch (e) {
        if (options.target === "wasm-gc")
          throw new CoreUnavailableError(
            `the WebAssembly GC ${name} module did not start: ${String(e)}`,
            { cause: e },
          );
        if (e instanceof CoreUnavailableError) throw e;
        fellBack = `the WebAssembly GC build did not start: ${e instanceof Error ? e.message : String(e)}`;
      }
    } else {
      if (options.target === "wasm-gc")
        throw new CoreUnavailableError("this engine has no WebAssembly GC");
      fellBack = "this engine has no WebAssembly GC";
    }
  }
  try {
    const mod = { ...(await source.js(name)) };
    finish(checkVersion(mod, name, "js"), "js", fellBack);
  } catch (e) {
    if (e instanceof CoreUnavailableError) throw e;
    throw new CoreUnavailableError(
      `the JavaScript ${name} module did not start: ${e instanceof Error ? e.message : String(e)}`,
      { cause: e },
    );
  }
}

/**
 * Loads the modules once (a second call, or a call while loading, shares the first; a module already loaded is not
 * loaded again). Resolves with the exports, which `core()` returns from then on. Rejects with CoreUnavailableError when
 * there is nothing to load; a later call tries again.
 */
export function loadCore(options: LoadOptions = {}): Promise<CoreExports> {
  const wanted = options.modules ?? DEFAULT_MODULES;
  const pending = wanted.map((name) => {
    const entry = moduleEntry(name);
    if (entry.state === "ready") return Promise.resolve();
    if (entry.promise) return entry.promise;
    entry.state = "loading";
    const promise = loadModule(name, options)
      .then(() => {
        entry.state = "ready";
        entry.promise = null;
      })
      .catch((e: unknown) => {
        entry.state = "unavailable";
        entry.promise = null;
        throw e;
      });
    entry.promise = promise;
    return promise;
  });
  return Promise.all(pending).then(() => core());
}
