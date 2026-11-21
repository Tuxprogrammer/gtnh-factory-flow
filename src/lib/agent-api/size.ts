/**
 * Responses aim at 12 KB of JSON: the assistant's client caps a tool result at about 25k tokens and reads every byte
 * it gets. A list answer that would go over drops items from its end and says so with `truncated: true`.
 */
export const RESPONSE_AIM_BYTES = 12 * 1024;

export function jsonBytes(value: unknown): number {
  return Buffer.byteLength(JSON.stringify(value), "utf8");
}

/**
 * The longest prefix of `items` whose wrapped answer fits `maxBytes` (at least one item, so a single large item is
 * still answered). `wrap` builds the whole response for a prefix and whether it was cut.
 */
export function fitList<T, R>(
  items: T[],
  wrap: (items: T[], truncated: boolean) => R,
  maxBytes: number = RESPONSE_AIM_BYTES,
): R {
  const whole = wrap(items, false);
  if (items.length <= 1 || jsonBytes(whole) <= maxBytes) {
    return whole;
  }
  let low = 1;
  let high = items.length - 1;
  while (low < high) {
    const mid = Math.ceil((low + high) / 2);
    if (jsonBytes(wrap(items.slice(0, mid), true)) <= maxBytes) {
      low = mid;
    } else {
      high = mid - 1;
    }
  }
  return wrap(items.slice(0, low), true);
}
