# View corpus

What the shared view model prints for a plan: input records and the views the website's own code makes of them, held to by
the Java core (`jvm/core/core-view`). One layout, one generator, one JVM test pattern, one vitest pattern.

## Layout

```
conformance/view/<record>/<set>.json
```

`<record>` is the view record family, lower case, one directory each: `rail`, `notices`, and as they arrive `card`,
`drawer`, `group`, `note`, `minimap`. `<set>` names a file inside it (`cases`, `math`, ...). Nothing else sits in
`conformance/view/` but this README and `records.json` (below). Every file is listed in `conformance/manifest.json` (area `view`); both suites fail
when one is missing or its hash differs.

## The envelope

Every file is one object with these keys, in this order:

```json
{ "record": "rail", "set": "cases", "measure": "none", "cases": [ { "name": "...", "input": {}, "view": {} } ] }
```

- `record`, `set`: the path the file lives at. A test that opens a file checks both.
- `measure`: the text measure the numbers were computed with. `none` when the record puts no text through a
  `TextMeasure`; `six-px` for the fixed test measure (six pixels a character, one and a half times that for the row
  figure: `Cards.SIX` in the Java tests, the same constants on the website's side). A golden is never computed on a
  host's real font.
- `cases`: `[{name, input, view}]`: the input record and the view of it. `view` is the JSON the Java `Json` writer
  writes for the same input, byte for byte (key order and number spelling included).
- `vectors`: plain arithmetic, `{group: [{...arguments, out}]}`. Exactly one of `cases` and `vectors`.

## Record convention

A view record is a Java `record` with public components, in `jvm/core/core-view`. On the wire:

- component order is declaration order; no whitespace; strings escaped as `JSON.stringify` does; numbers through
  `NumberText.json`;
- an optional (`@Nullable` in Java) is a missing key, never `null`: `foo?: T` in TypeScript;
- allowed component types: `int`, `double`, `Double`, `Integer`, `boolean`, `Boolean`, `String`, an enum (by name),
  `Uuid` (as text), `List<T>`, a nested record, `Map<String, T>` (sorted keys). Not allowed: `long`, arrays, `Object`,
  `Number`, a map keyed by anything but `String`.
- the record is written by one method that uses `com.gtnhplanner.core.view.Json` (the one writer). The writer is not
  generated, so the declarations test below checks what it writes against the record.

TypeScript declarations for every record are generated from the Java records, never written by hand.
`jvm/core/core-view/view-records.json` lists the root records (`roots`, by their flat names: `RailView.Dial` is
`RailViewDial`) and which corpus file holds which (`cases`: the record of each `input` and `view`). From the roots,
`tools/web-core/gen-records.mjs` follows every record and enum they name and writes:

- `src/lib/core/records.generated.ts`, gitignored, one interface per record (run by `gen-types.mjs`, so by the npm
  pre-hooks and the vitest setup);
- `conformance/view/records.json`, tracked: the shape table (class, components, type of each, `?` after an optional).

Three checks keep the record, its declaration and the writer in step:

- `tools/web-core/gen-records.test.mjs` (vitest): both generated files are what the Java sources give now, and every
  `input` and `view` in this corpus is a value of its declared record (no key the record lacks, none missing, no null).
- `ViewRecordsTest` (JUnit, `core-view`): the shape table equals what the compiler says each record is (reflection), so
  a parser that read a record wrongly cannot pass.
- `CorpusTest` (JUnit): the JSON the `Json` writer writes for each case is read back through the record, which refuses a
  key the record does not have.

## Generating

```bash
npx tsx tools/conformance/view/generate.ts           # write every file, remove any no family makes
npx tsx tools/conformance/view/generate.ts --check   # fail when a file differs
node tools/conformance/manifest.mjs --write          # refresh the manifest
```

The one entry point asks each family for its files: a family is a module exporting `viewFiles(): Record<string,string>`
(path under this directory to text), listed in `src/test-support/view-families.ts`. It builds each file with
`viewEnvelope(record, set, measure, body)` from `src/test-support/view-corpus.ts`. The same code gives the same bytes;
`src/test-support/view-corpus.test.ts` fails when the tree differs from what the families make now.

## Reading: the website (vitest)

```ts
const { cases } = readViewCases<RailInput, RailViewJson>("rail", "cases");   // src/test-support/view-corpus.ts
it.each(cases.map((c) => [c.name, c] as const))("%s", (_n, c) => {
  expect(JSON.parse(JSON.stringify(railViewOf(c.input)))).toEqual(c.view);
});
```

The website's own TypeScript is bound by the file; it is not switched to the core.

## Reading: the core (JUnit)

```java
class RailCasesTest extends CorpusTest<RailInput> {
    RailCasesTest() { super("rail", "cases", RailInput.class, 40); }       // record, set, input record, case floor
    protected String render(RailInput in) { return RailBuilder.build(in).toJson(); }
}
```

`CorpusTest` (`jvm/core/core-view/src/test/.../corpus`) opens the file through `ViewCorpus` (checks the envelope and the
measure), reads each `input` into the record with `RecordReader` (reflection, tests only), renders it and compares the
text with the stored `view`: key order and number spelling too. A file of `vectors` is read with
`ViewCorpus.open(record, set).vectors()` and judged by hand-written asserts (`RailMathVectorsTest`).

## Adding a record family

1. The record and its `Json` writer in `core-view`.
2. Its roots and corpus files in `view-records.json`.
3. A corpus module for the website's side with `viewFiles()`, one line in `view-families.ts`.
4. `generate.ts` (it also writes `records.json`), `manifest.mjs --write`.
5. A 10-line `CorpusTest` subclass.
6. Run `npm run test` and `:core-view:test` (the declarations tests pick the record and the file up by themselves).
