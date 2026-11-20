/**
 * The addresses this instance names for itself, all from build-time environment. Nothing in the source names a
 * default, so an install that sets none names no site: its pages carry no canonical address, its sitemap is empty,
 * an exported picture signs with the product name alone, and the Help menu has no chat link.
 * Each `process.env.NEXT_PUBLIC_*` stays a literal read: the build only inlines it that way.
 */

/** `raw` as a normalised http(s) address, or undefined when it is empty or is not one. */
function webAddress(raw: string | undefined): URL | undefined {
  const text = raw?.trim();
  if (!text) return undefined;
  try {
    const url = new URL(text);
    return url.protocol === "http:" || url.protocol === "https:" ? url : undefined;
  } catch {
    return undefined;
  }
}

/** The origin of `raw` (`https://host`, no trailing slash) when it is an http(s) address; a path or query is dropped. */
export function parseSiteUrl(raw: string | undefined): string | undefined {
  return webAddress(raw)?.origin;
}

/** This instance's origin (`NEXT_PUBLIC_SITE_URL`), or undefined when none is configured. */
export function siteUrl(): string | undefined {
  return parseSiteUrl(process.env.NEXT_PUBLIC_SITE_URL);
}

/** This instance's host name, for a signature on a picture, or undefined when no site is configured. */
export function siteHost(): string | undefined {
  const origin = siteUrl();
  return origin ? new URL(origin).host : undefined;
}

/**
 * A help or chat page for players (`NEXT_PUBLIC_SUPPORT_URL`, whole address kept: it is usually a thread), or
 * undefined. Without one the Help menu and the Feedback box show no chat link.
 */
export function supportUrl(): string | undefined {
  return webAddress(process.env.NEXT_PUBLIC_SUPPORT_URL)?.href;
}
