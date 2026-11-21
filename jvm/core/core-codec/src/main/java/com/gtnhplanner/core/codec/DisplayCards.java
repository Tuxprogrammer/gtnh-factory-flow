package com.gtnhplanner.core.codec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.core.model.ResourceKey;
import com.gtnhplanner.core.model.ResourceRegistry;
import com.gtnhplanner.core.model.Shown;
import com.gtnhplanner.core.model.SiteResource;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.machines.web.NodeMath;
import com.gtnhplanner.machines.web.Web;
import com.gtnhplanner.machines.web.WebJson;

/**
 * Cards from the other side's plan that this board cannot run as its own, shown on it as display-only cards
 * ({@link Shown}; parity decision on recipe kinds only one side has, and the union rule: never dropped). The plan's own
 * spelling of each card and of the recipes it runs stays in {@link Graph#site} and goes back out as it came
 * ({@link SiteCarry#writeBack}); the board's card ({@link #card}) carries the card's name, ports and counts as the plan
 * states them, so its wires draw and the solve can count it. {@link #site} is the site as the board now has it: a card
 * deleted on the board is gone from it, with its recipes and its kept wires, and a card moved there is written where it
 * stands.
 *
 * <p>
 * The solve, as the website treats such a card: a switched-off card is outside it (the website's solve skips it); any
 * other card whose plan carries its recipe is solved from that recipe with the website's own machine maths
 * ({@link NodeMath}, one machine at the card's settings: crafts a second, each input and output a second, EU/t), as one
 * craft a second per machine; a card those maths do not cover (bees, crops, the Tree Growth Simulator, the Bacterial
 * Vat), or whose maths give no run, is outside the solve.
 */
public final class DisplayCards {

    private DisplayCards() {}

    /** Writes a moved card's place into its plan card (its {@code position}, and its {@code pocketId} or none). */
    public interface Placer {

        void place(Node card, Map<String, Object> planCard);
    }

    // region Making

    /**
     * The board's card for a plan card it cannot run, at a board place: the recipe's name, a port per recipe slot (the
     * host's own value for the resource where it has one, else the plan's name for it), the plan's count and pin, and
     * whether and how it solves. {@code recipe} is the recipe the card runs, null when the plan does not carry it.
     */
    public static Node card(final Map<String, Object> planCard, @Nullable final Map<String, Object> recipe,
        final Shown.Reason reason, final Uuid id, final int x, final int y) {
        final Node node = new Node(id, x, y);
        node.machineName = name(recipe, planCard);
        NodeMath.Result math = null;
        if (reason != Shown.Reason.OFF && recipe != null) math = math(recipe, planCard);
        final List<Map<String, Object>> ins = slots(recipe, "inputs"), outs = slots(recipe, "outputs");
        for (int i = 0; i < ins.size(); i++)
            node.inputs.add(port(ins.get(i), math != null ? math.inputsPerSecond().get(i) : perCraft(ins.get(i))));
        for (int i = 0; i < outs.size(); i++)
            node.outputs.add(port(outs.get(i), math != null ? math.outputsPerSecond().get(i) : perCraft(outs.get(i))));
        final Double pin = number(planCard.get("solvePin"));
        final Double count = number(planCard.get("machineCount"));
        node.setMachineCount(pin != null ? pin : count != null ? count : 1);
        node.setMachineCountFixed(pin != null);
        node.setMachineCountExact(pin != null && Boolean.TRUE.equals(planCard.get("solvePinExact")));
        node.shown = new Shown(
            str(planCard.get("id")),
            reason,
            math != null,
            math == null ? 0 : math.euT(),
            x,
            y,
            null);
        return node;
    }

    /** The recipe a plan card runs (its first), among a plan's or a site's recipes; null when they lack it. */
    @Nullable
    public static Map<String, Object> recipeOf(final Map<String, Object> planCard, @Nullable final Object recipes) {
        final String id = str(planCard.get("recipeId"));
        if (id == null) return null;
        for (final Map<String, Object> r : objects(recipes)) if (id.equals(r.get("id"))) return r;
        return null;
    }

