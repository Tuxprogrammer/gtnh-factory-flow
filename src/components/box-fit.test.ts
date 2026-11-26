import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

/**
 * No box runs off the window (src/components/CLAUDE.md, Boxes). A file that opens a box (a body portal, a dialog or
 * menu role, a fixed layer over the app) builds it from the shared rule: the `.box-fit` cap (globals.css, `BOX_FIT`),
 * one of the shells that wear it (ChromeMenu, MenuPortal, MenuShell, the `.pn` dialog), or a cap in the viewport units
 * of the interface (`--ui-vh` / `--ui-vw` / `--ui-dvh`). The files below place their boxes by code of their own and
 * were measured by the browser probe (tools/browser-tests/box-bounds.mjs) at small windows; each says why it is here.
 * A new box goes through the shared rule, or is measured and added here with its reason.
 */
const OWN_PLACEMENT: Record<string, string> = {
  "DesignTabs.tsx": "the classic tab menu, not on the desktop board; clamped to the window by its own code",
  "InspectorPanel.tsx": "a row's hover copy, the size of the row it copies",
  "PowerSourceOverlay.tsx": "covers the board's own box, whose size the layout sets",
  "RecipeSearchOverlay.tsx": "the recipe window caps itself to the board (900 x 800 with room round it); measured",
  "WhatsNewGate.tsx": "a one-line toast at the foot of the window",
  "browse-menu.tsx": "placed and clamped to the window by its own code; measured",
  "chrome/ModScreenshot.tsx": "sized by min() of the window in screenshot.css; measured",
  "community/AccountMenu.tsx": "a short dropdown under the account key and a small centred box",
  "flow/BoardContextMenu.tsx": "its menu is the board's MenuPortal (BoardMenu); the picker is clamped by its own code",
  "flow/BoardHelp.tsx": "the help sheet covers the window and lays its cards out to fit",
  "flow/MachinePicker.tsx": "flips and caps its height to the room on either side by its own code",
  "flow/MinecraftSelect.tsx": "a list under its control inside a card",
  "flow/RecipeListPicker.tsx": "flips and caps its height to the room on either side by its own code",
  "flow/SettingTile.tsx": "a short option list under its tile, at most 360 px",
  "flow/clean/feedback/FeedbackPanel.tsx": "a centred box capped to the window width in feedback.css; measured",
  "flow/clean/interaction/TipHost.tsx": "tooltips: flipped and clamped to the window by the tip host",
  "flow/clean/minimap/BoardMinimap.tsx": "the minimap, a fixed corner of the board",
  "flow/clean/minimap/MinimapSettingsPanel.tsx": "rows drawn inside the settings box",
  "flow/clean/rail/SparePopup.tsx": "a small popup beside an overview row",
  "flow/clean/rail/rail-tip.tsx": "tooltips: flipped and clamped to the window by its own code; measured",
  "flow/clean/tour/TourHost.tsx": "the tour's callouts, placed beside their targets by the tour",
  "flow/clean/tour/TourOffer.tsx": "a small offer card",
  "flow/rate-rule.tsx": "a four-row rule list under its button",
  "flow/virtual/flow-portals.tsx": "layers inside the board, not boxes",
  "library/LibraryPage.tsx": "a page that fills its column; its drag label is one line",
  "library/LibraryTile.tsx": "a one-row strip of keys over a tile",
  "library/library-menu.tsx": "a short menu, at most 340 px, under its key",
  "nei/GlobalTitleTooltip.tsx": "tooltips: flipped and clamped to the window by its own code",
  "nei/McTip.tsx": "tooltips: flipped and clamped to the window by its own code; measured",
  "nei/MinecraftTooltip.tsx": "tooltips: flipped and clamped to the window by its own code",
  "pool/PoolWorksheet.tsx": "the worksheet is a page over the board, not a box",
  "pool/worksheet-pointer-drag.tsx": "the drag preview, capped to the window width in pool-worksheet.css",
};

const OPENS_A_BOX = /createPortal\(|role="(?:dialog|menu|alertdialog|listbox)"|className=[^>]*\bfixed\b[^>]*z-\[/;
const SHARED_RULE =
  /box-fit|BOX_FIT|<ChromeMenu\b|<MenuPortal\b|<MenuShell\b|className="pn[ "]|--ui-vh|--ui-vw|--ui-dvh/;

const root = path.resolve(__dirname);
function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) return sources(full);
    return /\.tsx$/.test(name) && !/\.test\.tsx$/.test(name) ? [full] : [];
  });
}

describe("every box fits the window", () => {
  const files = sources(root).map((file) => ({
    rel: path.relative(root, file).split(path.sep).join("/"),
    text: readFileSync(file, "utf8"),
  }));

  it("builds each box from the shared rule, or names why its own placement is measured", () => {
    const missing = files
      .filter(({ rel, text }) => OPENS_A_BOX.test(text) && !SHARED_RULE.test(text) && !(rel in OWN_PLACEMENT))
      .map(({ rel }) => rel);
    expect(missing).toEqual([]);
  });

  it("keeps no stale entries in the list", () => {
    const byName = new Map(files.map((file) => [file.rel, file.text]));
    const stale = Object.keys(OWN_PLACEMENT).filter((rel) => {
      const text = byName.get(rel);
      return text === undefined || !OPENS_A_BOX.test(text) || SHARED_RULE.test(text);
    });
    expect(stale).toEqual([]);
  });

  it("fails a new overlay that skips the rule", () => {
    const fresh = `export const X = () => createPortal(<div className="fixed z-[90]">x</div>, document.body);`;
    expect(OPENS_A_BOX.test(fresh) && !SHARED_RULE.test(fresh)).toBe(true);
    const shelled = `export const X = () => createPortal(<div className="box-fit fixed z-[90]">x</div>, document.body);`;
    expect(OPENS_A_BOX.test(shelled) && !SHARED_RULE.test(shelled)).toBe(false);
  });
});
