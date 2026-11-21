import { afterEach, beforeAll, expect, it } from "vitest";
import { formatPowerValue } from "@/lib/format/numbers";
import {
  powerDisplayFromEuT,
  powerDisplaySuffix,
  setActivePowerDisplayUnit,
} from "@/lib/format/rate-unit";
import { loadInput } from "@/lib/solver/plan/__fixtures__/load";
import { CORE_LOAD_MS, coreSolve, useCoreSolver } from "@/test-support/core-solve";

/**
 * A solved count is handed out as the nearest double to the ratio it is (core-base
 * SolvedNumber, read once where a solve's answer leaves the solver), so a figure exactly on a
 * display half rounds up as it should. The corpus's alumina line runs one 4320 EU/t IV machine
 * 32/45 of the time: 0.375 A IV, which prints 0.38. As the LP engine left the count
 * (0.7111111111111084) the card printed 0.37, the way the titanium line's 0.625 A HV printed
 * 0.62. The mod's side of the same case is core-solver's SolvedDigitsTest.
 */
beforeAll(() => useCoreSolver(), CORE_LOAD_MS);
afterEach(() => setActivePowerDisplayUnit("eu"));

const MACHINE = "e51d7803-7212-425e-a6a8-bee5f94a0bc5";

it("the alumina line's 32/45 IV machine draws 0.38 A IV, not 0.37", () => {
  const answer = coreSolve(loadInput("alumina-line-shared-reactor"));
  if (answer.kind !== "solved") throw new Error("the alumina line did not solve");
  const count = answer.solution.machineCounts[MACHINE]!;
  expect(count).toBe(32 / 45);
  setActivePowerDisplayUnit("amps");
  const amps = formatPowerValue(powerDisplayFromEuT(4320 * count, "IV"));
  expect(`${amps} ${powerDisplaySuffix("IV")}`).toBe("0.38 A IV");
  expect(formatPowerValue(powerDisplayFromEuT(4320 * 0.7111111111111084, "IV"))).toBe("0.37");
});

it("the titanium line's 2/3 HV reactor reads 0.63 A HV from the cleaned count", () => {
  setActivePowerDisplayUnit("amps");
  expect(formatPowerValue(powerDisplayFromEuT(480 * (2 / 3), "HV"))).toBe("0.63");
  expect(formatPowerValue(powerDisplayFromEuT(480 * 0.66666666666666496, "HV"))).toBe("0.62");
});
