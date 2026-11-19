import { describe, expect, it } from "vitest";
import { CC } from "@/components/flow/clean/tokens";
import { GLANCE_TONES } from "./card-parts";

describe("the help sheet's colours", () => {
  it("draws inputs and outputs in the board's own blue and orange", () => {
    expect(GLANCE_TONES.need).toBe(CC.source);
    expect(GLANCE_TONES.output).toBe(CC.product);
  });
});
