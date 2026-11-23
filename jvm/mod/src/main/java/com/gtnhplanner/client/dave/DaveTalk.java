package com.gtnhplanner.client.dave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.Text;

/**
 * What the Ask Dave panel shows and does, apart from drawing it: sending a question with the plan, stopping it, the
 * bridge going away, Add to plan and a recipe row's Add, and the rows the panel draws. One per game session (the
 * panel can close and open again; a disconnect forgets it), never saved. Pure: the link, the clock, and what adds to
 * the board come in from outside. Client thread.
 */
public final class DaveTalk {

    /** What one ask sends besides the question: the context, and the plan or null when it is left out. */
    public record Ask(String context, @Nullable String attachment) {}

    /** Adds one recipe to the open plan and tells {@code done} the line its row shows and whether that is a problem. */
    public interface RecipeAdder {

        void add(DaveChat.Recipe recipe, BiConsumer<String, Boolean> done);
    }

    /** What a row of the conversation is. */
    public enum Kind {
        QUESTION,
        ANSWER,
        /** A quiet line: a note under the answer, or one about the question. */
        NOTE,
        /** Where a running question is. */
        STATUS,
        ERROR,
        /** The Add to plan key; {@link Row#enabled} until used. */
        ADD_PLAN,
        /** A recipe Dave suggests, with its Add key ({@link Row#enabled} while it can be added). */
        RECIPE,
        /** What came of an Add: {@link Row#problem} when it failed. */
        OUTCOME
    }

    /** One row: its text, the turn it is part of and, for a recipe, its place in the turn's list. */
    public record Row(Kind kind, String text, @Nullable DaveChat.Turn turn, int index, boolean enabled,
        boolean problem) {}

    private record Told(String text, boolean problem) {}

    private final Supplier<DaveLink> links;
    private final LongSupplier clock;
    private DaveChat chat = new DaveChat();
    private boolean available;
    private boolean quiet;
    private int version;
    @Nullable
    private DaveLink listening;
    private final Consumer<String> listener = this::onEvent;
    private final Set<DaveChat.Turn> applied = new HashSet<>();
    private final Map<DaveChat.Turn, Told> appliedOutcome = new HashMap<>();
    private final Map<DaveChat.Turn, Map<Integer, Told>> recipeOutcome = new HashMap<>();
    private final Set<String> adding = new HashSet<>();

    /** {@code links} gives the link, or null when there is none (asked on {@link #tick}, never per frame). */
    public DaveTalk(final Supplier<DaveLink> links, final LongSupplier clock) {
        this.links = links;
        this.clock = clock;
    }

    /** Bumped on every change, so the panel lays its rows out again only then. */
    public int version() {
        return version;
    }

    /** Whether Ask Dave is offered: the link is there and the server takes asks. As of the last tick. */
    public boolean available() {
        return available;
    }

    public boolean running() {
        return chat.running() != null;
    }

    public List<DaveChat.Turn> turns() {
        return chat.turns();
    }

    /** Once a game tick: whether the link is there, a question cut off by the bridge going away, a long wait. */
    public void tick() {
        final DaveLink link = links.get();
        final boolean now = link != null && link.available();
        if (now != available) {
            available = now;
            version++;
        }
        if (!now && chat.running() != null) {
            chat.lost(t("gtnhplanner.dave.gone"));
            version++;
        }
        final boolean q = chat.quiet(clock.getAsLong());
        if (q != quiet) {
            quiet = q;
            version++;
        }
    }

    /** Whether {@code text} may be sent now: something to ask, short enough, nothing else running. */
    public boolean canSend(final String text) {
        return available && chat.running() == null && DaveChat.fits(text, DaveLink.MAX_TEXT);
    }

    /**
     * Sends {@code question} with what {@code ask} gives (read only now, as building it reads the whole plan). A
     * refusal becomes the turn's error, never lost.
     */
    public void send(final String question, final Supplier<Ask> ask) {
        final String q = question.trim();
        if (!DaveChat.fits(q, DaveLink.MAX_TEXT) || chat.running() != null) return;
        final long now = clock.getAsLong();
        version++;
        final DaveLink link = links.get();
        if (link == null || !link.available()) {
            chat.refused(q, t("gtnhplanner.dave.off"), now);
            return;
        }
        try {
            listenOn(link);
            final Ask a = ask.get();
            final String id = link.ask(q, a.context(), a.attachment());
            chat.asked(q, id, a.attachment() == null ? "gtnhplanner.dave.no_plan" : null, now);
        } catch (final IllegalArgumentException e) {
            chat.refused(
                q,
                Text.get()
                    .format("gtnhplanner.dave.refused", reason(e)),
                now);
        } catch (final IllegalStateException e) {
            chat.refused(q, t("gtnhplanner.dave.off"), now);
        }
    }

    /** Stops the running question: nothing more comes for it. */
    public void stop() {
        final String id = chat.stop();
        if (id == null) return;
        version++;
        final DaveLink link = listening;
        if (link != null) try {
            link.cancel(id);
        } catch (final RuntimeException e) {
            // The bridge is gone: there is nothing left to stop.
        }
    }

    /** The game left the server: the conversation goes. */
    public void forget() {
        stop();
        if (listening != null) try {
            listening.unlisten(listener);
        } catch (final RuntimeException e) {
            // Gone with the bridge.
        }
        listening = null;
        chat = new DaveChat();
        applied.clear();
        appliedOutcome.clear();
        recipeOutcome.clear();
        adding.clear();
        available = false;
        quiet = false;
        version++;
    }

    /**
     * Add to plan: hands the turn's proposal to {@code apply} once (the deliveries path, which adds only, as one undo
     * step, and refuses a proposal made for another plan); its outcome comes back through {@link #applied}.
     */
    public boolean addToPlan(final DaveChat.Turn turn, final Consumer<String> apply) {
        final String id = turn.proposalId();
        if (id == null || !applied.add(turn)) return false;
        version++;
        apply.accept(id);
        return true;
    }

