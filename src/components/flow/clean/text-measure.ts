/**
 * Text width as a pure function of (string, font): one canvas context for the
 * whole page and a cache, so a card never reads the DOM to size a word. Falls
 * back to a per-character estimate where there is no canvas (tests, server).
 */
import { useSyncExternalStore } from "react";

let context: CanvasRenderingContext2D | null | undefined;
const widths = new Map<string, number>();

const FALLBACK_EM = 0.56;

function canvas(): CanvasRenderingContext2D | null {
  if (context !== undefined) return context;
  try {
    context = typeof document === "undefined" ? null : document.createElement("canvas").getContext("2d");
  } catch {
    context = null;
  }
  return context;
}

/** The app's current face, read once per font change from the root's custom property. */
let appFamily: string | undefined;
export function resetMeasuredFont(): void {
  appFamily = undefined;
  widths.clear();
  fontEpoch += 1;
  for (const listener of fontListeners) listener();
}

let fontEpoch = 0;
const fontListeners = new Set<() => void>();

/** Counts font changes: a component that measures text in render calls this, so a new font re-measures it. */
export function useFontEpoch(): number {
  return useSyncExternalStore(
    (listener) => {
      fontListeners.add(listener);
      return () => fontListeners.delete(listener);
    },
    () => fontEpoch,
    () => 0,
  );
}

/** The epoch now, for a cache that holds something read from the font. */
export function currentFontEpoch(): number {
  return fontEpoch;
}

/**
 * The app's faces arrive after the page is drawn (next/font swaps each in once it loads), and a width measured before
 * then is the fallback's. When a face of the app's font lands, every width so far is dropped and measured again, so
 * chips and names are laid out in the face that is drawn. Once per face, never per frame.
 */
let watchingLoads = false;
function watchFontLoads(): void {
  if (watchingLoads || typeof document === "undefined" || !document.fonts?.addEventListener) return;
  watchingLoads = true;
  document.fonts.addEventListener("loadingdone", (event) => {
    if (widths.size === 0) return;
    const face = family();
    const loaded = (event as FontFaceSetLoadEvent).fontfaces ?? [];
    if (loaded.some((each) => face.includes(each.family.replace(/["']/g, "")))) resetMeasuredFont();
  });
}

function family(): string {
  if (appFamily) return appFamily;
  watchFontLoads();
  let value = "ui-sans-serif, system-ui, sans-serif";
  try {
    if (typeof document !== "undefined") {
      const computed = getComputedStyle(document.body).fontFamily;
      if (computed) value = computed;
    }
  } catch {
    // Keep the generic family.
  }
  appFamily = value;
  return value;
}

/** The clean card's face: the app's, which is Inter unless the font setting says otherwise. */
function cleanFace(): string {
  return family();
}

/**
 * A fixed measure in place of the canvas and the glyph table: the shared view cases' "six-px" (every character six px,
 * the row figure nine), which the core's card rules are held to with the same widths. Tests and the case generator only;
 * the app never sets it.
 */
export interface FixedTextMeasure {
  clean(text: string, size: number): number;
  figure(number: string, unit: string): number;
  name(name: string, small: boolean): number;
  game(text: string): number;
}

let fixedMeasure: FixedTextMeasure | undefined;

export function setFixedTextMeasure(measure: FixedTextMeasure | undefined): void {
  fixedMeasure = measure;
  // Every width may change: what was kept under the epoch (the card view's, the core's) is measured again.
  fontEpoch += 1;
}

/** The shared cases' measure: six px a character in every style, nine for the row figure, one px before the unit. */
export const SIX_PX_MEASURE: FixedTextMeasure = {
  clean: (text) => 6 * text.length,
  figure: (number, unit) => 9 * number.length + 1 + 6 * unit.length,
  name: (name) => 6 * name.length,
  game: (text) => 6 * text.length,
};

/** Width in CSS px of `text` at `size` px and `weight`, in the clean card's face. */
export function measureClean(text: string, size: number, weight = 400): number {
  if (fixedMeasure) return fixedMeasure.clean(text, size);
  const key = `c|${weight}|${size}|${text}`;
  const cached = widths.get(key);
  if (cached !== undefined) return cached;
  const ctx = canvas();
  let width: number;
  if (ctx) {
    ctx.font = `${weight} ${size}px ${cleanFace()}`;
    width = ctx.measureText(text).width;
  } else {
    width = text.length * size * 0.667;
  }
  widths.set(key, width);
  return width;
}

/**
 * A port row's words as clean.css and drawer.css draw them (.cc-num and .cd-num with the unit in their ::after,
 * .cc-pname and .cd-name, small with data-small), pinned to the stylesheets by text-measure.test.ts. Measured in
 * whatever face is drawn, a fit check against them never lets a word run past its box. Letter spacing follows every
 * character, the last one too, and the unit's box keeps its gap when it is empty.
 */
export const ROW_TYPE = {
  figure: { size: 14, spacing: -0.8 },
  unit: { size: 9, spacing: -0.6, gap: 1 },
  name: { size: 9, spacing: -0.6 },
  smallName: { size: 7, spacing: -0.45 },
} as const;

function drawnWidth(text: string, type: { size: number; spacing: number }): number {
  return text ? measureClean(text, type.size) + type.spacing * [...text].length : 0;
}

export function measureRowFigure(number: string, unit: string): number {
  if (fixedMeasure) return fixedMeasure.figure(number, unit);
  return drawnWidth(number, ROW_TYPE.figure) + ROW_TYPE.unit.gap + drawnWidth(unit, ROW_TYPE.unit);
}

export function measureRowName(name: string, small: boolean): number {
  if (fixedMeasure) return fixedMeasure.name(name, small);
  return drawnWidth(name, small ? ROW_TYPE.smallName : ROW_TYPE.name);
}

/** Width in CSS px of `text` at `size` px and `weight`, in the app's font. */
export function measureText(text: string, size: number, weight = 500): number {
  const key = `${weight}|${size}|${text}`;
  const cached = widths.get(key);
  if (cached !== undefined) return cached;
  const ctx = canvas();
  let width: number;
  if (ctx) {
    ctx.font = `${weight} ${size}px ${family()}`;
    width = ctx.measureText(text).width;
  } else {
    width = text.length * size * FALLBACK_EM;
  }
  widths.set(key, width);
  return width;
}

/**
 * A string's width in the game's own font at its one size (8 px), from Minecraft's glyph widths (each glyph's width
 * and a pixel after it). The mod decides by this whether a port's name stands at full size or drops to 0.75
 * (CardPaint.words), so the website takes the same decision whatever Monocraft's widths are.
 */
const MC_NARROW: Record<string, number> = {
  " ": 4, "!": 2, "'": 2, ",": 2, ".": 2, ":": 2, ";": 2, "|": 2, "i": 2, "`": 3, "l": 3,
  "\"": 4, "(": 4, ")": 4, "*": 4, "I": 4, "[": 4, "]": 4, "t": 4, "{": 4, "}": 4,
  "<": 5, ">": 5, "f": 5, "k": 5, "@": 7, "~": 7,
};
export function mcTextWidth(text: string): number {
  if (fixedMeasure) return fixedMeasure.game(text);
  let width = 0;
  for (const ch of text) width += MC_NARROW[ch] ?? 6;
  return width;
}
