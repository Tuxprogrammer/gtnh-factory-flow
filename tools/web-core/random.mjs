// The random run: cases the JVM made (jvm/web RandomCases, answered by the JVM's own classes) replayed against one
// loaded target. For the number cases the answer is also checked against Node's own: a JVM answer that disagrees with
// Node's is a difference in the core itself, reported on its own line ("jvm!=node"), not charged to the target.
import { readFileSync } from "node:fs";
import { attempt, bitsOf, fromBits, sameDouble } from "./compare.mjs";
import { EXACT_MATH_KINDS } from "./exact-math.mjs";

export function readCases(file) {
  return readFileSync(file, "utf8").split("\n").filter((l) => l.length > 0).map((l) => JSON.parse(l));
}

/** The core's own answers against Node's, once for all targets. */
export function checkJvmAgainstNode(cases, tally) {
  for (const c of cases) {
    switch (c[0]) {
      case "of": {
        const x = fromBits(c[1]);
        tally.check("jvm!=node/of", c[2] === String(x), () => `${c[1]}: JVM ${c[2]}, Node ${String(x)}`);
        tally.check("jvm!=node/json", c[3] === (JSON.stringify(x) ?? "null"), () => `${c[1]}: JVM ${c[3]}, Node ${JSON.stringify(x)}`);
        break;
      }
      case "fixed": {
        const r = attempt(() => fromBits(c[1]).toFixed(c[2]));
        const node = r.threw ? null : r.value;
        tally.check("jvm!=node/fixed", c[3] === node, () => `${c[1]} toFixed(${c[2]}): JVM ${c[3]}, Node ${node}`);
        break;
      }
      case "parse":
        tally.check("jvm!=node/parse", sameDouble(Number(c[1]), c[2]), () => `'${c[1]}': JVM ${c[2]}, Node ${bitsOf(Number(c[1]))}`);
        break;
      case "round":
        tally.check("jvm!=node/round", sameDouble(Math.round(fromBits(c[1])), c[2]), () => `${c[1]}: JVM ${c[2]}, Node ${bitsOf(Math.round(fromBits(c[1])))}`);
        break;
      case "f32":
        tally.check("jvm!=node/f32", sameDouble(Math.fround(fromBits(c[1])), c[2]), () => `${c[1]}: JVM ${c[2]}, Node ${bitsOf(Math.fround(fromBits(c[1])))}`);
        break;
      default:
    }
  }
}

/** The target against the JVM's answers. */
export function runRandom(api, cases, tally) {
  const base = api.BaseExports;
  for (const c of cases) {
    switch (c[0]) {
      case "of": {
        const x = fromBits(c[1]);
        const a = base.numberTextOf(x);
        tally.check("random/of", a === c[2], () => `${c[1]}: ${a} (JVM ${c[2]})`);
        const b = base.numberTextJson(x);
        tally.check("random/json", b === c[3], () => `${c[1]}: ${b} (JVM ${c[3]})`);
        break;
      }
      case "fixed": {
        const r = attempt(() => base.numberTextFixed(fromBits(c[1]), c[2]));
        if (c[3] === null) tally.check("random/fixed-throws", r.threw !== undefined, () => `toFixed(${c[2]}) returned ${r.value}`);
        else tally.check("random/fixed", r.value === c[3], () => `${c[1]} toFixed(${c[2]}): ${r.value ?? r.threw} (JVM ${c[3]})`);
        break;
      }
      case "parse": {
        const a = base.numberTextParse(c[1]);
        tally.checkDouble("random/parse", a, c[2], () => `'${c[1]}': ${bitsOf(a)} (JVM ${c[2]})`);
        break;
      }
      case "round": {
        const a = base.jsMathRound(fromBits(c[1]));
        tally.checkDouble("random/round", a, c[2], () => `${c[1]}: ${bitsOf(a)} (JVM ${c[2]})`);
        break;
      }
      case "f32": {
        const a = base.float32Round(fromBits(c[1]));
        tally.checkDouble("random/f32", a, c[2], () => `${c[1]}: ${bitsOf(a)} (JVM ${c[2]})`);
        break;
      }
      case "id": {
        const info = base.uuidInfo(c[1], c[2]);
        tally.check("random/uuid-info", info.text === c[3] && info.hash === c[4] && info.version === c[5] && info.variant === c[6],
          () => `${c[1]}:${c[2]}: ${JSON.stringify(info)} (JDK ${c[3]} ${c[4]} ${c[5]} ${c[6]})`);
        break;
      }
      case "cmp": {
        const a = base.uuidCompare(c[1], c[2], c[3], c[4]);
        tally.check("random/uuid-compare", a === c[5], () => `${c[1]}:${c[2]} vs ${c[3]}:${c[4]}: ${a} (JDK ${c[5]})`);
        break;
      }
      case "order": {
        const a = base.uuidOrder(c[1]);
        tally.check("random/uuid-order", a === c[2], () => `${c[1].split(",").length} ids: order differs from the JDK's`);
        break;
      }
      case "name": {
        const a = base.uuidName(c[1]);
        tally.check("random/uuid-name", a === c[2], () => `${JSON.stringify(c[1].slice(0, 40))}: ${a} (JDK ${c[2]})`);
        break;
      }
      case "uparse": {
        const a = base.uuidParse(c[1]);
        tally.check("random/uuid-parse", a === c[2], () => `${JSON.stringify(c[1])}: ${a} (JDK ${c[2]})`);
        break;
      }
      default:
        if (!EXACT_MATH_KINDS.has(c[0])) tally.check("random/unknown-kind", false, () => `case kind ${c[0]}`);
    }
  }
}
