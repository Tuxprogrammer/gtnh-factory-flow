/**
 * The boundary scan behind `core-boundary.test.ts`.
 *
 * `src/lib/model`, `solver`, `machines` and `power` are the code the shared Java core
 * replaces, so they, and everything they import at run time, must not touch React, the DOM,
 * browser storage, Next or Zustand, and must not import `src/lib/format` (display code sits
 * on top of the plan model, never under it). The scan reads the real import graph with the
 * TypeScript compiler API: type-only imports do not count, and the walk is transitive.
 */
import fs from "node:fs";
import path from "node:path";
import ts from "typescript";

/** Directories whose files are the roots of the scan, relative to the source root. */
export const CORE_DIRS = ["lib/model", "lib/solver", "lib/machines", "lib/power"];

export interface Violation {
  /** Repo-relative path of the file that has the problem. */
  file: string;
  kind: "import" | "global" | "layer" | "unresolved";
  /** The import specifier or global name. */
  what: string;
  /** The import chain from a core file to this one, for a file reached through another. */
  via?: string;
}

/** A file allowed to break the boundary, with why, and what retires the entry. */
export interface AllowedFile {
  file: string;
  reason: string;
  /**
   * The file is outside the boundary for good reason, with everything it imports: the walk
   * stops there. Without it, only the file's own violations are excused.
   */
  leaf?: boolean;
}

/** Module specifiers a core file must never reach, as package names (and their subpaths). */
const FORBIDDEN_PACKAGES = ["react", "react-dom", "next", "zustand", "@xyflow", "reactflow"];

/** Browser globals. A core file never names one, even behind a `typeof` guard. */
const BROWSER_GLOBALS = new Set([
  "window",
  "document",
  "localStorage",
  "sessionStorage",
  "indexedDB",
  "navigator",
  "requestAnimationFrame",
  "cancelAnimationFrame",
  "fetch",
  "Worker",
  "HTMLElement",
  "SVGElement",
  "MutationObserver",
  "ResizeObserver",
  "IntersectionObserver",
  "AudioContext",
]);

const GLOBAL_ROOTS = new Set(["globalThis", "self", "window"]);

function isForbiddenPackage(spec: string): boolean {
  return FORBIDDEN_PACKAGES.some((p) => spec === p || spec.startsWith(`${p}/`));
}

function isTestFile(file: string): boolean {
  return /\.test\.tsx?$/.test(file) || /\.d\.ts$/.test(file);
}

export function walkSource(dir: string, out: string[] = []): string[] {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walkSource(full, out);
    else if (/\.tsx?$/.test(entry.name) && !isTestFile(entry.name)) out.push(full);
  }
  return out;
}

/** Resolve a specifier to a source file under `srcRoot`, or null for a package or a non-source file. */
function resolveSpecifier(spec: string, fromFile: string, srcRoot: string): string | null | "unresolved" {
  let base: string;
  if (spec.startsWith("@/")) base = path.join(srcRoot, spec.slice(2));
  else if (spec.startsWith(".")) base = path.resolve(path.dirname(fromFile), spec);
  else return null;
  for (const candidate of [`${base}.ts`, `${base}.tsx`, path.join(base, "index.ts"), path.join(base, "index.tsx"), base]) {
    if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) {
      return /\.tsx?$/.test(candidate) ? candidate : null;
    }
  }
  return "unresolved";
}

interface ScannedFile {
  imports: string[];
  globals: string[];
}

function scanFile(file: string): ScannedFile {
  const text = fs.readFileSync(file, "utf8");
  const kind = file.endsWith("x") ? ts.ScriptKind.TSX : ts.ScriptKind.TS;
  const sf = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, kind);
  const imports: string[] = [];
  const globals = new Set<string>();

  // Names the file declares itself: a local called `window` is not the browser's.
  const declared = new Set<string>();
  const collect = (node: ts.Node) => {
    if (
      (ts.isParameter(node) || ts.isVariableDeclaration(node) || ts.isBindingElement(node) || ts.isFunctionDeclaration(node)) &&
      node.name &&
      ts.isIdentifier(node.name)
    ) {
      declared.add(node.name.text);
    }
    if (ts.isImportSpecifier(node) || ts.isImportClause(node)) {
      const name = node.name;
      if (name) declared.add(name.text);
    }
    ts.forEachChild(node, collect);
  };
  collect(sf);

  const typeOnlyClause = (clause: ts.ImportClause | undefined): boolean => {
    if (!clause) return false;
    if (clause.isTypeOnly) return true;
    if (clause.name) return false;
    const named = clause.namedBindings;
    return !!named && ts.isNamedImports(named) && named.elements.length > 0 && named.elements.every((e) => e.isTypeOnly);
  };

  const visit = (node: ts.Node) => {
    if (ts.isImportDeclaration(node) && ts.isStringLiteral(node.moduleSpecifier)) {
      if (!typeOnlyClause(node.importClause)) imports.push(node.moduleSpecifier.text);
    } else if (ts.isExportDeclaration(node) && node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)) {
      const clause = node.exportClause;
      const typeOnly =
        node.isTypeOnly ||
        (!!clause && ts.isNamedExports(clause) && clause.elements.length > 0 && clause.elements.every((e) => e.isTypeOnly));
      if (!typeOnly) imports.push(node.moduleSpecifier.text);
    } else if (ts.isCallExpression(node) && node.arguments.length >= 1 && ts.isStringLiteralLike(node.arguments[0])) {
      const callee = node.expression;
      if (callee.kind === ts.SyntaxKind.ImportKeyword || (ts.isIdentifier(callee) && callee.text === "require")) {
        imports.push(node.arguments[0].text);
      }
    } else if (ts.isImportEqualsDeclaration(node) && ts.isExternalModuleReference(node.moduleReference)) {
      const expr = node.moduleReference.expression;
      if (ts.isStringLiteral(expr)) imports.push(expr.text);
    }

    if (ts.isPropertyAccessExpression(node) && ts.isIdentifier(node.expression) && GLOBAL_ROOTS.has(node.expression.text)) {
      if (BROWSER_GLOBALS.has(node.name.text)) globals.add(node.name.text);
    }
    if (ts.isIdentifier(node) && BROWSER_GLOBALS.has(node.text) && !declared.has(node.text)) {
      const parent = node.parent;
      const isMemberName =
        (ts.isPropertyAccessExpression(parent) && parent.name === node) ||
        ((ts.isPropertyAssignment(parent) ||
          ts.isPropertySignature(parent) ||
          ts.isPropertyDeclaration(parent) ||
          ts.isMethodDeclaration(parent) ||
          ts.isMethodSignature(parent)) &&
          parent.name === node);
      // Type positions (`HTMLElement` in an annotation, `typeof window` in a type) cost nothing at run time.
      let inType = false;
      for (let p: ts.Node | undefined = parent; p; p = p.parent) {
        if (ts.isTypeNode(p) || ts.isInterfaceDeclaration(p) || ts.isTypeAliasDeclaration(p)) {
          inType = true;
          break;
        }
        if (ts.isStatement(p)) break;
      }
      if (!isMemberName && !inType) globals.add(node.text);
    }
    ts.forEachChild(node, visit);
  };
  visit(sf);
  return { imports, globals: [...globals] };
}

