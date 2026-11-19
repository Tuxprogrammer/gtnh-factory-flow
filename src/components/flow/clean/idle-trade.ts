import type { PlanLeftover } from "@/lib/solver/plan/types";

/**
 * Why a card on Any runs none while its input is there: the plan has to send something out, and it sends the card's
 * input out at its maker rather than run the card and send one of the card's own outputs out. The answer says so in
 * its leftovers (`PlanSolveExtras.leftovers`): one place of the leftover is an output of this card and carries none of
 * it, and the place that carries it is an output, on another card, of something this card takes in.
 */
export interface IdleTrade {
  /** `kind:id` of the card's output that running it would send out. */
  output: string;
  /** `kind:id` of the card's input that is sent out instead. */
  input: string;
  /** The card that sends it out. */
  makerId?: string;
}

/** A shared machine's section carries `#rN` on its id; the card is the id before it. */
const cardOf = (nodeId: string | undefined) => nodeId?.split("#r")[0];

/** Whether any leftover has an unused output place on this card: the cheap check the shell's layout key uses. */
export function mayIdleTrade(
  plan: { leftovers?: readonly PlanLeftover[] } | undefined,
  nodeId: string,
): boolean {
  return (plan?.leftovers ?? []).some((leftover) =>
    leftover.bearers.some((b) => b.side === "out" && b.share <= 0 && cardOf(b.nodeId) === nodeId),
  );
}

/**
 * The trade that keeps this card at zero, or nothing. `inputKeys` are the card's input rows (`kind:id`). Call it only
 * for a card on Any that runs none: a card that runs, or one with a count set, is not idle by this trade.
 */
export function idleTradeOf(
  plan: { leftovers?: readonly PlanLeftover[] } | undefined,
  nodeId: string,
  inputKeys: ReadonlySet<string>,
): IdleTrade | undefined {
  for (const leftover of plan?.leftovers ?? []) {
    const here = leftover.bearers.find(
      (b) => b.side === "out" && b.share <= 0 && b.resourceKey && cardOf(b.nodeId) === nodeId,
    );
    if (!here) continue;
    const borne = leftover.bearers.find(
      (b) =>
        b.side === "out" &&
        b.share > 0 &&
        b.resourceKey !== undefined &&
        inputKeys.has(b.resourceKey) &&
        cardOf(b.nodeId) !== nodeId,
    );
    if (borne)
      return {
        output: here.resourceKey!,
        input: borne.resourceKey!,
        makerId: cardOf(borne.nodeId),
      };
  }
  return undefined;
}
