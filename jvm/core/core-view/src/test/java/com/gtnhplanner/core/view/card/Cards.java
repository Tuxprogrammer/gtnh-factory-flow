package com.gtnhplanner.core.view.card;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.core.view.TextMeasure;
import com.gtnhplanner.core.view.TextStyle;

/** Card models for the tests, with no game: a recipe card of so many ports, a power card. */
public final class Cards {

    /** Six board pixels a character in every style, as the game font's common glyph: widths a test can add up. */
    public static final TextMeasure SIX = (style, text) -> 6.0 * text.length() * (style == TextStyle.ROW_FIGURE ? 1.5 : 1);

    private Cards() {}

    public static CardModel.Port port(final int index, final boolean output, final double perSecond,
        final double extra) {
        return new CardModel.Port(
            index,
            output,
            "item:minecraft:iron_ingot:" + index,
            "Iron " + index,
            null,
            false,
            1,
            1,
            perSecond,
            true,
            extra,
            false,
            false,
            null);
    }

    public static List<CardModel.Port> ports(final int n, final boolean output, final int... withExtra) {
        final List<CardModel.Port> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            boolean extra = false;
            for (final int e : withExtra) extra |= e == i;
            out.add(port(i, output, 1, extra ? 0.5 : 0));
        }
        return out;
    }

    /** A recipe card drawing {@code euPerTick}, with its ports. */
    public static CardModel recipe(final List<CardModel.Port> inputs, final List<CardModel.Port> outputs,
        final double euPerTick) {
        return card(inputs, outputs, euPerTick, false, false, 0, 0, null);
    }

    public static CardModel card(final List<CardModel.Port> inputs, final List<CardModel.Port> outputs,
        final double euPerTick, final boolean crop, final boolean usesHeat, final int coilHeat, final int recipeHeat,
        @Nullable final CardModel.Power power) {
        return new CardModel(
            new Uuid(1, 2),
            "Test Machine",
            null,
            List.of(),
            true,
            false,
            crop,
            false,
            "LV",
            1,
            false,
            coilHeat,
            usesHeat,
            recipeHeat,
            1,
            inputs,
            outputs,
            20,
            euPerTick,
            1,
            false,
            null,
            null,
            0,
            null,
            power);
    }
}
