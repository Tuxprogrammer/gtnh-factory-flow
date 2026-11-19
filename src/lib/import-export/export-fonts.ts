"use client";

import { getFontEmbedCSS } from "html-to-image";

/**
 * The @font-face CSS an image export needs, with the font files inlined as
 * data URLs; without it the cloned board renders in a generic fallback font.
 *
 * html-to-image's font scan walks every stylesheet and keeps only the faces
 * whose family the given element's subtree uses. The walk is the expensive
 * part, so the result is shared between captures, but only between captures
 * that draw the same fonts: the planner's font is a setting, and a result
 * scanned under Inter has no Minecraft face in it. Results are therefore kept
 * per font stack of the element (the stack every card and the footer inherit
 * from the app font), and a failed or empty scan is never kept, so the next
 * export scans again rather than rendering in the fallback font for the rest
 * of the session. Callers pass the CSS as `fontEmbedCSS` together with
 * `skipFonts` (the embed CSS takes precedence).
 */
const scans = new Map<string, Promise<string | undefined>>();

export function resolveExportFontCss(element: HTMLElement): Promise<string | undefined> {
  const stack = getComputedStyle(element).fontFamily;
  let scan = scans.get(stack);
  if (!scan) {
    scan = getFontEmbedCSS(element)
      .then((css) => css || undefined)
      .catch(() => undefined)
      .then((css) => {
        if (css === undefined) {
          scans.delete(stack);
        }
        return css;
      });
    scans.set(stack, scan);
  }
  return scan;
}
