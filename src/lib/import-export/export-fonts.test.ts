// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";

const getFontEmbedCSS = vi.fn<(element: HTMLElement) => Promise<string>>();
vi.mock("html-to-image", () => ({
  getFontEmbedCSS: (element: HTMLElement) => getFontEmbedCSS(element),
}));

import { resolveExportFontCss } from "./export-fonts";

// The cache is per module and per font stack, so each test draws its own stack.
function boardWith(fontFamily: string): HTMLElement {
  const element = document.createElement("div");
  element.style.fontFamily = fontFamily;
  document.body.append(element);
  return element;
}

describe("resolveExportFontCss", () => {
  beforeEach(() => {
    getFontEmbedCSS.mockReset();
  });

  it("scans once per font stack and shares the result between captures", async () => {
    getFontEmbedCSS.mockResolvedValue("@font-face{font-family:a}");
    const first = await resolveExportFontCss(boardWith("shared-a, monospace"));
    const second = await resolveExportFontCss(boardWith("shared-a, monospace"));
    expect(first).toBe("@font-face{font-family:a}");
    expect(second).toBe(first);
    expect(getFontEmbedCSS).toHaveBeenCalledTimes(1);
  });

  it("scans again when the planner's font changed since the last export", async () => {
    getFontEmbedCSS.mockImplementation(
      async (element) => `@font-face{font-family:${element.style.fontFamily}}`,
    );
    const inter = await resolveExportFontCss(boardWith("switch-inter, sans-serif"));
    const minecraft = await resolveExportFontCss(boardWith("switch-minecraft, monospace"));
    expect(inter).toContain("switch-inter");
    expect(minecraft).toContain("switch-minecraft");
    expect(getFontEmbedCSS).toHaveBeenCalledTimes(2);
  });

  it("retries after a failed scan instead of keeping the failure", async () => {
    getFontEmbedCSS
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValueOnce("@font-face{font-family:retry}");
    const board = boardWith("retry, monospace");
    expect(await resolveExportFontCss(board)).toBeUndefined();
    expect(await resolveExportFontCss(board)).toBe("@font-face{font-family:retry}");
  });

  it("does not keep an empty scan, which means the faces had not been declared yet", async () => {
    getFontEmbedCSS.mockResolvedValueOnce("").mockResolvedValueOnce("@font-face{font-family:late}");
    const board = boardWith("late, monospace");
    expect(await resolveExportFontCss(board)).toBeUndefined();
    expect(await resolveExportFontCss(board)).toBe("@font-face{font-family:late}");
  });
});
