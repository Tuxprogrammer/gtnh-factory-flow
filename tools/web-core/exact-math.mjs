// core-base ExactMath against the JVM's StrictMath, for one loaded target, and the build plugin's (jvm/web
// ExactMathPlugin) rewrites of java.lang.Math, StrictMath, negation and double-to-int conversion.
//   conformance/core-base/exact-math/exact-math-vectors.json   [x, (y,) answer] per function (ExactMathTest writes it)
//   the random cases' "math", "d2i", "d2l" and "neg" lines     (jvm/web RandomCases, answered on the JVM)
// Groups: exact-math/<fn> (ExactMath called directly), java-math/<fn> and strict-math/<fn> (java.lang.Math and
// StrictMath, which the plugin sends to ExactMath), java/d2i, java/d2l, java/neg, java/order-<form> (bracketing),
// java/double-to-string and java/bigdecimal-valueof (NumberText.javaString and BigDecimal.valueOf against the JDK),
// java/bigdecimal-op<n> (BigDecimal arithmetic and rounding to a MathContext against the JDK).
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { bitsOf, fromBits } from "./compare.mjs";

const ROOT = fileURLToPath(new URL("../../", import.meta.url));
export const EXACT_MATH_VECTORS = path.join(ROOT, "conformance/core-base/exact-math/exact-math-vectors.json");

/** A double against a 64-bit pattern: bit for bit, any NaN for a NaN (the JVM's doubleToLongBits has one NaN). */
function same(actual, hex) {
  const want = fromBits(hex);
  if (Number.isNaN(want)) return Number.isNaN(actual);
  return typeof actual === "number" && bitsOf(actual) === hex;
}

const long = (h) => h.padStart(16, "0");

function checkOne(base, tally, fn, xHex, yHex, answer, strictAlso) {
  const x = fromBits(xHex);
  const y = yHex === null ? 0 : fromBits(yHex);
  const args = yHex === null ? xHex : `${xHex}, ${yHex}`;
  if (fn === "round") {
    const a = long(base.exactMathRoundHex(x));
    tally.check("exact-math/round", a === answer, () => `round(${args}): ${a} (JVM ${answer})`);
    const b = long(base.javaMathRoundHex(false, x));
    tally.check("java-math/round", b === answer, () => `Math.round(${args}): ${b} (JVM ${answer})`);
    if (strictAlso) {
      const c = long(base.javaMathRoundHex(true, x));
      tally.check("strict-math/round", c === answer, () => `StrictMath.round(${args}): ${c} (JVM ${answer})`);
    }
    return;
  }
  const a = base.exactMath(fn, x, y);
  tally.check(`exact-math/${fn}`, same(a, answer), () => `${fn}(${args}): ${bitsOf(a)} (JVM ${answer})`);
  const b = base.javaMath(false, fn, x, y);
  tally.check(`java-math/${fn}`, same(b, answer), () => `Math.${fn}(${args}): ${bitsOf(b)} (JVM ${answer})`);
  if (strictAlso) {
    const c = base.javaMath(true, fn, x, y);
    tally.check(`strict-math/${fn}`, same(c, answer), () => `StrictMath.${fn}(${args}): ${bitsOf(c)} (JVM ${answer})`);
  }
}

/** The committed vectors, and the random cases' math and conversion lines when there are any. */
export function runExactMath(api, tally, cases = []) {
  const base = api.BaseExports;
  if (!base?.exactMath) {
    tally.check("exact-math/exports", false, () => "the build has no BaseExports.exactMath");
    return;
  }
  const started = Date.now();
  const { functions } = JSON.parse(readFileSync(EXACT_MATH_VECTORS, "utf8"));
  for (const [fn, rows] of Object.entries(functions)) {
    for (const r of rows) {
      if (r.length === 3) checkOne(base, tally, fn, r[0], r[1], r[2], true);
      else checkOne(base, tally, fn, r[0], null, r[1], true);
    }
  }
  for (const c of cases) {
    switch (c[0]) {
      case "math":
        checkOne(base, tally, c[1], c[2], c[3], c[4], false);
        break;
      case "d2i": {
        const a = base.javaToInt(fromBits(c[1]));
        tally.check("java/d2i", a === c[2], () => `(int) ${c[1]}: ${a} (JVM ${c[2]})`);
        break;
      }
      case "d2l": {
        const a = long(base.javaToLongHex(fromBits(c[1])));
        tally.check("java/d2l", a === c[2], () => `(long) ${c[1]}: ${a} (JVM ${c[2]})`);
        break;
      }
      case "neg": {
        const a = base.javaNegate(fromBits(c[1]));
        tally.check("java/neg", same(a, c[2]), () => `-${c[1]}: ${bitsOf(a)} (JVM ${c[2]})`);
        break;
      }
      case "jstr": {
        const x = fromBits(c[1]);
        const a = base.numberTextJavaString(x);
        tally.check("java/double-to-string", a === c[2], () => `${c[1]}: ${a} (JDK ${c[2]})`);
        const b = base.javaBigDecimalOf(x);
        tally.check("java/bigdecimal-valueof", b === c[3], () => `${c[1]}: ${b} (JDK ${c[3]})`);
        break;
      }
      case "dec": {
        const a = base.javaBigDecimalOp(c[1], c[2], c[3], c[4], c[5]);
        tally.check(`java/bigdecimal-op${c[1]}`, a === c[6], () => `op ${c[1]} (${c[2]}, ${c[3]}, ${c[4]}, mode ${c[5]}): ${a} (JDK ${c[6]})`);
        break;
      }
      case "assoc": {
        const a = base.javaOrder(c[1], fromBits(c[2]), fromBits(c[3]), fromBits(c[4]));
        tally.check(`java/order-${c[1]}`, same(a, c[5]), () => `form ${c[1]} (${c[2]}, ${c[3]}, ${c[4]}): ${bitsOf(a)} (JVM ${c[5]})`);
        break;
      }
      default:
    }
  }
  tally.group("exact-math/sin").ms = Date.now() - started;
}

/** The case kinds this file answers; random.mjs leaves them alone. */
export const EXACT_MATH_KINDS = new Set(["math", "d2i", "d2l", "neg", "assoc", "jstr", "dec"]);
