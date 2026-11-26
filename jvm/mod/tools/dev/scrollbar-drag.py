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


def slider_run(name, pause):
    """A slider dragged with the real mouse: its value must follow the mouse while held and stay where it was let go."""
    failed = []

    def get():
        s = harness("sliders")
        if name not in s:
            print(f"no slider '{name}' on screen; drawn now: {', '.join(s) or 'none'}")
            sys.exit(2)
        return s[name]

    s = get()
    x0, x1, y, steps = s["x"][0], s["x"][1], s["y"], max(1, s["steps"])
    start = s["value"]

    def expect(x):
        f = min(1.0, max(0.0, (x - x0) / max(1, x1 - x0)))
        return round(f * steps) / steps

    def at(v):
        return round(x0 + v * (x1 - x0))

    def check(what, ok, detail):
        print(f"{'PASS' if ok else 'FAIL'} {name} {what}: {detail}")
        if not ok:
            failed.append(what)

    def drag(frm, to, steps_=10, away=0):
        xdo("mousemove", frm, y)
        time.sleep(0.3)
        xdo("mousedown", 1)
        time.sleep(0.15)
        for i in range(1, steps_ + 1):
            xdo("mousemove", frm + (to - frm) * i // steps_, y)
            time.sleep(pause)
        if away:
            for i in range(1, 6):
                xdo("mousemove", to, y + away * i // 5)
                time.sleep(pause)
        time.sleep(0.3)
        held = get()
        xdo("mouseup", 1)
        time.sleep(0.4)
        return held

    # One step's worth either way: the mouse and the value each round to a pixel and a step.
    tol = 1.0 / steps + 0.03
    for frac in (0.75, 0.25):
        frm, to = at(get()["value"]), at(frac)
        held = drag(frm, to)
        after = get()
        check(f"follow to {frac}", held["held"] and abs(held["value"] - expect(to)) <= tol,
              f"mouse at {to} ({expect(to):.3f}), value {held['value']:.3f} while held")
        check(f"let go at {frac}", not after["held"] and abs(after["value"] - held["value"]) <= 1e-6,
              f"value {after['value']:.3f} after the release, {held['value']:.3f} while held")
    held = drag(at(get()["value"]), x1 + 200)
    check("end", abs(held["value"] - 1) <= 1e-6, f"dragged past the end: value {held['value']:.3f}")
    held = drag(at(get()["value"]), x0 - 200)
    check("start", abs(held["value"]) <= 1e-6, f"dragged past the start: value {held['value']:.3f}")
    held = drag(at(get()["value"]), at(0.5), away=-150)
    xdo("mousemove", x1 - 2, y)
    time.sleep(0.3)
    xdo("mousemove", x0 + 2, y)
    time.sleep(0.3)
    after = get()
    check("outside", abs(after["value"] - held["value"]) <= 1e-6 and not after["held"],
          f"let go away from it at {held['value']:.3f}, then {after['value']:.3f} with the mouse over it")
    # Put it back where it was.
    drag(at(get()["value"]), at(start))
    print(f"{name}: {'FAIL ' + ','.join(failed) if failed else 'PASS'}")
    return 1 if failed else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("bar", nargs="?", help="a bar's name (--list), a slider's with --slider, or a scene: "
                    + ", ".join(SCENES))
    ap.add_argument("--list", action="store_true", help="the bars and sliders drawn now")
    ap.add_argument("--slider", action="store_true", help="the name is a slider's (/sliders): drag it and check its "
                    "value follows the mouse, stops at both ends and stays where it was let go")
    ap.add_argument("--distance", type=int, default=100, help="screen pixels to drag (default 100)")
    ap.add_argument("--steps", type=int, default=10)
    ap.add_argument("--pause", type=float, default=0.03, help="seconds between steps")
    ap.add_argument("--prime", help="X,Y[;X,Y...]: plain clicks there first, in turn")
    ap.add_argument("--only", default="drag,end,outside,track,wheel")
    ap.add_argument("--park", help="X,Y: where the mouse rests between checks (a bar drawn only while the mouse is on "
                    "its strip, as the plan tabs', needs a spot on the strip off the bar)")
    ap.add_argument("--card", help="pattern-box: the card's id, to open the box again for Esc, Cancel and Make")
    ap.add_argument("--board-at", type=lambda v: tuple(int(x) for x in v.split(",")), default=(1000, 400),
                    help="delete: X,Y on the empty board where the mouse rests for the keys")
    ap.add_argument("--gui", type=int, default=2, help="the game's GUI scale (screen pixels per GUI pixel)")
    ap.add_argument("--geometry", help="axis,c0,c1,t0,t1 in screen px: the bar's place without the harness (a build "
                    "older than /scrollbars)")
    a = ap.parse_args()
    if a.list:
        print(json.dumps({"bars": harness("scrollbars"), "sliders": harness("sliders")}, indent=1))
        return 0
    if not a.bar:
        ap.error("name a bar (--list shows them)")
    global GUI
    GUI = a.gui
    if a.slider:
        return slider_run(a.bar, a.pause)
    if a.bar in SCENES:
        return SCENES[a.bar](a)
    return bar_run(a)


def bar_run(a):
    """The bar named a.bar pressed, dragged and let go with the real mouse; 0 when every check passes."""
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
        # A list that scrolls by whole rows (the pick menu's) settles on the row the thumb shows once let go: a pixel
        # or two, as the mouse and the thumb each round. A thumb that jumps back moves far more than that.
        settled = after is not None and held is not None and all(abs(p - q) <= tol for p, q in zip(after, held))
        check("let go", settled, f"thumb at {after} after the release, {held} while held")
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


GUI = 2


def gui_pt(p):
    """A point in the game's GUI pixels (the harness's /click and /pattern), on the screen."""
    return p[0] * GUI, p[1] * GUI


def click_at(pt, button=1):
    xdo("mousemove", *pt)
    time.sleep(0.25)
    xdo("click", button)
    time.sleep(0.5)


def key(*names):
    """Key chords pressed through the X server and held as a person holds them (150 ms), modifiers first and let go
    last (xdotool names: Return, Escape, ctrl+shift+z). A tap shorter than a frame or two can go unseen by the game."""
    for n in names:
        parts = n.split("+")
        for k in parts:
            xdo("keydown", k)
            time.sleep(0.05)
        time.sleep(0.15)
        for k in reversed(parts):
            xdo("keyup", k)
            time.sleep(0.03)
        time.sleep(0.6)


def log_lines(since, word):
    """Lines of the game's log after byte `since` that contain `word`."""
    path = os.environ.get("PLANNH_LOG", "build/dev-client/client.log")
    with open(path, "rb") as f:
        f.seek(since)
        return [l for l in f.read().decode("utf-8", "replace").splitlines() if word in l]


def log_end():
    path = os.environ.get("PLANNH_LOG", "build/dev-client/client.log")
    return os.path.getsize(path)


class Checks:
    def __init__(self, scene):
        self.scene, self.failed = scene, []

    def __call__(self, what, ok, detail):
        print(f"{'PASS' if ok else 'FAIL'} {self.scene} {what}: {detail}")
        if not ok:
            self.failed.append(what)

    def done(self):
        print(f"{self.scene}: {'FAIL ' + ','.join(self.failed) if self.failed else 'PASS'}")
        return 1 if self.failed else 0


def pattern_scene(a):
    """The pattern box on a shared machine (open it first: /pattern?card=1&join=14...): its bar, the wheel over its
    rows, a click on its footer off the keys, a click on a row's switch, then Enter (makes the patterns, once). Then it
    is opened again (/pattern?pick=1&id=, --card) for Esc (cancels) and again for a click on Cancel after the rows are
    dragged to the end."""
    c = Checks("pattern box")

    def box():
        return harness("pattern?pickat=1")

    def reopen():
        harness(f"pattern?pick=1&id={a.card}")
        time.sleep(0.8)
        return box()

    t = box()
    if not t.get("open"):
        print("open the pattern box first")
        return 2
    sub = argparse.Namespace(**{**vars(a), "bar": "pattern"})
    c("bar", bar_run(sub) == 0, "pattern box's bar: drag, ends, release away, track click, wheel on the bar")
    # The wheel over the rows themselves.
    t = box()
    row = next(p for p in t["switches"] if p)
    over = gui_pt((row[0] - 120, row[1]))
    xdo("mousemove", *over)
    time.sleep(0.3)
    before = box()["scroll"]
    for _ in range(3):
        xdo("click", 5)
        time.sleep(0.15)
    time.sleep(0.3)
    down = box()["scroll"]
    for _ in range(3):
        xdo("click", 4)
        time.sleep(0.15)
    time.sleep(0.3)
    up = box()["scroll"]
    c("wheel on rows", down > before and up < down, f"scroll {before} -> {down} (3 down) -> {up} (3 up)")
    # Cancel and Make pattern on the screen, under the rows.
    t = box()
    screen_h = SCREEN[1]
    keys_ok = all(0 <= gui_pt(t[k])[1] < screen_h for k in ("cancel", "make"))
    c("keys on screen", keys_ok and t["maxScroll"] > 0,
      f"cancel {gui_pt(t['cancel'])}, make {gui_pt(t['make'])}, screen {SCREEN}, rows scroll up to {t['maxScroll']}")
    # A click on the footer off the keys, with rows hidden under it: nothing changes, the box stays.
    t = box()
    hidden = sum(1 for p in t["switches"] if p is None)
    click_at(gui_pt((t["cancel"][0] - 60, t["cancel"][1])))
    after = box()
    c("footer click", after["open"] and after["included"] == t["included"],
      f"{hidden} rows hidden; box open {after['open']}, choices the same {after['included'] == t['included']}")
    # A click on a row's switch flips it; a second flips it back.
    t = box()
    idx = next(i for i, p in enumerate(t["switches"]) if p)
    click_at(gui_pt(t["switches"][idx]))
    flipped = box()["included"]
    click_at(gui_pt(t["switches"][idx]))
    back = box()["included"]
    c("row switch", flipped != t["included"] and back == t["included"], f"switch {idx} flipped and back")
    # Enter makes the patterns, once.
    made0 = len(harness("pattern?look=1")["encoded"])
    mark = log_end()
    key("Return")
    time.sleep(2.5)
    t = box()
    made = len(harness("pattern?look=1")["encoded"]) - made0
    lines = log_lines(mark, "Enter in ")
    runs = [l for l in lines if l.endswith(": runs")]
    c("enter", not t["open"] and made > 0 and len(runs) == 1,
      f"box open {t['open']}, {made} patterns made; Enter heard: "
      + "; ".join(l.split("Enter in ", 1)[1] for l in lines))
    if not a.card:
        return c.done()
    # Esc cancels: nothing made.
    t = reopen()
    made0 = len(harness("pattern?look=1")["encoded"])
    key("Escape")
    time.sleep(1.5)
    t = box()
    made = len(harness("pattern?look=1")["encoded"]) - made0
    c("esc", not t["open"] and made == 0, f"box open {t['open']}, {made} made")
    # Rows dragged to the end with the thumb, then Cancel clicked: closed, nothing made.
    t = reopen()
    bar = find("pattern")
    th = bar.thumb()
    drag(bar, (th[0] + th[1]) // 2, bar.t1 - bar.t0, 10, a.pause)
    t = box()
    made0 = len(harness("pattern?look=1")["encoded"])
    c("rows at the end", t["scroll"] == t["maxScroll"] and t["switches"][-1] is not None,
      f"scroll {t['scroll']} of {t['maxScroll']}, last row shown {t['switches'][-1] is not None}")
    click_at(gui_pt(t["cancel"]))
    t = box()
    made = len(harness("pattern?look=1")["encoded"]) - made0
    c("cancel", not t["open"] and made == 0, f"box open {t['open']}, {made} made")
    # And Make pattern clicked with the rows at the end makes them.
    t = reopen()
    bar = find("pattern")
    th = bar.thumb()
    drag(bar, (th[0] + th[1]) // 2, bar.t1 - bar.t0, 10, a.pause)
    made0 = len(harness("pattern?look=1")["encoded"])
    click_at(gui_pt(box()["make"]))
    time.sleep(2.5)
    t = box()
    made = len(harness("pattern?look=1")["encoded"]) - made0
    c("make", not t["open"] and made > 0, f"box open {t['open']}, {made} made")
    return c.done()


def cards():
    return harness("board")["cards"]


def delete_scene(a):
    """The delete question on a board of several cards (all of them selected with Ctrl+A): Delete asks, Esc keeps them,
    Enter deletes them (the action once), Ctrl+Z brings them back, Ctrl+Shift+Z and Ctrl+Y redo, and a bare Z does
    nothing. All keys through the X server."""
    c = Checks("delete question")
    n0 = len(cards())
    if n0 < 2:
        print("put two or more cards on the board first")
        return 2
    # The mouse over the board, clear of the boxes.
    xdo("mousemove", *a.board_at)
    time.sleep(0.3)

    def ask():
        key("ctrl+a")
        mark = log_end()
        key("Delete")
        return mark

    def asking():
        return any(p.startswith("gtnhplanner_delete_cards") for p in harness("status").get("panels", []))

    ask()
    n = len(cards())
    c("asks", n == n0 and asking(), f"{n0} cards, {n} after Delete; question open {asking()}")
    key("Escape")
    n = len(cards())
    c("esc keeps", n == n0 and not asking(), f"{n} cards after Esc; question open {asking()}")
    mark = ask()
    key("Return")
    time.sleep(1)
    n = len(cards())
    lines = log_lines(mark, "Enter in ")
    runs = [l for l in lines if l.endswith(": runs")]
    c("enter deletes", n == 0 and len(runs) == 1,
      f"{n} cards after Enter; Enter heard: " + "; ".join(l.split("Enter in ", 1)[1] for l in lines))
    key("ctrl+z")
    time.sleep(1)
    c("ctrl+z", len(cards()) == n0, f"{len(cards())} cards after Ctrl+Z")
    key("ctrl+shift+z")
    time.sleep(1)
    c("ctrl+shift+z", len(cards()) == 0, f"{len(cards())} cards after Ctrl+Shift+Z")
    key("ctrl+z")
    time.sleep(1)
    key("ctrl+y")
    time.sleep(1)
    c("ctrl+y", len(cards()) == 0, f"{len(cards())} cards after Ctrl+Z, Ctrl+Y")
    key("ctrl+z")
    time.sleep(1)
    key("z")
    time.sleep(1)
    n = len(cards())
    c("bare z", n == n0, f"{n} cards after Ctrl+Z then a bare Z ({n0} before)")
    return c.done()


def holds():
    """The open panels that still hold a mouse button (the /status readout), as their names."""
    return [p.split(" ")[0] for p in harness("status").get("panels", []) if "held:true" in p]


def popups():
    return harness("widgets").get("popups") or []


# What a left click on each control of a card or drawer does: "menu" opens a box (a popup panel), "edit" opens a
# menu or the board's inline number box (the field takes the keys), "step" changes the card in place (the tier chip).
CARD_CONTROLS = [("MACHINE", "menu"), ("AMPS", "edit"), ("SETTINGS", "menu"), ("ACTIONS", "menu"),
                 ("PLACE", "menu"), ("RULE", "menu"), ("MACHINES", "edit"), ("TIER", "step")]
DRAWER_CONTROLS = [("RULE", "menu"), ("RATE", "edit")]


def chips_scene(a):
    """Each control on the board's cards and drawers clicked with the real mouse: its box opens and is wholly on the
    screen, the tier chip steps the tier, Esc closes the box, and afterwards no panel holds a mouse button (a held
    button sends every later drag to the old widget). Put a multiblock card (tier and amps chips) and a drawer with a
    rule in view first; every control in view is clicked."""
    c = Checks("card chips")
    st = harness("status")
    gw, gh = st["guiWidth"], st["guiHeight"]
    board = harness("board")
    cv = board["canvas"]

    def in_view(p):
        return cv["x"] + 2 <= p["cx"] < cv["x"] + cv["w"] - 2 and cv["y"] + 2 <= p["cy"] < cv["y"] + cv["h"] - 2

    def state_of(kind, index):
        b = harness("board")
        return (b["cards"] if kind == "card" else b["drawers"])[index]

    def press(kind, index, part, does):
        p = state_of(kind, index)["parts"].get(part)
        if p is None or not in_view(p):
            return False
        label = f"{kind} {index} {part}"
        # The board's toolbar and notices lie over the canvas's top: only a control the mouse is on counts.
        xdo("mousemove", *gui_pt((p["cx"], p["cy"])))
        time.sleep(0.4)
        under = harness("status").get("hovered") or ""
        if not under.startswith("RecipeCard" if kind == "card" else "DrawerCard"):
            print(f"skip {label}: covered by {under or 'nothing'}")
            return False
        before = state_of(kind, index)
        n0 = len(popups())
        click_at(gui_pt((p["cx"], p["cy"])))
        time.sleep(0.4)
        ups = popups()
        held = holds()
        if does == "step":
            after = state_of(kind, index)
            changed = (after.get("tier"), after.get("machine")) != (before.get("tier"), before.get("machine"))
            c(label, changed and len(ups) == n0 and not held,
              f"tier {before.get('tier')} -> {after.get('tier')}, {len(ups) - n0} boxes opened, held {held}")
            # Back where it was: the right button steps it down.
            click_at(gui_pt((p["cx"], p["cy"])), button=3)
            return True
        new = ups[n0:]
        focused = harness("status").get("focused")
        opened = bool(new) or (does == "edit" and focused)
        on_screen = all(u["x"] >= 0 and u["y"] >= 0 and u["x"] + u["w"] <= gw and u["y"] + u["h"] <= gh for u in new)
        boxes = ", ".join(f"{u['name']} {u['x']},{u['y']} {u['w']}x{u['h']}" for u in new) or f"focused {focused}"
        c(label, opened and on_screen and not held, f"{boxes} on a {gw}x{gh} screen; held {held}")
        if not opened:
            return True
        # Esc with nothing open would close the planner.
        key("Escape")
        left = popups()
        c(label + " esc", len(left) == n0 and not holds(), f"{len(left) - n0} boxes left open; held {holds()}")
        return True

    clicked = 0
    for i, card in enumerate(board["cards"]):
        for part, does in CARD_CONTROLS:
            clicked += press("card", i, part, does)
    for i, _ in enumerate(board.get("drawers", [])):
        for part, does in DRAWER_CONTROLS:
            clicked += press("drawer", i, part, does)
    if clicked == 0:
        print("no card or drawer control in view: put a card on the board where it shows")
        return 2
    return c.done()


SCENES = {"pattern-box": pattern_scene, "delete": delete_scene, "card-chips": chips_scene}


if __name__ == "__main__":
    SCREEN = tuple(int(v) for v in subprocess.run(["xdotool", "getdisplaygeometry"], check=True, capture_output=True,
                                                  text=True).stdout.split())
    try:
        sys.exit(main())
    finally:
        # Never leave the button down in the game, whatever went wrong.
        subprocess.run(["xdotool", "mouseup", "1"], check=False)
