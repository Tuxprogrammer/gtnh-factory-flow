// Records every card's facts the website's own tests hand `cleanCardView` (through `useCleanCardModel`), for the card
// shadow (src/components/flow/clean/card-shadow.test.ts): the compiled core must make each card as the website does.
// Run through vitest.config.ts beside this file; each test worker appends its lines to CARD_INPUTS_OUT.
import { appendFileSync } from "node:fs";
import { afterAll } from "vitest";
import { setCardFactsListener } from "@/components/flow/clean/clean-model";
import { encodeLine, recordCard } from "@/components/flow/clean/card-shadow";

const lines = new Set<string>();

setCardFactsListener((facts) => {
  try {
    lines.add(encodeLine(recordCard(facts)));
  } catch {
    // A cyclic or otherwise unprintable card is not one the core could be handed either.
  }
});

afterAll(() => {
  setCardFactsListener(undefined);
  const out = process.env.CARD_INPUTS_OUT;
  if (out && lines.size) appendFileSync(out, [...lines].join("\n") + "\n");
});
