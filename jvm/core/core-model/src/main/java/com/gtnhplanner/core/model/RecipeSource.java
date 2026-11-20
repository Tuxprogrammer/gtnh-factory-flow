package com.gtnhplanner.core.model;

import java.util.Objects;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.machines.web.Web;

/**
 * Where a card's recipe is read from, asked of the host that has the recipes: NEI's pages and the per-mod readers in the
 * mod, the dataset on the website. A card holds only its {@link RecipeRef} (and the host's own live hold on it,
 * {@link Node#hostRecipe}); everything it shows or solves with about the recipe comes from here.
 *
 * <p>
 * One recipe record for both hosts: {@link Web.Recipe} (core-machines), the website's dataset recipe. A source hands out
 * a record that nothing changes after the machine maths have read it (a fresh one, or one it never touches again).
 *
 * <p>
 * One source, installed once at start-up before any plan is read ({@link #install}); until then {@link #NONE}, which
 * finds no recipe: cards keep whatever ports they were given, as a card built by hand does.
 */
public interface RecipeSource {

    /**
     * Reads the card's recipe ({@link Node#recipe}) onto it: clears and fills its {@code inputs} and {@code outputs} in
     * the recipe's order, each input with the alternatives its slot takes, and its {@code properties} (duration and the
     * reader's numbers). False when the host cannot find the recipe, and then the card is left exactly as it was. The
     * model folds the ports and applies the player's picks after a read that returned true.
     */
    boolean readRecipe(Node card);

    /** Rebuilds a power card's ports and model ({@link Node#powerModel}) from its source and settings. */
    void readPower(Node card);

    /** The card's recipe as the shared record, as the dataset writes one; null when the host has none for it. */
    @Nullable
    Web.Recipe record(Node card);

    /** The installed source. */
    static RecipeSource get() {
        return Holder.source;
    }

    /** Installs the host's own. */
    static void install(final RecipeSource source) {
        Holder.source = Objects.requireNonNull(source, "source");
    }

    /** A source that finds nothing (the default until a host installs its own). */
    RecipeSource NONE = new RecipeSource() {

        @Override
        public boolean readRecipe(final Node card) {
            return false;
        }

        @Override
        public void readPower(final Node card) {}

        @Nullable
        @Override
        public Web.Recipe record(final Node card) {
            return null;
        }
    };

    /** Where the installed source is kept. */
    final class Holder {

        static RecipeSource source = NONE;

        private Holder() {}
    }
}
