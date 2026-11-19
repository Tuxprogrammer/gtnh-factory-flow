#!/usr/bin/env node
// Puts the compiled core (jvm/web, built by TeaVM: WebAssembly GC and JavaScript) where Next serves it and the loader
// (src/lib/core/loader.ts) reads it: public/core/{wasm-gc,js}/ plus public/core/manifest.json. Gitignored: the compiled
// core is never committed. Runs as predev, prebuild, predev:local and in the vitest global setup, like
// tools/copy-highs-wasm.mjs, and never needs a JDK unless asked to build:
//
//   source, first that exists
//     GTNH_CORE_DIR                      a finished build (a CI artifact, a build host's output): wasm-gc/ and js/ inside it
//     jvm/web/build/generated/teavm      what `./gradlew :web:buildWasmGC :web:generateJavaScript` writes
//   --build or GTNH_CORE_BUILD=1         run Gradle first (needs JDK 25; the build downloads TeaVM from Maven Central)
//   --required or GTNH_CORE_REQUIRED=1   no core is an error (CI, a deploy that serves features needing the core)
//
// With none of these and no build on disk it prints one line and exits 0: the site runs without the core and the loader
// says it is unavailable (a checkout with only Node). The manifest is written last and names a content hash the loader
// puts on every URL, so a browser never mixes files from two builds.
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const builtDir = path.join(root, "jvm", "web", "build", "generated", "teavm");
const outDir = path.join(root, "public", "core");

/** The files that make the core, as paths relative to a build directory (and to public/core). */
export const CORE_FILES = ["wasm-gc/core.wasm", "wasm-gc/core.wasm-runtime.js", "js/core.js"];

const complete = (dir) =>
  CORE_FILES.every((f) => existsSync(path.join(dir, f)) && statSync(path.join(dir, f)).size > 0);

/** Gradle's own run, the way tools/ci/web-core.sh does it: JAVA_HOME_<n>_X64 variables name the JDKs it may use. */
function gradleBuild() {
  const envs = Object.keys(process.env)
    .filter((k) => /^JAVA_HOME_\d+_X64$/.test(k))
    .join(",");
  const args = ["--no-daemon", "--console=plain"];
  if (envs) args.push(`-Porg.gradle.java.installations.fromEnv=${envs}`);
  args.push(":web:buildWasmGC", ":web:generateJavaScript");
  console.log(`[copy-core] gradle ${args.join(" ")}`);
  const r = spawnSync(path.join(root, "jvm", "gradlew"), args, {
    cwd: path.join(root, "jvm"),
    stdio: "inherit",
  });
  if (r.error)
    throw new Error(`could not start Gradle (is a JDK 25 installed?): ${r.error.message}`);
  if (r.status !== 0) throw new Error(`the core build failed (Gradle exit ${r.status})`);
}

/**
 * Makes public/core current. Returns { status: "ready", source } or { status: "unavailable", reason }.
 * Throws when a build or a required core fails.
 */
export function run({ build = false, required = false } = {}) {
  const explicit = process.env.GTNH_CORE_DIR ? path.resolve(process.env.GTNH_CORE_DIR) : null;
  if (build) gradleBuild();
  const source = explicit ?? builtDir;
  if (!complete(source)) {
    const reason = explicit
      ? `GTNH_CORE_DIR ${explicit} is missing ${CORE_FILES.filter((f) => !existsSync(path.join(explicit, f))).join(", ")}`
      : "no core build found (run with GTNH_CORE_BUILD=1 and JDK 25, or point GTNH_CORE_DIR at a finished build)";
    if (required || explicit) throw new Error(reason);
    console.log(`[copy-core] ${reason}; the site runs without the core`);
    return { status: "unavailable", reason };
  }
  const hash = createHash("sha256");
  const files = [];
  const contents = new Map();
  for (const f of CORE_FILES) {
    let bytes = readFileSync(path.join(source, f));
    // The source map is not shipped (a debugging build is `-Pplain`), so the comment that names it goes too.
    if (f.endsWith(".js"))
      bytes = Buffer.from(
        bytes.toString("utf8").replace(/\n?\/\/# sourceMappingURL=\S*\s*$/, "\n"),
      );
    contents.set(f, bytes);
    hash.update(f).update(bytes);
    files.push({ path: f, bytes: bytes.length });
  }
  const version = hash.digest("hex").slice(0, 16);
  const manifestPath = path.join(outDir, "manifest.json");
  let current = null;
  try {
    current = JSON.parse(readFileSync(manifestPath, "utf8"));
  } catch {
    // none yet
  }
  if (current?.version === version && complete(outDir))
    return { status: "ready", source, version, changed: false };
  rmSync(manifestPath, { force: true });
  for (const f of CORE_FILES) {
    mkdirSync(path.dirname(path.join(outDir, f)), { recursive: true });
    writeFileSync(path.join(outDir, f), contents.get(f));
  }
  writeFileSync(manifestPath, `${JSON.stringify({ format: 1, version, files }, null, 2)}\n`);
  console.log(
    `[copy-core] ${source} -> ${path.relative(root, outDir)} (${version}, ${files.reduce((n, f) => n + f.bytes, 0)} bytes)`,
  );
  return { status: "ready", source, version, changed: true };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const flag = (name, env) => process.argv.includes(`--${name}`) || process.env[env] === "1";
  try {
    run({
      build: flag("build", "GTNH_CORE_BUILD"),
      required: flag("required", "GTNH_CORE_REQUIRED"),
    });
  } catch (e) {
    console.error(`[copy-core] ${e.message}`);
    process.exit(1);
  }
}
