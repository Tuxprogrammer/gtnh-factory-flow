/**
 * One value per server process, whichever bundle asks for it.
 *
 * A production Next.js server bundles `src/instrumentation.ts` apart from the
 * API routes, and each bundle has its own copy of every module it imports: a
 * cache kept in a module's own variables is a second, cold cache for the
 * routes, and the start-up prewarm fills only its own. Caches that the
 * prewarm fills are kept on `globalThis` instead, under a key per cache.
 *
 * Only in production: development reloads modules as they are edited and
 * tests load fresh ones per file, and both expect a fresh cache with them.
 */
export function processShared<T>(key: string, make: () => T): T {
  if (process.env.NODE_ENV !== "production") {
    return make();
  }
  const store = globalThis as unknown as Record<symbol, unknown>;
  const slot = Symbol.for(`gtnh-factory-flow:${key}`);
  if (!(slot in store)) {
    store[slot] = make();
  }
  return store[slot] as T;
}
