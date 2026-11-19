package com.gtnhplanner.machines.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

/** A resource file of this module as text, the one way its tables are read (the web build embeds them in the module). */
final class ResourceText {

    private ResourceText() {}

    /** The file's UTF-8 text, or null when the module has no such file. */
    @Nullable
    static String read(final String resource) {
        try (InputStream in = ResourceText.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }
}
