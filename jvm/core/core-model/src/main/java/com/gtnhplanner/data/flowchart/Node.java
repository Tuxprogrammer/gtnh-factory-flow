package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.core.model.RecipeRef;
import com.gtnhplanner.core.model.RecipeSource;
import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.MachineProfileRegistry;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.machines.web.Web;
import com.gtnhplanner.power.PowerModel;

/**
 * A card on a plan: a recipe ({@link #recipe}, read through {@link RecipeSource}) or a power card ({@link #powerSource})
 * run by some count of machines, with its ports, settings, pins and place.
 */
public class Node implements PinRule.Card {

    /** The power source id of a fluid tank card (cells to fluid and back), which has no count of its own. */
    public static final String FLUID_TANK = "fluid-tank";
    /** The power source id of a custom rate card, whose set count is always Exactly. */
    public static final String CUSTOM_RATE = "custom-rate";

    /**
     * Set only while the node is built, before any graph holds it: a reader that keeps a shared plan's ids
     * (cloud/map) gives a card the importer made the id its plan knows it by.
     */
    public Uuid id;
    public int x;
    public int y;

    /** The board group it is in ({@link BoardGroup}), or null. Every recipe of a shared machine is in its card's. */
    @Nullable
    public Uuid group;

    public final List<Port<?>> inputs = new ArrayList<>();
    public final List<Port<?>> outputs = new ArrayList<>();

    public String machineName;

    /** The recipe the card runs, as the plan saves it; {@link RecipeRef#NONE} on a power card or a hand-built card. */
    public RecipeRef recipe = RecipeRef.NONE;
    /**
     * The host's live hold on the recipe (the mod's NEI page and reader), set by the host when it makes or loads the
     * card; never saved, never compared. Null on a card the host has not resolved: it reads nothing.
     */
    @Nullable
    public transient Object hostRecipe;

    public final MachineConfig machineConfig;
    public final Map<RecipeProperty<?>, Object> properties = new HashMap<>();

    private boolean machineCountFixed;

    /**
     * Whether a set count is Exactly rather than Up to (docs/design/up-to.md); meaningless without
     * {@link #machineCountFixed}. Read the rule with {@link PinRule#of(Node)}: a custom rate card's count is Exactly
     * whatever this says.
     */
    private boolean machineCountExact;

    /**
     * Whether a machine count is set (Up to or Exactly, {@link PinRule}). Never for a fluid tank: it has no count to
     * show or clear (it keeps up with the plan), so a pin on it, from a website Build plan or an older save, would
     * force cells through the plan with nothing shown as set.
     */
    public boolean isMachineCountFixed() {
        return machineCountFixed && !FLUID_TANK.equals(powerSource);
    }

    /** Sets whether a count is set. Unsetting it lets go of Exactly too: a count set again is Up to. */
    public void setMachineCountExact(final boolean exact) {
        machineCountExact = exact;
    }

    public void setMachineCountFixed(final boolean fixed) {
        machineCountFixed = fixed;
        if (!fixed) machineCountExact = false;
    }

    /** Whether the set count is Exactly: only with a count set, so never for a fluid tank. */
    public boolean isMachineCountExact() {
        return machineCountExact && isMachineCountFixed();
    }

    /** A custom rate card's set count is always Exactly: its dial ({@link PinRule#of(PinRule.Card)}). */
    @Override
    public boolean countAlwaysExact() {
        return CUSTOM_RATE.equals(powerSource);
    }

    /** Sets the machine count in the card's settings (no refresh: the count is not a recipe setting); any fraction. */
    @Override
    public void setMachineCount(final double count) {
        machineConfig.setMachineCount(count);
    }

    /**
     * Target production rates by output port index, in ingredient units per second. A target is
     * a pin: AUTO holds the machine's extent so the targeted output hits the rate exactly, and
     * the rest of the chart follows. With several targets on one machine the largest implied
     * extent wins - parallel outputs share one extent, so only the tightest can be exact.
     */
    public final Map<Integer, Double> targetOutputRates = new LinkedHashMap<>();

    /**
     * Target consumption rates by input port index, units per second: pins as the output targets are (the machine's
     * extent is held so the input takes the rate exactly), the largest implied extent winning among all its targets.
     */
    public final Map<Integer, Double> targetInputRates = new LinkedHashMap<>();

    /**
     * Which item an input whose recipe slot takes several (an ore dictionary slot, GregTech's alternatives) is set to,
     * by input port index: the chosen alternative's resource key ({@link Port#key()}). The website's
     * {@code recipeInputOverrides}. A pick the port no longer lists does nothing and is kept.
     */
    public final Map<Integer, String> inputPicks = new TreeMap<>();

    /**
     * A non-recipe machine (a generator): its power source id ({@link com.gtnhplanner.power.PowerRegistry}), null on a
     * recipe card. Its ports come from the source's model at {@link #powerSettings}, not from NEI.
     */
    @Nullable
    public String powerSource;
    /** A power card's settings by setting id, as strings (the website's machineConfigTiers). */
    public final Map<String, String> powerSettings = new LinkedHashMap<>();
    /** The power card's model at its settings, rebuilt by {@link #refresh()}; null on a recipe card. */
    @Nullable
    public transient PowerModel powerModel;

