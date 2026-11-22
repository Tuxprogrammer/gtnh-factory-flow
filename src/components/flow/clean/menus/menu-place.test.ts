import { expect, it } from "vitest";
import { cardHostsMenu, placeMenu } from "./menu-place";

const H = 1000;

it("hangs under an anchor that is fully on screen, as before", () => {
  expect(placeMenu({ top: 100, bottom: 130 }, H, 1, 400)).toEqual({ below: true, top: 132, maxHeight: 400 });
});

it("opens above an anchor low in the window", () => {
  expect(placeMenu({ top: 900, bottom: 930 }, H, 1, 400)).toEqual({ below: false, bottom: 102, maxHeight: 400 });
});

it("opens above when the anchor's bottom edge is below the window", () => {
  const place = placeMenu({ top: 600, bottom: 4000 }, H, 1, 400);
  expect(place).toEqual({ below: false, bottom: 402, maxHeight: 400 });
});

it("opens below when the anchor's top edge is above the window", () => {
  const place = placeMenu({ top: -3000, bottom: 200 }, H, 1, 400);
  expect(place).toEqual({ below: true, top: 202, maxHeight: 400 });
});

it("pins the menu inside the window when the anchor fills it", () => {
  expect(placeMenu({ top: -2000, bottom: 4000 }, H, 1, 400)).toEqual({ below: true, top: 12, maxHeight: 400 });
  expect(placeMenu({ top: -2000, bottom: 4000 }, 300, 1, 400)).toEqual({ below: true, top: 12, maxHeight: 276 });
});

it("counts the interface scale: space is shell px", () => {
  const place = placeMenu({ top: 100, bottom: 130 }, H, 2, 600);
  expect(place).toEqual({ below: true, top: 67, maxHeight: Math.min(600, (H - 130) / 2 - 12) });
});

it("lets a card host its menu only when the menu has room above or below it", () => {
  expect(cardHostsMenu({ top: 100, bottom: 500 }, H, 1)).toBe(true);
  expect(cardHostsMenu({ top: 400, bottom: 980 }, H, 1)).toBe(true);
  expect(cardHostsMenu({ top: -3000, bottom: 700 }, H, 1)).toBe(true);
  expect(cardHostsMenu({ top: 120, bottom: 4190 }, H, 1)).toBe(false);
  expect(cardHostsMenu({ top: -1455, bottom: 2615 }, H, 1)).toBe(false);
  expect(cardHostsMenu({ top: -3030, bottom: 1040 }, H, 1)).toBe(false);
});
