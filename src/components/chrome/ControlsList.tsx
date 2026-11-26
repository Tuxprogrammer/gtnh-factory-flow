"use client";

import "./chrome.css";

import { useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { useDeviceSettings } from "@/lib/device-settings";
import { useDropdownDismiss } from "@/lib/hooks/use-dropdown-dismiss";
import { getUiScale } from "@/lib/ui-scale";
import { BOX_FIT_GUI, useSlideIntoWindow } from "@/components/box-fit";
import { CHROME_SCREEN_K } from "./ChromeMenu";

/** The wheel's row; with "Scroll changes values" off the wheel only zooms. */
const WHEEL_ROW = "Zoom; over a value, change it";

/** Every gesture on the board, in one list (the mod's "How the board works"). */
export const CONTROLS: ReadonlyArray<readonly [string, string]> = [
  ["R or U over an item", "Show its recipes or uses in the items list; + adds a recipe"],
  ["Drag an item out of the list", "Click the board to add a drawer for it"],
  ["Click an item in the list", "Its recipes (left-click) or uses (right-click)"],
  ["Click a port", "Its recipes (left) or uses (right); adding one wires it to the port"],
  ["Drag a port", "Drop on a card to wire it, or on empty board to add a drawer"],
  ["Right-click a wire", "Add a drawer on it, or delete it"],
  ["Middle-click a rate", "Clear the rate"],
  ["Right-click empty board", "Add a sticky note"],
  ["Click a sticky note", "Write on it (Esc or a click elsewhere when done); drag it anywhere to move it"],
  ["Click a card or drawer", "Select it (Shift-click adds to the selection)"],
  ["Drag the board", "Pan (Shift-drag selects a box)"],
  ["Drag a selected card", "Move the selection"],
  ["Ctrl+A", "Select everything"],
  ["Delete", "Delete the selection (Esc deselects)"],
  ["Wheel", WHEEL_ROW],
  ["WASD or arrows", "Pan (+/- or Page Up/Down zoom)"],
  ["Ctrl+C, Ctrl+X, Ctrl+V", "Copy, cut, paste the selection and its wires"],
  ["Ctrl+Z, Ctrl+Shift+Z", "Undo, redo"],
  ["Double-click an overview row", "Show the cards that make or use it"],
  // Not a gesture: the one place the board's in and out colours are said (free ends, forced rows, drawers, overview).
  ["Blue and orange", "Blue comes into the plan; orange leaves it"],
];

/** The controls list: a menu-skinned panel under the bar, closed like any dropdown. */
export function ControlsList({ onClose }: { onClose: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  const { scrollChangesValues } = useDeviceSettings();
  useDropdownDismiss(true, { refs: [ref], onClose, insideSelector: "[data-menu-anchor]" });
  useEffect(() => ref.current?.focus(), []);
  // Hung from the Help key's right edge, as its menu is: the items column sits at the window's right edge now, and a
  // panel pinned to the window would slide under it.
  // The panel wears the bar's extra zoom (chrome.css .mm): its offsets are in those pixels. It slides left when the
  // room right of the key's edge leaves it hanging off the window's left, and its rows scroll below the window's foot.
  const scale = getUiScale() * CHROME_SCREEN_K;
  const helpKey = typeof document === "undefined" ? null : document.querySelector(".mb-key[data-mb='help']");
  const right = helpKey ? Math.max(2, (window.innerWidth - helpKey.getBoundingClientRect().right) / scale) : 8;
  const slide = useSlideIntoWindow(ref, scale);
  return createPortal(
    <div
      ref={ref}
      role="dialog"
      aria-label="How the board works"
      tabIndex={-1}
      className={`mm mc-controls ${BOX_FIT_GUI} ui-zoom nodrag nowheel`}
      style={{ top: 24, right: right - slide, maxHeight: window.innerHeight / scale - 24 - 8 }}
      onPointerDown={(event) => event.stopPropagation()}
    >
      <div className="mm-title">HOW THE BOARD WORKS</div>
      <div className="mc-controls-grid mm-scroll">
        {CONTROLS.map(([gesture, effect]) => (
          <div key={gesture} className="mc-controls-row">
            <span className="mc-controls-key">{gesture}</span>
            <span className="mc-controls-effect">
              {effect === WHEEL_ROW && !scrollChangesValues ? "Zoom" : effect}
            </span>
          </div>
        ))}
      </div>
    </div>,
    document.body,
  );
}
