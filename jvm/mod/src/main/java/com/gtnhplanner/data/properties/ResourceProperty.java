package com.gtnhplanner.data.properties;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import javax.annotation.Nullable;

import com.gtnhplanner.core.model.PortType;
import com.gtnhplanner.core.model.ResourceKey;

import lombok.Getter;
import lombok.experimental.SuperBuilder;

@SuperBuilder(builderMethodName = "emptyBuilder")
public class ResourceProperty<T> extends SummaryProperty<T> implements PortType<T> {

    @lombok.Builder.Default
    private final ToIntFunction<T> amountExtractor = (v) -> 1;
    @lombok.Builder.Default
    private final BiConsumer<T, Integer> amountUpdater = (v, amount) -> {};

    /**
     * Whether two inputs of one recipe fold into one port when the recipe is read (an item's: the same item, or one
     * sharing an ore name with it). Never what decides a wire: that is {@code core.model.Wiring}, on resources.
     */
    @lombok.Builder.Default
    private final BiPredicate<T, T> connectionChecker = (a, b) -> true;
    /**
     * Whether two values are the same resource, so two outputs of them are one: stricter than folding inputs, which
     * joins items sharing an ore name. Null: as {@link #folds}.
     */
    @lombok.Builder.Default
    @Nullable
    private final BiPredicate<T, T> sameChecker = null;
    @lombok.Builder.Default
    private final ToIntFunction<T> hashCodeExtractor = Objects::hashCode;

    /**
     * The value as a resource key (an item's {@code item:<registry name>:<meta>}, a fluid's {@code fluid:<name>}): what
     * a card's pick among an input's items is saved as.
     */
    @lombok.Builder.Default
    private final Function<T, String> keyExtractor = String::valueOf;
    /** A copy of a value a port can own (its amount is written into it). */
    @lombok.Builder.Default
    private final java.util.function.UnaryOperator<T> copier = v -> v;

    /**
     * The value's resource as the plan JSON names it ({@code item:<registry name>[@meta]}, {@code fluid:<name>}).
     * Null: an item named by {@link #key}.
     */
    @lombok.Builder.Default
    @Nullable
    private final Function<T, ResourceKey> resourceExtractor = null;

    /** The stack recipe viewers (NEI) show for a value, of the host's own stack type; null when it has none. */
    @lombok.Builder.Default
    private final Function<T, Object> displayStackProvider = (v) -> null;
    @lombok.Builder.Default
    private final ToIntFunction<T> colorProvider = (v) -> -1;

    // Pin/arrow fallback colors; opaque white so a type that declares none stays visible.
    @Getter
    @lombok.Builder.Default
    private final int pinInputColor = 0xFFFFFFFF;
    @Getter
    @lombok.Builder.Default
    private final int pinOutputColor = 0xFFFFFFFF;
    @Getter
    @lombok.Builder.Default
    private final int arrowColor = 0xFFFFFFFF;

    @Override
    public String displayName() {
        return getKey();
    }

    @Override
    public int extractAmount(final T value) {
        return amountExtractor.applyAsInt(value);
    }

    @Override
    public void setAmount(final T value, final int newAmount) {
        amountUpdater.accept(value, newAmount);
    }

    @Override
    public boolean folds(final T a, final T b) {
        return connectionChecker.test(a, b);
    }

    /** Whether two values are the same resource (see {@link #sameChecker}). */
    @Override
    public boolean same(final T a, final T b) {
        return sameChecker != null ? sameChecker.test(a, b) : connectionChecker.test(a, b);
    }

    public int hashValue(final T value) {
        return hashCodeExtractor.applyAsInt(value);
    }

    @Override
    public ResourceKey resource(final T value) {
        return resourceExtractor != null ? resourceExtractor.apply(value) : ResourceKey.item(key(value));
    }

    /** The value's resource key; see {@link #keyExtractor}. */
    @Override
    public String key(final T value) {
        return keyExtractor.apply(value);
    }

    @Override
    public T copy(final T value) {
        return copier.apply(value);
    }

    /** The stack recipe viewers (NEI) show for this value; null if it has none. */
    @Nullable
    public Object displayStack(final T value) {
        return displayStackProvider.apply(value);
    }

    /** Representative bare-RGB color for this value, or -1 when none is derivable. */
    @Override
    public int color(final T value) {
        return colorProvider.applyAsInt(value);
    }

    public static <B> ResourcePropertyBuilder<B, ?, ?> builder(final String key, final B defaultValue) {
        return ResourceProperty.<B>emptyBuilder()
            .key(key)
            .defaultValue(defaultValue);
    }
}
