import type { MachineTier } from "@/lib/model/types";
import { TOKEN_TIERS } from "./clean/tokens.generated";

type VoltageTier = Exclude<MachineTier, "DEMO">;

/**
 * The game's own tier colours, transcribed from GTValues.TIER_COLORS (each an
 * EnumChatFormatting code): ULV red, LV dark green, MV gold, HV yellow, EV
 * dark gray, IV blue, LuV light purple, ZPM aqua - then the underlined ranks,
 * which REUSE earlier colours and are told apart by the underline exactly as
 * the game does (UV is LV's green underlined, UMV is ULV's red, UXV is UHV's
 * dark red). Borders and shadows are the colour at 55%, text flips black on
 * the light backgrounds.
 */
export const GT_TIER_COLORS: Record<
  VoltageTier,
  { background: string; border: string; text: string; shadow: string; underline?: boolean }
> = Object.fromEntries(
  TOKEN_TIERS.map((t) => [
    t.name,
    {
      background: t.background,
      border: t.border,
      text: t.text,
      shadow: t.border,
      ...(t.underline ? { underline: true } : {}),
    },
  ]),
) as Record<VoltageTier, { background: string; border: string; text: string; shadow: string; underline?: boolean }>;
