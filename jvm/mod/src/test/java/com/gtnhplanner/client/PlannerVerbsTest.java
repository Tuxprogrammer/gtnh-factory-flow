package com.gtnhplanner.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/** {@code /gtnhplanner}: only a known verb with one id of its own kind runs; everything else gets the usage line. */
class PlannerVerbsTest {

    private static final List<PlannerVerbs.Verb> VERBS = List
        .of(new PlannerVerbs.Verb("open", 'd', id -> {}), new PlannerVerbs.Verb("apply", 'p', id -> {}));

    private static PlannerVerbs.Call read(final String... args) {
        return PlannerVerbs.read(VERBS, args);
    }

    @Test
    void aVerbWithAnIdOfItsKindRuns() {
        final PlannerVerbs.Call open = read("open", "dabcdefghjkmnpqr");
        assertNotNull(open);
        assertEquals(
            "open",
            open.verb()
                .word());
        assertEquals("dabcdefghjkmnpqr", open.id());
        assertNotNull(read("apply", "pz23456789stuvwx"));
    }

    @Test
    void anIdOfTheOtherKindIsRefused() {
        assertNull(read("open", "pabcdefghjkmnpqr"));
        assertNull(read("apply", "dabcdefghjkmnpqr"));
    }

    @Test
    void anIdOffTheAlphabetOrLengthIsRefused() {
        assertNull(read("open", "dabcdefghjkmnpq"), "14 after the letter");
        assertNull(read("open", "dabcdefghjkmnpqrs"), "16 after the letter");
        for (final String odd : List.of("i", "l", "o", "0", "1", "A", "-", "_", " ", "§"))
            assertNull(read("open", "dabcdefghjkmnpq" + odd), odd);
        assertNull(read("open", "xabcdefghjkmnpqr"), "no such kind");
        assertNull(read("open", "dabcdefghjkmnpqr\n"));
    }

    @Test
    void anythingButOneVerbAndOneIdIsRefused() {
        assertNull(read());
        assertNull(read("open"));
        assertNull(read("open", "dabcdefghjkmnpqr", "x"));
        assertNull(read("open", "", "dabcdefghjkmnpqr"), "two spaces");
        assertNull(read("bookmark", "dabcdefghjkmnpqr"), "no such verb yet");
        assertNull(read("OPEN", "dabcdefghjkmnpqr"));
    }

    @Test
    void theWholeCommandStaysUnderAHundredCharacters() {
        final List<PlannerVerbs.Verb> long_ = List.of(new PlannerVerbs.Verb("o".repeat(70), 'd', id -> {}));
        assertNull(PlannerVerbs.read(long_, new String[] { "o".repeat(70), "dabcdefghjkmnpqr" }));
        final List<PlannerVerbs.Verb> fits = List.of(new PlannerVerbs.Verb("o".repeat(69), 'd', id -> {}));
        assertNotNull(PlannerVerbs.read(fits, new String[] { "o".repeat(69), "dabcdefghjkmnpqr" }), "99 characters");
    }

    @Test
    void usageNamesEveryVerb() {
        assertEquals("Use /gtnhplanner open <id> or /gtnhplanner apply <id>.", PlannerVerbs.usage(VERBS));
        assertEquals("Use /gtnhplanner open <id>.", PlannerVerbs.usage(VERBS.subList(0, 1)));
    }

    @Test
    void theCommandBookmarksAPlanById() {
        final PlannerVerbs.Call call = PlannerVerbs
            .read(PlannerCommand.VERBS, new String[] { "bookmark", "dabcdefghjkmnpqr" });
        assertNotNull(call);
        assertEquals(
            "bookmark",
            call.verb()
                .word());
        assertNull(PlannerVerbs.read(PlannerCommand.VERBS, new String[] { "bookmark", "pabcdefghjkmnpqr" }));
        assertEquals(
            "Use /gtnhplanner open <id>, /gtnhplanner apply <id> or /gtnhplanner bookmark <id>.",
            PlannerVerbs.usage(PlannerCommand.VERBS));
    }

    @Test
    void tabCompletesTheVerbOnly() {
        assertEquals(
            List.of("open", "apply", "bookmark"),
            PlannerVerbs.complete(PlannerCommand.VERBS, new String[] { "" }));
        assertEquals(List.of("bookmark"), PlannerVerbs.complete(PlannerCommand.VERBS, new String[] { "b" }));
        assertEquals(List.of(), PlannerVerbs.complete(PlannerCommand.VERBS, new String[] { "x" }));
        assertEquals(List.of(), PlannerVerbs.complete(PlannerCommand.VERBS, new String[] { "bookmark", "d" }));
    }

    @Test
    void refusalLines() {
        assertEquals(
            "Nothing is stored under that id, or it has expired.",
            DeliveryText.refused(404, "Plan not found."));
        assertEquals("The planner site is busy. Try again in a minute.", DeliveryText.refused(429, "x"));
        assertEquals(
            "The planner site said no: the library answered 503",
            DeliveryText.refused(503, "the library answered 503"));
        assertEquals("Opened Platline as a new plan.", DeliveryText.opened("Platline"));
    }
}
