// The recipe card's view rules (core-view CardViews) in the compiled core against the shared card cases
// (conformance/view/card/cases.json), which the website's own builders wrote and the JVM's CardCasesTest reads: every
// case's view must come out key for key and spelt the same, measured six px a character, in the mod's English words.
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../../", import.meta.url));

/** The language file the core's words come from (core-base Text keys), as Java's Properties reads it. */
export const LANG_FILE = "jvm/mod/src/main/resources/assets/gtnhplanner/lang/en_US.lang";

/**
 * Key to pattern of a .lang file, read as java.util.Properties reads one: `#`/`!` comment lines, `=` or `:` (or white
 * space) after the key, `\` escapes (`\n`, `\t`, `\uXXXX`, any other character as itself) and a trailing `\` joining the
 * next line. `prefix` keeps only the keys that start with it.
 */
export function langWords(prefix = "", file = path.join(ROOT, LANG_FILE)) {
  const lines = readFileSync(file, "utf8").split(/\r?\n/);
  const out = {};
  for (let i = 0; i < lines.length; i++) {
    let line = lines[i].replace(/^[ \t\f]+/, "");
    if (!line || line[0] === "#" || line[0] === "!") continue;
    // A logical line ends at a line that does not end in an odd number of backslashes.
    while (/(^|[^\\])(\\\\)*\\$/.test(line) && i + 1 < lines.length) {
      line = line.slice(0, -1) + lines[++i].replace(/^[ \t\f]+/, "");
    }
    let k = 0;
    while (k < line.length && !"=: \t\f".includes(line[k])) k += line[k] === "\\" ? 2 : 1;
    const key = unescape(line.slice(0, k));
    let rest = line.slice(k).replace(/^[ \t\f]*/, "");
    if (rest[0] === "=" || rest[0] === ":") rest = rest.slice(1).replace(/^[ \t\f]*/, "");
    if (key.startsWith(prefix)) out[key] = unescape(rest);
  }
  return out;
}

function unescape(s) {
  return s.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (_, e) =>
    e[0] === "u" && e.length === 5
      ? String.fromCharCode(parseInt(e.slice(1), 16))
      : e === "n"
        ? "\n"
        : e === "t"
          ? "\t"
          : e === "r"
            ? "\r"
            : e === "f"
              ? "\f"
              : e,
  );
}

/** The card's words, handed to the core once per loaded module. */
export function cardWords() {
  return langWords("gtnhplanner.card.");
}

/** The first place two JSON values differ, for a failure line. */
export function firstDifference(want, got, where = "view") {
  if (JSON.stringify(want) === JSON.stringify(got)) return null;
  if (want && got && typeof want === "object" && typeof got === "object") {
    const keys = [...new Set([...Object.keys(want), ...Object.keys(got)])];
    const order = JSON.stringify(Object.keys(want)) !== JSON.stringify(Object.keys(got));
    for (const k of keys) {
      const d = firstDifference(want[k], got[k], `${where}.${k}`);
      if (d) return d;
    }
    if (order)
      return `${where}: key order ${Object.keys(got).join(",")} for ${Object.keys(want).join(",")}`;
  }
  return `${where}: ${JSON.stringify(got)} for ${JSON.stringify(want)}`;
}

/** Run every shared card case; groups "view/card" (the view) and "view/card-geometry" (the record is whole). */
export function runView(api, tally) {
  const v = api.ViewExports;
  if (!v) {
    tally.check("view/exports-class", false, () => "the build has no ViewExports");
    return;
  }
  const words = cardWords();
  tally.check(
    "view/words",
    v.viewCardWords(JSON.stringify(words)) === Object.keys(words).length,
    () => "the core kept another number of words",
  );
  const corpus = JSON.parse(
    readFileSync(path.join(ROOT, "conformance/view/card/cases.json"), "utf8"),
  );
  tally.check(
    "view/measure",
    corpus.measure === "six-px",
    () => `cases.json is measured ${corpus.measure}`,
  );
  for (const c of corpus.cases) {
    let out;
    try {
      out = JSON.parse(v.viewCard(JSON.stringify(c.input)));
    } catch (e) {
      tally.check("view/card", false, () => `${c.name}: the core threw ${String(e).slice(0, 200)}`);
      continue;
    }
    // Text equality: key order and number spelling count, as CardCasesTest compares on the JVM.
    tally.check(
      "view/card",
      JSON.stringify(out.view) === JSON.stringify(c.view),
      () => `${c.name}: ${firstDifference(c.view, out.view)}`,
    );
    const g = out.geometry;
    const rows = c.view.inputs.length + c.view.outputs.length;
    tally.check(
      "view/card-geometry",
      g && g.width > 0 && g.height >= g.railsTop && g.anchors.length === rows,
      () => `${c.name}: geometry ${JSON.stringify(g)}`,
    );
  }
}
