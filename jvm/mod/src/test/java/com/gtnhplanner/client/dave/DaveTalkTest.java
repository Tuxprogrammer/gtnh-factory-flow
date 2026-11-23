package com.gtnhplanner.client.dave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.harness.LangText;

class DaveTalkTest {

    /** A bridge that takes every ask unless told to refuse, and hands events in as the test says. */
    private static final class FakeLink implements DaveLink {

        boolean up = true;
        RuntimeException refuse;
        final List<String[]> asks = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();
        Consumer<String> listener;
        int unlistened;

        @Override
        public boolean available() {
            return up;
        }

        @Override
        public String ask(final String text, final String contextJson, final String attachmentJson) {
            if (refuse != null) throw refuse;
            asks.add(new String[] { text, contextJson, attachmentJson });
            return "r" + asks.size();
        }

        @Override
        public void cancel(final String requestId) {
            cancelled.add(requestId);
        }

        @Override
        public void listen(final Consumer<String> events) {
            listener = events;
        }

        @Override
        public void unlisten(final Consumer<String> events) {
            if (listener == events) listener = null;
            unlistened++;
        }

        void event(final String json) {
            listener.accept(json);
        }
    }

    private FakeLink link;
    private long now;
    private DaveTalk talk;
    private LangText english;

    @BeforeEach
    void setUp() {
        english = LangText.english();
        link = new FakeLink();
        talk = new DaveTalk(() -> link, () -> now);
        talk.tick();
    }

    @AfterEach
    void tearDown() {
        english.close();
    }

    private static final DaveTalk.Ask WITH_PLAN = new DaveTalk.Ask("{\"planId\":\"p\"}", "{\"version\":1}");

    private void ask(final String q) {
        talk.send(q, () -> WITH_PLAN);
    }

    private static final String FINAL = "{\"type\":\"reply\",\"surface\":\"gtnhplanner\",\"requestId\":\"r1\",\"final\":true,"
        + "\"text\":\"Use the Haber line.\\nAdd to plan puts it in.\",\"data\":{\"proposalId\":\"p1\",\"note\":\"Two new cards.\","
        + "\"recipes\":[{\"recipeId\":\"gt.a\",\"label\":\"Ammonia\",\"tier\":\"HV\"},{\"recipeId\":\"gt.b\",\"label\":\"Nitric acid\"}]}}";

    private List<String> texts() {
        return talk.rows()
            .stream()
            .map(r -> r.kind() + " " + r.text())
            .collect(Collectors.toList());
    }

    @Test
    void offeredOnlyWhileTheBridgeTakesAsks() {
        assertTrue(talk.available());
        link.up = false;
        talk.tick();
        assertFalse(talk.available());
        final DaveTalk none = new DaveTalk(() -> null, () -> 0);
        none.tick();
        assertFalse(none.available());
        assertFalse(none.canSend("hello"));
    }

    @Test
    void aQuestionGoesWithThePlanAndShowsWhereItIs() {
        ask("  How do I make ammonia?  ");
        assertEquals(1, link.asks.size());
        assertEquals("How do I make ammonia?", link.asks.get(0)[0]);
        assertEquals("{\"planId\":\"p\"}", link.asks.get(0)[1]);
        assertEquals("{\"version\":1}", link.asks.get(0)[2]);
        assertEquals(List.of("QUESTION How do I make ammonia?", "STATUS Sent. Waiting for Dave."), texts());
        assertFalse(talk.canSend("another"), "one question at a time");
        link.event("{\"type\":\"thinking\",\"surface\":\"gtnhplanner\",\"requestId\":\"r1\"}");
        assertEquals("STATUS Dave is thinking.", texts().get(1));
        now += DaveChat.QUIET_MS;
        talk.tick();
        assertEquals("STATUS Still waiting for Dave. Stop ends the question.", texts().get(1));
    }

