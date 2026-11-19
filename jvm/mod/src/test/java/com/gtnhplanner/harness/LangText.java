package com.gtnhplanner.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import com.gtnhplanner.core.base.Text;

/**
 * The mod's English language file as the core's {@link Text}, for a test that asserts the words a player reads. Use it
 * in try-with-resources: closing puts back whatever was installed before.
 */
public final class LangText implements AutoCloseable {

    private final Text before = Text.get();

    private LangText(final Map<String, String> words) {
        Text.install(new Text() {

            @Override
            public String translate(final String key) {
                return words.getOrDefault(key, key);
            }

            @Override
            public String format(final String key, final Object... args) {
                final String pattern = words.get(key);
                return pattern == null ? key : String.format(pattern, args);
            }
        });
    }

    /** Installs the English words. */
    public static LangText english() {
        final Properties props = new Properties();
        try (InputStream in = LangText.class.getResourceAsStream("/assets/gtnhplanner/lang/en_US.lang")) {
            if (in == null) throw new IllegalStateException("en_US.lang is not on the classpath");
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        final Map<String, String> words = new HashMap<>();
        for (final String key : props.stringPropertyNames()) words.put(key, props.getProperty(key));
        return new LangText(words);
    }

    @Override
    public void close() {
        Text.install(before);
    }
}
