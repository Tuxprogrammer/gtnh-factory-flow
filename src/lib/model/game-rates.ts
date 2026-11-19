import type { GameRates } from "../datasets/types";

/**
 * The pack's rate-mod factors for the dataset in use, each 1 when the dataset carries no record (a pack with no rate
 * mod, or a dataset exported before the record existed). The factors come from the dataset's manifest entry and
 * never from constants here: a different pack config reads through the same code.
 */
export interface ActiveGameRates {
  beesYield: number;
  ic2CropsYield: number;
  recipeDurationDivisor: number;
}

const NONE: ActiveGameRates = { beesYield: 1, ic2CropsYield: 1, recipeDurationDivisor: 1 };

let active: ActiveGameRates = NONE;

function factor(value: number | undefined): number {
  // A record that is wrong (zero, negative, not a number) must not zero a card out: it reads as no rate mod.
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : 1;
}

/** Normalises a dataset's record; the one place a missing or invalid factor becomes 1. */
export function resolveGameRates(rates: GameRates | undefined): ActiveGameRates {
  return {
    beesYield: factor(rates?.beesYield),
    ic2CropsYield: factor(rates?.ic2CropsYield),
    recipeDurationDivisor: factor(rates?.recipeDurationDivisor),
  };
}

/** Read by the solver and the machine math. The store sets it whenever a dataset is set or cleared. */
export function getGameRates(): ActiveGameRates {
  return active;
}

export function setActiveGameRates(rates: GameRates | undefined): void {
  active = resolveGameRates(rates);
}
