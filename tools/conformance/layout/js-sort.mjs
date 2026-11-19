// Writes conformance/layout/js-sort.json: what JavaScript's Array.prototype.sort (V8, as the website runs it) does with
// comparators that are and are not consistent orders, for core-layout's JsSort to match (JsSortTest). The website's
// router and Arrange sort with comparators that ties can make cyclic, and then the order depends on the algorithm.
//
// Usage: node tools/conformance/layout/js-sort.mjs (deterministic; the comparators are hashes of the two elements).
import { writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");

/** -1, 0 or 1 from the two elements and a seed: any relation at all, cycles included. JsSortTest computes the same. */
export function hashOrder(seed, x, y) {
  let h = Math.imul(x, 0x9e3779b1) ^ Math.imul(y, 0x85ebca77) ^ seed;
  h ^= h >>> 15;
  h = Math.imul(h, 0x2c1b3c6d);
  h ^= h >>> 12;
  return ((h >>> 0) % 3) - 1;
}

/** The kinds: how element x compares with y, given its key. Keys are part of the case. */
const KINDS = {
  // Any relation: the comparator answers from a hash of the pair.
  hash: (c, x, y) => hashOrder(c.seed, x, y),
  // A consistent order on keys with many equal keys: stability.
  keys: (c, x, y) => c.keys[x] - c.keys[y],
  // Keys first, the hash between equal keys: cycles only among ties, as the router's dock order has.
  ties: (c, x, y) => c.keys[x] - c.keys[y] || hashOrder(c.seed, x, y),
};

function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const sizes = [];
for (let n = 0; n <= 70; n += 1) sizes.push(n);
sizes.push(100, 127, 128, 129, 200, 257, 500, 1000, 3000);
const cases = [];
let seed = 20261119;
for (const kind of Object.keys(KINDS)) {
  for (const n of sizes) {
    for (const shape of ["shuffled", "runs"]) {
      seed += 1;
      const random = rng(seed);
      const tieCount = Math.max(1, Math.floor(n / 6));
      const keys = [...Array(n)].map((_, i) =>
        shape === "runs" ? Math.floor(i / 3) + (random() < 0.1 ? Math.floor(random() * 8) - 4 : 0) : Math.floor(random() * tieCount),
      );
      const order = [...Array(n).keys()];
      if (shape === "shuffled")
        for (let i = n - 1; i > 0; i -= 1) {
          const j = Math.floor(random() * (i + 1));
          [order[i], order[j]] = [order[j], order[i]];
        }
      const c = { kind, seed, keys };
      const sorted = [...order].sort((x, y) => KINDS[kind](c, x, y));
      cases.push({ kind, shape, seed, keys, input: order, sorted });
    }
  }
}
writeFileSync(
  join(root, "conformance/layout/js-sort.json"),
  JSON.stringify({ generator: "tools/conformance/layout/js-sort.mjs", engine: `node ${process.version}`, cases }) + "\n",
);
console.log(`js-sort: ${cases.length} cases`);
