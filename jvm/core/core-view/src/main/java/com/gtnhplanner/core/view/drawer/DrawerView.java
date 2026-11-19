package com.gtnhplanner.core.view.drawer;

import javax.annotation.Nullable;

import com.gtnhplanner.core.view.TipView;

/**
 * What a drawer shows, every string final. A host draws it and adds only what is its own (pixels, fonts, its
 * pictures). The kinds are source, product, trash and buffer; a buffer carries a rule like a product and shows what its
 * makers force out beyond what its users take.
 */
public record DrawerView(String id, String kind, String label, String number, String unit, boolean zero,
    boolean numberSmall, boolean nameSmall, boolean nameClipped, boolean hasRule, String rule, String ruleMark,
    @Nullable Fig target, String boxText, boolean prompt, boolean unmet, @Nullable String reaches,
    @Nullable Double progress, String tone, boolean linked, String glanceRate, @Nullable Spill spill, Tips tips,
    @Nullable TipView flip) {

    /** A figure and the unit it is in. */
    public record Fig(String number, String unit) {}

    /** The Forced out tile of a buffer whose makers make more than its users take. */
    public record Spill(String number, String unit, TipView tip) {}

    public record Tips(TipView bar, TipView del, TipView rule, TipView rate, TipView body) {}
}
