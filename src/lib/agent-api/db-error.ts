/**
 * One line for a failed database call. The message alone is not enough: an answer with no JSON body (a HEAD
 * count, a proxy error page, a table the API layer has not loaded yet) has no message at all, and the code and
 * the HTTP status are then the only clue to the cause.
 */
export function describeDbError(
  error: { message?: string | null; code?: string | null; details?: string | null; hint?: string | null },
  response?: { status?: number; statusText?: string },
): string {
  const parts: string[] = [];
  const text = (value: string | null | undefined) => (typeof value === "string" ? value.trim() : "");
  if (text(error.message)) {
    parts.push(text(error.message));
  }
  if (text(error.code)) {
    parts.push(`code ${text(error.code)}`);
  }
  if (text(error.details)) {
    parts.push(text(error.details));
  }
  if (text(error.hint)) {
    parts.push(text(error.hint));
  }
  if (response?.status) {
    parts.push(`HTTP ${response.status}${text(response.statusText) ? ` ${text(response.statusText)}` : ""}`);
  }
  return parts.length ? parts.join("; ") : "no error text from the database";
}
