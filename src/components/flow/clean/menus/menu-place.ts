import { getUiScale } from "@/lib/ui-scale";

/** A menu shorter than this (shell px) has no room on a side; below it on both sides it is pinned to the window. */
const MIN_ROOM = 60;
/** A card that leaves less than this (shell px) above or below for the menu does not host it. */
const CARD_ROOM = 200;
const GAP = 12;

/**
 * Where a menu goes against its anchor, all in real px except `scale` (shell px = real px / scale). The anchor is
 * clamped to the window first, so an edge that is off screen is never a side the menu opens toward; when neither
 * side of what is visible has room the menu sits at the top of the window instead of outside it.
 */
export function placeMenu(
  anchor: { top: number; bottom: number },
  windowHeight: number,
  scale: number,
  maxHeight: number,
): { below: true; top: number; maxHeight: number } | { below: false; bottom: number; maxHeight: number } {
  const shell = (px: number) => px / scale;
  const top = Math.max(anchor.top, 0);
  const bottom = Math.min(anchor.bottom, windowHeight);
  const spaceBelow = shell(windowHeight - bottom) - GAP;
  const spaceAbove = shell(top) - GAP;
  if (Math.max(spaceBelow, spaceAbove) < MIN_ROOM) {
    return { below: true, top: GAP, maxHeight: Math.min(maxHeight, shell(windowHeight) - 2 * GAP) };
  }
  const below = spaceBelow >= Math.min(maxHeight, 200) || spaceBelow >= spaceAbove;
  return below
    ? { below: true, top: shell(bottom) + 2, maxHeight: Math.min(maxHeight, spaceBelow) }
    : { below: false, bottom: shell(windowHeight - top) + 2, maxHeight: Math.min(maxHeight, spaceAbove) };
}

/**
 * Whether a card's own rectangle can be what its menu hangs from: there is room for the menu below it or above it on
 * screen. A card taller than the window has neither, and its menu hangs from the key that was pressed instead.
 */
export function cardHostsMenu(card: { top: number; bottom: number }, windowHeight: number, scale: number): boolean {
  return (windowHeight - card.bottom) / scale - GAP >= CARD_ROOM || card.top / scale - GAP >= CARD_ROOM;
}

/** The rectangle a card's menu hangs from: the card when it can host the menu, else the key that was pressed. */
export function menuHangRect(key: Element, card: Element | undefined): DOMRect {
  const cardRect = card?.getBoundingClientRect();
  return cardRect && cardHostsMenu(cardRect, window.innerHeight, getUiScale()) ? cardRect : key.getBoundingClientRect();
}