export interface ScanResult {
  violations: Violation[];
  /** Every file the walk visited, repo-relative, for the test to show the scan is not empty. */
  visited: string[];
}

/**
 * Walk the runtime import graph from every non-test file in the core directories.
 * `srcRoot` is the `src` directory; paths in the result are relative to its parent.
 */
export function scanBoundary(srcRoot: string, allow: AllowedFile[] = [], coreDirs: string[] = CORE_DIRS): ScanResult {
  const repoRoot = path.dirname(srcRoot);
  const rel = (f: string) => path.relative(repoRoot, f).split(path.sep).join("/");
  const formatDir = path.join(srcRoot, "lib", "format") + path.sep;
  const storeDir = path.join(srcRoot, "store") + path.sep;

  const leaves = new Set(allow.filter((a) => a.leaf).map((a) => path.join(path.dirname(srcRoot), a.file)));
  const roots: string[] = [];
  for (const dir of coreDirs) {
    const full = path.join(srcRoot, dir);
    if (fs.existsSync(full)) roots.push(...walkSource(full));
  }

  const violations: Violation[] = [];
  const seen = new Set<string>();
  const parents = new Map<string, string>();
  const chainOf = (file: string): string | undefined => {
    const chain: string[] = [];
    for (let p = parents.get(file); p; p = parents.get(p)) chain.unshift(rel(p));
    return chain.length > 0 ? chain.join(" > ") : undefined;
  };
  const queue: { file: string; from?: string }[] = roots.map((file) => ({ file }));
  while (queue.length > 0) {
    const { file, from } = queue.shift()!;
    if (seen.has(file)) continue;
    seen.add(file);
    if (from) parents.set(file, from);
    const via = chainOf(file);
    const where = rel(file);
    if (file.endsWith(".tsx")) violations.push({ file: where, kind: "import", what: "a React component file (.tsx)", via });
    if (file.startsWith(formatDir)) violations.push({ file: where, kind: "layer", what: "src/lib/format", via });
    if (file.startsWith(storeDir)) violations.push({ file: where, kind: "import", what: "src/store (Zustand)", via });
    if (leaves.has(file)) continue;
    const scanned = scanFile(file);
    for (const name of scanned.globals) violations.push({ file: where, kind: "global", what: name, via });
    for (const spec of scanned.imports) {
      if (isForbiddenPackage(spec)) {
        violations.push({ file: where, kind: "import", what: spec, via });
        continue;
      }
      const target = resolveSpecifier(spec, file, srcRoot);
      if (target === "unresolved") violations.push({ file: where, kind: "unresolved", what: spec, via });
      else if (target) queue.push({ file: target, from: file });
    }
  }
  return { violations, visited: [...seen].map(rel).sort() };
}

/**
 * Drop the violations an allowed file is permitted. Returns what is left and the entries that
 * no longer do anything: a leaf the walk never reached, or any other entry with nothing to excuse.
 */
export function applyAllowList(
  violations: Violation[],
  allow: AllowedFile[],
  visited: string[],
): { remaining: Violation[]; stale: AllowedFile[] } {
  const allowed = new Set(allow.map((a) => a.file));
  const used = new Set<string>();
  const remaining = violations.filter((v) => {
    if (!allowed.has(v.file)) return true;
    used.add(v.file);
    return false;
  });
  for (const a of allow) if (a.leaf && visited.includes(a.file)) used.add(a.file);
  return { remaining, stale: allow.filter((a) => !used.has(a.file)) };
}
