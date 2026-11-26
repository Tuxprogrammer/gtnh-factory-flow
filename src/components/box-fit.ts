"use client";

import { useLayoutEffect, useState, type RefObject } from "react";

/**
 * Class names every box wears (globals.css): `.box-fit` caps it to the window, `.box-gui` says it is drawn at the
 * game GUI's zoom on top of the interface's (the mod-skinned bar, menus and panels). A box that scrolls puts its
 * scrolling part in `.box-body`. The guard (`box-fit.test.ts`) fails a new box that wears none of this.
 */
export const BOX_FIT = "box-fit";
export const BOX_FIT_GUI = "box-fit box-gui";

/**
 * How far (in the box's own px) a box hung from a key must slide sideways to sit inside the window, `margin` of its
 * own px from each edge. Its width is known only once drawn, so it is measured as it opens, before it paints, and
 * again only when `content` changes (one menu turning into another in place, as a tab's menu into its delete
 * question): a box closes on a scroll or a camera move, so its anchor never moves under it, and nothing here runs per
 * frame. `scale` is the box's zoom (real px per box px).
 */
export function useSlideIntoWindow(
  ref: RefObject<HTMLElement | null>,
  scale: number,
  margin = 2,
  content: string = "",
): number {
  const [slide, setSlide] = useState(0);
  useLayoutEffect(() => {
    const box = ref.current?.getBoundingClientRect();
    if (!box) return;
    const edge = margin * scale;
    // Measured where it stands, with this render's slide in it: the change adds to that slide (a fixed sum, so a
    // second run of the effect, as React's strict mode makes, lands in the same place).
    let by = 0;
    if (box.left < edge) {
      by = (edge - box.left) / scale;
    } else if (box.right > window.innerWidth - edge) {
      by = -Math.min(box.right - (window.innerWidth - edge), box.left - edge) / scale;
    }
    if (by) setSlide(slide + by);
    // Measured on open and when the content changes (see above).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [content]);
  return slide;
}
