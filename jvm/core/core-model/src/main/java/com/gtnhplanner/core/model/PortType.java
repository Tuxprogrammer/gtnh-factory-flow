package com.gtnhplanner.core.model;

/**
 * What a port holds, as the host that made it reads it. A port carries one value of its host's own type (the mod's
 * {@code ItemStack}, {@code FluidStack} or EU; a test's named ingredient) and never looks inside it: everything the
 * model needs to know comes through here. Identity is the value's {@link ResourceKey}; a wire is decided by
 * {@link Wiring} on keys, never by the value.
 */
public interface PortType<T> {

    /** The kind's name as a saved port carries it ({@code item}, {@code fluid}, {@code power}). */
    String getKey();

    /** The value's resource, spelled as the plan JSON spells it. */
    ResourceKey resource(T value);

    /**
     * The value's key in the host's own spelling, as the host saves it (the mod's {@code item:<registry name>:<meta>}
     * for a card's pick among a slot's items and a drawer's resource).
     */
    String key(T value);

    /** Units per craft, as a whole number. */
    int extractAmount(T value);

    /** Writes a whole amount per craft into the value (a port owns its value). */
    void setAmount(T value, int amount);

    /** A copy a port can own. */
    T copy(T value);

    /** What a player reads for the value. */
    String formatDisplayName(T value);

    /** A rate of the value, as the host prints one (a fluid's buckets, EU per tick). */
    String formatAmount(float perSecond);

    /** The value's representative colour as bare RGB, -1 when there is none. */
    int color(T value);

    /** The kind's pin colours and arrow colour (ARGB), for a value with no colour of its own. */
    int getPinInputColor();

    int getPinOutputColor();

    int getArrowColor();

    /** Whether two values are the same thing, so two outputs of them are one port. */
    boolean same(T a, T b);

    /**
     * Whether two inputs of one recipe are one port: how the host reads its recipes' slots (the mod folds two slots
     * that share an ore dictionary name, so a recipe that takes any plank twice takes two planks). A recipe-reading
     * rule, never a wiring one.
     */
    boolean folds(T a, T b);
}
