import { runArrangeJob, type ArrangeJob } from "./arrange-job";
import { loadLayoutCore } from "@/components/flow/layout-core";

/** The compiled core, when the layout area is on; a job waits for its load (the TypeScript runs if it fails). */
const ready = loadLayoutCore();

/**
 * The arranger, off the main thread. One job in; progress messages as it
 * goes; one result out. The scheduling lives in `arrange-solve.ts`; this
 * file stays a dumb calculator on purpose.
 */
self.onmessage = (event: MessageEvent<ArrangeJob>) => {
  void ready.then(() => run(event.data));
};

function run(job: ArrangeJob) {
  try {
    const done = runArrangeJob(job, (progress) => {
      self.postMessage({ progress });
    });
    self.postMessage({ done });
  } catch (error) {
    self.postMessage({ error: error instanceof Error ? error.message : String(error) });
  }
}
