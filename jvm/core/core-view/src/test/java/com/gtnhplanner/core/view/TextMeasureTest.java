package com.gtnhplanner.core.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The default line breaking, for a host whose drawing does not wrap by itself. */
class TextMeasureTest {

    private static final TextMeasure SIX = (style, text) -> 6.0 * text.length();

    @Test
    void wrapsAtTheLastSpaceThatFitsCutsALongWordAndKeepsNewlines() {
        // 60 px: ten characters a line.
        assertEquals(List.of("one two", "three four"), SIX.wrap(TextStyle.LABEL, "one two three four", 60));
        assertEquals(List.of("abcdefghij", "klm"), SIX.wrap(TextStyle.LABEL, "abcdefghijklm", 60));
        assertEquals(List.of("short", "", "next"), SIX.wrap(TextStyle.LABEL, "short\n\nnext", 60));
        assertEquals(List.of("fits"), SIX.wrap(TextStyle.LABEL, "fits", 60));
        assertEquals(List.of(""), SIX.wrap(TextStyle.LABEL, "", 60));
    }
}
