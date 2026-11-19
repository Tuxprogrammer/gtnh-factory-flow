// Writes conformance/core-base/number-text/number-text-vectors.json from JavaScript itself (Node), the ground truth for core-base's
// NumberText and JsMath: String(n), JSON.stringify(n), n.toFixed(d), Number(s) and Math.round(n).
//   node tools/conformance/core-base/gen-vectors.mjs [scale [out]]   scale multiplies the random sample (default 1; the
//   committed file is scale 1); out is another file to write, for a larger evidence run
// Then: node tools/conformance/manifest.mjs --write
// Doubles are written as their 64 bits in hex, so nothing depends on a reader's own float parsing.
import { writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const scale = Number(process.argv[2] ?? 1);
const out = process.argv[3] ?? join(dirname(fileURLToPath(import.meta.url)), "../../../conformance/core-base/number-text/number-text-vectors.json");

const view = new DataView(new ArrayBuffer(8));
const bitsOf = (x) => (view.setFloat64(0, x), view.getBigUint64(0).toString(16).padStart(16, "0"));
const fromBits = (b) => (view.setBigUint64(0, BigInt.asUintN(64, b)), view.getFloat64(0));
const next = (x, d) => fromBits(BigInt("0x" + bitsOf(x)) + BigInt(d));

// A fixed-seed generator (xorshift64*), so the file is the same on every run.
let s = 0x9e3779b97f4a7c15n;
const rand64 = () => {
  s ^= s >> 12n; s ^= (s << 25n) & 0xffffffffffffffffn; s ^= s >> 27n;
  return (s * 0x2545f4914f6cdd1dn) & 0xffffffffffffffffn;
};
const randInt = (n) => Number(rand64() % BigInt(n));
const randFinite = () => { for (;;) { const x = fromBits(rand64()); if (Number.isFinite(x)) return x; } };

const doubles = [0, -0, NaN, Infinity, -Infinity, Number.MIN_VALUE, Number.MAX_VALUE, 2.2250738585072014e-308,
  2 ** 53, 2 ** 53 - 1, 2 ** 53 + 2, 2 ** 64, 1e21, 1e-6, 1e-7, 0.1, 0.2, 0.3, 0.1 + 0.2, 1 / 3, 2 / 3, 1e23, 5e-324,
  123456789012345680000, 4.35, 0.000001234, 1.5e300, 9.999999999999999e22, 299792458, 1.7976931348623157e308];
for (const x of [...doubles]) if (Number.isFinite(x) && x !== 0) doubles.push(next(x, 1), next(x, -1), -x);
for (let e = -330; e <= 310; e++) { const x = Number("1e" + e); if (x > 0 && Number.isFinite(x)) doubles.push(x, next(x, 1), next(x, -1)); }
for (let e = -1074; e <= 1023; e++) doubles.push(2 ** e);
for (let i = 0; i < 400 * scale; i++) doubles.push(randFinite());
for (let i = 0; i < 800 * scale; i++) doubles.push((randInt(2e9) - 1e9) / 10 ** randInt(9));
for (let i = 0; i < 400 * scale; i++) doubles.push(randInt(100000) / (1 + randInt(999)));
for (let i = 0; i < 200 * scale; i++) doubles.push(randInt(1e6) * 10 ** (randInt(40) - 20));

const of = doubles.map((x) => [bitsOf(x), String(x), JSON.stringify(x) ?? "null"]);

const fixedValues = [0.15, 1.005, 2.5, -2.5, 0.5, 1.45, -0.0001, 1e21, 999.995, 1e-7, 0, -0, NaN, Infinity, -1.5,
  123.456, 1e20, 9.995, 0.045, 2 ** -1074, 1234.5678];
for (let i = 0; i < 400 * scale; i++) fixedValues.push(doubles[randInt(doubles.length)]);
const digitChoices = [0, 1, 2, 3, 6, 20, 100];
const fixed = [];
for (const x of fixedValues) {
  for (const d of [digitChoices[randInt(digitChoices.length)], 1, 2]) fixed.push([bitsOf(x), d, x.toFixed(d)]);
}

const strings = ["", " ", "\t\n 12 \r", " 12　", " -3 ", "﻿7", "\u00011", "1\u0001",
  "Infinity", "+Infinity", "-Infinity", "infinity", "INFINITY", "0x1F", "0X1f", "-0x10", "+0x10", "0x", "0xg", "0b101",
  "0B2", "0o17", "0o8", "0O7", "1.", ".5", ".", "-.5", "+.5", "1e", "1e+", "1e5", "1E-5", "1e+5", "-0", "+0", "0",
  "00012", "00.5", "1_000", "12abc", "1.2.3", "--1", "+-1", "1e1.5", "e5", " 1 2 ", "0.0000000000000000000001",
  "9007199254740993", "9007199254740995", "9007199254740992.5", "2.2250738585072011e-308",
  "2.2250738585072012e-308", "4.9406564584124654e-324", "2.4703282292062327e-324", "2.4703282292062328e-324",
  "1.7976931348623157e308", "1.7976931348623158e308", "1.7976931348623159e308", "1e309", "1e-400", "1e400",
  "-1e400", "0x1fffffffffffff", "0x20000000000001", "0x20000000000003", "0x" + "f".repeat(300),
  "0b" + "1".repeat(80), "1".repeat(400), "0." + "0".repeat(400) + "1", "1" + "0".repeat(308),
  "123456789012345678901234567890", "1e99999999999999999999", "1e-99999999999999999999", "٣", "１", "5e-324",
  "3e-324", "2e-324", "0.1e1", "100e-2", "1.0000000000000002", "1.00000000000000011102230246251565404236316680908203125",
  "1.00000000000000011102230246251565404236316680908203124", "1.00000000000000011102230246251565404236316680908203126"];
for (let i = 0; i < 400 * scale; i++) strings.push(of[randInt(of.length)][1]);
for (let i = 0; i < 400 * scale; i++) strings.push(doubles[randInt(doubles.length)].toPrecision(1 + randInt(21)));
for (let i = 0; i < 800 * scale; i++) {
  let d = String(1 + randInt(9));
  const n = randInt(40);
  for (let j = 0; j < n; j++) d += String(randInt(10));
  const dot = randInt(d.length + 1);
  const t = (randInt(2) ? "-" : "") + d.slice(0, dot) + (randInt(2) ? "." : "") + d.slice(dot) + "e" + (randInt(700) - 350);
  strings.push(t);
}
const parse = strings.map((t) => [t, bitsOf(Number(t))]);

const roundValues = [0.5, -0.5, 1.5, -1.5, 2.5, -2.5, -0.4, 0.4, -0, 0, 0.49999999999999994, -0.49999999999999994,
  4503599627370495.5, -4503599627370495.5, 2 ** 52, 2 ** 53, NaN, Infinity, -Infinity, 1e300, -1e-300];
for (let i = 0; i < 400 * scale; i++) roundValues.push((randInt(2e6) - 1e6) / 2 ** randInt(8));
for (let i = 0; i < 100 * scale; i++) roundValues.push(randFinite());
const round = roundValues.map((x) => [bitsOf(x), bitsOf(Math.round(x))]);

writeFileSync(out, JSON.stringify({
  about: "JavaScript's own answers (Node " + process.version + ") for core-base's NumberText and JsMath. Doubles are 64-bit patterns in hex. of: [bits, String(n), JSON.stringify(n)]; fixed: [bits, digits, n.toFixed(digits)]; parse: [text, bits of Number(text)]; round: [bits, bits of Math.round(n)]. Regenerate with gen-vectors.mjs; never edit by hand.",
  of, fixed, parse, round,
}) + "\n");
console.log(`wrote ${out}: ${of.length} of, ${fixed.length} fixed, ${parse.length} parse, ${round.length} round`);