    @Test
    void theAnswerItsNoteAndWhatItOffers() {
        ask("Ammonia?");
        final int before = talk.version();
        link.event(FINAL);
        assertTrue(talk.version() > before, "the panel lays out again");
        assertEquals(
            List.of(
                "QUESTION Ammonia?",
                "ANSWER Use the Haber line.",
                "ANSWER Add to plan puts it in.",
                "NOTE Two new cards.",
                "ADD_PLAN Add to plan",
                "RECIPE Ammonia (HV)",
                "RECIPE Nitric acid"),
            texts());
        assertTrue(talk.canSend("next"));
    }

    @Test
    void aPlanLeftOutIsSaidUnderTheQuestion() {
        talk.send("Is this ok?", () -> new DaveTalk.Ask("{}", null));
        assertNull(link.asks.get(0)[2], "no attachment");
        assertEquals(
            "NOTE Dave cannot see this plan this time: it is too big to send. He can answer in words or build a new plan.",
            texts().get(1));
    }

    @Test
    void refusalsAreShownNeverLost() {
        link.refuse = new IllegalArgumentException("text is longer than 2000 characters");
        ask("Too long");
        assertEquals("ERROR Dave could not take that question: text is longer than 2000 characters", texts().get(1));
        link.refuse = new IllegalStateException("not available");
        ask("Again");
        assertEquals("ERROR Dave is not reachable on this server right now.", texts().get(3));
        // The server's own refusals (its cooldown among them) come as an error event.
        link.refuse = null;
        ask("Third");
        link.event(
            "{\"type\":\"error\",\"surface\":\"gtnhplanner\",\"requestId\":\"r1\",\"final\":true,\"message\":\"Slow down.\"}");
        assertEquals("ERROR Slow down.", texts().get(5));
        ask("Fourth");
        link.event("{\"type\":\"error\",\"surface\":\"gtnhplanner\",\"requestId\":\"r2\",\"final\":true}");
        assertEquals("ERROR Dave could not answer.", texts().get(7));
    }

    @Test
    void theBridgeGoingAwayEndsTheQuestion() {
        ask("Still there?");
        link.up = false;
        talk.tick();
        assertEquals("ERROR The connection to Dave was lost before he answered.", texts().get(1));
        assertFalse(talk.running());
    }

    @Test
    void stopCancelsAndSaysSo() {
        ask("Never mind");
        talk.stop();
        assertEquals(List.of("r1"), link.cancelled);
        assertEquals("STATUS You stopped this question.", texts().get(1));
        link.event(FINAL);
        assertEquals(2, texts().size(), "nothing after a stop");
    }

    @Test
    void addToPlanGoesOnceThroughTheDeliveriesPath() {
        ask("Ammonia?");
        link.event(FINAL);
        final DaveChat.Turn turn = talk.turns()
            .get(0);
        final List<String> applied = new ArrayList<>();
        assertTrue(talk.addToPlan(turn, applied::add));
        assertFalse(talk.addToPlan(turn, applied::add), "used once");
        assertEquals(List.of("p1"), applied);
        final DaveTalk.Row key = talk.rows()
            .stream()
            .filter(r -> r.kind() == DaveTalk.Kind.ADD_PLAN)
            .findFirst()
            .orElseThrow();
        assertEquals("Added to plan", key.text());
        assertFalse(key.enabled());
        talk.applied(turn, "This was made for another plan.", true);
        final DaveTalk.Row told = talk.rows()
            .get(
                talk.rows()
                    .indexOf(addPlanRow()) + 1);
        assertEquals(DaveTalk.Kind.OUTCOME, told.kind());
        assertEquals("This was made for another plan.", told.text());
        assertTrue(told.problem());
    }

    @Test
    void addToPlanThatAddedNothingCanBePressedAgain() {
        ask("Ammonia?");
        link.event(FINAL);
        final DaveChat.Turn turn = talk.turns()
            .get(0);
        final List<String> applied = new ArrayList<>();
        assertTrue(talk.addToPlan(turn, applied::add));
        talk.applied(turn, "This was made for another plan.", true);
        DaveTalk.Row key = addPlanRow();
        assertEquals("Add to plan", key.text(), "nothing was added");
        assertTrue(key.enabled());
        assertTrue(talk.addToPlan(turn, applied::add), "on the right plan this time");
        talk.applied(turn, "Added 2 cards and 4 wires to Plan.", false);
        key = addPlanRow();
        assertEquals("Added to plan", key.text());
        assertFalse(key.enabled());
        assertFalse(talk.addToPlan(turn, applied::add), "added once");
        assertEquals(List.of("p1", "p1"), applied);
    }

