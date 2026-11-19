package com.gtnhplanner.core.power;

import javax.annotation.Nullable;

import com.gtnhplanner.machines.web.Tiers;

/**
 * The tier a power card's EU counts in under the Amps dial (power-recipe.ts powerCardTier): the lowest tier whose one
 * amp carries one machine's output (GTUtility.getTier), the smallest 1 A dynamo it fits. Null for a card that is not a
 * generator: a card with no power info, or one that makes no EU.
 */
public final class PowerCardTier {

    private PowerCardTier() {}

    /** @param euPerTick net EU/t of one machine, or null when the card has no power info (not a power card) */
    @Nullable
    public static String of(@Nullable final Double euPerTick) {
        if (euPerTick == null || !(euPerTick > 0)) return null;
        return Tiers.forEuT(euPerTick);
    }
}
