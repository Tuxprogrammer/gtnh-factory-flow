/**
 * A board edit the store refuses says why, as a flash on the board's notices (`flashNotice`, as the board tells a
 * refused power wire). The store reports the refusal here and the notices bar (`NoticeBar.tsx`) registers itself as
 * the sink: the store cannot import the bar, which imports the store.
 */

/** A port takes one drawer: a second one, wired or dropped on it, is refused. */
export const PORT_HAS_DRAWER = "This port already has a drawer.";

let sink: ((text: string) => void) | undefined;

export function registerBoardRefusalSink(show: (text: string) => void): void {
  sink = show;
}

export function refuseBoardEdit(text: string): void {
  sink?.(text);
}
