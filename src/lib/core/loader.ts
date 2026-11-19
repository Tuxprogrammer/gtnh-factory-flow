// Loads the shared Java core compiled for the web (jvm/web, TeaVM) and hands back its exports.
//
// One module for every place the website runs code: the browser main thread, a Worker (classic or module), a Node
// server route and vitest. It loads once, asynchronously, with `loadCore()`; after that `core()` is synchronous and
// every call into the core is a plain function call, with no promise and no postMessage.
//
//   await loadCore();
//   core().BaseExports.numberTextOf(0.1);       // "0.1"
//
// Which build runs: WebAssembly GC where the engine has it (Chrome and Edge 119, Firefox 120, Safari 18.2), otherwise
// the JavaScript build of the same sources. The wasm file is tried first and any failure to compile or start it
// (an engine without GC or without the exception handling the build uses) falls back to the JavaScript build; a
// target asked for with `options.target` is never swapped. Both give the same answers (the conformance run in
// tools/web-core proves it on every change).
//
// Where the files come from: tools/copy-core.mjs puts them in public/core/ (manifest.json, wasm-gc/, js/). A browser
// fetches them from `<baseUrl>/core/`; Node reads the same directory. Without them the core is "unavailable":
// loadCore() rejects with CoreUnavailableError and the site keeps working for everything that does not need it.
import type { CoreExports } from "./exports.generated";

export type CoreTarget = "wasm-gc" | "js";
export type CoreState = "idle" | "loading" | "ready" | "unavailable";

export interface LoadOptions {
  /** Force a target. Without it WebAssembly GC is used where supported and the JavaScript build is the fallback. */
  target?: CoreTarget;
  /** Browser: where the core files are served, a path or URL ending in "/" (default "/core/"). */
  baseUrl?: string;
  /** Node: the directory holding manifest.json (default `<cwd>/public/core`, or GTNH_CORE_PUBLIC_DIR). */
  dir?: string;
}

export interface CoreInfo {
  target: CoreTarget;
  /** The build's content hash (manifest.json). */
  version: string;
  /** Milliseconds from the call of loadCore to the exports being usable. */
  loadMs: number;
  /** Why the WebAssembly build was not used, when the JavaScript build runs although Wasm was wanted. */
  fellBack?: string;
}

export class CoreUnavailableError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = "CoreUnavailableError";
  }
}

export class CoreNotLoadedError extends Error {
  constructor() {
    super("The core is not loaded: await loadCore() first");
    this.name = "CoreNotLoadedError";
  }
}

/** The export convention's version this loader speaks; WebCore.webCoreExportVersion() must answer it. */
export const EXPECTED_EXPORT_VERSION = 1;

interface Manifest {
  format: number;
  version: string;
  files: { path: string; bytes: number }[];
}

interface Shared {
  state: CoreState;
  promise: Promise<CoreExports> | null;
  exports: CoreExports | null;
  info: CoreInfo | null;
}

// On globalThis, not in the module: a dev server that re-evaluates this module, or a bundle that holds two copies of
// it, must not load a second instance of a 1.5 MB module.
const SHARED_KEY = Symbol.for("gtnh.core.loader");
function shared(): Shared {
  const g = globalThis as unknown as Record<symbol, Shared | undefined>;
  return (g[SHARED_KEY] ??= { state: "idle", promise: null, exports: null, info: null });
}

export function coreState(): CoreState {
  return shared().state;
}

export function coreInfo(): CoreInfo | null {
  return shared().info;
}

