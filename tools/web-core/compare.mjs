// Shared by the vector run and the random run: bit patterns of doubles, and one place that collects differences.
const view = new DataView(new ArrayBuffer(8));

export function bitsOf(x) {
  view.setFloat64(0, x);
  return view.getBigUint64(0).toString(16).padStart(16, "0");
}

export function fromBits(hex) {
  view.setBigUint64(0, BigInt("0x" + hex));
  return view.getFloat64(0);
}

/** A double equals the double a bit pattern spells: bit for bit (so -0 is not 0), and any NaN is any NaN. */
export function sameDouble(actual, expectedHex) {
  const e = fromBits(expectedHex);
  if (Number.isNaN(e)) return typeof actual === "number" && Number.isNaN(actual);
  return typeof actual === "number" && bitsOf(actual) === expectedHex;
}

/** What a call throws or returns, so "must throw" cases are one comparison. */
export function attempt(fn) {
  try {
    return { value: fn() };
  } catch (e) {
    return { threw: e };
  }
}

/**
 * Counts checks per group and keeps the first few differences of each.
 *
 * A difference that is a named finding, sent to the stream that owns the code and listed in known-differences.json, is
 * counted as KNOWN instead of failing. A listed difference that no longer happens fails the run, so the entry is
 * removed when the finding is fixed. Nothing is ever listed without a finding behind it.
 */
export class Tally {
  constructor(target = null, known = [], keep = 8) {
    this.target = target;
    this.keep = keep;
    this.groups = new Map();
    this.known = known.filter((k) => k.target === target);
    this.knownHits = new Map(this.known.map((k) => [k.id, 0]));
  }
  /** Compare a double to a bit pattern; a negative zero read as zero is the one difference a rule can excuse. */
  checkDouble(name, actual, expectedHex, detail) {
    if (sameDouble(actual, expectedHex)) return this.check(name, true);
    if (actual === 0 && Object.is(actual, 0) && expectedHex === "8000000000000000") {
      const rule = this.known.find((k) => k.match === "negative-zero-read-as-zero" && k.groups.includes(name));
      if (rule) {
        this.knownHits.set(rule.id, this.knownHits.get(rule.id) + 1);
        return true;
      }
    }
    return this.check(name, false, detail);
  }
  group(name) {
    if (!this.groups.has(name)) this.groups.set(name, { checked: 0, differ: 0, first: [] });
    return this.groups.get(name);
  }
  /** Record one check; `detail` is a function, called only when the check failed. */
  check(name, ok, detail) {
    const g = this.group(name);
    g.checked++;
    if (!ok) {
      g.differ++;
      if (g.first.length < this.keep) g.first.push(detail());
    }
    return ok;
  }
  get differences() {
    let n = 0;
    for (const g of this.groups.values()) n += g.differ;
    return n;
  }
  get checks() {
    let n = 0;
    for (const g of this.groups.values()) n += g.checked;
    return n;
  }
}