    /**
     * The plan's spelling of a display-only card's port: the slot of the recipe the plan's card runs (its kind, id and
     * name), so its wires go back out naming the port as the plan's recipe does; null for a port that has none.
     */
    @Nullable
    public static Map<String, Object> slot(final Graph g, final Node card, final boolean output, final int index) {
        if (card.shown == null) return null;
        for (final Map<String, Object> c : objects(g.site.get("nodes"))) {
            if (!card.shown.planId()
                .equals(c.get("id"))) continue;
            final List<Map<String, Object>> slots = slots(recipeOf(c, g.site.get("recipes")), output ? "outputs" : "inputs");
            return index >= 0 && index < slots.size() ? slots.get(index) : null;
        }
        return null;
    }

    /** What a player reads for the card: its machine, else the recipe's name, else the id the plan gives it. */
    private static String name(@Nullable final Map<String, Object> recipe, final Map<String, Object> planCard) {
        if (recipe != null) {
            final String machine = str(recipe.get("machineType"));
            if (machine != null && !machine.isEmpty()) return machine;
            final String name = str(recipe.get("name"));
            if (name != null && !name.isEmpty()) return name;
        }
        final String id = str(planCard.get("recipeId"));
        return id != null ? id : "";
    }

    /**
     * One machine at the card's settings by the website's maths, when they cover the recipe and give a run whose rows
     * line up with the recipe's slots; null otherwise (the card is then outside the solve).
     */
    @Nullable
    private static NodeMath.Result math(final Map<String, Object> recipe, final Map<String, Object> planCard) {
        try {
            final Web.Recipe r = WebJson.recipe(doubles(recipe));
            final Web.Node n = WebJson.node(doubles(planCard));
            if (r == null || n == null || !NodeMath.covers(r, n)) return null;
            final double parallel = n.parallel != null && n.parallel > 0 ? n.parallel : 1;
            final NodeMath.Result m = NodeMath.compute(r, n, 1, parallel);
            if (!(m.operationsPerSecond() > 0) || Double.isInfinite(m.operationsPerSecond())) return null;
            if (m.inputsPerSecond()
                .size() != slots(recipe, "inputs").size()
                || m.outputsPerSecond()
                    .size() != slots(recipe, "outputs").size())
                return null;
            for (final double v : m.inputsPerSecond()) if (!Double.isFinite(v)) return null;
            for (final double v : m.outputsPerSecond()) if (!Double.isFinite(v)) return null;
            return Double.isFinite(m.euT()) ? m : null;
        } catch (final RuntimeException e) {
            // A record the maths cannot read is shown, outside the solve, rather than lost.
            return null;
        }
    }

    /** A slot's amount a craft: a kept (never used up) input carries none; an output's chance counted in. */
    private static double perCraft(final Map<String, Object> slot) {
        if (Boolean.FALSE.equals(slot.get("consumed"))) return 0;
        final Double amount = number(slot.get("amount"));
        final Double chance = number(slot.get("chance"));
        return (amount == null ? 0 : amount) * (chance == null ? 1 : chance);
    }

    /**
     * A port for a slot carrying {@code amount} (a second, for a solved card): the host's own value for the resource
     * where it has one, so the card draws it as any other, else the plan's name for it ({@link SiteResource}).
     */
    private static Port<?> port(final Map<String, Object> slot, final double amount) {
        final ResourceKey.Kind kind = ResourceKey.Kind.of(str(slot.get("kind")));
        final String id = str(slot.get("id"));
        final ResourceKey key = ResourceKey.of(kind == null ? ResourceKey.Kind.ITEM : kind, id == null ? "" : id);
        Port<?> port = hostPort(key, Math.max(1, amount));
        if (port != null && amount <= 0) whole(port, 0);
        if (port == null) {
            final String name = str(slot.get("displayName"));
            port = new Port<>(
                SiteResource.TYPE,
                new SiteResource(
                    key,
                    name != null ? name : hostName(key),
                    (int) Math.min(Integer.MAX_VALUE, Math.max(0, com.gtnhplanner.core.base.ExactMath.round(amount)))),
                1f);
        }
        port.setExactAmount(amount);
        return port;
    }

