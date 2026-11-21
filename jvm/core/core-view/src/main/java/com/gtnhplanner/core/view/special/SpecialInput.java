package com.gtnhplanner.core.view.special;

import java.util.List;

import javax.annotation.Nullable;

import com.gtnhplanner.core.view.card.CardInput;

/**
 * What a special card shows beyond the recipe card it starts from, as plain data: the facts of its kind. The card's
 * own facts (its rows, count, read-outs) are its {@link CardInput}; {@link SpecialViews#special} reads both. A wire
 * record (the conventions of {@link CardInput}).
 *
 * @param kind      which special card it is
 * @param holder    a custom rate card's or fluid tank's facts; null on any other kind
 * @param generator a generator's facts; null on any other kind
 */
public record SpecialInput(Kind kind, @Nullable Holder holder, @Nullable Generator generator) {

    /** A holder's or another kind's input with no generator's facts. */
    public SpecialInput(final Kind kind, @Nullable final Holder holder) {
        this(kind, holder, null);
    }

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

    /**
     * A generator (a power card): its name (its source's), the tier its tier select stands at (null when it has none),
     * every setting but that tier select in its source's order, its readings in its model's order, and the warnings
     * its model gives. Which of them the card shows is the plan's pins ({@code pinned}), kept per source.
     */
    public record Generator(String name, @Nullable GeneratorTier tier, List<Setting> settings, List<Reading> readings,
        List<String> warnings) {}

    /** A generator's tier select: its value, and whether it has only that one (the tier is then fixed). */
    public record GeneratorTier(String label, boolean fixed) {}

    /**
     * One of a generator's settings: its id and label, how it is changed, its value ({@code choice}: a select's
     * option label; {@code number} and its {@code unit}: a number's value, clamped to its range; {@code on}: a
     * switch's), whether the other settings leave it meaning anything ({@code enabled}) and whether the plan pins it to
     * the card.
     */
    public record Setting(String id, String label, SettingKind kind, String choice, double number,
        @Nullable String unit, boolean on, boolean enabled, boolean pinned) {}

    /** How a generator's setting is changed. */
    public enum SettingKind {
        CHOICE,
        NUMBER,
        TOGGLE
    }

    /** One of a generator's readings (its efficiency, a flow, a lifespan), and whether the plan pins it to the card. */
    public record Reading(String label, String value, boolean pinned) {}

    /** One shared case: the board's dials, the card's own facts and its kind's. */
    public record Case(CardInput.Dials dials, CardInput card, SpecialInput special) {}
}
