package com.gtnhplanner.core.machines;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The pack's rate factors as game-rates.ts resolves them: a missing or broken factor reads as no rate mod. */
class GameRatesTest {

    @AfterEach
    void reset() {
        GameRates.install(GameRates.NONE);
    }

    @Test
    void aWrongFactorReadsAsOne() {
        final GameRates r = new GameRates(0, -4, Double.NaN);
        assertEquals(GameRates.NONE, r);
        assertEquals(new GameRates(4, 1, 64), new GameRates(4, Double.POSITIVE_INFINITY, 64));
    }

    @Test
    void withNothingInstalledEveryFactorIsOne() {
        assertEquals(GameRates.NONE, GameRates.active());
    }

    @Test
    void aHostCanSetFixedOrLiveFactors() {
        GameRates.install(new GameRates(4, 4, 64));
        assertEquals(64, GameRates.active()
            .recipeDurationDivisor());
        final double[] divisor = { 8 };
        GameRates.install(() -> new GameRates(1, 1, divisor[0]));
        assertEquals(8, GameRates.active()
            .recipeDurationDivisor());
        divisor[0] = 16;
        assertEquals(16, GameRates.active()
            .recipeDurationDivisor());
        GameRates.install(() -> null);
        assertEquals(GameRates.NONE, GameRates.active());
    }
}
