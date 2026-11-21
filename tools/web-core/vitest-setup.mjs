// Vitest global setup: the core's TypeScript declarations are written, and the compiled core is copied to public/core
// when a build exists (no JDK needed). GTNH_CORE_BUILD=1 builds it first and GTNH_CORE_REQUIRED=1 makes a missing core
// an error (CI sets both through tools/ci/website.sh); otherwise the tests that need the core skip themselves.
import { run as copyCore } from "../copy-core.mjs";
import { run as generateTypes } from "./gen-types.mjs";

export default function setup() {
  generateTypes();
  copyCore({
    build: process.env.GTNH_CORE_BUILD === "1",
    required: process.env.GTNH_CORE_REQUIRED === "1",
  });
}
