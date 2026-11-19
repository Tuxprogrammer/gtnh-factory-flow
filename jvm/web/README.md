# jvm/web: the core compiled for the browser and Node

This module compiles the `core-*` modules with TeaVM 0.16 into two targets from the same sources:

| Target | Files (under `build/generated/teavm/`) | Use |
|---|---|---|
| WebAssembly GC | `wasm-gc/core.wasm`, `wasm-gc/core.wasm-runtime.js` | the default in browsers and Node 24 |
| JavaScript | `js/core.js` (ES2015 module) | fallback, and a second opinion in the conformance run |

It compiles `:core-base`, `:core-power` and `:core-layout` (the `coreModules` list in `build.gradle.kts`). The TeaVM plugin adds no repository; the module's own
`repositories { mavenCentral() }` is for the TeaVM libraries. `:web:build` does not run TeaVM (the mod's
`build :mod:compatCheck` stays as it was); `:web:webCore` does.

## Commands

```bash
cd jvm
./gradlew --no-daemon :web:check :web:webCore :web:webRandomCases   # build both targets, override check, JVM tests, random cases
node ../tools/web-core/run.mjs --build web/build/generated/teavm --random web/build/random/cases.jsonl
tools/ci/web-core.sh                                                # all of the above, plus sizes and times (the CI job)
```

Needs Gradle's daemon JVM 25 and a Java 25 toolchain (`--release 21`), Node 24, and access to Maven Central and the
Gradle plugin portal. `-PrandomSeed=N` and `-PrandomScale=N` change the random cases (default seed 20261119, scale 1,
about 400,000 cases).

## How the website gets the core

The compiled core is never committed. `tools/copy-core.mjs` puts it in `public/core/` (gitignored: `manifest.json`,
`wasm-gc/`, `js/`), and `src/lib/core/loader.ts` loads it in the browser main thread, a Worker, a Node server route and
vitest (`await loadCore()` once, then `core()` is synchronous). `src/lib/core/exports.generated.ts` (gitignored) holds
the TypeScript declarations that `tools/web-core/gen-types.mjs` reads from the Java exports; the loader test compares
them with the compiled module, so a declaration that disagrees with the Java fails a test.

`npm run dev`, `dev:local`, `build`, `typecheck` and `lint` run these first (npm pre-hooks), and so does vitest's
global setup. Source of the files, first that exists:

| Source | When |
|---|---|
| `GTNH_CORE_DIR` (a finished build: `wasm-gc/` and `js/` inside it) | a CI artifact or a build host's output |
| `jvm/web/build/generated/teavm` | what `./gradlew :web:buildWasmGC :web:generateJavaScript` writes |

`GTNH_CORE_BUILD=1` (or `--build`) runs that Gradle build first; it needs JDK 25 and Maven Central. With no build on
disk and nothing asked for, `copy-core` prints one line and the site starts without the core: `loadCore()` rejects with
`CoreUnavailableError` and nothing else changes. A checkout with only Node therefore runs. `GTNH_CORE_REQUIRED=1` (or
`--required`) makes a missing core an error: CI sets it (`tools/ci/website.sh` builds with `--build --required`).

### What the operator's build must do

1. Before `next build`, produce `public/core/` in the build context: either run with `GTNH_CORE_BUILD=1` (JDK 25 on
   `JAVA_HOME`, network to Maven Central and the Gradle plugin portal) or build once elsewhere and set
   `GTNH_CORE_DIR` to that output. `npm run build` does the copy itself through `prebuild`.
2. `public/core/` must exist when `next build` runs, and ship with the app: `next start` indexes `public/` at build
   time, so a file copied in afterwards answers 404 (the server-side loader reads the directory itself and works
   either way). Served as static assets, `.wasm` as `application/wasm`, gzip on.
3. Cache: Next sends `Cache-Control: public, max-age=0` for `public/` files. The loader puts the manifest's content
   hash on every file URL (`?v=<hash>`) and fetches `manifest.json` with `no-cache`, so a proxy may cache
   `/core/wasm-gc/*` and `/core/js/*` for a long time; a new build is picked up on the next page load.
4. Nothing requires the core until a feature calls `loadCore()`. Until then a build without it is valid.
5. Size to budget: about 1.49 MB raw (345 KB gzip) for the wasm, 1.53 MB raw (332 KB gzip) for the JavaScript fallback;
   a browser downloads one of them, not both.

### Browsers without WebAssembly GC

WebAssembly GC ships in Chrome and Edge 119, Firefox 120 and Safari 18.2. On an older engine, or any engine where the
wasm fails to compile or start (it also needs exception handling), the loader falls back to `js/core.js`, which gives
the same answers (the conformance run proves it) and costs more to download and start. `coreInfo().target` says which
one runs and `coreInfo().fellBack` why. `loadCore({ target })` pins a target and never falls back, for tests.

