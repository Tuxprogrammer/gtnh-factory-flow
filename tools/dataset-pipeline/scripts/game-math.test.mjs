import { describe, expect, it } from "vitest";
import { gtPowInt } from "./game-math.mjs";

describe("gtPowInt (GregTech's GTUtility.powInt)", () => {
  it("multiplies by squaring, as the game does", () => {
    // The game's doubles (Java GTUtility.powInt), not the exact decimals 0.92236816 and 0.8170728068875469.
    expect(gtPowInt(0.98, 4)).toBe(0.9223681599999999);
    expect(gtPowInt(0.98, 10)).toBe(0.8170728068875466);
    expect(gtPowInt(1.1, 4)).toBe(1.4641000000000004);
  });

  it("is exact for powers of two and handles 0 and negative exponents", () => {
    expect(gtPowInt(2, 10)).toBe(1024);
    expect(gtPowInt(4, 15)).toBe(2 ** 30);
    expect(gtPowInt(2, 1024)).toBe(Infinity);
    expect(gtPowInt(0.5, 0)).toBe(1);
    expect(gtPowInt(2, -2)).toBe(0.25);
  });

  it("refuses a fractional exponent", () => {
    expect(() => gtPowInt(2, 0.5)).toThrow();
  });
});
