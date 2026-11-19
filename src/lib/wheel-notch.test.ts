import { describe, expect, it } from "vitest";
import { createWheelNotch } from "./wheel-notch";

const at = (timeStamp: number, deltaY: number, deltaX = 0) => ({ timeStamp, deltaY, deltaX });

describe("wheel notch", () => {
  it("steps once for a notch that arrives as five events", () => {
    const notch = createWheelNotch();
    const steps = [0, 2, 4, 6, 9].map((t) => notch(at(1000 + t, 24)));
    expect(steps).toEqual([1, 0, 0, 0, 0]);
  });

  it("steps again for the next notch", () => {
    const notch = createWheelNotch();
    expect(notch(at(1000, -100))).toBe(-1);
    expect(notch(at(1004, -100))).toBe(0);
    expect(notch(at(1200, -100))).toBe(-1);
  });

  it("steps once per gap under a held spin", () => {
    const notch = createWheelNotch(60);
    const steps = [0, 20, 40, 60, 80, 120].map((t) => notch(at(t, 10)));
    expect(steps).toEqual([1, 0, 0, 1, 0, 1]);
  });

  it("ignores events with no movement and does not let them eat a step", () => {
    const notch = createWheelNotch();
    expect(notch(at(1000, 0))).toBe(0);
    expect(notch(at(1001, 5))).toBe(1);
  });

  it("reads a sideways wheel", () => {
    const notch = createWheelNotch();
    expect(notch(at(1000, 0, -3))).toBe(-1);
  });
});
