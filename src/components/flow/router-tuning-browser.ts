import { setRouterTuningStorage } from "./router-tuning";

/**
 * Persists the router dials in this browser. Import it for its effect from each host that
 * reads the dials on the main thread; the dial module itself holds no browser storage.
 */
const STORAGE_KEY = "gtnh-factory-flow.router-tuning.v1";

setRouterTuningStorage({
  read: () => (typeof localStorage === "undefined" ? null : localStorage.getItem(STORAGE_KEY)),
  write: (raw) => {
    if (typeof localStorage !== "undefined") localStorage.setItem(STORAGE_KEY, raw);
  },
});
