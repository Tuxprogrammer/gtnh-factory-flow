import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

/**
 * The website and its tools name no host of the original public planner site, its repositories, its tip jar or its
 * chat, and none of the owner's own hosts: a literal of one would be an address every install sends data or players
 * to, or presents itself as. The site's own address comes from NEXT_PUBLIC_SITE_URL (`src/lib/site-url.ts`).
 * This reads every tracked text file outside `jvm/` (the Java side has its own scan, `NoRemoteHostTest`) and
 * outside documentation (`docs/`, `*.md`), and fails on a name below unless a line is on the allow-list with its
 * reason. An allow-list entry that no longer matches anything fails too, so the list cannot go stale.
 */

/**
 * The names this project must not carry: the original site, its repositories, its donation page, its chat. The
 * lookbehind skips the Java package `com.gtnhplanner.dev`, which is code, not a host.
 */
const NAMES =
  /(?<!com\.)gtnhplanner\.(?:com|net|org|io|app|dev)\b|jackwrichards|gtnewhorizons|ko-fi\.com|discord\.(?:com|gg)|samiracle|tuxhpc/gi;

interface Allowed {
  /** The file's path, or a directory prefix ending in `/`. */
  file: string;
  /** The line must contain this; omitted, every line of the file or directory is covered. */
  line?: string;
  /** Only a name matching this is covered; omitted, any name is. */
  only?: RegExp;
  /** Why the name stays: compatibility parsing, credit, licence, changelog history, or a file this stream does not own. */
  reason: string;
}

const ALLOWED: Allowed[] = [
  { file: "LICENSE", reason: "Licence: the copyright holders' names." },
  {
    file: "src/lib/changelog.ts",
    line: "jackwrichards/gtnh-planner-mod/releases/latest",
    reason: "Changelog history: an old entry's link, as it was written.",
  },
  {
    file: "src/lib/changelog.ts",
    line: "jackwrichards/gtnh-factory-flow/blob/main/docs/releases/3.1.4.md",
    reason: "Changelog history: an old entry's link, as it was written.",
  },
  {
    file: "src/lib/power/sources/railcraft.ts",
    line: "GTNewHorizons/Railcraft",
    reason: "Credit: which game source tag the numbers were read from.",
  },
  {
    file: "src/lib/import-export/plan-code.test.ts",
    line: "https://gtnhplanner.com",
    reason: "Compatibility: a pasted plan link made on that site opens here, because only the fragment is read.",
  },
  {
    file: "tools/dataset-pipeline/",
    only: /gtnewhorizons/i,
    reason:
      "The dataset pipeline fetches the public game pack, its Maven artifacts and its wiki, and builds against its " +
      "Gradle plugins: that is the game's data and build, not the planner's site. Run by the operator, never by the app.",
  },
  {
    file: ".github/workflows/deploy-umami.yml",
    line: "samiracle.fr",
    reason: "A deploy workflow that has no runner here and is never dispatched; the CI stream owns .github/workflows.",
  },
  { file: "src/lib/no-remote-host.test.ts", reason: "This test spells the names it hunts." },
];

/** Never read: documentation (another agent's), the Java side (its own scan) and generated trees. */
function skipped(path: string): boolean {
  return (
    path.startsWith("jvm/") ||
    path.startsWith("docs/") ||
    path.startsWith("scratch/") ||
    path.endsWith(".md") ||
    path === "package-lock.json"
  );
}

function trackedTextFiles(): { path: string; text: string }[] {
  const listed = execFileSync("git", ["ls-files", "-z", "--cached", "--others", "--exclude-standard"], { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
  const files: { path: string; text: string }[] = [];
  for (const path of listed.split("\0")) {
    if (!path || skipped(path)) continue;
    let bytes: Buffer;
    try {
      bytes = readFileSync(path);
    } catch {
      continue; // listed but absent from this checkout
    }
    if (bytes.subarray(0, 8000).includes(0)) continue; // binary
    files.push({ path, text: bytes.toString("utf8") });
  }
  return files;
}

function covers(entry: Allowed, path: string, line: string, name: string): boolean {
  const inFile = entry.file.endsWith("/") ? path.startsWith(entry.file) : path === entry.file;
  return inFile && (entry.line === undefined || line.includes(entry.line)) && (!entry.only || entry.only.test(name));
}

describe("no remote host in the website or its tools", () => {
  const files = trackedTextFiles();

  it("reads the tree it means to", () => {
    expect(files.length).toBeGreaterThan(500);
    expect(files.some((f) => f.path === "src/app/layout.tsx")).toBe(true);
    expect(files.some((f) => f.path.startsWith("tools/"))).toBe(true);
    expect(files.some((f) => f.path.startsWith(".github/"))).toBe(true);
  });

  it("names none of the hosts, repositories or accounts it must not, except the listed lines", () => {
    const found: string[] = [];
    const used = new Set<Allowed>();
    for (const { path, text } of files) {
      text.split("\n").forEach((line, index) => {
        for (const match of line.matchAll(NAMES)) {
          const entry = ALLOWED.find((a) => covers(a, path, line, match[0]));
          if (entry) used.add(entry);
          else found.push(`${path}:${index + 1}: ${match[0]}`);
        }
      });
    }
    expect(found).toEqual([]);
    expect(ALLOWED.filter((a) => !used.has(a)).map((a) => `${a.file} ${a.line ?? ""}`)).toEqual([]);
  });

  it("gives every allowed line a reason", () => {
    for (const entry of ALLOWED) expect(entry.reason.length).toBeGreaterThan(20);
  });
});
