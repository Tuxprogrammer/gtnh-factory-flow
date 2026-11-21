package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.Float32Ops;
import com.gtnhplanner.core.model.PortType;
import com.gtnhplanner.core.model.ResourceKey;
import com.gtnhplanner.core.model.Wiring;

/**
 * One input or output of a card: what it carries per craft and at what chance. The value is the host's own (the
 * mod's stack, a test's named ingredient) and is read only through its {@link PortType}; what the port is in a plan is
 * its {@link #resource()}, and which wires it takes is {@link Wiring}'s rule on resources.
 */
public class Port<T> {

    private final PortType<T> type;
    private final T value;
    private float chance;
    /**
     * The exact amount per craft when the type's whole number cannot hold it (a generator burns 0.37 L a second);
     * NaN when unset, and {@link #amount()} reads the type's own.
     */
    private double exactAmount = Double.NaN;
    /**
     * Added by a machine's model rather than read from its recipe (the Bacterial Vat's radio hatch material): the
     * website adds its own, so a posted plan leaves it out of the recipe.
     */
    public boolean fromModel;
    /**
     * The items an input's recipe slot takes, as the recipe lists them (an ore dictionary slot's every member,
     * GregTech's alternatives), the recipe's own first; empty when it takes one thing only. A card can be set to any of
     * them ({@code Node.inputPicks}), and a wire of any of them lands on it. Read from the recipe on every refresh, never
     * saved.
     */
    private List<T> alternatives = List.of();

    /** The value's resource and the alternatives' (computed once: wiring asks them every frame a wire is held). */
    @Nullable
    private ResourceKey resource;
    @Nullable
    private List<ResourceKey> alternativeResources;

    public Port(final PortType<T> type, final T value, final float chance) {
        this.type = type;
        this.value = value;
        this.chance = chance;
    }

    public PortType<T> getType() {
        return type;
    }

    public T getValue() {
        return value;
    }

    public float getChance() {
        return chance;
    }

    public void setChance(final float chance) {
        this.chance = chance;
    }

    public double getExactAmount() {
        return exactAmount;
    }

    public void setExactAmount(final double exactAmount) {
        this.exactAmount = exactAmount;
    }

    public List<T> getAlternatives() {
        return alternatives;
    }

    public void setAlternatives(final List<T> alternatives) {
        this.alternatives = alternatives;
        this.alternativeResources = null;
    }

    /** Whether the port can be set to another item (its slot lists more than one). */
    public boolean hasChoices() {
        return alternatives.size() > 1;
    }

    /** What the port carries, as the plan JSON names it. */
    public ResourceKey resource() {
        if (resource == null) resource = type.resource(value);
        return resource;
    }

    /** The resources the port's slot lists in its place (its alternatives'), the recipe's own first. */
    public List<ResourceKey> alternativeResources() {
        if (alternativeResources == null) {
            final List<ResourceKey> keys = new ArrayList<>(alternatives.size());
            for (final T alt : alternatives) keys.add(type.resource(alt));
            alternativeResources = List.copyOf(keys);
        }
        return alternativeResources;
    }

    /**
     * Whether this port, as an input, takes what {@code offered} carries: its own resource or one its slot lists
     * ({@link Wiring}).
     */
    public boolean takes(final Port<?> offered) {
        return Wiring.accepts(resource(), alternativeResources(), offered.resource());
    }

    /** Whether this port, as an output, feeds {@code slot}: the slot takes it. */
    public boolean feeds(final Port<?> slot) {
        return slot.takes(this);
    }

    /** The port's key in the host's spelling ({@link PortType#key}): what a pick and a drawer are saved as. */
    public String key() {
        return type.key(value);
    }

    /** The host key of one of this port's alternatives. */
    @SuppressWarnings("unchecked")
    public String keyOf(final Object alternative) {
        return type.key((T) alternative);
    }

