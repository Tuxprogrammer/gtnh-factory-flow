// Vitest global setup: write the generated token files before any test file imports them.
import { run } from "./generate.mjs";

export default function setup() {
  run();
}
