// The sample of a golden file the JavaScript target runs when the run is not `--full` (the WebAssembly target always runs
// every case). A sample must still catch an error in any group of cases, so it is chosen by group, not over the whole file:
//   - a group of at most 4 * n cases is kept whole (the special groups: a rare machine, a housing, a crop family);
//   - a bigger group keeps its first case and every n-th after it.
// Deterministic: the same file gives the same cases on every run, so a failure names a case that fails again.
//   sampleOf(items, n, key)   the cases to run, in file order
// `key(item)` is the case's group name. n <= 1 keeps everything.
export function sampleOf(items, n, key) {
  if (!(n > 1)) return items;
  const sizes = new Map();
  for (const item of items) sizes.set(key(item), (sizes.get(key(item)) ?? 0) + 1);
  const seen = new Map();
  return items.filter((item) => {
    const k = key(item);
    const i = seen.get(k) ?? 0;
    seen.set(k, i + 1);
    return sizes.get(k) <= 4 * n || i % n === 0;
  });
}

/** Every n-th case run on JavaScript; what the pipeline's full run (`--full`) replaces with all of them. */
export const SAMPLE_EVERY = 8;
