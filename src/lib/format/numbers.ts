/**
 * Number text the board shows: compact SI ladder, thousands separators, power values. Display only, no model
 * dependency; the plan model must never import this file.
 */

export function formatRate(value: number, digits = 2): string {
  if (!Number.isFinite(value)) {
    return "unbounded";
  }

  if (Math.abs(value) >= 100) {
    return formatNumberWithThousands(value.toFixed(0));
  }

  if (Math.abs(value) >= 10) {
    return formatNumberWithThousands(value.toFixed(1));
  }

  return formatNumberWithThousands(value.toFixed(digits));
}

/**
 * The SI ladder GregTech itself uses for EU. Past exa nothing in the game
 * reaches, and a number that long has stopped being readable anyway.
 */
const COMPACT_SUFFIXES = ["", "k", "M", "G", "T", "P", "E"] as const;

/**
 * Late-game numbers at a width a node cell can hold: 2,147,483,648 becomes
 * "2.15G" and 1,440,000 L/s becomes "1.44M".
 *
 * - exact zero prints "0", never "0.00";
 * - a small but REAL rate never prints as zero (a chanced output at 0.004/s
 *   runs), so values under 1 keep two significant digits.
 *
 * Otherwise two decimals (one from 100 up) before the suffix, trailing zeros
 * dropped.
 */
export function formatCompact(value: number): string {
  if (!Number.isFinite(value)) {
    return "unbounded";
  }
  if (value === 0) {
    return "0";
  }

  const sign = value < 0 ? "-" : "";
  let abs = Math.abs(value);

  if (abs < 1) {
    // Two significant digits, however far down the value sits.
    const leadingZeros = Math.max(0, Math.ceil(-Math.log10(abs)) - 1);
    const digits = Math.min(12, leadingZeros + 2);
    return `${sign}${trimTrailingDecimalZeros(abs.toFixed(digits))}`;
  }

  let tier = 0;
  while (abs >= 1000 && tier < COMPACT_SUFFIXES.length - 1) {
    abs /= 1000;
    tier += 1;
  }
  let decimals = abs >= 100 ? 1 : 2;
  // 999.96 rounds to "1000.0" — carry it to the next suffix instead.
  if (Number(abs.toFixed(decimals)) >= 1000 && tier < COMPACT_SUFFIXES.length - 1) {
    abs /= 1000;
    tier += 1;
    decimals = 2;
  }
  const text = trimTrailingDecimalZeros(abs.toFixed(decimals));
  return `${sign}${text}${COMPACT_SUFFIXES[tier]}`;
}

/**
 * `formatCompact` for a number in MOTION: fixed decimals per magnitude,
 * trailing zeros kept, whole numbers below a thousand. `formatCompact` trims
 * zeros, so an easing value would flicker between widths ("20.1", "20",
 * "19.93"); the landing frame goes back through `formatCompact`.
 */
export function formatCompactStable(value: number): string {
  if (!Number.isFinite(value)) {
    return "unbounded";
  }
  const sign = value < 0 ? "-" : "";
  let abs = Math.abs(value);
  if (abs < 1000) {
    return `${sign}${Math.round(abs)}`;
  }
  let tier = 0;
  while (abs >= 1000 && tier < COMPACT_SUFFIXES.length - 1) {
    abs /= 1000;
    tier += 1;
  }
  return `${sign}${abs.toFixed(abs >= 100 ? 1 : 2)}${COMPACT_SUFFIXES[tier]}`;
}

/** Power in the selected display unit: keep a real trickle distinct from zero. */
export function formatPowerValue(value: number, stable = false): string {
  if (value !== 0 && Math.abs(value) < 0.01) {
    return value < 0 ? ">-0.01" : "<0.01";
  }
  // Fractional amps must not round to zero during a number animation either.
  return stable && Math.abs(value) >= 1 ? formatCompactStable(value) : formatCompact(value);
}

export function formatNumberWithThousands(value: number | string): string {
  // American separators: comma thousands, dot decimal ("1,234.56").
  const text = String(value);
  const sign = text.startsWith("-") ? "-" : "";
  const unsigned = sign ? text.slice(1) : text;
  const [integer, fraction] = unsigned.split(".");
  const grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ",");

  return `${sign}${grouped}${fraction !== undefined ? `.${fraction}` : ""}`;
}

export function trimTrailingDecimalZeros(value: string): string {
  return value.replace(/(\.\d*[1-9])0+$/, "$1").replace(/\.0+$/, "");
}

/** Keep fractional answers at large counts too; never round up to a build. */
export function formatMachineListCount(count: number): string {
  return count > 0 && count < 0.000001
    ? "<0.000001"
    : count.toLocaleString("en-US", { maximumFractionDigits: 6 });
}

export function formatRatioShare(share: number): string {
  if (share > 0 && share < 0.0001) return "<0.01%";
  if (share < 1 && share > 0.9999) return ">99.99%";
  return `${Number((share * 100).toFixed(2))}%`;
}
