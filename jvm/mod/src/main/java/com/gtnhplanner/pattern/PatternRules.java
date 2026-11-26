package com.gtnhplanner.pattern;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * "Pattern this" without the game: the outcomes the server answers with, which of a packed recipe's keys a terminal has
 * no slot for, and what the player is told about a batch.
 */
public final class PatternRules {

    /** The pattern was made and given to the player. */
    public static final byte MADE = 0;
    /** No blank pattern in the player's inventory (survival). */
    public static final byte NO_BLANK = 1;
    /** The recipe cannot be patterned: nothing packed, no output, or AE2's encoder refused it. */
    public static final byte CANNOT = 2;
    /** The server cannot make patterns (no AE2 or Not Enough Energistics there), or never answered. */
    public static final byte NO_SERVER = 3;
    /**
     * No free slot in the player's main inventory for the pattern: nothing was made and no blank taken. An encoded
     * pattern does not stack, and the game's own hand-over destroys an item a creative player has no room for, so the
     * server makes a pattern only where it has an empty slot to put it.
     */
    public static final byte NO_ROOM = 4;

    /** A packed recipe with more keys than this is refused unread. */
    public static final int MAX_KEYS = 128;

    /** Inputs are keyed {@code #0}, {@code #1} ...; outputs {@code Outputs0} ... (Not Enough Energistics' keys). */
    public static final String IN = "#", OUT = "Outputs";

    private PatternRules() {}

    /**
     * How many of {@code keys} name a slot at or past {@code size}: the stacks a terminal of that many slots leaves
     * out, as its NEI transfer reads only slots {@code 0} to {@code size - 1}.
     */
    public static int beyond(final Collection<?> keys, final String prefix, final int size) {
        int n = 0;
        for (final Object k : keys) {
            final int slot = slot(String.valueOf(k), prefix);
            if (slot >= size) n++;
        }
        return n;
    }

    /** The slot a key names under {@code prefix}, or -1 when it names none. */
    static int slot(final String key, final String prefix) {
        if (!key.startsWith(prefix) || key.length() == prefix.length() || key.length() > prefix.length() + 4) return -1;
        int n = 0;
        for (int i = prefix.length(); i < key.length(); i++) {
            final char c = key.charAt(i);
            if (c < '0' || c > '9') return -1;
            n = n * 10 + (c - '0');
        }
        return n;
    }

    /**
     * The main-inventory slot a new pattern goes to, or -1 when it has nowhere to go: where the blank it is made from
     * was, when that blank is the last of its stack (taking it empties the slot), else the first empty slot.
     * {@code blankSlot} is -1 when no blank is taken (creative); {@code blankCount} is the size of the blank's stack.
     */
    public static int slotFor(final int firstEmpty, final int blankSlot, final int blankCount) {
        if (blankSlot >= 0 && blankCount == 1) return blankSlot;
        return firstEmpty;
    }

    /**
     * How many of {@code need} patterns the player can be given now, made one after another as the server makes them:
     * each takes the first blank left (none in creative; {@code blankStacks} are the blank stacks' sizes in slot order)
     * and needs a slot, an empty one or the one its blank's stack just left. {@code blanksShort} says the blanks ran
     * out
     * before the room did.
     */
    public static Room room(final int empty, final int[] blankStacks, final boolean creative, final int need) {
        final int[] left = blankStacks.clone();
        int free = empty, stack = 0, fit = 0;
        while (fit < need) {
            if (!creative) {
                while (stack < left.length && left[stack] <= 0) stack++;
                if (stack >= left.length) return new Room(fit, true);
                if (--left[stack] == 0) free++;
            }
            if (free == 0) return new Room(fit, false);
            free--;
            fit++;
        }
        return new Room(fit, false);
    }

    /** See {@link #room}. */
    public record Room(int fit, boolean blanksShort) {}

    /**
     * What came of a batch: each count is recipes, {@code dropped} the stacks that did not fit a terminal. An outcome
     * this side does not know (a newer server's) counts as one that cannot be patterned: never as made.
     */
    public record Tally(int made, int noBlank, int cannot, int noServer, int noRoom, int dropped) {

        public int total() {
            return made + noBlank + cannot + noServer + noRoom;
        }

        public boolean clean() {
            return made == total() && dropped == 0;
        }

        public Tally plus(final byte outcome, final int droppedToo) {
            final boolean known = outcome >= MADE && outcome <= NO_ROOM;
            return new Tally(
                made + (outcome == MADE ? 1 : 0),
                noBlank + (outcome == NO_BLANK ? 1 : 0),
                cannot + (outcome == CANNOT || !known ? 1 : 0),
                noServer + (outcome == NO_SERVER ? 1 : 0),
                noRoom + (outcome == NO_ROOM ? 1 : 0),
                dropped + droppedToo);
        }
    }

    public static final Tally NONE = new Tally(0, 0, 0, 0, 0, 0);

    /** The sentence the board shows for a batch. */
    public static String summary(final Tally t) {
        final List<String> parts = new ArrayList<>();
        if (t.made() > 0) parts.add(t.made() == 1 ? "Made a pattern" : "Made " + t.made() + " patterns");
        if (t.noServer() > 0) parts.add("This server cannot make patterns");
        else if (t.noBlank() > 0) parts.add(
            t.made() > 0 ? "Out of blank patterns for " + t.noBlank() + " more"
                : "No blank patterns in your inventory");
        else if (t.noRoom() > 0) {
            if (t.made() > 0) parts.add("No room in your inventory for " + t.noRoom() + " more");
            else {
                parts.add("No free inventory slots");
                parts.add("Nothing was made");
            }
        }
        if (t.cannot() > 0) parts.add(
            t.total() == 1 ? "This recipe cannot be patterned"
                : t.cannot() + (t.cannot() == 1 ? " recipe cannot" : " recipes cannot") + " be patterned");
        if (t.dropped() > 0) parts.add(
            t.dropped() + (t.dropped() == 1 ? " stack did not fit the terminal and was left out"
                : " stacks did not fit the terminal and were left out"));
        if (parts.isEmpty()) return "Nothing to pattern";
        return String.join(". ", parts);
    }
}
