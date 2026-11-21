// The committed vector files, run against one loaded target. These are the files the JVM tests read too
// (NumberTextTest, UuidTest): JavaScript's own answers for numbers, java.util.UUID's for ids.
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { attempt, bitsOf, fromBits } from "./compare.mjs";

const ROOT = fileURLToPath(new URL("../../", import.meta.url));

/** Where each vector file is: the shared tree first, then the place it was before it moved there. */
const FILES = {
  numberText: [
    "conformance/core-base/number-text/number-text-vectors.json",
    "jvm/core/core-base/src/test/resources/number-text/number-text-vectors.json",
  ],
  uuid: [
    "conformance/core-base/uuid/uuid-vectors.json",
    "jvm/core/core-base/src/test/resources/uuid/uuid-vectors.json",
  ],
};

export function vectorFile(key) {
  for (const rel of FILES[key]) if (existsSync(path.join(ROOT, rel))) return path.join(ROOT, rel);
  throw new Error(`vector file ${key} not found, looked in ${FILES[key].join(", ")}`);
}

export function loadVectors() {
  const read = (k) => JSON.parse(readFileSync(vectorFile(k), "utf8"));
  return { numberText: read("numberText"), uuid: read("uuid") };
}

const hex16 = (s) => s.padStart(16, "0");

/**
 * conformance/core-base/solved-number/solved-number-vectors.json: SolvedNumber.clean bit for bit, through the core
 * module (BaseExports) and the solver module (SolverJavaExports), where a solve's answer is cleaned.
 */
export function runSolvedNumber(api, tally) {
  const file = path.join(ROOT, "conformance/core-base/solved-number/solved-number-vectors.json");
  const rows = JSON.parse(readFileSync(file, "utf8")).rows;
  const modules = [
    ["core", api.BaseExports],
    ["solver", api.SolverJavaExports],
  ];
  for (const [module, exports] of modules) {
    for (const [x, answer, what] of rows) {
      const got = exports.solvedNumberClean(fromBits(x));
      const detail = () => `${x} (${what}): ${bitsOf(got)} (JVM ${answer})`;
      tally.checkDouble(`solved-number/${module}`, got, answer, detail);
    }
  }
}

/** Run every vector. `tally` collects the results, grouped as "<file>/<kind>". */
export function runVectors(api, v, tally) {
  const base = api.BaseExports;
  const nt = v.numberText;
  for (const [bits, js, json] of nt.of) {
    const x = fromHexBits(bits);
    const a = base.numberTextOf(x);
    tally.check("number-text/of", a === js, () => `${bits}: ${a} (JavaScript ${js})`);
    const b = base.numberTextJson(x);
    tally.check("number-text/json", b === json, () => `${bits}: ${b} (JavaScript ${json})`);
  }
  for (const [bits, digits, js] of nt.fixed) {
    const a = base.numberTextFixed(fromHexBits(bits), digits);
    tally.check("number-text/fixed", a === js, () => `${bits} toFixed(${digits}): ${a} (JavaScript ${js})`);
  }
  for (const [text, bits] of nt.parse) {
    const a = base.numberTextParse(text);
    tally.checkDouble("number-text/parse", a, bits, () => `'${text}': ${bitsOf(a)} (JavaScript ${bits})`);
  }
  for (const [bits, out] of nt.round) {
    const a = base.jsMathRound(fromHexBits(bits));
    tally.checkDouble("number-text/round", a, out, () => `${bits}: ${bitsOf(a)} (JavaScript ${out})`);
  }

  const u = v.uuid;
  for (const [msb, lsb, text, hash] of u.ids) {
    const m = hex16(msb);
    const l = hex16(lsb);
    const a = base.uuidToString(m, l);
    tally.check("uuid/string", a === text, () => `${m}:${l}: ${a} (JDK ${text})`);
    const h = base.uuidHash(m, l);
    tally.check("uuid/hash", h === hash, () => `${m}:${l}: ${h} (JDK ${hash})`);
    const info = base.uuidInfo(m, l);
    tally.check("uuid/info", info.text === text && info.hash === hash, () => `${m}:${l}: ${JSON.stringify(info)}`);
  }
  const list = u.ids.map(([m, l]) => `${hex16(m)}:${hex16(l)}`).join(",");
  const order = base.uuidOrder(list);
  tally.check("uuid/order", order === u.order.join(","), () => `order differs from the JDK's`);
  for (let i = 0; i + 1 < u.ids.length; i++) {
    const [am, al] = u.ids[i];
    const [bm, bl] = u.ids[i + 1];
    const a = base.uuidCompare(hex16(am), hex16(al), hex16(bm), hex16(bl));
    const posI = u.order.indexOf(i);
    const posJ = u.order.indexOf(i + 1);
    const equal = am === bm && al === bl;
    const expected = equal ? 0 : posI < posJ ? -1 : 1;
    tally.check("uuid/compare", a === expected, () => `ids ${i} and ${i + 1}: ${a} (JDK ${expected})`);
  }
  for (const [name, id] of u.names) {
    const a = base.uuidName(name);
    tally.check("uuid/name", a === id, () => `'${name}': ${a} (JDK ${id})`);
  }
  for (const [text, out] of u.parse) {
    const a = base.uuidParse(text);
    tally.check("uuid/parse", a === out, () => `'${text}': ${a} (JDK ${out})`);
  }
}

function fromHexBits(hex) {
  const dv = new DataView(new ArrayBuffer(8));
  dv.setBigUint64(0, BigInt("0x" + hex));
  return dv.getFloat64(0);
}

/** The probes about the build itself: the override is the class compiled in, the export version is the one expected. */
export function runBuildProbes(api, tally) {
  const base = api.BaseExports;
  tally.check("build/exports-class", typeof base === "object" || typeof base === "function", () => `BaseExports is ${typeof base}`);
  const version = attempt(() => api.webCoreExportVersion());
  tally.check("build/export-version", version.value === 1, () => `webCoreExportVersion: ${version.value ?? version.threw}`);
  const mem = attempt(() => api.webCoreRuntimeMaxMemory());
  tally.check("build/runtime-override", mem.value === 4294967296, () => `Runtime.maxMemory: ${mem.value ?? mem.threw}`);
  const tooMany = attempt(() => base.numberTextFixed(1, 101));
  tally.check("build/fixed-throws", tooMany.threw !== undefined, () => "baseNumberTextFixed(1, 101) returned");
  const none = attempt(() => base.numberTextFixed(1, -1));
  tally.check("build/fixed-throws", none.threw !== undefined, () => "baseNumberTextFixed(1, -1) returned");
}
