/**
 * Turns the wheel's events into one step per notch. A high-resolution mouse (or a smooth-scrolling browser) sends
 * several wheel events for one click of the wheel, so a control that moves one choice per event runs several choices
 * on a single notch. The first event of a burst steps; the ones that follow within `WHEEL_NOTCH_GAP_MS` are the same
 * notch. A held spin still steps once per gap.
 */
export const WHEEL_NOTCH_GAP_MS = 60;

export type WheelNotch = (event: {
  deltaX: number;
  deltaY: number;
  timeStamp: number;
}) => 1 | -1 | 0;

/** One gate per control. Returns 1 (next), -1 (previous) or 0 (not a step: no movement, or the rest of a notch). */
export function createWheelNotch(gapMs: number = WHEEL_NOTCH_GAP_MS): WheelNotch {
  let last = -Infinity;
  return (event) => {
    const delta = event.deltaY || event.deltaX;
    if (!delta) {
      return 0;
    }
    if (event.timeStamp - last < gapMs) {
      return 0;
    }
    last = event.timeStamp;
    return delta > 0 ? 1 : -1;
  };
}
