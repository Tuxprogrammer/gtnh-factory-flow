package com.gtnhplanner.client.dave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The Ask Dave conversation against the events the bridge sends (its protocol, section 2.7). */
class DaveChatTest {

    private static String thinking(final String id) {
        return "{\"type\":\"thinking\",\"surface\":\"gtnhplanner\",\"requestId\":\"" + id + "\"}";
    }

    private static String reply(final String id, final boolean last, final String text, final String data) {
        return "{\"type\":\"reply\",\"surface\":\"gtnhplanner\",\"requestId\":\"" + id
            + "\",\"final\":"
            + last
            + ",\"text\":\""
            + text
            + "\",\"componentsJson\":[]"
            + (data == null ? "" : ",\"data\":" + data)
            + "}";
    }

    private static String error(final String id, final String message) {
        return "{\"type\":\"error\",\"surface\":\"gtnhplanner\",\"requestId\":\"" + id
            + "\",\"final\":true,\"message\":\""
            + message
            + "\"}";
    }

    @Test
    void aQuestionGoesFromAskingThroughThinkingToAnswered() {
        final DaveChat chat = new DaveChat();
        final DaveChat.Turn t = chat.asked("what feeds this?", "r1", null, 0);
        assertEquals(DaveChat.State.ASKING, t.state());
        assertTrue(chat.onEvent(thinking("r1"), 10));
        assertEquals(DaveChat.State.THINKING, t.state());
        assertTrue(chat.onEvent(reply("r1", false, "Checking ammonia lines...", null), 20));
        assertEquals(DaveChat.State.THINKING, t.state());
        assertTrue(chat.onEvent(reply("r1", true, "Use the Haber line.\\nAdd puts it in.", null), 30));
        assertEquals(DaveChat.State.ANSWERED, t.state());
        assertEquals(List.of("Checking ammonia lines...", "Use the Haber line.", "Add puts it in."), t.lines());
        assertNull(chat.running());
    }

    @Test
    void aReplyMayComeWithoutAThinkingEventFirst() {
        final DaveChat chat = new DaveChat();
        final DaveChat.Turn t = chat.asked("hi", "r1", null, 0);
        assertTrue(chat.onEvent(reply("r1", true, "Hello.", null), 5));
        assertEquals(DaveChat.State.ANSWERED, t.state());
    }

    @Test
    void theFinalRepliesDataGivesTheProposalRecipesAndNote() {
        final DaveChat chat = new DaveChat();
        final DaveChat.Turn t = chat.asked("build it", "r1", null, 0);
        chat.onEvent(
            reply(
                "r1",
                true,
                "Done.",
                "{\"proposalId\":\"p1\",\"note\":\"Two cards.\",\"other\":7,\"recipes\":[{\"recipeId\":\"gt.a\",\"label\":\"Ammonia\",\"machine\":\"Large Chemical Reactor\",\"tier\":\"HV\"},{\"label\":\"no id\"},{\"recipeId\":\"gt.b\"}]}"),
            1);
        assertEquals("p1", t.proposalId());
        assertEquals("Two cards.", t.note());
        assertEquals(
            List.of(
                new DaveChat.Recipe("gt.a", "Ammonia", "Large Chemical Reactor", "HV"),
                new DaveChat.Recipe("gt.b", "gt.b", null, null)),
            t.recipes());
    }

    @Test
    void dataOnAnEarlierReplyIsActedOnOnlyOnceTheFinalComesAndTheLatestWins() {
        final DaveChat chat = new DaveChat();
        final DaveChat.Turn t = chat.asked("build it", "r1", null, 0);
        chat.onEvent(reply("r1", false, "Working.", "{\"proposalId\":\"p1\"}"), 1);
        assertNull(t.proposalId(), "not before the final reply");
        chat.onEvent(reply("r1", false, "", "{\"proposalId\":\"p2\"}"), 2);
        chat.onEvent(reply("r1", true, "Done.", null), 3);
        assertEquals("p2", t.proposalId());
    }

    @Test
    void eventsForAnotherSurfaceAnUnknownRequestOrAFinishedOneAreDropped() {
        final DaveChat chat = new DaveChat();
        final DaveChat.Turn t = chat.asked("q", "r1", null, 0);
        assertFalse(chat.onEvent(thinking("r9"), 1));
        assertFalse(chat.onEvent(thinking("r1").replace("gtnhplanner", "other"), 1));
        assertFalse(chat.onEvent("not json", 1));
        assertFalse(chat.onEvent("{\"type\":\"thinking\"}", 1));
        chat.onEvent(reply("r1", true, "Done.", null), 2);
        assertFalse(chat.onEvent(reply("r1", true, "Again.", null), 3));
        assertFalse(chat.onEvent(error("r1", "late"), 3));
        assertEquals(List.of("Done."), t.lines());
        assertEquals(DaveChat.State.ANSWERED, t.state());
    }

    @Test
    void stopEndsTheQuestionAndLaterEventsForItAreDropped() {
        final DaveChat chat = new DaveChat();
        final DaveChat.Turn t = chat.asked("q", "r1", null, 0);
        chat.onEvent(thinking("r1"), 1);
        assertEquals("r1", chat.stop());
        assertEquals(DaveChat.State.STOPPED, t.state());
        assertNull(chat.stop(), "nothing left to stop");
        assertFalse(chat.onEvent(reply("r1", true, "Too late.", "{\"proposalId\":\"p1\"}"), 2));
        assertTrue(
            t.lines()
                .isEmpty());
        assertNull(t.proposalId());
    }

    @Test
    void anErrorEndsTheQuestionWithItsMessage() {
        final DaveChat chat = new DaveChat();
        final DaveChat.Turn t = chat.asked("q", "r1", null, 0);
        chat.onEvent(reply("r1", false, "Partly.", null), 1);
        assertTrue(chat.onEvent(error("r1", "Dave is not connected right now."), 2));
        assertEquals(DaveChat.State.ERROR, t.state());
        assertEquals("Dave is not connected right now.", t.error());
        assertNull(chat.running());
    }

    @Test
    void aRefusedQuestionAndALostBridgeAreErrorsNotSilence() {
        final DaveChat chat = new DaveChat();
        final DaveChat.Turn refused = chat.refused("q", "slow down", 0);
        assertEquals(DaveChat.State.ERROR, refused.state());
        assertEquals("slow down", refused.error());
        final DaveChat.Turn t = chat.asked("q2", "r2", null, 1);
        chat.lost("gone");
        assertEquals(DaveChat.State.ERROR, t.state());
        assertEquals("gone", t.error());
    }

    @Test
    void aQuietQuestionKeepsWaitingAndCanStillBeStopped() {
        final DaveChat chat = new DaveChat();
        final DaveChat.Turn t = chat.asked("q", "r1", null, 0);
        assertFalse(chat.quiet(DaveChat.QUIET_MS - 1));
        assertTrue(chat.quiet(DaveChat.QUIET_MS));
        chat.onEvent(thinking("r1"), DaveChat.QUIET_MS);
        assertFalse(chat.quiet(DaveChat.QUIET_MS + 1), "a thinking event counts as news");
        assertEquals(DaveChat.State.THINKING, t.state());
        assertEquals("r1", chat.stop());
    }

    @Test
    void aQuestionMustSaySomethingWithinTheLimit() {
        assertFalse(DaveChat.fits("   ", 2000));
        assertTrue(DaveChat.fits("x".repeat(2000), 2000));
        assertFalse(DaveChat.fits("x".repeat(2001), 2000));
    }
}
