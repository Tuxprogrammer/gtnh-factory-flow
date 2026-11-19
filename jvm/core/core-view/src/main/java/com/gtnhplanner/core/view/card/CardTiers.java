package com.gtnhplanner.core.view.card;

/**
 * The voltage tiers as a card names them, ULV first, and a tier's place among them. Moved out of the mod's
 * {@code CardDefaults} (which reads NEI) so the card's view rules can step and compare tiers without the game. Unlike
 * {@code machines.web.Tiers.index}, an unknown name is -1 and case does not matter: the card's chips read names the
 * player's plan and old saves spell either way.
 */
public final class CardTiers {

    public static final String[] TIERS = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV", "UIV",
        "UMV", "UXV", "MAX" };

    private CardTiers() {}

    public static int tierIndex(final String name) {
        for (int i = 0; i < TIERS.length; i++) if (TIERS[i].equalsIgnoreCase(name)) return i;
        return -1;
    }
}
