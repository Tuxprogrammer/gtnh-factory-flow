import { GT_TIER_COLORS } from "../tier-colors";
import { contrastInk, mixHex } from "./colors";
import { TOKEN_COLORS, TOKEN_GEOMETRY, TOKEN_KIND_MIXES, TOKEN_PULSE } from "./tokens.generated";

/**
 * Tokens of the clean look, as TypeScript for code that needs a value (a mix,
 * a measurement) and as the same numbers in `clean.css` for everything else.
 * The values are generated from `jvm/core/core-view/tokens/tokens.json`, the one source the mod reads too.
 * Other clean-look packages read this file; the card package owns it.
 */
export const CC = TOKEN_COLORS.palette;

/**
 * Geometry in CSS px at zoom 1: the mod's card exactly, 320 wide (16 cells), 112 rails, 32 icons; rows stay two
 * cells. The values are tokens.json's (the website's where the hosts differ by design).
 */
const CARD = TOKEN_GEOMETRY.card;
export const CC_GEOMETRY = {
  cardWidth: CARD.CARD_WIDTH,
  cell: TOKEN_GEOMETRY.grid.BOARD_GRID,
  headHeight: CARD.HEAD_HEIGHT_WEB,
  rowHeight: CARD.PORT_ROW_HEIGHT_WEB,
  railWidth: CARD.RAIL_WIDTH,
  railInset: CARD.RAIL_INSET,
  pictureWidth: CARD.PICTURE_WIDTH,
  pictureMinHeight: CARD.PICTURE_MIN_HEIGHT_WEB,
  pictureArtMax: CARD.PICTURE_ART_MAX_WEB,
  iconSize: CARD.ICON_SIZE,
  iconInset: CARD.ICON_INSET_WEB,
  wordsX: CARD.WORDS_X_WEB,
  wordsWidth: CARD.WORDS_WIDTH_WEB,
  keySize: CARD.KEY_SIZE_WEB,
  chipWidth: CARD.CHIP_WIDTH_WEB,
  chipHeight: CARD.HEAD_CHIP_HEIGHT,
  stripGap: CARD.STRIP_GAP_WEB,
  stripPad: CARD.STRIP_PAD_WEB,
  stripChipHeight: CARD.SETTINGS_CHIP_HEIGHT,
  stripPitch: CARD.SETTINGS_ROW_PITCH_WEB,
  stripChipGap: CARD.STRIP_CHIP_GAP_WEB,
  stripInset: CARD.STRIP_INSET_WEB,
} as const;

/** Mod pulse periods (seconds). */
export const CC_PULSE = {
  glow: TOKEN_PULSE.GLOW,
  accept: TOKEN_PULSE.ACCEPT,
  prompt: TOKEN_PULSE.PROMPT,
} as const;

export interface TierChipColors {
  background: string;
  border: string;
  ink: string;
  hover: string;
  pressed: string;
  underline: boolean;
}

/**
 * A flat tier chip's colours: the tier's background and border, a label by
 * luminance (black on LuV, as the mod has it), hover 88% bg + 12% white,
 * pressed 92% bg + 8% black.
 */
export function tierChipColors(tier: string): TierChipColors | undefined {
  const entry = (GT_TIER_COLORS as Record<string, (typeof GT_TIER_COLORS)[keyof typeof GT_TIER_COLORS]>)[tier];
  if (!entry) return undefined;
  return {
    background: entry.background,
    border: entry.border,
    ink: contrastInk(entry.background),
    hover: mixHex(entry.background, "#FFFFFF", 0.88),
    pressed: mixHex(entry.background, "#000000", 0.92),
    underline: entry.underline === true,
  };
}

/** The mixes drawers and forced rows share (spec section 1, computed not typed). */
export type DrawerKind = "source" | "product" | "trash" | "buffer";

export function drawerMixes(kind: DrawerKind) {
  return TOKEN_KIND_MIXES[kind];
}
