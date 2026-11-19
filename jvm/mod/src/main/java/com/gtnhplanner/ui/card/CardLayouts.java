package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.ui.theme.HybMeasure;

/**
 * The mod's way to core-view's {@link CardLayout}: a card's models as the core's, the settings the plan pins to it, and
 * the game font's widths.
 */
public final class CardLayouts {

    private CardLayouts() {}

    public static CardLayout of(final CardModel model, final Graph plan) {
        return of(List.of(model), plan, false);
    }

    public static CardLayout of(final List<CardModel> models, final Graph plan) {
        return of(models, plan, false);
    }

    /** A card for its recipes, top first; {@code foot}: the card has a bar along its foot. */
    public static CardLayout of(final List<CardModel> models, final Graph plan, final boolean foot) {
        final List<com.gtnhplanner.core.view.card.CardModel> cores = new ArrayList<>(models.size());
        for (final CardModel m : models) cores.add(m.core);
        return new CardLayout(
            cores,
            CardSettings.pinned(plan, models.get(0)),
            SettingPins.version(),
            foot,
            HybMeasure.INSTANCE);
    }
}
