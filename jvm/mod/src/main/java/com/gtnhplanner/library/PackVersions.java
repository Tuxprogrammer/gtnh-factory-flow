package com.gtnhplanner.library;

import java.util.List;

import javax.annotation.Nullable;

/**
 * Which of the site's item lists belongs to the pack the game runs. The GTNH core mod's own version ("2.9.105") is not
 * the pack's ("2.9.1"); the pack's is what the core mod shows in the window title, and what a list is published
 * under.
 */
public final class PackVersions {

    private PackVersions() {}

    /** The pack's release as the core mod shows it, or "" when the core mod is absent or names no release. */
    public static String release() {
        try {
            final Object shown = Class.forName("com.dreammaster.coremod.DreamCoreMod")
                .getField("displayedModpackVersion")
                .get(null);
            return shown instanceof String ? release((String) shown) : "";
        } catch (ReflectiveOperationException | LinkageError e) {
            return "";
        }
    }

    /** {@code shown} when it names one release ("2.9.1", "2.9.0-RC-2"), "" for a placeholder such as "2.9.x". */
    static String release(final String shown) {
        final String s = shown.trim();
        return s.matches("\\d+\\.\\d+\\.\\d+([.-][0-9A-Za-z.-]+)?") ? s : "";
    }

    /**
     * The list for a pack: the one published for its release, else for the core mod's version, else the site's
     * current list when it is of the same line ("2.9."), else the first of that line, else the site's current one,
     * else the first. {@code lists} holds {id, pack version} pairs in the site's order.
     */
    @Nullable
    public static String pick(final List<String[]> lists, @Nullable final String current, final String release,
        final String core) {
        if (lists.isEmpty()) return null;
        for (final String wanted : new String[] { release, core }) {
            if (wanted.isEmpty()) continue;
            for (final String[] v : lists) if (v[1].equals(wanted)) return v[0];
        }
        final String line = line(release.isEmpty() ? core : release);
        String ofCurrent = null;
        for (final String[] v : lists) if (v[0].equals(current)) ofCurrent = v[1];
        if (!line.isEmpty()) {
            if (ofCurrent != null && ofCurrent.startsWith(line)) return current;
            for (final String[] v : lists) if (v[1].startsWith(line)) return v[0];
        }
        return ofCurrent != null ? current : lists.get(0)[0];
    }

    /** "2.9." of "2.9.105"; "" when the version has no second dot. */
    private static String line(final String version) {
        final int dot = version.indexOf('.', version.indexOf('.') + 1);
        return dot > 0 ? version.substring(0, dot + 1) : "";
    }
}
