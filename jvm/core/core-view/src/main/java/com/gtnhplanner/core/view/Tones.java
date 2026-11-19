package com.gtnhplanner.core.view;

import java.util.Locale;

/**
 * Named colour roles, resolved by each host. A view record says which role a thing wears ({@link Role}, crossing to
 * TypeScript as its name), never a colour: the renderer resolves it. Core code that must compute with a colour (a mix
 * over the card's surface, say) asks a {@code Tones} for it, so a host with a theme of its own gets its own.
 *
 * <p>
 * Every role is a colour of {@code tokens.json}, so {@link #TOKENS} is the same table on both hosts, and the mod
 * resolves through it. The website resolves a role to the custom property its stylesheet already carries
 * ({@link Role#cssVar()}, generated from the same JSON by {@code tools/tokens/generate.mjs}), and to
 * {@code tokens.generated.ts}' value where it has to compute.
 */
public interface Tones {

    /** A colour role: its {@code tokens.json} group and key, and the shared value. */
    enum Role {

        SURFACE("palette", "surface", Tokens.Palette.SURFACE),
        EDGE("palette", "edge", Tokens.Palette.EDGE),
        HAIR("palette", "hair", Tokens.Palette.HAIR),
        INK("palette", "ink", Tokens.Palette.INK),
        MUTED("palette", "muted", Tokens.Palette.MUTED),
        GOLD("palette", "gold", Tokens.Palette.GOLD),
        SELECT("palette", "select", Tokens.Palette.SELECT),
        RED("palette", "red", Tokens.Palette.RED),
        AMBER("palette", "amber", Tokens.Palette.AMBER),
        OK("palette", "ok", Tokens.Palette.OK),
        SOURCE("palette", "source", Tokens.Palette.SOURCE),
        PRODUCT("palette", "product", Tokens.Palette.PRODUCT),
        /** The foot bar's tones: an Up to reason, FORCED IN, or plain. */
        FULL("tone", "full", Tokens.Tone.FULL),
        BOTTLENECK("tone", "bottleneck", Tokens.Tone.BOTTLENECK),
        STARVED("tone", "starved", Tokens.Tone.STARVED),
        CLOGGED("tone", "clogged", Tokens.Tone.CLOGGED),
        UNUSED("tone", "unused", Tokens.Tone.UNUSED),
        LIMITED("tone", "limited", Tokens.Tone.LIMITED),
        FORCED("tone", "forced", Tokens.Tone.FORCED),
        PLAIN("tone", "plain", Tokens.Tone.PLAIN);

        /** The {@code tokens.json} colour group and the key in it. */
        public final String group, key;
        /** The shared value, ARGB. */
        public final int token;

        Role(final String group, final String key, final int token) {
            this.group = group;
            this.key = key;
            this.token = token;
        }

        /** The website's custom property for the role: {@code --cc-<key>} for the palette, {@code --cc-tone-<key>}. */
        public String cssVar() {
            final String kebab = key.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase(Locale.ROOT);
            return "palette".equals(group) ? "--cc-" + kebab : "--cc-" + group + "-" + kebab;
        }
    }

    /** A role's colour on this host, ARGB. */
    int argb(Role role);

    /** The shared table: every role's {@code tokens.json} value. The mod's tones. */
    Tones TOKENS = role -> role.token;
}
