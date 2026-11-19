#!/usr/bin/env python3
"""Fail unless every JVM module ran tests, and enough of them.

Reads the JUnit result XML Gradle wrote (<module>/build/test-results/test/*.xml) for the mod and for each
directory under jvm/core/ and jvm/web/ that has a build.gradle.kts, and compares the count of tests that ran (tests minus
skipped) with the floors in jvm-test-floors.txt (a module without a line needs at least 1).

  check-jvm-tests.py            exit 1 and name every module that falls short
  check-jvm-tests.py --report   print the counts and always exit 0
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JVM = ROOT / "jvm"


def modules():
    mods = [("mod", JVM / "mod")]
    for group in ("core", "web"):
        base = JVM / group
        if base.is_dir():
            for d in sorted(base.iterdir()):
                if (d / "build.gradle.kts").is_file():
                    mods.append((d.name, d))
    return mods


def floors():
    out = {}
    for line in (Path(__file__).parent / "jvm-test-floors.txt").read_text().splitlines():
        line = line.split("#", 1)[0].split()
        if len(line) == 2:
            out[line[0]] = int(line[1])
    return out


def count(mod_dir):
    files = sorted((mod_dir / "build" / "test-results" / "test").glob("*.xml"))
    tests = skipped = bad = 0
    for f in files:
        s = ET.parse(f).getroot()
        tests += int(s.get("tests", 0))
        skipped += int(s.get("skipped", 0))
        bad += int(s.get("failures", 0)) + int(s.get("errors", 0))
    return len(files), tests, skipped, bad


def main():
    report = "--report" in sys.argv
    fl = floors()
    problems = []
    total = 0
    for name, d in modules():
        nfiles, tests, skipped, bad = count(d)
        ran = tests - skipped
        total += ran
        floor = max(1, fl.get(name, 1))
        note = ""
        if nfiles == 0:
            note = "no result files (the test task did not run)"
        elif ran < floor:
            note = f"ran {ran}, below the floor {floor}"
        elif bad:
            note = f"{bad} failed"
        print(f"[ci:jvm] {name}: {ran} ran, {skipped} skipped, floor {floor} in {nfiles} files" + (f"  <-- {note}" if note else ""))
        if note:
            problems.append(f"{name}: {note}")
    tf = fl.get("total", 1)
    print(f"[ci:jvm] total: {total} ran, floor {tf}")
    if total < tf:
        problems.append(f"total: ran {total}, below the floor {tf}")
    if problems and not report:
        print("[ci:jvm] FAIL: " + "; ".join(problems), file=sys.stderr)
        return 1
    return 0


sys.exit(main())
