package com.gtnhplanner.power;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.core.model.ResourceKey;
import com.gtnhplanner.core.model.ResourceRegistry;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;

/**
 * The custom rate card (the website's custom-rate.ts): a source or a drain you dial by hand. Supply makes a resource at
 * the dialed rate for whatever asks; request drains it at that rate. It is a non-recipe card like a generator, one
 * craft
 * a second, so its one port's amount is its rate, exact.
 * <p>
 * It holds a resource only while wired: wire any port to it and it takes that port's resource (the side the port is
 * on picks supply or request); pull the last wire and it lets go and shows its two empty sockets again. The dial (mode
 * and rate) stays on the card either way. Settings, as strings on {@link Node#powerSettings}: {@link #MODE},
 * {@link #RATE} (per second; EU/t for EU) and {@link #RESOURCE} (the resource's key, absent while empty).
 */
public final class CustomRate {

    /** Its source id, stored in plans: never rename. */
    public static final String ID = Node.CUSTOM_RATE;
    public static final String MODE = "mode", RATE = "rate", RESOURCE = "resource";
    public static final String SUPPLY = "supply", REQUEST = "request";
    /** The rate a card starts on, before anyone has dialed one. */
    public static final double DEFAULT_RATE = 1;

    public static final PowerSetting.Select MODE_SETTING = new PowerSetting.Select(
        MODE,
        "Mode",
        List.of(new PowerSetting.Option(SUPPLY, "Supply"), new PowerSetting.Option(REQUEST, "Request")),
        SUPPLY);
    public static final PowerSetting.Number RATE_SETTING = new PowerSetting.Number(
        RATE,
        "Rate",
        0,
        1e12,
        1,
        DEFAULT_RATE);

    /** Its entry for {@link PowerRegistry#get}: never in the generator catalog. */
    public static final PowerSource SOURCE = new PowerSource(
        ID,
        "Custom Rate",
        PowerGroup.OTHER,
        null,
        "Supplies or drains any resource at a rate you set.",
        List.of(MODE_SETTING, RATE_SETTING),
        read -> new PowerModel(0, List.of(), List.of(), List.of()));

    private CustomRate() {}

    public static boolean is(@Nullable final Node node) {
        return node != null && ID.equals(node.powerSource);
    }

    /** The resource it holds, or null while it is empty. */
    @Nullable
    public static String resource(final Node node) {
        final String key = node.powerSettings.get(RESOURCE);
        return key == null || key.isEmpty() ? null : key;
    }

    public static boolean supply(final Node node) {
        return !REQUEST.equals(MODE_SETTING.value(node.powerSettings.get(MODE)));
    }

    /** The dial: per second, or EU/t on a card holding EU. */
    public static double rate(final Node node) {
        return RATE_SETTING.value(node.powerSettings.get(RATE));
    }

    /** The rate's unit as the dial shows it: L/s for a fluid, EU/t for EU, /s for an item (or nothing yet). */
    public static String unit(@Nullable final String key) {
        if (key == null) return "/s";
        if (key.startsWith("fluid:")) return "L/s";
        if (Energy.KEY.equals(key)) return "EU/t";
        return "/s";
    }

    /** What it moves a second at its dial. */
    public static double perSecond(final Node node) {
        final double rate = Math.max(0, rate(node));
        return Energy.KEY.equals(resource(node)) ? rate * 20 : rate;
    }

    /** Its port, from its settings: an output when it supplies, an input when it requests; none while empty. */
    public static void build(final Node node) {
        node.inputs.clear();
        node.outputs.clear();
        node.properties.clear();
        node.powerModel = SOURCE.compute(node.powerSettings);
        node.machineName = SOURCE.name();
        node.properties.put(RecipePropertyAPI.DURATION_TICKS, 20);
        final String key = resource(node);
        if (key == null) return;
        final Port<?> port = port(key, perSecond(node));
        if (port == null) return;
        (supply(node) ? node.outputs : node.inputs).add(port);
    }

    /**
     * A port for a resource key (the mod's spelling, as saved) moving {@code perSecond}, exact; null when this game has
     * no such resource. The host makes it ({@link ResourceRegistry#port}).
     */
    @Nullable
    static Port<?> port(final String key, final double perSecond) {
        final ResourceKey resource = com.gtnhplanner.core.model.SavedKeys.resource(key);
        return resource == null ? null
            : ResourceRegistry.get()
                .port(resource, perSecond);
    }

    /** Takes a resource on a side, keeping the dial; the caller rebuilds the ports. */
    public static void hold(final Node node, final String key, final boolean supply) {
        node.powerSettings.put(RESOURCE, key);
        node.powerSettings.put(MODE, supply ? SUPPLY : REQUEST);
    }

    /** Lets go of its resource, keeping the dial; the caller rebuilds the ports. */
    public static void release(final Node node) {
        node.powerSettings.remove(RESOURCE);
    }

    /** A new card's settings: the dial last set on one (from {@code remembered}), no resource. */
    public static Map<String, String> fresh(final Map<String, String> remembered) {
        final Map<String, String> out = new java.util.LinkedHashMap<>(remembered);
        out.remove(RESOURCE);
        return out;
    }
}
