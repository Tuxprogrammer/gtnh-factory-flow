package com.gtnhplanner.core.base;

import java.util.Objects;

/**
 * Words the player reads. The core names a language key and its arguments; the host supplies the words in the
 * player's language (the mod: Minecraft's language files; the website: its own table). The host installs its own once
 * at start-up; until then {@link #KEYS}, where every key reads as itself. Text made here is for people: nothing may
 * parse, compare or hash it.
 */
public interface Text {

    /** The words for {@code key}; the key itself when the host has none. */
    String translate(String key);

    /**
     * The words for {@code key} with {@code args} put into the language file's placeholders ({@code %s}, {@code %d},
     * {@code %.1f}, {@code %1$s}: the forms Java's formatter and Minecraft's language files use); the bare key, without
     * the arguments, when the host has no words for it.
     */
    String format(String key, Object... args);

    /** The installed text source. */
    static Text get() {
        return Installed.text;
    }

    /** Installs the host's text source. */
    static void install(final Text text) {
        Installed.text = Objects.requireNonNull(text, "text");
    }

    /** No language: every key reads as itself. */
    Text KEYS = new Text() {

        @Override
        public String translate(final String key) {
            return key;
        }

        @Override
        public String format(final String key, final Object... args) {
            return key;
        }
    };
}
