# jvm/web: the core compiled for the browser and Node

This module compiles the `core-*` modules with TeaVM 0.16 into two targets from the same sources:

| Target | Files (under `build/generated/teavm/`) | Use |
|---|---|---|
| WebAssembly GC | `wasm-gc/<module>.wasm`, `wasm-gc/core.wasm-runtime.js` | the default in browsers and Node 24 |
| JavaScript | `js/<module>.js` (ES2015 module) | fallback, and a second opinion in the conformance run |

Each target is built once per load module (see "Load modules").

It compiles `:core-base`, `:core-machines`, `:core-power`, `:core-layout`, `:core-solver`, `:core-model` and `:core-view` (the `coreModules` list in `build.gradle.kts`). The TeaVM plugin adds no repository; the module's own
`repositories { mavenCentral() }` is for the TeaVM libraries. `:web:build` does not run TeaVM (the mod's
`build :mod:compatCheck` stays as it was); `:web:webCore` does.

## Commands

```bash
cd jvm
./gradlew --no-daemon :web:check :web:webCore :web:webRandomCases   # build both targets, override check, JVM tests, random cases
node ../tools/web-core/run.mjs --build web/build/generated/teavm --random web/build/random/cases.jsonl [--full]
tools/ci/web-core.sh                                                # all of the above, plus sizes and times (the CI job)
```

`run.mjs` runs every case on the WebAssembly target and a sample on the JavaScript target (`tools/web-core/sample.mjs`: a
group of at most 32 cases is kept whole, a bigger one keeps its first case and every 8th): of the machine, bee and crop
goldens, every other reference board, and of the solver corpus every golden but the two that take over 3 s there, every
hand-written Up to plan and every 4th of the Up to plans made from the goldens. The job stays near three minutes. `--full` runs everything on both targets; the
pipeline passes it when `jvm/web` or the TeaVM version changes. Also `--solver-jvm DIR` (the digests of the JVM's
`DigestDump`, to count answers bit-identical to the JVM), `--solver-report FILE` (per-case times and bit-identity as JSON),
`--solver-runs N` (least time of N solves), `--solver-only NAME,...` (just the solver cases whose name contains one of the texts) and `--bench` (adds the 10-times-tiled platline).

Run Gradle by hand with `-Dorg.gradle.jvmargs=-Xmx3g`: the two TeaVM builds run side by side and run out of Gradle's default
512 MiB heap (`tools/copy-core.mjs` and the CI scripts pass it).

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

#### Exactly what the deploy workflow needs

The site's image (`tools/dataset-pipeline/docker/Dockerfile`) already holds JDK 25 at `/opt/java/jdk-25` (beside the JDK 21
that is `java` by default) and Node 22, and the build runs `npm install && npm run build` in it with 6 GB and 4 CPUs.
Nothing in the image or in `src/app/api/community/images/route.ts` has to change. The command that runs inside the
container does:

```bash
export JAVA_HOME=/opt/java/jdk-25 PATH=/opt/java/jdk-25/bin:$PATH
export GTNH_CORE_BUILD=1 GTNH_CORE_REQUIRED=1     # build the core in prebuild, and fail the build if it is missing
npm install && npm run build
```

`prebuild` then runs `tools/copy-core.mjs`, which runs Gradle (`:web:buildWasmGC :web:generateJavaScript`, no daemon) and
copies the result to `public/core/` before `next build` indexes `public/`. It needs network to Maven Central, the Gradle
plugin portal and the Gradle distribution host. With `HOME=/tmp` Gradle's home is discarded with the container, so every
deploy fetches the dependencies again; mounting a writable directory and setting `GRADLE_USER_HOME` to it keeps them.
Gradle's heap is 512 MB by default; `GTNH_GRADLE_ARGS='["-Dorg.gradle.jvmargs=-Xmx3g"]'` raises it (it runs before
`next build`, so the two do not overlap in the 6 GB). A warm build takes about 40 seconds on four cores.

