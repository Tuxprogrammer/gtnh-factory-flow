package com.gtnhplanner.core.view.special;

import javax.annotation.Nullable;

import com.gtnhplanner.core.view.card.CardInput;

/**
 * What a special card shows beyond the recipe card it starts from, as plain data: the facts of its kind. The card's
 * own facts (its rows, count, read-outs) are its {@link CardInput}; {@link SpecialViews#special} reads both. A wire
 * record (the conventions of {@link CardInput}).
 *
 * @param kind   which special card it is
 * @param holder a custom rate card's or fluid tank's facts; null on any other kind
 */
public record SpecialInput(Kind kind, @Nullable Holder holder) {

    /**
     * The cards the plain card cannot be: a machine running several recipes, a generator, the two holders (a custom
     * rate card, a fluid tank) and a crop card. The website's {@code SpecialKind}.
     */
    public enum Kind {
        SHARED,
        POWER,
        CUSTOM_RATE,
        TANK,
        CROP
    }

    /**
     * A holder: it takes what is wired to it. {@code empty}: it holds nothing yet, and shows two sockets in place of its
     * rails. {@code dial}: a custom rate card's dial (null on a fluid tank).
     */
    public record Holder(boolean empty, @Nullable Dial dial) {}

    /**
     * A custom rate card's dial: it supplies (else requests), at {@code perSecond} (EU a second on a card holding EU),
     * of a resource of {@code kind} ({@code item}, {@code fluid}, {@code power}; {@code item} while it holds none); and
     * which of its two settings the card shows on its strip (the mod lets a player unpin them; the website shows both).
     */
    public record Dial(boolean supply, double perSecond, String kind, boolean showMode, boolean showRate) {}

    /** One shared case: the board's dials, the card's own facts and its kind's. */
    public record Case(CardInput.Dials dials, CardInput card, SpecialInput special) {}
}
