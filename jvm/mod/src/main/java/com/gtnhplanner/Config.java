package com.gtnhplanner;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import net.minecraftforge.common.config.Configuration;

import com.gtnhplanner.core.view.ViewSettings;

public final class Config {

    /** Dev diagnostic: log a headless repro of every arrow-routing recompute. */
    public static boolean debugRouteDump = ViewSettings.DEBUG_ROUTE_DUMP_DEFAULT;

    /**
     * Drops the summary's Machine Counts section entirely rather than folding it: a fold is "not on
     * this chart" and lives per slot, this is "never" and lives with the install.
     */
    public static boolean hideMachineCountsSection = ViewSettings.HIDE_MACHINE_COUNTS_SECTION_DEFAULT;

    /**
     * Ingredients nobody plumbs, so the wiring diagnostics stay quiet about them. Display names
     * rather than registry ids: this is a nuisance filter the user edits by hand.
     */
    private static final String[] DEFAULT_FREE_INGREDIENTS = ViewSettings.FREE_INGREDIENTS_DEFAULT;

    /** {@link #DEFAULT_FREE_INGREDIENTS} folded to lower case for lookup. */
    private static Set<String> freeIngredients = lowercased(DEFAULT_FREE_INGREDIENTS);

    /** Whether wiring diagnostics should stay quiet about this ingredient. */
    public static boolean isFreeIngredient(final String displayName) {
        return freeIngredients.contains(
            displayName.trim()
                .toLowerCase(Locale.ROOT));
    }

    /** Replaces the free list. Names are matched case- and whitespace-insensitively from here on. */
    public static void setFreeIngredients(final String... names) {
        freeIngredients = lowercased(names);
    }

    public static void resetFreeIngredients() {
        setFreeIngredients(DEFAULT_FREE_INGREDIENTS);
    }

    private static Set<String> lowercased(final String[] names) {
        final Set<String> set = new HashSet<>();
        for (final String name : names) {
            set.add(
                name.trim()
                    .toLowerCase(Locale.ROOT));
        }
        return set;
    }

    /**
     * How long the balancer is allowed to look for a better answer, as a percentage of the tuned
     * default. One number rather than one per budget, because the budgets are not independent -
     * halving the time a solve gets and leaving the tie enumeration at five candidates only means
     * running out of time inside the enumeration.
     *
     * <p>
     * The ceiling is 200 and not more because AUTO solves from {@code draw()}: a chart that takes
     * its whole budget freezes the GUI for it, and 40 seconds is already past what anyone would
     * read as anything but a hang.
     */
    public static int solverEffortPercent = ViewSettings.SOLVER_EFFORT_PERCENT_DEFAULT;

    public static final int SOLVER_EFFORT_MIN = ViewSettings.SOLVER_EFFORT_PERCENT_MIN;
    public static final int SOLVER_EFFORT_MAX = ViewSettings.SOLVER_EFFORT_PERCENT_MAX;

    /**
     * {@link #solverEffortPercent} as the solver reads it. Clamped rather than trusted: Forge
     * clamps what it parses out of the config file, but the field is public and nothing stops a
     * later caller from assigning to it, and an unclamped percentage multiplies a 20-second budget.
     */
    public static int solverEffort() {
        return Math.clamp(solverEffortPercent, SOLVER_EFFORT_MIN, SOLVER_EFFORT_MAX);
    }

    public static void synchronizeConfiguration(final File configFile) {
        final Configuration configuration = new Configuration(configFile);

        debugRouteDump = configuration.getBoolean(
            ViewSettings.DEBUG_ROUTE_DUMP_KEY,
            "debug",
            ViewSettings.DEBUG_ROUTE_DUMP_DEFAULT,
            "Log a replayable dump of the arrow-routing input on every route recompute");

        hideMachineCountsSection = configuration.getBoolean(
            ViewSettings.HIDE_MACHINE_COUNTS_SECTION_KEY,
            "gui",
            ViewSettings.HIDE_MACHINE_COUNTS_SECTION_DEFAULT,
            "Leave the Machine Counts section (per-machine operation counts, and the ops/cycle"
                + " totals) out of the summary panel altogether, instead of folding it away");

        setFreeIngredients(
            configuration
                .get(
                    "solver",
                    ViewSettings.FREE_INGREDIENTS_KEY,
                    DEFAULT_FREE_INGREDIENTS,
                    "Ingredients the solver never suggests wiring up. Display names, case"
                        + " insensitive. Anything effectively free in the pack belongs here:"
                        + " otherwise every chart that takes water from outside reports a missing"
                        + " edge to whatever else happens to produce it.")
                .getStringList());

        solverEffortPercent = configuration.getInt(
            ViewSettings.SOLVER_EFFORT_PERCENT_KEY,
            "solver",
            ViewSettings.SOLVER_EFFORT_PERCENT_DEFAULT,
            SOLVER_EFFORT_MIN,
            SOLVER_EFFORT_MAX,
            "How long AUTO balancing may spend looking for a better answer, as a percentage of the"
                + " default. Lower gives up sooner on big charts; higher makes them balance better"
                + " and the GUI pause longer, because the solve runs while the screen draws.");

        com.gtnhplanner.library.SiteConfig.read(configuration);

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