/** The loaded core, or null before loadCore has finished. */
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
  s.state = "idle";
  s.promise = null;
  s.exports = null;
  s.info = null;
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
  wasm(): Promise<string | Uint8Array>;
  wasmOptions: Record<string, unknown>;
  /** The JavaScript build's exports. */
  js(): Promise<Record<string, unknown>>;
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
  const file = (name: string) => path.join(dir, name);
  return {
    manifest,
    runtime: async () => {
      await nativeImport(
        `${url.pathToFileURL(file("wasm-gc/core.wasm-runtime.js")).href}?v=${manifest.version}`,
      );
    },
    wasm: async () => new Uint8Array(await fs.readFile(file("wasm-gc/core.wasm"))),
    wasmOptions: { nodejs: true },
    js: () => nativeImport(`${url.pathToFileURL(file("js/core.js")).href}?v=${manifest.version}`),
  };
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
  const at = (name: string) => `${new URL(name, base).href}?v=${manifest.version}`;
  return {
    manifest,
    // A module import runs the runtime script the same way on the main thread and in a classic or module worker, with
    // no <script> tag, no importScripts and no eval; the script only sets globalThis.TeaVM.
    runtime: async () => {
      await nativeImport(at("wasm-gc/core.wasm-runtime.js"));
    },
    wasm: async () => at("wasm-gc/core.wasm"),
    wasmOptions: {},
    js: () => nativeImport(at("js/core.js")),
  };
}

async function startWasm(source: Source): Promise<CoreExports> {
  await source.runtime();
  const teavm = (globalThis as unknown as { TeaVM?: TeaVMGlobal }).TeaVM;
  if (!teavm?.wasmGC) throw new Error("the wasm runtime did not define TeaVM.wasmGC");
  const loaded = await teavm.wasmGC.load(await source.wasm(), source.wasmOptions);
  return loaded.exports as CoreExports;
}

function checkVersion(exports: CoreExports, target: CoreTarget): CoreExports {
  const version = (
    exports as unknown as { webCoreExportVersion?: () => number }
  ).webCoreExportVersion?.();
  if (version !== EXPECTED_EXPORT_VERSION) {
    throw new CoreUnavailableError(
      `the ${target} core answers export version ${String(version)}, this site expects ${EXPECTED_EXPORT_VERSION}: rebuild it (tools/copy-core.mjs)`,
    );
  }
  return exports;
}

async function load(options: LoadOptions): Promise<CoreExports> {
  const started = performance.now();
  const source = await (options.dir !== undefined || inNode()
    ? nodeSource(options)
    : browserSource(options));
  const finish = (exports: CoreExports, target: CoreTarget, fellBack?: string) => {
    const s = shared();
    s.exports = exports;
    s.info = {
      target,
      version: source.manifest.version,
      loadMs: performance.now() - started,
      ...(fellBack ? { fellBack } : {}),
    };
    s.state = "ready";
    return exports;
  };
  let fellBack: string | undefined;
  if (options.target !== "js") {
    if (wasmGcSupported()) {
      try {
        return finish(checkVersion(await startWasm(source), "wasm-gc"), "wasm-gc");
      } catch (e) {
        if (options.target === "wasm-gc")
          throw new CoreUnavailableError(`the WebAssembly GC core did not start: ${String(e)}`, {
            cause: e,
          });
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
    const mod = { ...(await source.js()) };
    return finish(checkVersion(mod as unknown as CoreExports, "js"), "js", fellBack);
  } catch (e) {
    if (e instanceof CoreUnavailableError) throw e;
    throw new CoreUnavailableError(
      `the JavaScript core did not start: ${e instanceof Error ? e.message : String(e)}`,
      { cause: e },
    );
  }
}

/**
 * Loads the core once (a second call, or a call while loading, shares the first). Resolves with the exports, which
 * `core()` returns from then on. Rejects with CoreUnavailableError when there is no core to load; a later call tries again.
 */
export function loadCore(options: LoadOptions = {}): Promise<CoreExports> {
  const s = shared();
  if (s.exports) return Promise.resolve(s.exports);
  if (s.promise) return s.promise;
  s.state = "loading";
  const promise = load(options).catch((e: unknown) => {
    s.state = "unavailable";
    s.promise = null;
    throw e;
  });
  s.promise = promise;
  return promise;
}
