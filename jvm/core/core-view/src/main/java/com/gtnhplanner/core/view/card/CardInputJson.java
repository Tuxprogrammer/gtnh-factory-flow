package com.gtnhplanner.core.view.card;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.core.base.RateUnit;
import com.gtnhplanner.data.flowchart.PinRule;
import com.gtnhplanner.data.flowchart.balancer.UpToLoop;
import com.gtnhplanner.data.flowchart.balancer.UpToReason;

/**
 * {@link CardInput} from its wire JSON with no reflection, so a host that has no record reader (the web build) can hand
 * the core a card as text. The wire is the record convention's (conformance/view/README.md): a missing key is the
 * component's empty value (null for an optional, false, 0, an empty list or map), enums by name. Held to the tests'
 * reflective reader on every shared case ({@code CardInputJsonTest}).
 */
public final class CardInputJson {

    private CardInputJson() {}

    /** One shared case, {@code {"dials", "card"}}, from its text. */
    public static CardInput.Case readCase(final String text) {
        final Map<String, Object> o = JsonTree.object(JsonTree.parse(text));
        return new CardInput.Case(dials(o.get("dials")), card(o.get("card")));
    }

    /** The board's dials from a parsed tree. */
    public static CardInput.Dials dials(@Nullable final Object tree) {
        final Map<String, Object> o = JsonTree.object(tree);
        return new CardInput.Dials(enumOf(RateUnit.values(), str(o, "rate")), str(o, "power"), bool(o, "averageDraw"));
    }

    /** A card from a parsed tree. */
    public static CardInput card(@Nullable final Object tree) {
        final Map<String, Object> o = JsonTree.object(tree);
        final Map<String, String> names = new TreeMap<>();
        for (final Map.Entry<String, Object> e : JsonTree.object(o.get("cardNames"))
            .entrySet()) if (e.getValue() instanceof String s) names.put(e.getKey(), s);
        return new CardInput(
            str(o, "nodeId"),
            str(o, "name"),
            bool(o, "hasPicker"),
            JsonTree.str(o, "tier"),
            bool(o, "tierFixed"),
            bool(o, "hatchControl"),
            o.get("power") instanceof Map ? power(o.get("power")) : null,
            o.get("steam") instanceof Map ? steam(o.get("steam")) : null,
            JsonTree.num(o, "parallel"),
            JsonTree.num(o, "structuralParallel"),
            list(o.get("pinned"), CardInputJson::setting),
            JsonTree.str(o, "art"),
            JsonTree.str(o, "glanceArt"),
            o.get("product") instanceof Map ? product(o.get("product")) : null,
            o.get("circuit") instanceof Map ? circuit(o.get("circuit")) : null,
            list(o.get("inputs"), CardInputJson::port),
            list(o.get("outputs"), CardInputJson::port),
            count(o.get("count")),
            JsonTree.str(o, "powerTier"),
            o.get("upTo") instanceof Map ? upTo(o.get("upTo")) : null,
            list(o.get("forced"), CardInputJson::forced),
            o.get("loop") instanceof String s ? enumOf(UpToLoop.Kind.values(), s) : null,
            list(o.get("leftovers"), CardInputJson::leftover),
            o.get("turbine") instanceof Map ? turbine(o.get("turbine")) : null,
            names,
            bool(o, "selected"),
            menu(o.get("menu")));
    }

    private static CardInput.PowerReport power(final Object tree) {
        final Map<String, Object> o = JsonTree.object(tree);
        return new CardInput.PowerReport(bool(o, "ok"), str(o, "tier"), JsonTree.num(o, "amps"),
            JsonTree.num(o, "drawEuT"), JsonTree.num(o, "parallels"), JsonTree.str(o, "gateReason"),
            bool(o, "multiblock"), JsonTree.str(o, "hatchChip"));
    }

    private static CardInput.SteamReport steam(final Object tree) {
        final Map<String, Object> o = JsonTree.object(tree);
        return new CardInput.SteamReport(JsonTree.num(o, "drawSteamPerTick"), bool(o, "highPressure"),
            JsonTree.num(o, "parallels"));
    }

    private static CardInput.Setting setting(final Object tree) {
        final Map<String, Object> o = JsonTree.object(tree);
        return new CardInput.Setting(str(o, "id"), str(o, "label"), str(o, "value"), JsonTree.str(o, "icon"),
            bool(o, "warn"));
    }

    private static CardInput.Product product(final Object tree) {
        final Map<String, Object> o = JsonTree.object(tree);
        return new CardInput.Product(str(o, "icon"), bool(o, "fluid"));
    }

