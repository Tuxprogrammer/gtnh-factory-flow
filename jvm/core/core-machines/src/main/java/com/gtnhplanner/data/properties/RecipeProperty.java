package com.gtnhplanner.data.properties;

import java.util.Objects;
import java.util.function.Function;

import com.gtnhplanner.core.base.Text;

/**
 * A named fact a recipe reader puts on a card (its duration, an EU cost, a mana cost), keyed by name. Built with
 * {@link #builder}. The builder is written out in the shape Lombok's {@code @SuperBuilder} gives a class (the core has
 * no Lombok), so the host's subclasses that still use {@code @SuperBuilder} (the mod's SummaryProperty and
 * ResourceProperty) build on it: keep the builder's name, type parameters, {@code self()} and {@code build()}.
 */
public class RecipeProperty<T> {

    /** The recipe's duration in ticks, as its reader read it. */
    public static final RecipeProperty<Integer> DURATION_TICKS = RecipeProperty.<Integer>builder("duration_ticks", 0)
        .build();

    private final String key;

    private final T defaultValue;

    private final boolean perSec;

    private final Function<T, String> displayFormatter;

    protected RecipeProperty(final RecipePropertyBuilder<T, ?, ?> b) {
        this.key = b.key;
        this.defaultValue = b.defaultValue;
        this.perSec = b.perSec;
        this.displayFormatter = b.displayFormatter;
    }

    public String getKey() {
        return key;
    }

    public T getDefaultValue() {
        return defaultValue;
    }

    public boolean isPerSec() {
        return perSec;
    }

    public String displayName() {
        return Text.get()
            .translate("gtnhplanner.properties." + key);
    }

    public String formatDisplayName(final T value) {
        if (displayFormatter == null) return displayName();
        return displayFormatter.apply(value);
    }

    public static <T> RecipePropertyBuilder<T, ?, ?> emptyBuilder() {
        return new RecipePropertyBuilderImpl<T>();
    }

    public static <B> RecipePropertyBuilder<B, ?, ?> builder(final String key, final B defaultValue) {
        return RecipeProperty.<B>emptyBuilder()
            .key(key)
            .defaultValue(defaultValue);
    }

    @Override
    public boolean equals(final Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        final RecipeProperty<?> that = (RecipeProperty<?>) o;
        return Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }

    /** Lombok's {@code @SuperBuilder} builder shape: {@code perSec} defaults to false, the formatter to none. */
    public abstract static class RecipePropertyBuilder<T, C extends RecipeProperty<T>, B extends RecipePropertyBuilder<T, C, B>> {

        private String key;
        private T defaultValue;
        private boolean perSec;
        private Function<T, String> displayFormatter;

        public B key(final String key) {
            this.key = key;
            return self();
        }

        public B defaultValue(final T defaultValue) {
            this.defaultValue = defaultValue;
            return self();
        }

        public B perSec(final boolean perSec) {
            this.perSec = perSec;
            return self();
        }

        public B displayFormatter(final Function<T, String> displayFormatter) {
            this.displayFormatter = displayFormatter;
            return self();
        }

        protected abstract B self();

        public abstract C build();
    }

    private static final class RecipePropertyBuilderImpl<T>
        extends RecipePropertyBuilder<T, RecipeProperty<T>, RecipePropertyBuilderImpl<T>> {

        private RecipePropertyBuilderImpl() {}

        @Override
        protected RecipePropertyBuilderImpl<T> self() {
            return this;
        }

        @Override
        public RecipeProperty<T> build() {
            return new RecipeProperty<T>(this);
        }
    }
}
