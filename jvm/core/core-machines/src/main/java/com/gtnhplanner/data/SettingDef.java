package com.gtnhplanner.data;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.gtnhplanner.core.base.Text;

/**
 * One machine setting a card can carry: its key, label, type, default and range or choices, the tag it shows, and
 * when it shows. Plain data over the card's stored settings (key to value), so it names no host type.
 */
public class SettingDef<T> {

    public final String key;
    public final String label;
    public final Class<T> type;
    public final T defaultValue;
    public final int minInt;
    public final int maxInt;
    @Nullable
    public final List<String> options;
    @Nullable
    private final BiFunction<T, Map<String, Object>, String> badgeFn;
    private final BiPredicate<RecipeContext, Map<String, Object>> visibility;

    private SettingDef(final String key, final Class<T> type, final T defaultValue, final int minInt, final int maxInt,
        @Nullable final List<String> options, @Nullable final BiFunction<T, Map<String, Object>, String> badgeFn,
        final BiPredicate<RecipeContext, Map<String, Object>> visibility) {
        this.key = key;
        this.label = Text.get()
            .translate("gtnhplanner.settings." + key);
        this.type = type;
        this.defaultValue = defaultValue;
        this.minInt = minInt;
        this.maxInt = maxInt;
        this.options = options;
        this.badgeFn = badgeFn;
        this.visibility = visibility;
    }

    @Nonnull
    public static SettingDef<Integer> intDef(final String key, final int def, final int min, final int max) {
        return intDef(key, def, min, max, null);
    }

    @Nonnull
    public static SettingDef<Integer> intDef(final String key, final int def, final int min, final int max,
        @Nullable final BiFunction<Integer, Map<String, Object>, String> badgeFn) {
        return new SettingDef<>(key, Integer.class, def, min, max, null, badgeFn, (ctx, s) -> true);
    }

    @Nonnull
    public static SettingDef<Boolean> boolDef(final String key, final boolean def,
        final BiFunction<Boolean, Map<String, Object>, String> badgeFn) {
        return new SettingDef<>(key, Boolean.class, def, 0, 0, null, badgeFn, (ctx, s) -> true);
    }

    @Nonnull
    public static SettingDef<String> enumDef(final String key, final String def, final List<String> options,
        final BiFunction<String, Map<String, Object>, String> badgeFn) {
        return new SettingDef<>(key, String.class, def, 0, 0, options, badgeFn, (ctx, s) -> true);
    }

    public boolean hasOptions() {
        return options != null && !options.isEmpty();
    }

    /**
     * The short tag this setting's value shows on a card, or null for none. It reads the card's other settings as
     * they are stored ({@code MachineConfig.settings}: key to Integer, Boolean or String), so a setting is data and
     * needs no host object.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public String badge(@Nullable final Object val, final Map<String, Object> settings) {
        if (badgeFn == null) return null;
        return badgeFn.apply((T) val, settings);
    }

    public boolean isVisible(final RecipeContext ctx, final Map<String, Object> settings) {
        return visibility.test(ctx, settings);
    }

    public SettingDef<T> withVisibility(final BiPredicate<RecipeContext, Map<String, Object>> condition) {
        return new SettingDef<>(key, type, defaultValue, minInt, maxInt, options, badgeFn, condition);
    }
}
