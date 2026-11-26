#!/usr/bin/env python3
"""Drags a planner scroll bar with the real mouse and checks, from the screen's pixels, that it moved.

Input goes through the X server (xdotool), as a player's mouse does: press on the thumb, move in steps, let go. Nothing
calls widget methods. The harness (`/scrollbars`) is read only to find where a bar is; whether the thumb moved is read
from screenshots of the bar (ImageMagick `import`). Exit status 0 when every check passes, 1 when one fails, 2 when the
bar is not on screen.

Run it on the machine whose X display shows the dev game, with the planner open where the bar is showing:

    DISPLAY=:N tools/dev/scrollbar-drag.py overview            # the overview's bar
    DISPLAY=:N tools/dev/scrollbar-drag.py --list              # the bars drawn now, as the harness sees them
    tools/dev/scrollbar-drag.py overview --prime 2797,95;2400,700   # first plain clicks there, in turn: a toolbar
                                    # key, then the empty board (a click on the board left every later drag dead)

Checks: a press on the thumb dragged --distance pixels moves it as far (to the end, if nearer), and it stays put
after the release; a drag past the end stops at the end; a release away from the bar ends the drag; a click on the
track moves the thumb toward the click; the wheel over the bar moves it. --only picks some (drag,end,outside,track,
wheel). Env: PLANNH_DEV_PORT (25599).
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request

IDLE, LIT = (0x6A, 0x6C, 0x74), (0xE8, 0xE9, 0xEE)


def harness(path):
    port = os.environ.get("PLANNH_DEV_PORT", "25599")
    with urllib.request.urlopen(f"http://localhost:{port}/{path}", timeout=20) as r:
        return json.loads(r.read())


def xdo(*args):
    subprocess.run(["xdotool", *map(str, args)], check=True)


def pixels(x, y, w, h):
    """The screen's pixels in a rectangle, as rows of (r, g, b)."""
    out = subprocess.run(
        ["import", "-window", "root", "-crop", f"{w}x{h}+{x}+{y}", "-depth", "8", "rgb:-"],
        check=True,
        capture_output=True,
    ).stdout
    return [[tuple(out[(j * w + i) * 3:(j * w + i) * 3 + 3]) for i in range(w)] for j in range(h)]


def near(c, ref, tol=6):
    return all(abs(a - b) <= tol for a, b in zip(c, ref))


class Bar:
    def __init__(self, name, info):
        self.name, self.axis = name, info["axis"]
        self.c0, self.c1 = info["cross"]
        self.t0, self.t1 = info["track"]
        self.scroll, self.max = info["scroll"], info["max"]
        self.scale = info.get("scale", 4.0)

    def mid(self):
        return (self.c0 + self.c1) // 2

    def point(self, along, cross=None):
        """A screen point on the bar's axes, kept on the screen (the X server stops the pointer at its edge too)."""
        cross = self.mid() if cross is None else cross
        x, y = (along, cross) if self.axis == "x" else (cross, along)
        return min(max(x, 0), SCREEN[0] - 1), min(max(y, 0), SCREEN[1] - 1)

    def thumb(self):
        """The thumb's start and end along the track, read from the screen; None when none is drawn."""
        c = self.mid()
        if self.axis == "y":
            line = [row[0] for row in pixels(c, self.t0, 1, self.t1 - self.t0)]
        else:
            line = pixels(self.t0, c, self.t1 - self.t0, 1)[0]
        best, run = None, None
        for i, px in enumerate(line + [(0, 0, 0)]):
            if near(px, IDLE) or near(px, LIT):
                run = (run[0], i) if run else (i, i)
            elif run:
                if best is None or run[1] - run[0] > best[1] - best[0]:
                    best = run
                run = None
        return None if best is None else (self.t0 + best[0], self.t0 + best[1] + 1)


def find(name, geometry=None):
    if geometry:
        axis, c0, c1, t0, t1 = geometry.split(",")
        return Bar(name, {"axis": axis, "cross": [int(c0), int(c1)], "track": [int(t0), int(t1)], "scroll": 0, "max": 0})
    bars = harness("scrollbars")
    if name not in bars:
        print(f"no bar '{name}' on screen; drawn now: {', '.join(bars) or 'none'}")
        sys.exit(2)
    return Bar(name, bars[name])


PARK = None
SCREEN = (1 << 15, 1 << 15)


def park(bar):
    """The mouse off the bar, so it draws unlit and the next press starts clean."""
    if PARK:
        xdo("mousemove", *PARK)
        time.sleep(0.4)
        return
    along = (bar.t0 + bar.t1) // 2
    x, y = bar.point(along, bar.mid() + 300 if bar.axis == "y" else bar.mid() + 120)
    xdo("mousemove", x, y)
    time.sleep(0.4)


