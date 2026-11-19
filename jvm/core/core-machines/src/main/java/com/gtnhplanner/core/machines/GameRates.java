package com.gtnhplanner.core.machines;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The pack's rate-mod factors (GTNHRates), each 1 without the mod: what a Forestry bee housing's produce roll yields
 * ({@code beesYield}), what multiplies a Crop Manager's drop rounds ({@code ic2CropsYield}), and what divides the GT
 * furnaces' fixed smelt ({@code recipeDurationDivisor}, the mod's gtRecipesEnergyDiscount). The website's
 * src/lib/model/game-rates.ts.
 *
 * <p>
 * The host fills it in: the website from the dataset manifest's gameRates record, the mod from the rate mod's own
 * config as the game runs. The machine math reads {@link #active()}; nothing here holds a pack's numbers as constants.
 */
public record GameRates(double beesYield, double ic2CropsYield, double recipeDurationDivisor) {

    /** No rate mod: every factor 1. */
    public static final GameRates NONE = new GameRates(1, 1, 1);

    /**
     * resolveGameRates: a factor that is wrong (zero, negative, not a number) must not zero a card out, so it reads as
     * no rate mod.
     */
    public GameRates {
        beesYield = factor(beesYield);
        ic2CropsYield = factor(ic2CropsYield);
        recipeDurationDivisor = factor(recipeDurationDivisor);
    }

    private static double factor(final double value) {
        return Double.isFinite(value) && value > 0 ? value : 1;
    }

    private static volatile Supplier<GameRates> source = () -> NONE;

    /** getGameRates: the factors in force, read on every call (a host may change them while the game runs). */
    public static GameRates active() {
        final GameRates rates = source.get();
        return rates != null ? rates : NONE;
    }

    /** setActiveGameRates: fixed factors, as the website sets them when a dataset is chosen. */
    public static void install(final GameRates rates) {
        Objects.requireNonNull(rates, "rates");
        source = () -> rates;
    }

    /** A host that reads its factors live (the mod reads the rate mod's config); a null answer reads as no rate mod. */
    public static void install(final Supplier<GameRates> live) {
        source = Objects.requireNonNull(live, "live");
    }
}
