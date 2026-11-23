package com.gtnhplanner.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class PackVersionsTest {

    private static final List<String[]> TWO = Arrays
        .asList(new String[] { "stable-2.9.0", "2.9.0" }, new String[] { "stable-2.9.1", "2.9.1" });

    @Test
    void thePacksReleasePicksItsOwnListNotTheFirstOfTheLine() {
        assertEquals("stable-2.9.1", PackVersions.pick(TWO, "stable-2.9.0", "2.9.1", "2.9.105"));
        assertEquals("stable-2.9.0", PackVersions.pick(TWO, "stable-2.9.1", "2.9.0", "2.9.84"));
    }

    @Test
    void withoutAReleaseTheSitesCurrentListOfTheSameLineIsTaken() {
        assertEquals("stable-2.9.1", PackVersions.pick(TWO, "stable-2.9.1", "", "2.9.105"));
        assertEquals("stable-2.9.0", PackVersions.pick(TWO, null, "", "2.9.105"));
    }

    @Test
    void aCoreVersionAListIsPublishedUnderStillMatches() {
        final List<String[]> byCore = Arrays.asList(new String[] { "a", "2.9.84" }, new String[] { "b", "2.9.105" });
        assertEquals("b", PackVersions.pick(byCore, "a", "", "2.9.105"));
    }

    @Test
    void anotherLineFallsBackToTheCurrentListThenTheFirst() {
        assertEquals("stable-2.9.1", PackVersions.pick(TWO, "stable-2.9.1", "3.0.0", "3.0.2"));
        assertEquals("stable-2.9.0", PackVersions.pick(TWO, "gone", "3.0.0", "3.0.2"));
        assertNull(PackVersions.pick(Collections.<String[]>emptyList(), "x", "2.9.1", "2.9.105"));
    }

    @Test
    void aPlaceholderIsNotARelease() {
        assertEquals("", PackVersions.release("2.9.x"));
        assertEquals("", PackVersions.release(""));
        assertEquals("2.9.1", PackVersions.release(" 2.9.1 "));
        assertEquals("2.9.0-RC-2", PackVersions.release("2.9.0-RC-2"));
    }
}
