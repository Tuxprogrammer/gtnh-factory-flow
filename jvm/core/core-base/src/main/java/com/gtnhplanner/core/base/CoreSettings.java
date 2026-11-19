package com.gtnhplanner.core.base;

import java.util.Objects;

/**
 * The player's settings the core reads. The host installs its own once at start-up (the mod: its Forge config and
 * NEI's settings, read live, so a change applies on the next call); until then {@link #DEFAULTS}. A new setting is a
 * new method here with its default in {@link #DEFAULTS}, registered in the restructure's interface list.
 */
public interface CoreSettings {

    int SOLVER_EFFORT_MIN = 25;
    int SOLVER_EFFORT_DEFAULT = 100;
    int SOLVER_EFFORT_MAX = 200;

    /**
     * How long AUTO balancing may spend looking for a better answer, as a percentage of the default, between
     * {@link #SOLVER_EFFORT_MIN} and {@link #SOLVER_EFFORT_MAX}. Callers clamp what they read: an unclamped
     * percentage multiplies a time budget.
     */
    int solverEffort();

    /**
     * Whether the pack gives this ingredient away (water, say), so a chart that pulls it from outside is not a wiring
     * mistake. Matched by display name, ignoring case and surrounding space.
     */
    boolean isFreeIngredient(String displayName);

    /**
     * Whether NEI's burnable override is on: recipes then offer a setting that puts a fuel slot on the input or the
     * output side, instead of the side the fuel's burn time picks.
     */
    boolean burnableOverride();

    /** The installed settings. */
    static CoreSettings get() {
        return Installed.settings;
    }

    /** Installs the host's settings. */
    static void install(final CoreSettings settings) {
        Installed.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Every setting at the mod's default: full effort, nothing free, no burnable override. */
    CoreSettings DEFAULTS = new CoreSettings() {

        @Override
        public int solverEffort() {
            return SOLVER_EFFORT_DEFAULT;
        }

        @Override
        public boolean isFreeIngredient(final String displayName) {
            return false;
        }

        @Override
        public boolean burnableOverride() {
            return false;
        }
    };
}