The other way builds the core elsewhere (the pipeline's output or any machine with JDK 25) and passes the directory that
holds `wasm-gc/` and `js/`: mount it into the container and set `GTNH_CORE_DIR=<that directory>` instead of the two
variables above; no JDK is used in the container then. Leaving all of this out is valid: the site builds and runs without
the core until a feature that needs it ships.

### Developing without a JDK

A machine with only Node needs `public/core/` from somewhere. In order of cost:

| Way | Cost | State |
|---|---|---|
| Build on the build host and copy it back (`box.sh <stream> core-pull`, the restructure's wrapper, outside this repo) | needs the host; builds only when `jvm/` changed; about a minute cold | done |
| A pipeline artifact and a fetch script | the web-core job uploads `public/core` (8 MB), a script downloads it; needs a GitHub token that can read Actions artifacts; expires in 14 to 60 days; exists only for pushed commits | not built: needs a credential |
| Commit the built core | 8 MB of binary per core change in a public repo, conflicts on every merge, a stale core that tests pass against | ruled out |

`tools/copy-core.mjs` leaves a complete `public/core/` alone when it has no build and no `GTNH_CORE_DIR`, so a copied
core survives `npm run dev`.

### Browsers without WebAssembly GC

WebAssembly GC ships in Chrome and Edge 119, Firefox 120 and Safari 18.2. On an older engine, or any engine where the
wasm fails to compile or start (it also needs exception handling), the loader falls back to `js/core.js`, which gives
the same answers (the conformance run proves it) and costs more to download and start. `coreInfo().target` says which
one runs and `coreInfo().fellBack` why. `loadCore({ target })` pins a target and never falls back, for tests.

## The solver

`:core-solver` compiles with three stand-ins for what ojAlgo 57 asks of the JVM (`overrides.txt`, and the `T*` classlib
stubs in `src/main/java/org/teavm/classlib`): `DaemonPoolExecutor` runs a task on the calling thread when it is submitted,
`MultiviewSet` swaps a concurrent set for a plain one, and the stubs add the thread, executor, adder and management classes
that TeaVM's classlib lacks. One more override is for speed only: `DenseTableau` is ojAlgo's own, its equilibration walking
the non-zero entries row by row (the same values; the dense scan was 17% of the heaviest plan's solve). The core solves on one thread (`Parallel` uses `Worker.INLINE` by default), as the browser has
none. `SolverExports.solverSolve(json)` takes the solve input in the JSON `SolveInputJson` writes and returns the answer in
the corpus's golden format; core-solver's `SolverCodec` (the codec the JVM tests read and compare with) reads and writes both with `JsonTree` (no reflection, no Gson); `solverSolveCached(json)` is the same through one island cache per module instance, for the website's solve Worker. The run solves the
whole corpus in `conformance/solver/` on both targets and compares it as `SolverGoldenTest` and `UpToParityTest` do. It also
runs the 223 recorded website cases (`conformance/solver/website/cases.json.gz`) as `WebsiteCasesTest` does: 219 equal by the
golden rule (1e-6 relative), 3 listed as differing and 1 as equal within the LP's tolerance, each with its reason in
`tools/web-core/solver.mjs`.

## Load modules

The core is compiled as two modules per target, so a visitor who never solves does not download the solver. TeaVM has no
code splitting: each module is its own compilation, and both carry `:core-base` and its statics, so module state is not
shared (the solver never reads a card-side value). `modules.txt` is the one list (`<module> <main class> <exports...>`),
read by `build.gradle.kts` and by `tools/web-core/modules.mjs`:

| Module | Main class | Exports | Holds |
|---|---|---|---|
| `core` | `WebCore` | every `<Module>Exports` class not named below | base, formats, machines, power, layout, the recipe card (view) |
| `solver` | `WebSolver` | `SolverExports`, `SolverJavaExports` | the solver and ojAlgo (`SolverJavaExports`: the conformance run's arithmetic probes) |

Exactly one module takes `*`, so a new `<Module>Exports` class joins the card side without an edit here. All modules share
one runtime script, `core.wasm-runtime.js`. `tools/copy-core.mjs` publishes every file under a name with its content hash
(`core.1a2b3c4d.wasm`) and writes a manifest (format 2) that lists the names; `next.config.ts` serves
`/core/wasm-gc/*` and `/core/js/*` as `immutable` for a year and `/core/manifest.json` as `no-cache`, so a returning
visitor fetches the manifest and nothing else. `loadCore({ modules })` loads the named modules (default: `core`);
the namespaces of a module that is not loaded throw `CoreNotLoadedError`, and `coreFor(area)` in `adopt.ts` loads the module
its area needs. The solver module is meant to load inside the solve Worker. `ExportConventionTest` and the conformance
run (`build/module-<name>` checks) hold each module to the exports `modules.txt` assigns it; `webCoreExportVersion` is
checked per module.

### core-model and core-view in the build

`:core-model` and `:core-view` are in `coreModules`, inside the `core` module: `ViewExports` reaches the recipe card's
rules (`CardViews`, `CardGeometry`, the reflection-free reader `CardInputJson`) and what they read of the plan model
(`PinRule`, the Up to records). TeaVM keeps only what an export reaches, so the rail, drawer, group, minimap and note
builders add nothing until an export calls them. The card added (raw, gzip -9) `core.wasm` 1,825,174 / 477,302 to
1,891,920 / 502,657 (+67 KB, +25 KB gz) and `core.js` 1,806,182 / 432,108 to 1,861,898 / 451,215 (+56 KB, +19 KB gz);
the solver module is unchanged. The card side draws the board with layout and power, so it stays in `core`; the plan
codec also runs where there is no board (the live service, the server's plan routes), so it likely wants a module of
its own: measure it then.

`ViewExports`: `viewCardWords(json)` installs the host's words as core-base `Text` (a key-to-pattern object, `%s`,
`%n$s`, `%%`); `viewCard(caseJson)` and `viewCardsSix(dialsJson, cardsJson)` measure text six px a character (the shared
cases); `viewCardMeasured(caseJson, measure)` and `viewCards(dialsJson, cardsJson, measure)` call
`measure.width(style, text)` back for each width (`TextStyle` names). Each card answers `{"view", "geometry"}`.
`tools/web-core/view.mjs` runs the shared card cases on both targets in the conformance run.

## Compiler options

Raw / gzip -9 bytes of each file, per TeaVM option (one build each; speed is the conformance run's time for the same
cases on one core, wasm / JavaScript, in seconds):

| Option | core.wasm | solver.wasm | core.js | solver.js | Result |
|---|---|---|---|---|---|
| `AGGRESSIVE` | 2.11 MB / 482 KB | 2.52 MB / 775 KB | 2.18 MB / 468 KB | 1.79 MB / 502 KB | goldens 7.0 / 32.3, Up to 8.6 / 29.8, machines 7.6 |
| `BALANCED` (now) | 1.52 MB / 375 KB | 1.55 MB / 553 KB | 1.61 MB / 362 KB | 0.99 MB / 331 KB | goldens 7.2 / 31.4, Up to 9.5 / 33.8, machines 6.9 |
| `NONE` | 1.66 MB / 406 KB | 2.00 MB / 656 KB | 1.70 MB / 392 KB | 1.24 MB / 400 KB | goldens 11.5 / 28.6, Up to 10.3 / 34.3, machines 10.8 |

`AGGRESSIVE` inlines until the code is larger and no faster; `BALANCED` keeps the speed and is 22 to 34 percent smaller on
the wire, so it is the build's level (`-PwebOptimization=AGGRESSIVE|NONE` builds the others). The other options:
`fastGlobalAnalysis=true` fails the solver's JavaScript compile (TeaVM error) and is not used; `strict=true` changes the JavaScript
size by under 5 percent either way and the wasm not at all; `debugInformation=true` only adds a deobfuscator file and
leaves the module unchanged; `-Pplain` (no obfuscation, for debugging) is 3 to 5 times larger in JavaScript and
1.3 to 1.6 times in wasm. The runtime script is 17 KB (6 KB gzipped) and shared, so it is not worth an option.

What the card module (`core`) holds: 0.78 MB of its WebAssembly is the embedded handler and power tables
(`handlers.json` and the power data, read by machines and power alike), about half of it. Parts that could leave it as a third
module, measured by compiling them alone: layout (`LayoutExports`) is 124 KB raw and 45 KB gzipped smaller in `core`, and costs 81 KB
as a module of its own (the duplicated `core-base`); the bee and crop readouts are 42 KB raw and 16 KB gzipped. Neither is
worth a third module: a visitor who uses layout would pay 36 KB more than today, and the bee and crop readouts share
the machine tables. Machines and power share the same tables, so a module of power alone is as big as machines and power
together (243 KB against 264 KB gzipped).

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
`check/OverrideCheck.java` (tasks `checkOverrides*`, one per module and target, e.g. `checkOverridesWasmGC` for core and
`checkOverridesSolverWasmGC` for solver; each is part of `check` and a dependency of its own `generate*` task) fails
the build if a listed override is not first on that compilation's classpath, replaces nothing, or if an unlisted class
shadows another. Every override applies to every module. The list is
`overrides.txt`. The runtime probe `webCoreRuntimeMaxMemory` (4 GiB) is checked in the conformance run, and without the
override the compile itself fails.

## Java's arithmetic: the build plugin

`src/main/java/com/gtnhplanner/web/teavm/ExactMathPlugin.java` is a TeaVM plugin (found through
`META-INF/services/org.teavm.vm.spi.TeaVMPlugin`; a build tool, never in the output). Every module's compilation loads
it from the same classpath. It rewrites every class TeaVM compiles, the core, ojAlgo and the class library alike, where
TeaVM's code differs from the JVM's:

- `java.lang.Math` and `StrictMath`: sin, cos, tan, asin, acos, atan, atan2, exp, log, log10, pow, cbrt, sinh, cosh,
  tanh, expm1, log1p, hypot, rint, round, toRadians, toDegrees and ulp run core-base's `ExactMath` (fdlibm in plain
  Java, the JVM's `StrictMath` bits). TeaVM's own are the browser's `Math` (other algorithms) or shortcuts that are
  wrong (`round(-2.5)` is -3, `cbrt` is a power, `tanh` overflows to NaN). A TeaVM upgrade that drops one of these
  methods fails the build.
- Negation of a double or float becomes a multiplication by -1: the WebAssembly backend writes `-x` as `0 - x`, which
  turns -(+0) into +0.
- JavaScript target only: a double or float converted to int or long goes through `JavaCasts`, which saturates as Java
  does; TeaVM writes `x | 0` (wraps) and `BigInt(x)` (throws on NaN).
- JavaScript target only: TeaVM's printer drops the brackets of a double `+` or `*` whose right operand is the same
  kind of operation (`a + (b - c)` runs as `(a + b) - c`, `a * (b / c)` as `(a * b) / c`), so such an operation is
  rewritten into a form it must bracket (`a - (-1 * b)`, `a * (b - 0)`), the same bits.
- `java.math.BigDecimal.valueOf(double)`: core-base's `NumberText.javaDecimal`, the decimal JDK 19 and later give
  (TeaVM's reads its own `Double.toString`, which approximates the shortest digits).

TeaVM's `java.math.BigDecimal` itself is replaced (`src/main/java/org/teavm/classlib/java/math/TBigDecimal.java`, in
`overrides.txt`): TeaVM's file with exact digit counts where it estimated them (`precision()`, the text constructor's
precision, `aproxPrecision`, rounding's carry) and a `divide(divisor, MathContext)` that rounds the magnitudes'
quotient, so `round`, `plus`, `divide` and `multiply` with a MathContext, which ojAlgo uses on every coefficient, give
the JDK's answers. ojAlgo's own jar is rewritten the same way at build time for every runtime (module
`:core-ojalgo`, `jvm/core/core-ojalgo/`, core-solver's api), so the mod, the tests and the web build all run one
ojAlgo arithmetic.

`tools/web-core/exact-math.mjs` proves each rewrite on both targets (`exact-math/*` against
`conformance/core-base/exact-math/`, `java-math/*` and `strict-math/*` through `java.lang.Math`, `java/d2i`, `java/d2l`,
`java/neg`, `java/order-*`, `java/double-to-string`, `java/bigdecimal-*` on the random cases), in each module: core
through `BaseExports`, solver through `SolverJavaExports` (the same groups under `solver/`, apart from `exact-math/*` and
`java/double-to-string`, which call core-base directly; `teavm/JavaProbe` holds the probes both call). Core code still calls
`ExactMath` itself (the purity gate's `math-exact` rule): the
JVM's `Math` is not `StrictMath` either.

## Traps

- TeaVM's Wasm GC backend compiles `-x` on a double as `0.0 - x`, so negating `+0.0` gives `+0.0`: the build plugin
  above rewrites every negation, so this no longer shows.
- No `Long.parseUnsignedLong`; no `long` across the boundary.
- Gradle's configuration cache: task actions must not read script-level values.
- Node reads `core.js` as an ES module without a `type` field and prints a harmless
  `MODULE_TYPELESS_PACKAGE_JSON` warning; the website copy sits beside a package scope that says module.