    /** What came of Add to plan for {@code turn}. */
    public void applied(final DaveChat.Turn turn, final String text, final boolean problem) {
        appliedOutcome.put(turn, new Told(text, problem));
        // A refusal or a failure added nothing, so the player may press it again (on the right plan, or later).
        if (problem) applied.remove(turn);
        version++;
    }

    /** A recipe row's Add: the recipe as a new card. Ignored while it is being added or once it was. */
    public boolean addRecipe(final DaveChat.Turn turn, final int index, final RecipeAdder adder) {
        final List<DaveChat.Recipe> recipes = turn.recipes();
        if (index < 0 || index >= recipes.size() || !canAddRecipe(turn, index)) return false;
        final String key = key(turn, index);
        adding.add(key);
        version++;
        adder.add(recipes.get(index), (text, problem) -> {
            adding.remove(key);
            recipeOutcome.computeIfAbsent(turn, k -> new HashMap<>())
                .put(index, new Told(text, problem));
            version++;
        });
        return true;
    }

    private boolean canAddRecipe(final DaveChat.Turn turn, final int index) {
        if (adding.contains(key(turn, index))) return false;
        final Told told = recipeOutcome.getOrDefault(turn, Map.of())
            .get(index);
        return told == null || told.problem();
    }

    private String key(final DaveChat.Turn turn, final int index) {
        return System.identityHashCode(turn) + ":" + index;
    }

    /** The conversation as the panel shows it, oldest first. */
    public List<Row> rows() {
        final List<Row> out = new ArrayList<>();
        for (final DaveChat.Turn turn : chat.turns()) {
            out.add(new Row(Kind.QUESTION, turn.question, turn, -1, false, false));
            if (turn.noteKey != null) out.add(new Row(Kind.NOTE, t(turn.noteKey), turn, -1, false, false));
            for (final String line : turn.lines()) out.add(new Row(Kind.ANSWER, line, turn, -1, false, false));
            switch (turn.state()) {
                case ASKING -> out.add(status(turn, quiet ? "gtnhplanner.dave.quiet" : "gtnhplanner.dave.asking"));
                case THINKING -> out.add(status(turn, quiet ? "gtnhplanner.dave.quiet" : "gtnhplanner.dave.thinking"));
                case STOPPED -> out.add(status(turn, "gtnhplanner.dave.stopped"));
                case ERROR -> out.add(new Row(Kind.ERROR, errorText(turn.error()), turn, -1, false, true));
                case ANSWERED -> answered(turn, out);
            }
        }
        return out;
    }

    private void answered(final DaveChat.Turn turn, final List<Row> out) {
        final String note = turn.note();
        if (note != null && !note.isBlank()) out.add(new Row(Kind.NOTE, note, turn, -1, false, false));
        if (turn.lines()
            .isEmpty() && note == null
            && turn.proposalId() == null
            && turn.recipes()
                .isEmpty())
            out.add(new Row(Kind.NOTE, t("gtnhplanner.dave.no_words"), turn, -1, false, false));
        if (turn.proposalId() != null) {
            final boolean used = applied.contains(turn);
            out.add(
                new Row(
                    Kind.ADD_PLAN,
                    t(used ? "gtnhplanner.dave.add_plan.used" : "gtnhplanner.dave.add_plan"),
                    turn,
                    -1,
                    !used && available,
                    false));
            final Told told = appliedOutcome.get(turn);
            if (told != null) out.add(new Row(Kind.OUTCOME, told.text(), turn, -1, false, told.problem()));
        }
        final List<DaveChat.Recipe> recipes = turn.recipes();
        for (int i = 0; i < recipes.size(); i++) {
            final DaveChat.Recipe r = recipes.get(i);
            final String label = r.tier() == null || r.tier()
                .isEmpty() ? r.label() : r.label() + " (" + r.tier() + ")";
            out.add(new Row(Kind.RECIPE, label, turn, i, canAddRecipe(turn, i), false));
            final Told told = recipeOutcome.getOrDefault(turn, Map.of())
                .get(i);
            if (told != null && !adding.contains(key(turn, i)))
                out.add(new Row(Kind.OUTCOME, told.text(), turn, i, false, told.problem()));
        }
    }

    /** The Add key's word for a recipe row: Add, Adding while it goes on, Added once it went on. */
    public String addWord(final Row row) {
        if (row.turn() != null && adding.contains(key(row.turn(), row.index())))
            return t("gtnhplanner.dave.recipe.adding");
        return t(row.enabled() ? "gtnhplanner.dave.recipe.add" : "gtnhplanner.dave.recipe.added");
    }

    private static Row status(final DaveChat.Turn turn, final String key) {
        return new Row(Kind.STATUS, t(key), turn, -1, false, false);
    }

    private static String errorText(@Nullable final String message) {
        return message == null || message.isBlank() ? t("gtnhplanner.dave.error.empty") : message;
    }

    private static String reason(final RuntimeException e) {
        return e.getMessage() == null || e.getMessage()
            .isBlank() ? t("gtnhplanner.dave.error.empty") : e.getMessage();
    }

    private void listenOn(final DaveLink link) {
        if (listening == link) return;
        if (listening != null) try {
            listening.unlisten(listener);
        } catch (final RuntimeException e) {
            // The old bridge is gone.
        }
        link.listen(listener);
        listening = link;
    }

    private void onEvent(final String json) {
        if (chat.onEvent(json, clock.getAsLong())) {
            quiet = false;
            version++;
        }
    }

    private static String t(final String key) {
        return Text.get()
            .translate(key);
    }
}
