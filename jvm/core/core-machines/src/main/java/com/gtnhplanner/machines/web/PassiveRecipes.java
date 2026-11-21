package com.gtnhplanner.machines.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.NumberText;

/**
 * The website's bee and crop recognisers (passive-production.ts: isCropProductionRecipe, isBeeProductionRecipe,
 * getCropsNhStats, isIndustrialApiaryMachineType), translated word for word. A recipe they name is a bee or crop card,
 * whose maths are the website's own passive-production model and this module's {@code Bees} and {@code CropsNh}; the
 * generic machine readout does not cover it ({@link NodeMath#covers}), so the website and this module agree on which
 * cards the readout answers.
 */
final class PassiveRecipes {

    private PassiveRecipes() {}

    /** A bee or crop card: the website's websiteOnly (src/lib/core/machines.ts). */
    static boolean beeOrCrop(final Web.Recipe recipe) {
        final List<String> label = words(
            (recipe.source != null && recipe.source.recipeMap != null ? recipe.source.recipeMap : "") + " "
                + (recipe.machineType != null ? recipe.machineType : ""));
        return isCropProduction(label) || isBeeProduction(label) || hasCropsNhStats(recipe)
            || isIndustrialApiary(recipe.machineType);
    }

    /** isCropProductionRecipe: not the Tree Growth Simulator; "ic2 crop(s)", "crop(s)nh", "crop production", "crop farm". */
    static boolean isCropProduction(final List<String> label) {
        if (String.join(" ", label).equals("tree growth simulator")) return false;
        for (int i = 0; i < label.size(); i++) {
            final String w = label.get(i);
            final String next = i + 1 < label.size() ? label.get(i + 1) : "";
            if (w.equals("ic2") && (next.equals("crop") || next.equals("crops"))) return true;
            if (w.equals("cropnh") || w.equals("cropsnh")) return true;
            if (w.equals("crop") && (next.equals("production") || next.equals("farm"))) return true;
        }
        return false;
    }

    /** isBeeProductionRecipe: "bee produce", "bee production", "bee product(s)", "apiary", "alveary". */
    static boolean isBeeProduction(final List<String> label) {
        for (int i = 0; i < label.size(); i++) {
            final String w = label.get(i);
            final String next = i + 1 < label.size() ? label.get(i + 1) : "";
            if (w.equals("bee") && (next.equals("produce") || next.equals("production") || next.equals("product")
                || next.equals("products"))) return true;
            if (w.equals("apiary") || w.equals("alveary")) return true;
        }
        return false;
    }

    /** isIndustrialApiaryMachineType: the normalised machine name contains "industrial apiary" (as text, not words). */
    static boolean isIndustrialApiary(@Nullable final String machineType) {
        return machineType != null && String.join(" ", words(machineType)).contains("industrial apiary");
    }

    /** getCropsNhStats(recipe) !== undefined: metadata.cropsNh with a positive tier, growthPoints and dropChance. */
    static boolean hasCropsNhStats(final Web.Recipe recipe) {
        final Object meta = recipe.metadata != null ? recipe.metadata.get("cropsNh") : null;
        if (!(meta instanceof Map)) return false;
        final Map<?, ?> m = (Map<?, ?>) meta;
        return positive(m.get("tier")) && positive(m.get("growthPoints")) && positive(m.get("dropChance"));
    }

    /** toPositiveNumber: JavaScript's Number(value) finite and above 0 (a list or object is not a number here). */
    private static boolean positive(@Nullable final Object value) {
        final double n;
        if (value instanceof Number) n = ((Number) value).doubleValue();
        else if (value instanceof Boolean) n = (Boolean) value ? 1 : 0;
        else if (value instanceof String) n = NumberText.parse((String) value);
        else return false;
        return !Double.isNaN(n) && !Double.isInfinite(n) && n > 0;
    }

    /**
     * normalizeLabel, as its words: lower case, the words "recipe", "recipes" and "map" dropped (a word being a run of
     * ASCII letters, digits and underscores, JavaScript's \w), then every run of anything but ASCII letters and digits a
     * single break.
     */
    static List<String> words(final String value) {
        final String s = value.trim().toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            if (!isWord(s.charAt(i))) {
                i++;
                continue;
            }
            int end = i;
            while (end < s.length() && isWord(s.charAt(end))) end++;
            final String word = s.substring(i, end);
            if (!word.equals("recipe") && !word.equals("recipes") && !word.equals("map")) {
                int from = 0;
                for (int k = 0; k <= word.length(); k++) {
                    if (k == word.length() || word.charAt(k) == '_') {
                        if (k > from) out.add(word.substring(from, k));
                        from = k + 1;
                    }
                }
            }
            i = end;
        }
        return out;
    }

    private static boolean isWord(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }
}
