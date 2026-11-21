package com.gtnhplanner.core.model;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.Uuid;

/**
 * A card from the other side's plan that this board shows but cannot run as one of its own (parity decision on recipe
 * kinds only one side has: a display-only card, never dropped). The plan's own spelling of the card stays the plan's
 * ({@code Graph.site}), and goes back out as it came; the board's card is the player's handle on it: where it stands,
 * its wires, and whether it is still there. It cannot be edited into something else.
 *
 * @param planId    the card's id in the plan, which the plan written back still calls it
 * @param reason    why this board cannot run it
 * @param solved    whether it takes part in the solve, from the numbers the plan's record of its recipe gives (the
 *                  website's own machine maths); else it is outside the solve and carries nothing
 * @param euPerTick one machine's draw, EU/t, when solved
 * @param x         where the board first put it, so a card nobody moved goes back with the plan's own position
 * @param y         as {@code x}
 * @param group     the board group it was first in, null for none
 */
public record Shown(String planId, Reason reason, boolean solved, double euPerTick, int x, int y,
    @Nullable Uuid group) {

    /** Why a card is display-only. */
    public enum Reason {

        /** Switched off in the plan: the website leaves it out of the solve too. */
        OFF("off"),
        /** Its recipe is a kind the game has no page for (ore veins, small ores, essentia, Thaumcraft, IC2 crops). */
        KIND("kind"),
        /** The game looks for such recipes but has none like this one (another pack version, another mod set). */
        MISSING("missing"),
        /** The plan does not carry the card's recipe at all. */
        ABSENT("absent");

        private final String word;

        Reason(final String word) {
            this.word = word;
        }

        /** The reason's word, as the save and the card view spell it. */
        public String word() {
            return word;
        }

        /** The reason a word names; null for none. */
        @Nullable
        public static Reason of(@Nullable final String word) {
            for (final Reason r : values()) if (r.word.equals(word)) return r;
            return null;
        }
    }

    /** The same card first placed at another place (where the board put it once the plan was laid out). */
    public Shown at(final int x, final int y, @Nullable final Uuid group) {
        return new Shown(planId, reason, solved, euPerTick, x, y, group);
    }

    /** Whether the board's card stands where it was first put. */
    public boolean unmoved(final int x, final int y, @Nullable final Uuid group) {
        return this.x == x && this.y == y && (this.group == null ? group == null : this.group.equals(group));
    }
}
