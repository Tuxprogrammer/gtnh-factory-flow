# conformance/

The one copy of every piece of test data that specifies behaviour both the website and the game must share. Plain
files; any runner can read them. `manifest.json` says what each is.

## Layout

```
conformance/
  manifest.json      areas (what each directory specifies, who reads it) and files (area, size, sha256 of every file)
  .gitattributes     `* -text`: the bytes are hashed, so nothing may convert them
  solver/corpus/     inputs/ (SolveInput dumps) and goldens/ (the answer's digest); the solver's whole answer, 1e-6
  solver/up-to/      Up to parity: {name, input, answer} per plan, written by the website's engine
  board/             S1 scenarios and what the mod's own code made of them
  machines/          machine, bee and crop goldens (gzip, one JSON object per line); machine-table reference coefficients
  live/              live-plan op vectors
  tasks/             task order-key vectors
  core-base/         the kernel's value vectors: number-text (JavaScript's spelling of doubles) and uuid (java.util.UUID)
  schema/            JSON Schema for the plan, blueprint payload, dataset and solver data, generated from the Zod schemas
  plans/             plan files both sides read: valid/, invalid/, website/ (what the website writes), mod/ (what the mod writes)
  pending/           files that disagree with a copy above and wait for a decision; no suite reads them
```

An area is a directory under `conformance/` with an entry in `manifest.json` `areas`: `specifies` (what the data
pins), `readers` (the tests that read it) and, where there is one, `generator` (the command that rewrites it). A file
belongs to the area of its nearest listed directory.

## How a test finds the tree

Always through the helper, never by a path of its own and never through the classpath.

- JVM (every project under `jvm/`, `:mod` and each `jvm/core/*`): Gradle sets the system property `gtnh.conformance`
  to the directory on every Test task and declares it as a task input (`jvm/build.gradle.kts`), and compiles
  `jvm/conformance-support/` into every project's test source set. In a test:
  `Conformance.open("solver/corpus/goldens/mv.json")`, `Conformance.path(...)`, `Conformance.list("solver/up-to")`,
  `Conformance.text(...)` (`com.gtnhplanner.conformance.Conformance`). From an IDE, with no property set, it walks up from
  the working directory to the first `conformance/manifest.json`. The helper lives in test sources only, so the purity
  gate on `src/main` of the core modules is not involved. A golden-writing run (`-PwriteGoldens`) writes to the real file
  through `Conformance.path`.
- Website (vitest): `src/test-support/conformance.ts`: `conformancePath(...)`, `readConformanceJson(...)`,
  `readConformanceText(...)`, `readConformanceBytes(...)`, `listConformance(...)`. It finds the tree from its own place;
  `GTNH_CONFORMANCE=<dir>` overrides it.
- Anything else (the Node runner, a script): the directory is `conformance/` at the repository root; read
  `manifest.json` for what is there. `tools/conformance/manifest.mjs` exports `ROOT`, `dataFiles`, `measure` and
  `problems` for it.

Nothing here is fetched or generated at test time. Files that are compressed stay compressed (`*.jsonl.gz`); the reader
unzips.

## Drift check

Both suites fail when a file under `conformance/` is not in `manifest.json`, when a listed file is missing, or when its
size or sha256 differs: `src/test-support/conformance-manifest.test.ts` (vitest) and
`jvm/mod/src/test/java/com/gtnhplanner/conformance/ConformanceManifestTest.java` (JUnit, in `:mod`). The files named
`manifest.json`, `.gitattributes` and `README.md` (at any depth) are not data and are not listed.

## Changing the data

1. Change or add the file (a generator command is in its area's `generator`).
2. `node tools/conformance/manifest.mjs --write` rewrites `files` from the tree; add an entry to `areas` first for a new
   directory. `--check` is what the drift tests do.
3. Run both suites. A change under `conformance/` is a change to both sides: the CI pipeline runs everything for it.

A change to a golden moves behaviour on both sides. Say in the commit which side's behaviour moved and why the other
side now agrees.