    /** The recipe's own item for the slot: what the port is when nothing is picked. */
    public T own() {
        return alternatives.isEmpty() ? value : alternatives.get(0);
    }

    /** Where the port's item is among its alternatives; -1 when it has none. */
    public int choiceIndex() {
        final String key = key();
        for (int i = 0; i < alternatives.size(); i++) if (type.key(alternatives.get(i))
            .equals(key)) return i;
        return -1;
    }

    /** The alternative with that host key, or null. */
    @Nullable
    public T alternative(final String key) {
        for (final T alt : alternatives) if (type.key(alt)
            .equals(key)) return alt;
        return null;
    }

    /**
     * The same port holding another item (one of its alternatives): the same amount, chance and alternatives, the item
     * copied so no two ports share one.
     */
    public Port<T> withValue(final T other) {
        final T copy = type.copy(other);
        type.setAmount(copy, getAmount());
        final Port<T> p = new Port<>(type, copy, chance);
        p.exactAmount = exactAmount;
        p.fromModel = fromModel;
        p.alternatives = alternatives;
        p.alternativeResources = alternativeResources;
        return p;
    }

    /** {@link #withValue} for an alternative of a port whose type is not known here. */
    @SuppressWarnings("unchecked")
    public Port<?> withAlternative(final Object alternative) {
        return withValue((T) alternative);
    }

    public int getAmount() {
        return type.extractAmount(value);
    }

    /** Units per craft: the exact amount when one is set, else the type's whole number. */
    public double amount() {
        return Double.isNaN(exactAmount) ? getAmount() : exactAmount;
    }

    public String getDisplayName() {
        return type.formatDisplayName(value);
    }

    /** Pin color for this port's ingredient; the type's pin color when none is derivable. */
    public int getPinColor(final boolean input) {
        return colorOr(input ? type.getPinInputColor() : type.getPinOutputColor());
    }

    /** Edge-arrow color for this port's ingredient; the type's arrow color as fallback. */
    public int getArrowColor() {
        return colorOr(type.getArrowColor());
    }

    /** Representative color for this port's ingredient; {@code fallback}'s alpha is kept. */
    private int colorOr(final int fallback) {
        final int rgb = type.color(value);
        if (rgb == -1) return fallback;
        return rgb & 0xFFFFFF | (fallback >> 24 & 0xFF) << 24;
    }

    /**
     * Whether two inputs of one recipe are one port, as the host reads its recipes ({@link PortType#folds}): never a
     * question of what may be wired.
     */
    @SuppressWarnings("unchecked")
    public boolean folds(final Port<?> other) {
        if (!type.equals(other.type)) return false;
        return ((PortType<Object>) type).folds(value, other.value);
    }

    /** Whether the other port carries the same resource (not just one it could wire to): two such outputs are one. */
    @SuppressWarnings("unchecked")
    public boolean same(final Port<?> other) {
        if (!type.equals(other.type)) return false;
        return ((PortType<Object>) type).same(value, other.value);
    }

    public void merge(final Port<?> other) {
        // Exact amounts add up too (a crop listing one item twice), else the second's would be lost.
        final double exact = Double.isNaN(exactAmount) && Double.isNaN(other.exactAmount) ? Double.NaN
            : amount() + other.amount();
        final int newAmount = getAmount() + other.getAmount();
        // An amount-weighted average is undefined when there is no amount to weight by, and the NaN
        // chance it would leave here reaches the balancer as a NaN coefficient, where it poisons a
        // whole solve instead of failing anywhere near this line.
        if (newAmount != 0) {
            this.chance = Float32Ops.div(
                Float32Ops.add(
                    Float32Ops.mul(Float32Ops.of(this.getAmount()), this.chance),
                    Float32Ops.mul(Float32Ops.of(other.getAmount()), other.chance)),
                Float32Ops.of(newAmount));
        }
        type.setAmount(value, newAmount);
        exactAmount = exact;
    }
}
