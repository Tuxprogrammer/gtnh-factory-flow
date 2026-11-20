#!/usr/bin/env node
// Puts the compiled core (jvm/web, built by TeaVM: WebAssembly GC and JavaScript) where Next serves it and the loader
// (src/lib/core/loader.ts) reads it: public/core/{wasm-gc,js}/ plus public/core/manifest.json. Gitignored: the compiled
// core is never committed. Runs as predev, prebuild, predev:local and in the vitest global setup, like
// tools/copy-highs-wasm.mjs, and never needs a JDK unless asked to build:
//
//   source, first that exists
//     GTNH_CORE_DIR                      a finished build (a CI artifact, a build host's output): wasm-gc/ and js/ inside it
//     jvm/web/build/generated/teavm      what `./gradlew :web:webCore` writes
//   --build or GTNH_CORE_BUILD=1         run Gradle first (needs JDK 25; the build downloads TeaVM from Maven Central)
//   --required or GTNH_CORE_REQUIRED=1   no core is an error (CI, a deploy that serves features needing the core)
//
// With none of these and no build on disk it prints one line and exits 0: the site runs without the core and the loader
// says it is unavailable (a checkout with only Node).
//
// Every file is published under a name that carries a hash of its content (`js/core.3fa91c07.js`), so a file never
// changes under its name: the server sends them as immutable (next.config.ts) and a returning visitor downloads nothing.
// manifest.json is the one file that changes; it is written last, names every file, and the loader reads it with
// `no-cache` so a deploy is seen at once and a browser never mixes files from two builds.
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { buildFiles, moduleFiles, readModules, runtimeFile } from "./web-core/modules.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const builtDir = path.join(root, "jvm", "web", "build", "generated", "teavm");
const outDir = path.join(root, "public", "core");

const modules = readModules();

/** The files that make the core, as paths relative to a build directory: every module's two targets and the shared runtime. */
export const CORE_FILES = buildFiles(modules);

/** The name a file is published under: its content's hash before the extension (`js/core.js` -> `js/core.3fa91c07.js`). */
const hashedName = (file, bytes) => {
  const at = file.indexOf(".", file.lastIndexOf("/") + 1);
  return `${file.slice(0, at)}.${createHash("sha256").update(bytes).digest("hex").slice(0, 8)}${file.slice(at)}`;
};

const complete = (dir) =>
  CORE_FILES.every((f) => existsSync(path.join(dir, f)) && statSync(path.join(dir, f)).size > 0);

/** Gradle's own run, the way tools/ci/web-core.sh does it: JAVA_HOME_<n>_X64 variables name the JDKs it may use. */
function gradleBuild() {
  const envs = Object.keys(process.env)
    .filter((k) => /^JAVA_HOME_\d+_X64$/.test(k))
    .join(",");
  const args = ["--no-daemon", "--console=plain"];
  // Extra Gradle options as a JSON array (tools/ci/website.sh passes the CI heap and network retry settings).
  if (process.env.GTNH_GRADLE_ARGS) args.push(...JSON.parse(process.env.GTNH_GRADLE_ARGS));
  // The two TeaVM builds run side by side in Gradle's JVM, whose default heap (512 MiB) runs out with the solver in: a
  // caller that gave no heap of its own gets 3 GiB (what the CI jobs use).
  if (!args.some((a) => a.startsWith("-Dorg.gradle.jvmargs="))) args.push("-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8");
  if (envs) args.push(`-Porg.gradle.java.installations.fromEnv=${envs}`);
  args.push(":web:webCore");
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
  if (modules.length === 0) throw new Error("jvm/web/modules.txt is missing: this checkout has no core to copy");
  if (!complete(source)) {
    const reason = explicit
      ? `GTNH_CORE_DIR ${explicit} is missing ${CORE_FILES.filter((f) => !existsSync(path.join(explicit, f))).join(", ")}`
      : "no core build found (run with GTNH_CORE_BUILD=1 and JDK 25, or point GTNH_CORE_DIR at a finished build)";
    if (required || explicit) throw new Error(reason);
    console.log(`[copy-core] ${reason}; the site runs without the core`);
    return { status: "unavailable", reason };
  }
  // The source map is not shipped (a debugging build is `-Pplain`), so the comment that names it goes too.
  const read = (f) => {
    const bytes = readFileSync(path.join(source, f));
    return f.endsWith(".js")
      ? Buffer.from(bytes.toString("utf8").replace(/\n?\/\/# sourceMappingURL=\S*\s*$/, "\n"))
      : bytes;
  };
  const published = new Map(); // build path -> { path, bytes, content }
  for (const f of CORE_FILES) {
    const content = read(f);
    published.set(f, { path: hashedName(f, content), bytes: content.length, content });
  }
  const manifest = {
    format: 2,
    // One id for the whole build, from the file names (which carry the content hashes): what the loader reports.
    version: createHash("sha256").update([...published.values()].map((p) => p.path).join("\n")).digest("hex").slice(0, 16),
    runtime: published.get(runtimeFile(modules)).path,
    modules: Object.fromEntries(
      modules.map((m) => {
        const f = moduleFiles(modules, m.name);
        return [m.name, { wasm: published.get(f.wasm).path, js: published.get(f.js).path }];
      }),
    ),
    files: [...published.values()].map((p) => ({ path: p.path, bytes: p.bytes })),
  };
  const version = manifest.version;
  const manifestPath = path.join(outDir, "manifest.json");
  let current = null;
  try {
    current = JSON.parse(readFileSync(manifestPath, "utf8"));
  } catch {
    // none yet
  }
  if (current?.version === version && current.files.every((f) => existsSync(path.join(outDir, f.path))))
    return { status: "ready", source, version, changed: false };
  rmSync(manifestPath, { force: true });
  // A hashed name never changes content, so files of an older build only pile up: clear them before writing.
  for (const dir of ["wasm-gc", "js"]) rmSync(path.join(outDir, dir), { recursive: true, force: true });
  for (const p of published.values()) {
    mkdirSync(path.dirname(path.join(outDir, p.path)), { recursive: true });
    writeFileSync(path.join(outDir, p.path), p.content);
  }
  writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(
    `[copy-core] ${source} -> ${path.relative(root, outDir)} (${version}, ${manifest.files.reduce((n, f) => n + f.bytes, 0)} bytes)`,
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
