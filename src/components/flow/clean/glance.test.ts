import { describe, expect, it } from "vitest";
import { glanceBar, glanceFace, glanceProduct } from "./glance";

describe("the zoomed-out bar", () => {
  it("fills to what runs over the count, in the reason's tone with its word", () => {
    expect(glanceBar({ capacity: 24, reached: 18, reason: "STARVED" })).toEqual({
      tone: "starved",
      fill: 0.75,
      word: "STARVED",
    });
    // A bottleneck's runs to what the line wants, as the card's own bar.
    expect(glanceBar({ capacity: 24, reached: 24, reason: "BOTTLENECK", wants: 32 })).toEqual({
      tone: "bottleneck",
      fill: 0.75,
      word: "BOTTLENECK",
    });
    expect(glanceBar({ capacity: 6, reached: 3, reason: null })).toEqual({
      tone: "plain",
      fill: 0.5,
      word: "",
    });
    expect(glanceBar({ capacity: 0, reached: 0, reason: "UNUSED" })!.fill).toBe(0);
    expect(glanceBar(undefined)).toBeUndefined();
  });

  it("says a ring's name in its tone on a card in a ring", () => {
    expect(glanceBar({ capacity: 2, reached: 0, reason: "STARVED" }, "dead")).toEqual({
      tone: "starved",
      fill: 0,
      word: "DEAD LOOP",
    });
    expect(glanceBar({ capacity: 2, reached: 0, reason: "UNUSED" }, "clog")).toEqual({
      tone: "clogged",
      fill: 0,
      word: "CLOG LOCK",
    });
  });

  it("is two background layers over the picture, the word the layer's own text", () => {
    const face = glanceFace("url(/art.png)", { tone: "clogged", fill: 0.5, word: "CLOGGED" });
    expect(face["data-bar"]).toBe("");
    expect(face["data-tone"]).toBe("clogged");
    expect(face.text).toBe("CLOGGED");
    const style = face.style as Record<string, string>;
    expect(style["--glance-fill"]).toBe("50%");
    // The picture stays a url the minimap can read back.
    expect(style.backgroundImage.split(", ").at(-1)).toBe("url(/art.png)");
    expect(glanceFace("url(/art.png)", undefined)).toEqual({
      style: { backgroundImage: "url(/art.png)" },
      text: "",
    });
    expect(glanceFace(undefined, undefined).style).toBeUndefined();
  });

  it("carries a structure render's width over height, so a tall card keeps it whole", () => {
    const bar = { tone: "clogged" as const, fill: 0.5, word: "CLOGGED" };
    const reactor = glanceFace("url(/power-art/large-chemical-reactor.png)", bar).style as Record<
      string,
      string
    >;
    expect(Number(reactor["--glance-art-ratio"])).toBeCloseTo(4 / 3, 2);
    // An icon is square: the stylesheet's default.
    const icon = glanceFace("url(/icons/x.png)", bar).style as Record<string, string>;
    expect(icon["--glance-art-ratio"]).toBeUndefined();
  });

  it("shows what the card makes in its corner: the first output's icon, on top of the picture and the bar", () => {
    const acid = glanceProduct([
      { kind: "fluid", iconPath: "/t/nitric_acid.png" },
      { kind: "fluid", iconPath: "/t/water.png" },
    ])!;
    expect(acid).toEqual({ image: "url(/t/nitric_acid.png)", fluid: true });
    const plain = glanceFace("url(/art.png)", undefined, acid);
    expect(plain["data-product"]).toBe("fluid");
    expect(plain.style).toEqual({ backgroundImage: "url(/t/nitric_acid.png), url(/art.png)" });
    const dust = glanceProduct([{ kind: "item", iconPath: "/t/dust.png" }])!;
    const barred = glanceFace("url(/art.png)", { tone: "full", fill: 1, word: "FULL" }, dust);
    expect(barred["data-product"]).toBe("item");
    const layers = (barred.style as Record<string, string>).backgroundImage.split(", ");
    // The product on top, the picture still last (the minimap reads it from there).
    expect(layers[0]).toBe("url(/t/dust.png)");
    expect(layers.at(-1)).toBe("url(/art.png)");
    // Power, or a first output with no picture, shows nothing; a card with none keeps its old face.
    expect(glanceProduct([{ kind: "power" }])).toBeUndefined();
    expect(
      glanceProduct([{ kind: "item" }, { kind: "item", iconPath: "/t/byproduct.png" }]),
    ).toBeUndefined();
    expect(glanceProduct(undefined)).toBeUndefined();
    expect(glanceFace("url(/art.png)", undefined, undefined)).toEqual({
      style: { backgroundImage: "url(/art.png)" },
      text: "",
    });
  });
});
