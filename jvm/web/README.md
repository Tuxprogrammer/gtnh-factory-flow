# jvm/web: the core compiled for the browser and Node

This module compiles the `core-*` modules with TeaVM 0.16 into two targets from the same sources:

| Target | Files (under `build/generated/teavm/`) | Use |
|---|---|---|
| WebAssembly GC | `wasm-gc/core.wasm`, `wasm-gc/core.wasm-runtime.js` | the default in browsers and Node 24 |
| JavaScript | `js/core.js` (ES2015 module) | fallback, and a second opinion in the conformance run |

It depends on `:core-base` only today. The TeaVM plugin adds no repository; the module's own
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

## Traps

- TeaVM's Wasm GC backend compiles `-x` on a double as `0.0 - x`, so negating `+0.0` gives `+0.0`. Listed in
  `tools/web-core/known-differences.json`; the run prints it as KNOWN and fails if the entry stops matching.
- No `Long.parseUnsignedLong`; no `long` across the boundary.
- Gradle's configuration cache: task actions must not read script-level values.
- Node reads `core.js` as an ES module without a `type` field and prints a harmless
  `MODULE_TYPELESS_PACKAGE_JSON` warning; the website copy sits beside a package scope that says module.
