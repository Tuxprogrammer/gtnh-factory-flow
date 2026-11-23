/**
 * A library post's download hands the plan out as `{ name, plan }`
 * (`/api/community/plans/<id>/download`), and a saved copy of that answer is
 * a plan file a player will try to open. The plan inside is read as any plan
 * file; the post's name names the new design. Anything else (a bare plan,
 * text that is not JSON) comes back as it was, for the plan parser to accept
 * or refuse.
 */
export function unwrapNamedPlan(text: string): { text: string; name?: string } {
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    return { text };
  }
  if (!raw || typeof raw !== "object" || Array.isArray(raw) || "nodes" in raw) {
    return { text };
  }
  const { name, plan } = raw as { name?: unknown; plan?: unknown };
  if (!plan || typeof plan !== "object" || Array.isArray(plan)) {
    return { text };
  }
  return {
    text: JSON.stringify(plan),
    ...(typeof name === "string" && name.trim() ? { name } : {}),
  };
}
