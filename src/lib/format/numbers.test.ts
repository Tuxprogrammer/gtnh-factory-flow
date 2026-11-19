import { describe, expect, it } from "vitest";
import { formatMachineListCount, formatRate, trimTrailingDecimalZeros } from "./numbers";

describe("number text", () => {
  it("trims only decimal zeros from formatted numbers", () => {
    expect(trimTrailingDecimalZeros("90.0")).toBe("90");
    expect(trimTrailingDecimalZeros("500")).toBe("500");
    expect(trimTrailingDecimalZeros("11.50")).toBe("11.5");
    expect(trimTrailingDecimalZeros("1.25")).toBe("1.25");
  });

  it("formats large rates with compact thousands separators", () => {
    expect(formatRate(125829120, 0)).toBe("125,829,120");
    expect(formatRate(3040.5, 1)).toBe("3,041");
    expect(formatRate(77.123, 1)).toBe("77.1");
  });

  it("keeps fractional counts above 100 and never displays a positive sliver as zero", () => {
    expect(formatMachineListCount(101.375)).toBe("101.375");
    expect(formatMachineListCount(0.00001)).toBe("0.00001");
    expect(formatMachineListCount(0.0000001)).toBe("<0.000001");
    expect(formatMachineListCount(0)).toBe("0");
  });
});
