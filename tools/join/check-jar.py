#!/usr/bin/env python3
"""Checks that the mod jar carries the shaded core: each named class once at its own path at Java 8 (class major 52),
and at most once under META-INF/versions/<n>/ (the multi-release copy for newer Java).

Usage: python3 -I tools/join/check-jar.py <jar> <class name>...   (names like com.gtnhplanner.data.flowchart.OrderKeys)
"""
import struct
import sys
import zipfile


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    jar, names = argv[1], argv[2:]
    ok = True
    with zipfile.ZipFile(jar) as z:
        entries = z.namelist()
        print(f"jar {jar}: {len(entries)} entries")
        for name in names:
            path = name.replace(".", "/") + ".class"
            base = [e for e in entries if e == path]
            versioned = [e for e in entries if e.startswith("META-INF/versions/") and e.endswith("/" + path)]
            for e in base + versioned:
                head = z.read(e)[:8]
                magic, _minor, major = struct.unpack(">IHH", head)
                print(f"  {e}: class major {major}" + ("" if magic == 0xCAFEBABE else " (not a class file)"))
            if len(base) != 1:
                ok = False
                print(f"  FAIL {path}: {len(base)} copies at its own path")
            elif struct.unpack(">IHH", z.read(base[0])[:8])[2] != 52:
                ok = False
                print(f"  FAIL {path}: not Java 8")
            if len(versioned) > 1:
                ok = False
                print(f"  FAIL {path}: {len(versioned)} multi-release copies")
    print("JAR-CHECK: PASS" if ok else "JAR-CHECK: FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