    /**
     * The host's own port for a resource, or null when it has none or cannot make one (a host whose game is not running,
     * as in a headless test): the card then shows the plan's name for it.
     */
    @Nullable
    private static Port<?> hostPort(final ResourceKey key, final double amount) {
        try {
            return ResourceRegistry.get()
                .port(key, amount);
        } catch (final RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** The host's name for a resource the plan names none for; the key's id when it cannot say. */
    private static String hostName(final ResourceKey key) {
        try {
            return ResourceRegistry.get()
                .displayName(key);
        } catch (final RuntimeException | LinkageError e) {
            return key.id();
        }
    }

    /** Sets the whole amount a craft a port's value holds (a kept input holds none). */
    private static <T> void whole(final Port<T> port, final int amount) {
        port.getType()
            .setAmount(port.getValue(), amount);
    }

    // endregion

    // region The site as the board has it

    /**
     * The board's {@link Graph#site} with its display-only cards as the board has them now: a card no longer on the
     * board is gone, with the recipes no other kept card runs and the kept wires to it; a card moved (or put in another
     * group) is written where it stands by {@code placer} (null: positions as the plan had them). The graph's own site
     * is not changed; one the board changed nothing in comes back as it is.
     */
    public static Map<String, Object> site(final Graph g, @Nullable final Placer placer) {
        final Map<String, Object> site = g.site;
        final List<Map<String, Object>> cards = objects(site.get("nodes"));
        if (cards.isEmpty()) return site;
        final Map<String, Node> onBoard = new LinkedHashMap<>();
        for (final Node n : g.getNodes()) if (n.shown != null) onBoard.put(n.shown.planId(), n);
        final List<Object> nodes = new ArrayList<>();
        final Set<String> gone = new LinkedHashSet<>();
        boolean changed = false;
        for (final Map<String, Object> card : cards) {
            final String id = str(card.get("id"));
            final Node n = id == null ? null : onBoard.get(id);
            if (n == null) {
                if (id != null) gone.add(id);
                changed = true;
                continue;
            }
            if (placer == null || n.shown.unmoved(n.x, n.y, n.group)) {
                nodes.add(card);
                continue;
            }
            final Map<String, Object> moved = new LinkedHashMap<>(card);
            placer.place(n, moved);
            nodes.add(moved);
            changed = true;
        }
        if (!changed) return site;
        final Map<String, Object> out = new LinkedHashMap<>(site);
        if (nodes.isEmpty()) out.remove("nodes");
        else out.put("nodes", nodes);
        if (gone.isEmpty()) return out;

        final Set<String> run = new LinkedHashSet<>();
        for (final Object o : nodes) {
            final Map<String, Object> card = object(o);
            run.add(str(card.get("recipeId")));
            for (final Map<String, Object> x : objects(card.get("extraRecipes"))) run.add(str(x.get("recipeId")));
        }
        final List<Object> recipes = new ArrayList<>();
        for (final Map<String, Object> r : objects(site.get("recipes"))) if (run.contains(str(r.get("id")))) recipes.add(r);
        if (recipes.isEmpty()) out.remove("recipes");
        else out.put("recipes", recipes);

        final List<Object> edges = new ArrayList<>();
        for (final Map<String, Object> e : objects(site.get("edges")))
            if (!gone.contains(str(e.get("source"))) && !gone.contains(str(e.get("target")))) edges.add(e);
        if (edges.isEmpty()) out.remove("edges");
        else out.put("edges", edges);
        return out;
    }

    // endregion

    /** A copy of a JSON tree with every number a {@link Double}, as the machine maths read numbers. */
    private static Object doubles(@Nullable final Object v) {
        if (v instanceof Map) {
            final Map<String, Object> out = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet())
                out.put(String.valueOf(e.getKey()), doubles(e.getValue()));
            return out;
        }
        if (v instanceof List) {
            final List<Object> out = new ArrayList<>();
            for (final Object o : (List<?>) v) out.add(doubles(o));
            return out;
        }
        return v instanceof Number ? (Object) ((Number) v).doubleValue() : v;
    }

    @Nullable
    private static Double number(@Nullable final Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }

    private static List<Map<String, Object>> slots(@Nullable final Map<String, Object> recipe, final String side) {
        return recipe == null ? List.of() : objects(recipe.get(side));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(@Nullable final Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(@Nullable final Object v) {
        final List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List) for (final Object o : (List<?>) v) if (o instanceof Map) out.add((Map<String, Object>) o);
        return out;
    }

    @Nullable
    private static String str(@Nullable final Object v) {
        return v instanceof String ? (String) v : null;
    }
}
