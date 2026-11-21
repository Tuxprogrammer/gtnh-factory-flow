import { useSyncExternalStore } from "react";

/**
 * Whether plans are being solved by the website's stand-in engine instead of the compiled core: the core did not load
 * (or was still loading past the solve Worker's bounded wait). Set from each solve's reply (`solve-books.ts`); the
 * page shows `SolveDegradedNotice` while it is true and clears it once the core answers.
 */
let degraded = false;
const listeners = new Set<() => void>();

export function setSolveDegraded(next: boolean): void {
  if (next === degraded) return;
  degraded = next;
  for (const listener of listeners) listener();
}

export function isSolveDegraded(): boolean {
  return degraded;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useSolveDegraded(): boolean {
  return useSyncExternalStore(subscribe, isSolveDegraded, () => false);
}
