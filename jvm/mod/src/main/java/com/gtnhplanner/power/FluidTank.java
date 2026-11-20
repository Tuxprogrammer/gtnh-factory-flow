package com.gtnhplanner.power;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.core.model.FluidContainers;
import com.gtnhplanner.core.model.ResourceKey;
import com.gtnhplanner.core.model.ResourceRegistry;
import com.gtnhplanner.core.model.SavedKeys;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;

/**
 * The fluid tank card (the website's Tank, its free mirror of every fluid Canner recipe): it empties a filled cell into
 * its fluid and the empty cell, or fills empty cells, for nothing and at once. What it can do is GregTech's Canner's,
 * as on the website ({@link FluidContainers}): the litres a container holds are the Canner recipe's, never a guessed
 * 1000, and it empties a container only where the Canner does (never the five platinum-group molten metals).
 * <p>
 * Like the custom rate card it holds nothing until a port is dropped on it, and lets go when nothing is wired to it:
 * a cell it is given is emptied, a cell asked of it is filled, a fluid asked of it comes out of cells, a fluid given to
 * it goes into them. A card, one craft a second: its count is the cells a second it handles. Settings, as strings on
 * {@link Node#powerSettings}: {@link #MODE} and {@link #CELL} (the filled container's resource key, absent while
 * empty).
 */
public final class FluidTank {

    /** Its source id, stored in plans: never rename. */
    public static final String ID = Node.FLUID_TANK;
    public static final String MODE = "mode", CELL = "cell";
    public static final String EMPTY = "empty", FILL = "fill";

    public static final PowerSetting.Select MODE_SETTING = new PowerSetting.Select(
        MODE,
        "Mode",
        List.of(new PowerSetting.Option(EMPTY, "Empty cells"), new PowerSetting.Option(FILL, "Fill cells")),
        EMPTY);

    /** Its entry for {@link PowerRegistry#get}: never in the generator catalog. */
    public static final PowerSource SOURCE = new PowerSource(
        ID,
        "Fluid Tank",
        PowerGroup.OTHER,
        null,
        "Empties cells into their fluid, or fills them, free and instant. Wire a cell or a fluid to it.",
        List.of(MODE_SETTING),
        read -> new PowerModel(0, List.of(), List.of(), List.of()));

    private FluidTank() {}

    public static boolean is(@Nullable final Node node) {
        return node != null && ID.equals(node.powerSource);
    }

    /** The filled container it handles, or null while it is empty. */
    @Nullable
    public static String cell(final Node node) {
        final String key = node.powerSettings.get(CELL);
        return key == null || key.isEmpty() ? null : key;
    }

    public static boolean emptying(final Node node) {
        return !FILL.equals(MODE_SETTING.value(node.powerSettings.get(MODE)));
    }

    /**
     * What a filled container's key (the mod's spelling, as saved) holds when a tank handles it in a direction
     * ({@code emptying}): the Canner's pair; null when the Canner has no such recipe.
     */
    @Nullable
    public static FluidContainers.Pair pair(@Nullable final String cellKey, final boolean emptying) {
        final ResourceKey cell = SavedKeys.resource(cellKey);
        return cell == null ? null : FluidContainers.pair(cell, emptying, ResourceRegistry.get());
    }

    /** What the card holds: its pair in its own direction; null while it is empty or holds what the Canner refuses. */
    @Nullable
    public static FluidContainers.Pair pair(final Node node) {
        return pair(cell(node), emptying(node));
    }

    /**
     * The pair a resource on a side makes it handle ({@code supply}: it gives that resource out, else takes it in), as
     * {mode, cell key}; null when it cannot (an empty cell, a fluid with no cell, an item that holds no fluid, a
     * container the Canner never empties given to it).
     */
    @Nullable
    public static String[] take(final String key, final boolean supply) {
        final ResourceKey resource = SavedKeys.resource(key);
        if (resource == null) return null;
        // Asked every frame while a wire is in hand (which cards would take it): kept per resource and side.
        final String[] known = TAKES.computeIfAbsent(key + (supply ? ">" : "<"), k -> {
            final FluidContainers.Hold hold = FluidContainers.take(resource, supply, ResourceRegistry.get());
            // The cell as the game spells it (its port's key), which is what a tank keeps.
            final Port<?> cell = hold == null ? null
                : ResourceRegistry.get()
                    .port(
                        hold.pair()
                            .cell(),
                        1);
            return cell == null ? NONE : new String[] { hold.emptying() ? EMPTY : FILL, cell.key() };
        });
        return known == NONE ? null : known.clone();
    }

    private static final String[] NONE = new String[0];
    private static final Map<String, String[]> TAKES = new java.util.concurrent.ConcurrentHashMap<>();

    /** Takes the pair for a resource on a side; false, changing nothing, when it cannot. */
    public static boolean hold(final Node node, final String key, final boolean supply) {
        final String[] pick = take(key, supply);
        if (pick == null) return false;
        node.powerSettings.put(MODE, pick[0]);
        node.powerSettings.put(CELL, pick[1]);
        return true;
    }

    /** Lets go of its pair, keeping its mode; the caller rebuilds the ports. */
    public static void release(final Node node) {
        node.powerSettings.remove(CELL);
    }

    /** A new card's settings: none held. */
    public static Map<String, String> fresh() {
        return new java.util.LinkedHashMap<>();
    }

    /**
     * Its ports, one craft a second: emptying, a cell in and its fluid and the empty cell out; filling, the fluid and
     * an
     * empty cell in and the cell out. None while it holds nothing.
     */
    public static void build(final Node node) {
        node.inputs.clear();
        node.outputs.clear();
        node.properties.clear();
        node.powerModel = SOURCE.compute(node.powerSettings);
        node.machineName = SOURCE.name();
        node.properties.put(RecipePropertyAPI.DURATION_TICKS, 20);
        final FluidContainers.Pair p = pair(node);
        if (p == null) return;
        final ResourceRegistry game = ResourceRegistry.get();
        final Port<?> cell = game.port(p.cell(), 1), empty = game.port(p.empty(), 1),
            fluid = game.port(p.fluid(), p.litres());
        if (cell == null || empty == null || fluid == null) return;
        if (emptying(node)) {
            node.inputs.add(cell);
            node.outputs.add(fluid);
            node.outputs.add(empty);
        } else {
            node.inputs.add(fluid);
            node.inputs.add(empty);
            node.outputs.add(cell);
        }
    }
}