    private DaveTalk.Row addPlanRow() {
        return talk.rows()
            .stream()
            .filter(r -> r.kind() == DaveTalk.Kind.ADD_PLAN)
            .findFirst()
            .orElseThrow();
    }

    @Test
    void aRecipeRowAddsItsRecipeAndSaysHowItWent() {
        ask("Ammonia?");
        link.event(FINAL);
        final DaveChat.Turn turn = talk.turns()
            .get(0);
        final List<BiConsumer<String, Boolean>> pending = new ArrayList<>();
        final List<String> asked = new ArrayList<>();
        final DaveTalk.RecipeAdder adder = (r, done) -> {
            asked.add(r.recipeId());
            pending.add(done);
        };
        assertTrue(talk.addRecipe(turn, 1, adder));
        assertFalse(talk.addRecipe(turn, 1, adder), "not twice while it goes on");
        assertEquals(List.of("gt.b"), asked);
        assertEquals("Adding", talk.addWord(recipeRow(1)));
        pending.get(0)
            .accept("This game has no recipe like this one.", true);
        assertEquals("OUTCOME This game has no recipe like this one.", texts().get(texts().size() - 1));
        assertTrue(recipeRow(1).enabled(), "a failed Add can be tried again");
        assertTrue(talk.addRecipe(turn, 1, adder));
        pending.get(1)
            .accept("Added to the board.", false);
        assertFalse(recipeRow(1).enabled());
        assertEquals("Added", talk.addWord(recipeRow(1)));
        assertEquals("Add", talk.addWord(recipeRow(0)));
    }

    private DaveTalk.Row recipeRow(final int index) {
        return talk.rows()
            .stream()
            .filter(r -> r.kind() == DaveTalk.Kind.RECIPE && r.index() == index)
            .findFirst()
            .orElseThrow();
    }

    @Test
    void leavingTheServerForgetsTheConversation() {
        ask("Hello");
        talk.forget();
        assertEquals(List.of("r1"), link.cancelled);
        assertEquals(1, link.unlistened);
        assertTrue(
            talk.rows()
                .isEmpty());
        assertFalse(talk.available(), "until the next tick looks again");
    }

    /** The panel's words, pinned: plain and short, no em dashes. */
    @Test
    void copy() throws Exception {
        final Properties lang = new Properties();
        try (var in = DaveTalkTest.class.getResourceAsStream("/assets/gtnhplanner/lang/en_US.lang")) {
            lang.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        final List<String> keys = lang.stringPropertyNames()
            .stream()
            .filter(k -> k.startsWith("gtnhplanner.dave."))
            .sorted()
            .collect(Collectors.toList());
        assertEquals(27, keys.size(), keys.toString());
        for (final String k : keys) assertFalse(
            lang.getProperty(k)
                .contains("—"),
            k);
        assertEquals("Ask Dave", lang.getProperty("gtnhplanner.dave.key"));
        assertEquals("Send", lang.getProperty("gtnhplanner.dave.send"));
        assertEquals("Stop", lang.getProperty("gtnhplanner.dave.stop"));
        assertEquals("%d/%d", lang.getProperty("gtnhplanner.dave.count"));
        assertEquals("Add to plan", lang.getProperty("gtnhplanner.dave.add_plan"));
        assertEquals("Add", lang.getProperty("gtnhplanner.dave.recipe.add"));
        assertEquals("Added to the board.", lang.getProperty("gtnhplanner.dave.recipe.done"));
        assertEquals("That recipe could not be read.", lang.getProperty("gtnhplanner.dave.recipe.unread"));
        assertEquals("The site does not know this recipe.", lang.getProperty("gtnhplanner.dave.recipe.unknown"));
        assertEquals(
            "Ask Dave about this plan or what to build next. He sees the open plan and what you have selected.",
            lang.getProperty("gtnhplanner.dave.empty"));
    }
}