    private static CardInput.Circuit circuit(final Object tree) {
        final Map<String, Object> o = JsonTree.object(tree);
        return new CardInput.Circuit(JsonTree.str(o, "icon"), JsonTree.str(o, "number"));
    }

    private static CardInput.Port port(final Object tree) {
        final Map<String, Object> o = JsonTree.object(tree);
        return new CardInput.Port(str(o, "key"), str(o, "handleId"), bool(o, "output"), str(o, "name"),
            str(o, "kind"), str(o, "resourceId"), JsonTree.num(o, "perSecond"), JsonTree.boxed(o, "energyPerUnit"),
            bool(o, "wired"), JsonTree.boxed(o, "chance"), JsonTree.boxed(o, "consumeChance"),
            (int) JsonTree.num(o, "alternatives"), bool(o, "free"), JsonTree.boxed(o, "setRate"));
    }

    private static CardInput.Count count(@Nullable final Object tree) {
        final Map<String, Object> o = JsonTree.object(tree);
        return new CardInput.Count(enumOf(PinRule.values(), str(o, "rule")), JsonTree.boxed(o, "pin"),
            JsonTree.num(o, "needed"), JsonTree.boxed(o, "utilization"), JsonTree.num(o, "nodeParallel"),
            bool(o, "customRate"), bool(o, "unset"));
    }

    private static CardInput.UpTo upTo(final Object tree) {
        final Map<String, Object> o = JsonTree.object(tree);
        return new CardInput.UpTo(JsonTree.num(o, "capacity"), JsonTree.num(o, "reached"),
            o.get("reason") instanceof String s ? enumOf(UpToReason.values(), s) : null, JsonTree.str(o, "portKey"),
            JsonTree.str(o, "resourceKey"), strings(o.get("tied")), JsonTree.boxed(o, "wants"),
            strings(o.get("upstream")));
    }

    private static CardInput.Forced forced(final Object tree) {
        final Map<String, Object> o = JsonTree.object(tree);
        return new CardInput.Forced(str(o, "portKey"), JsonTree.num(o, "perSecond"), JsonTree.str(o, "drawerId"));
    }

    private static CardInput.Leftover leftover(final Object tree) {
        return new CardInput.Leftover(list(JsonTree.object(tree)
            .get("bearers"), CardInputJson::bearer));
    }

    private static CardInput.Bearer bearer(final Object tree) {
        final Map<String, Object> o = JsonTree.object(tree);
        return new CardInput.Bearer(JsonTree.str(o, "nodeId"), JsonTree.str(o, "side"), JsonTree.num(o, "share"),
            JsonTree.str(o, "resourceKey"));
    }

    private static CardInput.Turbine turbine(final Object tree) {
        final Map<String, Object> o = JsonTree.object(tree);
        return new CardInput.Turbine((int) JsonTree.num(o, "turbines"), JsonTree.num(o, "flow"),
            JsonTree.num(o, "setFlow"), JsonTree.num(o, "optimal"), str(o, "unit"), JsonTree.num(o, "euMade"),
            JsonTree.num(o, "euCounted"));
    }

    private static CardInput.Menu menu(@Nullable final Object tree) {
        final Map<String, Object> o = JsonTree.object(tree);
        return new CardInput.Menu(bool(o, "calm"), bool(o, "locked"), bool(o, "ownsRecipe"), bool(o, "shared"),
            bool(o, "holder"), bool(o, "emptyCropFarm"), bool(o, "tasks"));
    }

    // ---- the convention's reads

    private interface Read<T> {

        T of(Object tree);
    }

    private static <T> List<T> list(@Nullable final Object tree, final Read<T> each) {
        final List<T> out = new ArrayList<>();
        for (final Object item : JsonTree.array(tree)) out.add(each.of(item));
        return out;
    }

    private static List<String> strings(@Nullable final Object tree) {
        final List<String> out = new ArrayList<>();
        for (final Object item : JsonTree.array(tree)) if (item instanceof String s) out.add(s);
        return out;
    }

    private static String str(final Map<String, Object> o, final String key) {
        final String s = JsonTree.str(o, key);
        return s == null ? "" : s;
    }

    private static boolean bool(final Map<String, Object> o, final String key) {
        return Boolean.TRUE.equals(JsonTree.bool(o, key));
    }

    private static <E extends Enum<E>> E enumOf(final E[] values, final String name) {
        for (final E e : values) if (e.name()
            .equals(name)) return e;
        throw new IllegalArgumentException("no constant named " + name);
    }
}
