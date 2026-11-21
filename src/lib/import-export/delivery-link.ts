import { parseFactoryProjectJson } from "@/lib/import-export";
import type { FactoryProject } from "@/lib/model/types";

/** The address fragment key of a delivered plan's short link: `<site>/#d=<id>`. */
export const DELIVERY_HASH_KEY = "d";

/** A delivery id: `d` and 15 characters of the id alphabet. A proposal id (`p...`) is not opened as a plan. */
export const DELIVERY_ID_PATTERN = /^d[a-hjkmnp-z2-9]{15}$/;

export const DELIVERY_NOT_FOUND = "That plan link is not valid, or it has expired. Ask for a new one.";
export const DELIVERY_UNREACHABLE = "That plan could not be loaded. Try the link again in a moment.";

/** The short link that opens a delivered plan by id. */
export function deliveryLink(id: string, origin: string): string {
  return `${origin}/#${DELIVERY_HASH_KEY}=${id}`;
}

/** What follows `#d=` in an address fragment (unchecked), or undefined when the fragment is not a delivery link. */
export function readDeliveryKeyFromHash(hash: string): string | undefined {
  const fragment = hash.startsWith("#") ? hash.slice(1) : hash;
  const key = `${DELIVERY_HASH_KEY}=`;
  return fragment.startsWith(key) ? fragment.slice(key.length) : undefined;
}

/**
 * The plan a delivery id names, read from the delivery route. The id is checked before any request; an unknown,
 * expired or non-delivery entry is refused in the same words. Throws an Error with copy a player can read.
 */
export async function fetchDeliveredPlan(
  id: string,
  fetchImpl: typeof fetch = fetch,
): Promise<FactoryProject> {
  if (!DELIVERY_ID_PATTERN.test(id)) {
    throw new Error(DELIVERY_NOT_FOUND);
  }
  let response: Response;
  try {
    response = await fetchImpl(`/api/deliveries/${id}`, { cache: "no-store" });
  } catch {
    throw new Error(DELIVERY_UNREACHABLE);
  }
  if (response.status === 404) {
    throw new Error(DELIVERY_NOT_FOUND);
  }
  if (!response.ok) {
    throw new Error(DELIVERY_UNREACHABLE);
  }
  try {
    const body = (await response.json()) as { kind?: unknown; plan?: unknown };
    if (body.kind !== "delivery") {
      throw new Error("not a delivery");
    }
    return parseFactoryProjectJson(JSON.stringify(body.plan));
  } catch {
    throw new Error(DELIVERY_NOT_FOUND);
  }
}
