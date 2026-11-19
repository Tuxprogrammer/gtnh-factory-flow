package com.gtnhplanner.core.layout;

/**
 * Every dial the grid router has, the website's {@code router-tuning.ts}: the router reads all its costs and limits
 * from one of these, never from constants, so a host can turn them per device. {@link #DEFAULT} is the shipped
 * behaviour. Costs are in pixels of travel on an empty lane. The Arrange group shapes Arrange only and is left out of
 * {@link #key()}, so turning one never re-routes a wire.
 */
public record RouterTuning(double costEmpty, double costShared, double costOverflow, double costInsideExempt,
    double costOutsideHome, double turn45, double turn90, double reverse, double earlyTurn, double cleanCells,
    double crossing, boolean diagonals, double diagonalDistanceCells, double dockTravelCells, double diagonalLength,
    double diagonalLaneCapacity, double dockPlanBias, double dockPlanWindow, double dockShare,
    double negotiationRounds, double negotiationBudget, boolean longestFirst, double windowPad, double wideRungCells,
    double islandAir, double arrangeCrossing, double searchTrials, double finalists, double polishBudget,
    double arrangeRowGap, double arrangeColumnGap, double arrangeDrawerGap) {

    /** {@code DEFAULT_ROUTER_TUNING}. A reversal is all but forbidden: only a pinned dot can force one. */
    public static final RouterTuning DEFAULT = new RouterTuning(
        1,
        1.3,
        6,
        3,
        3,
        35,
        80,
        100000,
        100,
        2,
        400,
        true,
        6,
        6,
        Math.sqrt(2),
        10,
        0.35,
        12,
        300,
        8,
        2,
        true,
        4,
        30,
        0.5,
        400,
        20000,
        6,
        100,
        1,
        2,
        1);

    /** These dials with the negotiation off: the rough solve while a card is in hand. */
    public RouterTuning withNegotiationRounds(final double rounds) {
        return new RouterTuning(
            costEmpty,
            costShared,
            costOverflow,
            costInsideExempt,
            costOutsideHome,
            turn45,
            turn90,
            reverse,
            earlyTurn,
            cleanCells,
            crossing,
            diagonals,
            diagonalDistanceCells,
            dockTravelCells,
            diagonalLength,
            diagonalLaneCapacity,
            dockPlanBias,
            dockPlanWindow,
            dockShare,
            rounds,
            negotiationBudget,
            longestFirst,
            windowPad,
            wideRungCells,
            islandAir,
            arrangeCrossing,
            searchTrials,
            finalists,
            polishBudget,
            arrangeRowGap,
            arrangeColumnGap,
            arrangeDrawerGap);
    }

    /** These dials with another crossing price: the arrange prices a crossing at {@code arrangeCrossing}. */
    public RouterTuning withCrossing(final double price) {
        return new RouterTuning(
            costEmpty,
            costShared,
            costOverflow,
            costInsideExempt,
            costOutsideHome,
            turn45,
            turn90,
            reverse,
            earlyTurn,
            cleanCells,
            price,
            diagonals,
            diagonalDistanceCells,
            dockTravelCells,
            diagonalLength,
            diagonalLaneCapacity,
            dockPlanBias,
            dockPlanWindow,
            dockShare,
            negotiationRounds,
            negotiationBudget,
            longestFirst,
            windowPad,
            wideRungCells,
            islandAir,
            arrangeCrossing,
            searchTrials,
            finalists,
            polishBudget,
            arrangeRowGap,
            arrangeColumnGap,
            arrangeDrawerGap);
    }

    /** The judge's quick dials (route-judge.ts): a short negotiation and a narrower wide rung. */
    public RouterTuning quickJudge() {
        return new RouterTuning(
            costEmpty,
            costShared,
            costOverflow,
            costInsideExempt,
            costOutsideHome,
            turn45,
            turn90,
            reverse,
            earlyTurn,
            cleanCells,
            crossing,
            diagonals,
            diagonalDistanceCells,
            dockTravelCells,
            diagonalLength,
            diagonalLaneCapacity,
            dockPlanBias,
            dockPlanWindow,
            dockShare,
            1,
            0.5,
            longestFirst,
            windowPad,
            Math.min(wideRungCells, 12),
            islandAir,
            arrangeCrossing,
            searchTrials,
            finalists,
            polishBudget,
            arrangeRowGap,
            arrangeColumnGap,
            arrangeDrawerGap);
    }

    /**
     * {@code routerTuningKey}: text that changes whenever a routing dial does, the dials in the dev menu's order, as
     * JavaScript writes them, so a key stored by either side compares equal.
     */
    public String key() {
        final StringBuilder s = new StringBuilder(160);
        final double[] numbers = { costEmpty, costShared, costOverflow, costInsideExempt, costOutsideHome, turn45,
            turn90, reverse, earlyTurn, cleanCells };
        for (final double n : numbers) s.append(JsMath.text(n))
            .append(',');
        s.append(diagonals)
            .append(',');
        final double[] more = { diagonalLength, diagonalDistanceCells, dockTravelCells, crossing, negotiationRounds,
            negotiationBudget };
        for (final double n : more) s.append(JsMath.text(n))
            .append(',');
        s.append(longestFirst)
            .append(',');
        final double[] rest = { dockPlanBias, dockShare, dockPlanWindow, diagonalLaneCapacity, wideRungCells,
            windowPad };
        for (int i = 0; i < rest.length; i++) {
            if (i > 0) s.append(',');
            s.append(JsMath.text(rest[i]));
        }
        return s.toString();
    }
}
