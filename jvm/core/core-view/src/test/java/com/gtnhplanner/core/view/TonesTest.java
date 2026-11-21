package com.gtnhplanner.core.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Every colour role is a tokens.json colour, with the value and the custom property the website's stylesheet has. */
class TonesTest {

    @Test
    void everyRoleIsATokensColourAndItsCustomProperty() throws IOException {
        final JsonObject colors;
        try (Reader r = Files.newBufferedReader(Paths.get("tokens", "tokens.json"), StandardCharsets.UTF_8)) {
            colors = new JsonParser().parse(r)
                .getAsJsonObject()
                .getAsJsonObject("colors");
        }
        for (final Tones.Role role : Tones.Role.values()) {
            final JsonObject group = colors.getAsJsonObject(role.group);
            assertTrue(group.has(role.key), role.name());
            final int argb = 0xFF000000 | Integer.parseInt(
                group.get(role.key)
                    .getAsString()
                    .substring(1),
                16);
            assertEquals(argb, role.token, role.name());
            assertEquals(argb, Tones.TOKENS.argb(role), role.name());
        }
        assertEquals("--cc-surface", Tones.Role.SURFACE.cssVar());
        assertEquals("--cc-tone-bottleneck", Tones.Role.BOTTLENECK.cssVar());
    }
}
