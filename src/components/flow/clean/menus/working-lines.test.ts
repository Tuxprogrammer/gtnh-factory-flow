import { expect, it } from "vitest";
import { ticks } from "./working-lines";

it("writes a run's ticks whole, as 1/n under one, and plainly when the power supply stretches it", () => {
  expect(ticks(512)).toBe("512 t");
  expect(ticks(1)).toBe("1 t");
  expect(ticks(1 / 4)).toBe("1/4 t");
  expect(ticks(1 / 3)).toBe("1/3 t");
  // 64 parallels banked under one tick, the supply paying for 170 a tick: the run stretches to 64/170 of a tick.
  expect(ticks(64 / 170)).toBe("0.376 t");
});
