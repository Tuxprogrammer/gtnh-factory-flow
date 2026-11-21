"use client";

import { TriangleAlert } from "lucide-react";
import { useSolveDegraded } from "@/store/solve-health";

/**
 * Shown while plans are solved by the website's stand-in engine because the compiled core did not load
 * (`src/store/solve-health.ts`): the numbers may be slow to come or missing on a big plan, and a reload tries again.
 */
export function SolveDegradedNotice() {
  const degraded = useSolveDegraded();
  if (!degraded) {
    return null;
  }
  return (
    <div
      role="status"
      data-solve-degraded=""
      className="flex min-w-0 items-center gap-2 border-b border-amber-700/60 bg-amber-950/60 px-3 py-1.5 text-xs text-amber-100"
    >
      <TriangleAlert aria-hidden className="h-3.5 w-3.5 shrink-0" />
      <p className="min-w-0 flex-1">
        The solver did not load, so plans are solved by a slower backup that can fail on big plans. Reload the page to
        try again.
      </p>
    </div>
  );
}
