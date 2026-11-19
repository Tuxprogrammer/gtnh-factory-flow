// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { toSvg } from "html-to-image";
import { EXPORT_IMAGE_PLACEHOLDER } from "./export-image-placeholder";

// jsdom loads no images, so give the cloned <img> the browser's rule: a data URL
// with content loads, an empty source errors.
const realSrc = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, "src");

beforeEach(() => {
  Object.defineProperty(HTMLImageElement.prototype, "src", {
    configurable: true,
    get() {
      return this.getAttribute("src") ?? "";
    },
    set(value: string) {
      this.setAttribute("src", value);
      queueMicrotask(() => {
        if (value.startsWith("data:")) this.onload?.(new Event("load"));
        else this.onerror?.(new Event("error"));
      });
    },
  });
  vi.stubGlobal("SVGImageElement", class {});
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response("missing", { status: 404 })),
  );
  vi.spyOn(console, "warn").mockImplementation(() => undefined);
});

afterEach(() => {
  if (realSrc) Object.defineProperty(HTMLImageElement.prototype, "src", realSrc);
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function boardWithMissingIcon(url: string): HTMLElement {
  const board = document.createElement("div");
  const img = document.createElement("img");
  img.setAttribute("src", url);
  board.append(img);
  document.body.append(board);
  return board;
}

describe("export image placeholder", () => {
  it("is a data URL with content, so a missing picture loads as a blank", () => {
    expect(EXPORT_IMAGE_PLACEHOLDER).toMatch(/^data:image\/gif;base64,.{20,}/);
  });

  it("fails the export when a card image 404s and no placeholder is given", async () => {
    const board = boardWithMissingIcon("http://localhost/missing-a.png");
    const failure = await toSvg(board, { width: 10, height: 10, skipFonts: true }).catch((e) => e);
    // The library rejects with the image's error event, not an Error.
    expect(failure).toBeInstanceOf(Event);
  });

  it("still renders the board when a card image 404s and the placeholder is given", async () => {
    const board = boardWithMissingIcon("http://localhost/missing-b.png");
    const svg = await toSvg(board, {
      width: 10,
      height: 10,
      skipFonts: true,
      imagePlaceholder: EXPORT_IMAGE_PLACEHOLDER,
    });
    expect(svg.startsWith("data:image/svg+xml")).toBe(true);
    expect(decodeURIComponent(svg)).toContain(EXPORT_IMAGE_PLACEHOLDER);
  });
});
