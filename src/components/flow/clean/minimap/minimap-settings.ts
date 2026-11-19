"use client";

import { useSyncExternalStore } from "react";
import { VIEW_SETTINGS } from "@/lib/view-settings.generated";

/**
 * The minimap's settings, the mod's: show, size, shape, corner, and whether it follows the card you select. Its
 * zoom is not a setting: the map always fits the whole plan. No see-through of its own: the map is see-through enough
 * (the owner, 2026-10-14). Kept in the browser under `boardMinimap`; the gear on the map and the settings dialog's
 * rows both write through `setMinimap`.
 */

export const MINIMAP_KEY: string = VIEW_SETTINGS.minimapShow.key;

export type MinimapSize = "small" | "medium" | "large" | "huge";
export type MinimapShape = "square" | "circle";
export type MinimapCorner = "top-left" | "top-right" | "bottom-left" | "bottom-right";

export interface MinimapSettings {
  show: boolean;
  size: MinimapSize;
  shape: MinimapShape;
  corner: MinimapCorner;
  /** Centre on the selected card when there is one (the mod's "Centre on the machine you look at"). */
  follow: boolean;
}

const SIZE = VIEW_SETTINGS.minimapSize;
const CORNER = VIEW_SETTINGS.minimapCorner;

/** The sizes in GUI px as the mod has them, scaled for the page (settings.json). */
export const MINIMAP_SIZES = Object.fromEntries(SIZE.ids.map((id, i) => [id, SIZE.values[i]])) as Record<
  MinimapSize,
  number
>;
export const MINIMAP_SIZE_LABELS = Object.fromEntries(SIZE.ids.map((id, i) => [id, SIZE.labels[i]])) as Record<
  MinimapSize,
  string
>;
export const MINIMAP_CORNER_LABELS = Object.fromEntries(
  CORNER.ids.map((id, i) => [id, CORNER.labels[i]]),
) as Record<MinimapCorner, string>;
export const DEFAULT_MINIMAP: MinimapSettings = {
  show: VIEW_SETTINGS.minimapShow.default,
  size: SIZE.default,
  shape: VIEW_SETTINGS.minimapCircle.default ? "circle" : "square",
  corner: CORNER.default,
  follow: VIEW_SETTINGS.minimapFollow.default,
};

const SIZES = Object.keys(MINIMAP_SIZES) as MinimapSize[];
const CORNERS = Object.keys(MINIMAP_CORNER_LABELS) as MinimapCorner[];

/** Anything stored, as settings: bad or missing parts fall back to the defaults. */
export function normalizeMinimap(raw: unknown): MinimapSettings {
  const r = (raw && typeof raw === "object" ? raw : {}) as Record<string, unknown>;
  const d = DEFAULT_MINIMAP;
  return {
    show: typeof r.show === "boolean" ? r.show : d.show,
    size: SIZES.includes(r.size as MinimapSize) ? (r.size as MinimapSize) : d.size,
    shape: r.shape === "circle" ? "circle" : "square",
    corner: CORNERS.includes(r.corner as MinimapCorner) ? (r.corner as MinimapCorner) : d.corner,
    follow: typeof r.follow === "boolean" ? r.follow : d.follow,
  };
}

let current: MinimapSettings | undefined;
const listeners = new Set<() => void>();

function read(): MinimapSettings {
  try {
    const text = window.localStorage.getItem(MINIMAP_KEY);
    return normalizeMinimap(text ? JSON.parse(text) : undefined);
  } catch {
    return DEFAULT_MINIMAP;
  }
}

export function getMinimap(): MinimapSettings {
  return (current ??= read());
}

export function setMinimap(patch: Partial<MinimapSettings>): void {
  const next = normalizeMinimap({ ...getMinimap(), ...patch });
  if (JSON.stringify(next) === JSON.stringify(getMinimap())) return;
  current = next;
  try {
    window.localStorage.setItem(MINIMAP_KEY, JSON.stringify(next));
  } catch {
    // Without storage the map still follows the setting until the page closes.
  }
  for (const listener of listeners) listener();
}

/** A copy of the current settings, to put back later (the tour borrows them). */
export function snapshotMinimap(): MinimapSettings {
  return { ...getMinimap() };
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  const onStorage = (event: StorageEvent) => {
    if (event.key === MINIMAP_KEY || event.key === null) {
      current = read();
      listener();
    }
  };
  window.addEventListener("storage", onStorage);
  return () => {
    listeners.delete(listener);
    window.removeEventListener("storage", onStorage);
  };
}

export function useMinimapSettings(): MinimapSettings {
  return useSyncExternalStore(subscribe, getMinimap, () => DEFAULT_MINIMAP);
}