## Adding a core module

1. One line: add `":core-xyz"` to `coreModules` in `build.gradle.kts`.
2. A `<Module>Exports` class in `src/main/java/com/gtnhplanner/web/exports/` (see the convention). The build derives
   the preserved classes from the files in that directory; nothing else lists them.
3. The module must compile under TeaVM: no Gson, reflection, threads, `String.format`, `DecimalFormat`, or float text
   (the purity gate in `jvm/core/purity` already forbids most of these). TeaVM stops at the first missing class, so
   run `:web:generateWasmGC` and fix one at a time.

## Export convention

- Exported functions are `public static` methods annotated `@JSExport` in `com.gtnhplanner.web.exports.<Module>Exports`
  (`BaseExports` for core-base). JavaScript calls `api.BaseExports.numberTextParse(s)`: TeaVM exports a non-main class
  as an object named by the class's simple name. There is no alias, so the Java method name is the JavaScript name.
- Names are unique within the class and start lower case, grouped by a prefix (`numberText*`, `uuid*`).
- `WebCore` (the main class) exports are top-level functions and start `webCore` (`webCoreExportVersion`,
  `webCoreRuntimeMaxMemory`).
- Parameter and return types: `int`, `double`, `boolean`, `String`, `void`, or a JSO subtype. No `long`, arrays,
  records or collections. Ids cross as hex strings; lists cross as a string. A structured result is a JSO type built
  with `@JSBody` (see `UuidInfo`). A module with rich inputs exposes a JSON-in / JSON-out `String` entry point.
- No public static non-exported functions in an exports class.
- `ExportConventionTest` checks all of this on the JVM.

## Overrides

A class in this module with the name of a class on TeaVM's classpath replaces it, but only when it comes first on
the classpath, and the plugin puts project classes last. `build.gradle.kts` moves them first in `afterEvaluate`, and
`check/OverrideCheck.java` (tasks `checkOverrides*`, part of `check` and a dependency of both `generate*` tasks) fails
the build if a listed override is not first, replaces nothing, or if an unlisted class shadows another. The list is
`overrides.txt`. The runtime probe `webCoreRuntimeMaxMemory` (4 GiB) is checked in the conformance run, and without the
override the compile itself fails.

## Java's arithmetic: the build plugin

`src/main/java/com/gtnhplanner/web/teavm/ExactMathPlugin.java` is a TeaVM plugin (found through
`META-INF/services/org.teavm.vm.spi.TeaVMPlugin`; a build tool, never in the output). It rewrites every class TeaVM
compiles, the core, ojAlgo and the class library alike, where TeaVM's code differs from the JVM's:

- `java.lang.Math` and `StrictMath`: sin, cos, tan, asin, acos, atan, atan2, exp, log, log10, pow, cbrt, sinh, cosh,
  tanh, expm1, log1p, hypot, rint, round, toRadians, toDegrees and ulp run core-base's `ExactMath` (fdlibm in plain
  Java, the JVM's `StrictMath` bits). TeaVM's own are the browser's `Math` (other algorithms) or shortcuts that are
  wrong (`round(-2.5)` is -3, `cbrt` is a power, `tanh` overflows to NaN). A TeaVM upgrade that drops one of these
  methods fails the build.
- Negation of a double or float becomes a multiplication by -1: the WebAssembly backend writes `-x` as `0 - x`, which
  turns -(+0) into +0.
- JavaScript target only: a double or float converted to int or long goes through `JavaCasts`, which saturates as Java
  does; TeaVM writes `x | 0` (wraps) and `BigInt(x)` (throws on NaN).

`tools/web-core/exact-math.mjs` proves each rewrite on both targets (`exact-math/*` against
`conformance/core-base/exact-math/`, `java-math/*` and `strict-math/*` through `java.lang.Math`, `java/d2i`, `java/d2l`,
`java/neg` on the random cases). Core code still calls `ExactMath` itself (the purity gate's `math-exact` rule): the
JVM's `Math` is not `StrictMath` either.

## Traps

- TeaVM's Wasm GC backend compiles `-x` on a double as `0.0 - x`, so negating `+0.0` gives `+0.0`: the build plugin
  above rewrites every negation, so this no longer shows.
- No `Long.parseUnsignedLong`; no `long` across the boundary.
- Gradle's configuration cache: task actions must not read script-level values.
- Node reads `core.js` as an ES module without a `type` field and prints a harmless
  `MODULE_TYPELESS_PACKAGE_JSON` warning; the website copy sits beside a package scope that says module.
