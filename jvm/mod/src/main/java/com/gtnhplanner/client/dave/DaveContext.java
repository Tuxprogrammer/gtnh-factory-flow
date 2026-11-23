package com.gtnhplanner.client.dave;

import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The context an ask carries: a small JSON object Dave reads in his prompt (the bridge takes 16 KiB; this stays under
 * {@link #MAX_CHARS} so it never crowds the question). The plan itself goes as the attachment; this only names it, says
 * what is selected and, when the plan was too big to attach, says so. Pure.
 */
public final class DaveContext {

    /** The most text the context may take. */
    public static final int MAX_CHARS = 3_500;
    /** The most selected cards named. */
    public static final int MAX_SELECTION = 8;

    /** GregTech's voltage tiers, lowest first: the plan's tier is the highest any card runs at. */
    static final List<String> TIERS = List
        .of("ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV", "UIV", "UMV", "UXV", "MAX");

    /** A selected card: its board id, the recipe id the snapshot gives it, and a name a player would know it by. */
    public record Card(String id, String recipeId, String label) {}

    /** The open plan as the context names it. */
    public record Plan(String planId, String planName, String dataset, int cardCount, boolean live, String tier,
        List<Card> selection) {}

    private DaveContext() {}

    /** The context's JSON text; {@code tooLarge} when the plan is left out of the ask. */
    public static String json(final Plan p, final boolean tooLarge) {
        int keep = Math.min(
            MAX_SELECTION,
            p.selection()
                .size());
        String text = build(p, tooLarge, keep);
        while (text.length() > MAX_CHARS && keep > 0) text = build(p, tooLarge, --keep);
        return text;
    }

    private static String build(final Plan p, final boolean tooLarge, final int keep) {
        final JsonObject o = new JsonObject();
        o.addProperty("planId", cut(p.planId(), 100));
        o.addProperty("planName", cut(p.planName(), 120));
        o.addProperty("dataset", cut(p.dataset(), 40));
        o.addProperty("cardCount", p.cardCount());
        o.addProperty("live", p.live());
        o.addProperty("tier", cut(p.tier(), 8));
        final JsonArray sel = new JsonArray();
        for (final Card c : p.selection()
            .subList(0, keep)) {
            final JsonObject s = new JsonObject();
            s.addProperty("id", cut(c.id(), 64));
            s.addProperty("recipeId", cut(c.recipeId(), 120));
            s.addProperty("label", cut(c.label(), 80));
            sel.add(s);
        }
        o.add("selection", sel);
        if (tooLarge) o.addProperty("attachment", "too-large");
        return o.toString();
    }

    /** The highest of {@code tiers} by GregTech's order; "" when none is a voltage tier. */
    public static String topTier(final Collection<String> tiers) {
        int top = -1;
        for (final String t : tiers) top = Math.max(top, TIERS.indexOf(t));
        return top < 0 ? "" : TIERS.get(top);
    }

    private static String cut(@Nullable final String s, final int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
