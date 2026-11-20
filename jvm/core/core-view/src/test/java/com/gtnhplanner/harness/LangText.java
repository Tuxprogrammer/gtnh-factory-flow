package com.gtnhplanner.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.base.Text;

/**
 * The mod's English words installed as the core's {@link Text} for a test, and the words before put back on close.
 * The one language file is the mod's ({@code jvm/mod/src/main/resources/assets/gtnhplanner/lang/en_US.lang}); a core
 * test finds it from the repository root, which the conformance tree's root sits in.
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
        final Path file = Conformance.root()
            .getParent()
            .resolve("jvm/mod/src/main/resources/assets/gtnhplanner/lang/en_US.lang");
        final Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new IllegalStateException("en_US.lang not found at " + file, e);
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
