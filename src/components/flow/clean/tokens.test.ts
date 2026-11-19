import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { mixHex } from "./colors";
import { CC, CC_GEOMETRY, drawerMixes } from "./tokens";
import { TOKEN_COLORS, TOKEN_GEOMETRY, TOKEN_KIND_MIXES, TOKEN_TIERS } from "./tokens.generated";
import { GT_TIER_COLORS } from "../tier-colors";

const dir = path.resolve(__dirname, "../../../../jvm/core/core-view/tokens");
const read = (name: string) => JSON.parse(readFileSync(path.join(dir, name), "utf8"));
const tokens = read("tokens.json");
const expected: Record<string, string> = read("mixes.expected.json");
const css = (file: string) => readFileSync(path.join(__dirname, file), "utf8").toLowerCase();

describe("shared view tokens", () => {
  it("generates the website's values from tokens.json", () => {
    expect(TOKEN_COLORS).toEqual(tokens.colors);
    expect(TOKEN_TIERS).toEqual(tokens.tiers);
    expect(CC.surface).toBe(tokens.colors.palette.surface);
  });

  it("mixes every kind the way the shared table says, with the runtime mixHex too", () => {
    const { kinds, mixes } = tokens.kindMixes;
    let checked = 0;
    for (const [kind, colour] of Object.entries<string>(kinds)) {
      const tint = tokens.colors.palette[colour];
      for (const [name, mix] of Object.entries<{ over: string; share: number }>(mixes)) {
        const key = `${kind}.${name}`;
        const want = expected[key];
        expect(mixHex(tint, tokens.colors.palette[mix.over], mix.share), key).toBe(want);
        expect((TOKEN_KIND_MIXES as Record<string, Record<string, string>>)[kind][name], key).toBe(want);
        checked++;
      }
    }
    expect(checked).toBe(Object.keys(expected).length);
    expect(drawerMixes("buffer").tint).toBe(CC.buffer);
  });

  it("draws the tiers from the shared table", () => {
    for (const t of TOKEN_TIERS) {
      const chip = GT_TIER_COLORS[t.name];
      expect(chip.background, t.name).toBe(t.background);
      expect(chip.border, t.name).toBe(t.border);
    }
  });

  it("keeps the website's own geometry where the hosts differ, and the shared values everywhere else", () => {
    expect(CC_GEOMETRY.cardWidth).toBe(TOKEN_GEOMETRY.card.CARD_WIDTH);
    expect(CC_GEOMETRY.railWidth).toBe(TOKEN_GEOMETRY.card.RAIL_WIDTH);
    expect(CC_GEOMETRY.cell).toBe(TOKEN_GEOMETRY.grid.BOARD_GRID);
    expect(CC_GEOMETRY.rowHeight).toBe(TOKEN_GEOMETRY.card.PORT_ROW_HEIGHT_WEB);
    expect(TOKEN_GEOMETRY.card.PORT_ROW_HEIGHT_MOD).not.toBe(TOKEN_GEOMETRY.card.PORT_ROW_HEIGHT_WEB);
  });

  it("matches the hand-kept lengths of clean.css", () => {
    const clean = css("clean.css");
    expect(clean).toContain(`width: ${TOKEN_GEOMETRY.card.CARD_WIDTH}px;`);
    expect(clean).toContain(
      `grid-template-columns: ${TOKEN_GEOMETRY.card.RAIL_WIDTH}px minmax(0, 1fr) ${TOKEN_GEOMETRY.card.RAIL_WIDTH}px;`,
    );
    const drawer = css("drawer/drawer.css");
    expect(drawer).toContain(`width: ${TOKEN_GEOMETRY.drawer.WIDTH}px`);
    expect(drawer).toContain(`height: ${TOKEN_GEOMETRY.drawer.HEIGHT}px`);
  });
});