def drag(bar, start, distance, steps, pause, away=0):
    """Press at `start` along the track, move `distance` in `steps`, then `away` across it, and let go."""
    xdo("mousemove", *bar.point(start))
    time.sleep(0.3)
    xdo("mousedown", 1)
    time.sleep(0.15)
    for i in range(1, steps + 1):
        xdo("mousemove", *bar.point(start + distance * i // steps))
        time.sleep(pause)
    if away:
        for i in range(1, 6):
            xdo("mousemove", *bar.point(start + distance, bar.mid() + away * i // 5))
            time.sleep(pause)
    time.sleep(0.3)
    held = bar.thumb()
    xdo("mouseup", 1)
    time.sleep(0.4)
    return held


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("bar", nargs="?")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--distance", type=int, default=100, help="screen pixels to drag (default 100)")
    ap.add_argument("--steps", type=int, default=10)
    ap.add_argument("--pause", type=float, default=0.03, help="seconds between steps")
    ap.add_argument("--prime", help="X,Y[;X,Y...]: plain clicks there first, in turn")
    ap.add_argument("--only", default="drag,end,outside,track,wheel")
    ap.add_argument("--park", help="X,Y: where the mouse rests between checks (a bar drawn only while the mouse is on "
                    "its strip, as the plan tabs', needs a spot on the strip off the bar)")
    ap.add_argument("--geometry", help="axis,c0,c1,t0,t1 in screen px: the bar's place without the harness (a build "
                    "older than /scrollbars)")
    a = ap.parse_args()
    if a.list:
        print(json.dumps(harness("scrollbars"), indent=1))
        return 0
    if not a.bar:
        ap.error("name a bar (--list shows them)")
    checks = a.only.split(",")
    failed = []

    def check(name, ok, detail):
        print(f"{'PASS' if ok else 'FAIL'} {a.bar} {name}: {detail}")
        if not ok:
            failed.append(name)

    for spot in (a.prime or "").split(";"):
        if not spot:
            continue
        x, y = spot.split(",")
        xdo("mousemove", x, y)
        time.sleep(0.3)
        xdo("click", 1)
        time.sleep(0.6)
    global PARK
    if a.park:
        PARK = tuple(int(v) for v in a.park.split(","))
        xdo("mousemove", *PARK)
        time.sleep(0.5)
    bar = find(a.bar, a.geometry)
    park(bar)
    th = bar.thumb()
    if th is None:
        print(f"no thumb drawn on '{a.bar}' (scroll {bar.scroll}, most {bar.max}): is there more than fits?")
        return 2
    # The thumb sits on the bar's own pixels: the mouse and the thumb each round to one, so two of them apart at most.
    tol = max(3, round(2 * bar.scale))
    size = th[1] - th[0]

    def to_start():
        """Thumb back to the start of the track, with the real mouse, so each check has room to move."""
        t = bar.thumb()
        if t[0] - bar.t0 > tol:
            drag(bar, (t[0] + t[1]) // 2, -(t[0] - bar.t0) - 40, 8, a.pause)
            park(bar)
        return bar.thumb()

    if "drag" in checks:
        th = to_start()
        room = bar.t1 - th[1]
        want = min(a.distance, room)
        held = drag(bar, (th[0] + th[1]) // 2, a.distance, a.steps, a.pause)
        park(bar)
        after = bar.thumb()
        moved = held[0] - th[0] if held else 0
        check("drag", abs(moved - want) <= tol, f"mouse {a.distance} px, thumb {moved} px while held (room {room})")
        check("let go", after == held, f"thumb at {after} after the release, {held} while held")
    if "end" in checks:
        th = to_start()
        drag(bar, (th[0] + th[1]) // 2, bar.t1 - bar.t0 + 200, a.steps, a.pause)
        park(bar)
        after = bar.thumb()
        check("end", abs(after[1] - bar.t1) <= tol and abs(after[1] - after[0] - size) <= tol,
              f"thumb {after} on a track ending at {bar.t1}")
        drag(bar, (after[0] + after[1]) // 2, -(bar.t1 - bar.t0) - 200, a.steps, a.pause)
        park(bar)
        back = bar.thumb()
        check("start", abs(back[0] - bar.t0) <= tol, f"thumb {back} on a track starting at {bar.t0}")
    if "outside" in checks:
        th = to_start()
        held = drag(bar, (th[0] + th[1]) // 2, min(60, bar.t1 - th[1]), 6, a.pause, away=-250)
        # Over the track again with the button up: a drag that did not end would carry the thumb.
        xdo("mousemove", *bar.point(bar.t1 - 2))
        time.sleep(0.3)
        xdo("mousemove", *bar.point(bar.t0 + 2))
        time.sleep(0.3)
        park(bar)
        after = bar.thumb()
        check("outside", held is not None and after == held, f"let go away from the bar at {held}, then {after}")
    if "track" in checks:
        th = to_start()
        target = min(bar.t1 - 2, th[1] + size + 10)
        xdo("mousemove", *bar.point(target))
        time.sleep(0.3)
        xdo("click", 1)
        time.sleep(0.4)
        park(bar)
        after = bar.thumb()
        check("track", after[0] > th[0] + tol, f"click on the track at {target}: thumb {th} -> {after}")
    if "wheel" in checks:
        th = to_start()
        xdo("mousemove", *bar.point((th[0] + th[1]) // 2))
        time.sleep(0.3)
        for _ in range(3):
            xdo("click", 5)
            time.sleep(0.1)
        time.sleep(0.3)
        park(bar)
        after = bar.thumb()
        check("wheel", after[0] > th[0], f"three wheel steps down: thumb {th} -> {after}")
    print(f"{a.bar}: {'FAIL ' + ','.join(failed) if failed else 'PASS'}")
    return 1 if failed else 0


if __name__ == "__main__":
    SCREEN = tuple(int(v) for v in subprocess.run(["xdotool", "getdisplaygeometry"], check=True, capture_output=True,
                                                  text=True).stdout.split())
    try:
        sys.exit(main())
    finally:
        # Never leave the button down in the game, whatever went wrong.
        subprocess.run(["xdotool", "mouseup", "1"], check=False)
