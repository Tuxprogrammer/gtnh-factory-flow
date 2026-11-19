package com.gtnhplanner.core.view;

import java.util.ArrayList;
import java.util.List;

/**
 * How wide a host draws a string, in board pixels: the one fact about text the core cannot know, since the mod draws
 * the game font and the website its own face. A view rule that shortens, steps down or wraps text asks this and
 * nothing else, so the rule is shared and only the widths are the host's.
 *
 * <p>
 * The mod's is its font renderer ({@code ui.theme.HybMeasure}); the website's will be {@code text-measure.ts} (a
 * canvas measure of the clean face per style, and Minecraft's glyph table for {@link TextStyle#GAME}). Where the two
 * faces differ, a rule that picks the first line that fits can pick a different line on each side: that is expected,
 * and a test of such a rule names the widths it ran with.
 */
public interface TextMeasure {

    /** The width of {@code text} drawn in {@code style}, in board pixels (whole on the mod, fractional on the web). */
    double width(TextStyle style, String text);

    /**
     * {@code text} broken into lines no wider than {@code room}: at the last space that fits, a word wider than the
     * room cut where it stops fitting, a newline always. A host whose own drawing wraps (the game's font renderer)
     * overrides this so the lines are the ones it draws.
     */
    default List<String> wrap(final TextStyle style, final String text, final double room) {
        final List<String> lines = new ArrayList<>();
        for (final String paragraph : text.split("\n", -1)) {
            String rest = paragraph;
            while (width(style, rest) > room && rest.length() > 1) {
                int cut = 1;
                while (cut < rest.length() && width(style, rest.substring(0, cut + 1)) <= room) cut++;
                final int space = rest.lastIndexOf(' ', cut);
                final int end = space > 0 ? space : cut;
                lines.add(rest.substring(0, end));
                rest = rest.substring(space > 0 ? end + 1 : end);
            }
            lines.add(rest);
        }
        return lines;
    }
}