    /**
     * The blocks in the world this card's machines are, each {dimension, x, y, z}: set by linking the card to them in
     * game, so the plan can point at its machines and they at it. Saved with the plan, never copied with the card.
     */
    public final List<int[]> worldLinks = new ArrayList<>();

    /**
     * A shared plan's placements in other worlds, by world ({@code cloud/map/game/GameCloud.world}): kept, never shown,
     * so the plan carries them. {@link #worldLinks} are this world's. Never in a local save.
     */
    public final transient java.util.SortedMap<String, List<int[]>> elsewhere = new java.util.TreeMap<>();

    /**
     * A recipe card, read at once: the host has found the recipe ({@code hostRecipe}, its own hold on it) and picked its
     * machine profile ({@code profileId}, null for the default).
     */
    public Node(final Uuid id, final int x, final int y, final String machineName, final RecipeRef recipe,
        @Nullable final Object hostRecipe, @Nullable final String profileId) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.machineName = machineName;
        this.recipe = recipe;
        this.hostRecipe = hostRecipe;
        if (profileId != null && !MachineProfileRegistry.defaultId()
            .equals(profileId)) {
            this.machineConfig = new MachineConfig(this, MachineProfileRegistry.get(profileId));
        } else {
            this.machineConfig = new MachineConfig(this);
        }

        refresh();
        machineConfig.seedRouteDefaults();
    }

    /** Whether this is a non-recipe machine (a generator) rather than a recipe from NEI. */
    public boolean isPower() {
        return powerSource != null;
    }

    /** A new power card for a source at the given settings (missing ones read as the source's defaults). */
    public static Node power(final String sourceId, final Map<String, String> settings, final int x, final int y) {
        final Node node = new Node(Uuid.randomUUID(), x, y);
        node.powerSource = sourceId;
        node.powerSettings.putAll(settings);
        node.refresh();
        return node;
    }

    /**
     * Reads the card again: a power card from its source, a recipe card from its recipe ({@link RecipeSource}). A recipe
     * the host cannot find leaves the card as it is.
     */
    public void refresh() {
        if (powerSource != null) {
            RecipeSource.get()
                .readPower(this);
            return;
        }
        if (!RecipeSource.get()
            .readRecipe(this)) return;
        deduplicate(inputs, false);
        deduplicate(outputs, true);
        applyPicks();
        afterRefresh.accept(this);
    }

    /** The card's recipe as the shared record ({@link RecipeSource#record}); null when the host has none for it. */
    @Nullable
    public Web.Recipe record() {
        return RecipeSource.get()
            .record(this);
    }

    /**
     * Sets each input to the item picked for it ({@link #inputPicks}), and an input with no pick back to the recipe's
     * own. Ports keep their place, so wires and drawers on them keep theirs.
     */
    public void applyPicks() {
        for (int i = 0; i < inputs.size(); i++) {
            final Port<?> port = inputs.get(i);
            if (!port.hasChoices()) continue;
            final String want = inputPicks.get(i);
            final Object to = want == null ? port.own() : port.alternative(want);
            if (to != null && !port.keyOf(to)
                .equals(port.key())) inputs.set(i, port.withAlternative(to));
        }
    }

    /**
     * Sets input {@code index} to the alternative with {@code key}: null, or the recipe's own item, clears the pick.
     * False, changing nothing, when the port has no such alternative or already holds it.
     */
    public boolean pickInput(final int index, @Nullable final String key) {
        if (index < 0 || index >= inputs.size()) return false;
        final Port<?> port = inputs.get(index);
        if (!port.hasChoices()) return false;
        final Object to = key == null ? port.own() : port.alternative(key);
        if (to == null) return false;
        if (port.keyOf(to)
            .equals(port.keyOf(port.own()))) inputPicks.remove(index);
        else inputPicks.put(index, port.keyOf(to));
        if (port.keyOf(to)
            .equals(port.key())) return false;
        applyPicks();
        return true;
    }

    /**
     * Set by the game side (GTProvider): adjusts a recipe node once its ports are read, as the modelled machines do
     * (machines/game/MachineModels). Does nothing without the game, as in the headless tests.
     */
    public static java.util.function.Consumer<Node> afterRefresh = node -> {};

    /**
     * Folds ports of one resource into one. Inputs fold by what wires to them (an ore name: a recipe that takes any
     * plank twice takes two planks); outputs only when they are the same resource ({@code same}), since two items that
     * share an ore name are both made: Lazulia's Lapis Lazuli folded into its Lapis Dust and was lost (issue 487).
     */
    static void deduplicate(final List<Port<?>> ports, final boolean same) {
        final List<Port<?>> aggregate = new ArrayList<>(ports);
        for (int i = 0; i < aggregate.size(); i++) {
            for (int j = i + 1; j < aggregate.size(); j++) {
                if (same ? aggregate.get(i)
                    .same(aggregate.get(j))
                    : aggregate.get(i)
                        .folds(aggregate.get(j))) {
                    aggregate.get(i)
                        .merge(aggregate.get(j));
                    aggregate.remove(j);
                    j--;
                }
            }
        }

        ports.clear();
        ports.addAll(aggregate);
    }

    public int getRecipeDuration() {
        return (int) properties.getOrDefault(RecipeProperty.DURATION_TICKS, 0);
    }

    /**
     * To be used only for serialization/deserialization
     */
    public Node(final Uuid id, final int x, final int y) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.machineConfig = new MachineConfig(this);
    }
}
