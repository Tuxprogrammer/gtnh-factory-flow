package com.gtnhplanner.core.view.rail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.Text;

/**
 * The notices bar's list, worked out from what the solve said, most pressing first. The core decides which notices
 * stand, in what order, what each is about (the cards and drawers "Show me" frames) and which words it takes; a host
 * turns each into a sentence with {@link #text}, through its own language table and its own number format. Each host
 * pre-digests its solver's notes into a {@link Input} (the website from {@code PlanSolveExtras}, the mod from its
 * answer), so the core knows neither.
 */
public final class Notices {

    private Notices() {}

    // region Input

    /** A failed solve: {@code kind} is NO_PIN, EMPTY_GRAPH, PIN_CONFLICT, CANNOT_RUN, NO_WAY or FAILED. */
    public record Failure(String kind, List<String> names, List<String> focus) {}

    /** A dead loop or clog lock: {@code kind} is DEAD_LOOP or CLOG_LOCK; {@code cards} are the ring's cards. */
    public record Loop(String kind, List<String> cards) {}

    /** What holds a drawer's rate back: {@code kind} is exact, full, starved, clogged, unused, limited or drawer. */
    public record Holder(String kind, String name) {}

    /**
     * One of the solver's notes. {@code type} is UNMET, OVERSHOOT, PORT_TAKEN, NOT_CONNECTED, WIRED_IGNORED or CHOICE.
     * {@code resource} is the resource's kind (item, fluid, power) for the rates it names; {@code supply} whether the
     * drawer is a source; {@code exact} whether its rule is Exactly; {@code asked}, {@code reached}, {@code made} and
     * {@code target} the rates in units a second; {@code hasShortfall} whether the solver gave what holds it back.
     */
    public record Note(String type, String id, String label, String resource, boolean supply, boolean exact,
        double asked, double reached, double made, double target, boolean hasShortfall, List<Holder> holders,
        String subject, List<String> focus) {}

    public record Input(@Nullable Failure failure, boolean hasNodes, List<Loop> loops, List<Note> notes) {}

    // endregion

    // region Output

    /** A value a notice's words take. */
    public record Arg(String kind, @Nullable String text, double value, @Nullable String unit, List<Holder> holders,
        List<String> list) {

        public static Arg text(final String text) {
            return new Arg("text", text, 0, null, List.of(), List.of());
        }

        /** A rate in units a second of a resource kind ({@code item}, {@code fluid}, {@code power}). */
        public static Arg rate(final double perSecond, final String resource) {
            return new Arg("rate", null, perSecond, resource, List.of(), List.of());
        }

        public static Arg holders(final List<Holder> holders) {
            return new Arg("holders", null, 0, null, holders, List.of());
        }

        public static Arg names(final List<String> names) {
            return new Arg("names", null, 0, null, List.of(), names);
        }
    }

    /**
     * A notice. {@code id} is stable across solves while the same sentence stands. {@code severity} is error, warn or
     * info; {@code tag} the key of an alarm's name and {@code loop} its ring's tone (dead or clog), both only on an
     * alarm.
     */
    public record Notice(String id, String severity, String key, List<Arg> args, List<String> focus,
        @Nullable String tag, @Nullable String loop) {}

    // endregion

    public static final String NO_PIN = "gtnhplanner.notice.noPin";
    public static final String PIN_CONFLICT = "gtnhplanner.notice.pinConflict";
    public static final String CANNOT_RUN = "gtnhplanner.notice.cannotRun";
    public static final String NO_WAY = "gtnhplanner.notice.noWay";
    public static final String FAILED = "gtnhplanner.notice.failed";
    public static final String UNWIRED = "gtnhplanner.notice.unwired";
    public static final String IGNORED = "gtnhplanner.notice.ignored";
    public static final String CHOICE = "gtnhplanner.notice.choice";
    public static final String OVERSHOOT = "gtnhplanner.notice.overshoot";
    public static final String AND = "gtnhplanner.notice.and";

    /** Where a notice goes in the list, lowest first: a failed solve, then alarms, then warnings, then the rest. */
    public static int rank(final Notice n) {
        if (n.severity().equals("error")) return n.tag() != null ? 1 : 0;
        return n.severity().equals("warn") ? 2 : 3;
    }

    /** The list: the failure, the rings, the drawers that fall short, then the other notes; most pressing first. */
    public static List<Notice> build(final Input in) {
        final List<Notice> out = new ArrayList<>();
        final Failure failure = in.failure();
        if (failure != null) {
            if (failure.kind().equals("NO_PIN")) {
                if (in.hasNodes()) out.add(new Notice("no-pin", "info", NO_PIN, List.of(), List.of(), null, null));
            } else if (!failure.kind().equals("EMPTY_GRAPH")) {
                out.add(failureNotice(failure));
            }
        }
        for (final Loop loop : in.loops()) {
            if (loop.cards().isEmpty()) continue;
            final boolean dead = loop.kind().equals("DEAD_LOOP");
            final String tone = dead ? "dead" : "clog";
            out.add(
                new Notice("loop:" + loop.kind() + ":" + String.join(",", loop.cards()), "error",
                    "gtnhplanner.loop." + tone + (loop.cards().size() == 1 ? ".one" : ".many"), List.of(),
                    loop.cards(), "gtnhplanner.loop." + tone + ".tag", tone));
        }
        for (final Note note : in.notes()) if (note.type().equals("UNMET")) out.add(unmet(note));
        for (final Note note : in.notes()) {
            switch (note.type()) {
                case "OVERSHOOT" -> out.add(
                    new Notice("overshoot:" + note.id(), "warn", OVERSHOOT,
                        List.of(Arg.text(note.subject()), Arg.rate(note.made(), note.resource()),
                            Arg.text(note.label()), Arg.rate(note.target(), note.resource())),
                        note.focus(), null, null));
                case "PORT_TAKEN" -> out.add(
                    new Notice("unmet:" + note.id(), "warn",
                        "gtnhplanner.notice.portTaken." + (note.supply() ? "supply" : "drawer")
                            + (note.exact() ? "Exact" : ""),
                        List.of(Arg.text(note.label()), Arg.rate(note.asked(), note.resource())), note.focus(), null,
                        null));
                case "NOT_CONNECTED" -> out.add(
                    new Notice("unwired:" + note.id(), "warn", UNWIRED, List.of(Arg.text(note.label())),
                        note.focus(), null, null));
                case "WIRED_IGNORED" -> out.add(
                    new Notice("ignored:" + note.id(), "warn", IGNORED, List.of(Arg.text(note.label())),
                        note.focus(), null, null));
                case "CHOICE" -> out.add(new Notice("choice", "warn", CHOICE, List.of(), List.of(), null, null));
                default -> {}
            }
        }
        return dedupe(byUrgency(out));
    }

    /** Most pressing first; notices of one rank keep their order. */
    public static List<Notice> byUrgency(final List<Notice> list) {
        final List<Notice> out = new ArrayList<>(list);
        out.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        return out;
    }

    /** A notice that says what an earlier one says, in the same words, is dropped. */
    private static List<Notice> dedupe(final List<Notice> list) {
        final Set<String> seen = new HashSet<>();
        final List<Notice> out = new ArrayList<>();
        for (final Notice n : list) if (seen.add(sameness(n))) out.add(n);
        return out;
    }

    /** What makes two notices the same sentence: their key and everything that goes into it. */
    private static String sameness(final Notice n) {
        final Json j = new Json();
        j.begin().str("key", n.key());
        j.list("args", n.args(), Notices::writeArg);
        j.end();
        return j.toString();
    }

    private static Notice failureNotice(final Failure f) {
        return switch (f.kind()) {
            case "PIN_CONFLICT" -> new Notice("pin-conflict", "error", PIN_CONFLICT, List.of(Arg.names(f.names())),
                f.focus(), null, null);
            case "CANNOT_RUN" -> new Notice("cannot-run", "error", CANNOT_RUN, List.of(), List.of(), null, null);
            case "NO_WAY" -> new Notice("no-way", "error", NO_WAY, List.of(), List.of(), null, null);
            default -> new Notice("failed", "error", FAILED, List.of(), List.of(), null, null);
        };
    }

    private static Notice unmet(final Note n) {
        final String who = n.supply() ? "supply" : "drawer";
        final List<String> focus = n.focus();
        final String id = "unmet:" + n.id();
        if (n.hasShortfall()) {
            return new Notice(id, "warn", "gtnhplanner.notice.unmet.short." + who,
                List.of(Arg.text(n.label()), Arg.rate(n.reached(), n.resource()), Arg.rate(n.asked(), n.resource()),
                    Arg.holders(n.holders())),
                focus, null, null);
        }
        if (n.reached() > n.asked()) {
            return new Notice(id, "warn", "gtnhplanner.notice.unmet.over." + who,
                List.of(Arg.text(n.label()), Arg.rate(n.asked(), n.resource()), Arg.rate(n.reached(), n.resource())),
                focus, null, null);
        }
        return new Notice(id, "warn", "gtnhplanner.notice.unmet.under." + who + (n.exact() ? "Exact" : ""),
            List.of(Arg.text(n.label()), Arg.rate(n.asked(), n.resource()), Arg.rate(n.reached(), n.resource())),
            focus, null, null);
    }

    // region Words

    /** A host's number format: a rate in units a second of a resource kind, with its unit, as the host spells it. */
    public interface RateFormat {

        String rate(double perSecond, String resource);
    }

    /** The sentence of a notice, in the installed language, with {@code rates} spelling the rates. */
    public static String text(final Notice notice, final RateFormat rates) {
        final Object[] args = new Object[notice.args().size()];
        for (int i = 0; i < args.length; i++) args[i] = word(notice.args().get(i), rates);
        return Text.get()
            .format(notice.key(), args);
    }

    /** An alarm's name, in the installed language. */
    public static String tag(final Notice notice) {
        return notice.tag() == null ? "" : Text.get()
            .translate(notice.tag());
    }

    private static String word(final Arg a, final RateFormat rates) {
        switch (a.kind()) {
            case "rate":
                return rates.rate(a.value(), a.unit());
            case "names": {
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < a.list().size(); i++) {
                    if (i > 0) {
                        sb.append(' ')
                            .append(
                                Text.get()
                                    .translate(AND))
                            .append(' ');
                    }
                    sb.append(a.list().get(i));
                }
                return sb.toString();
            }
            case "holders": {
                if (a.holders().isEmpty()) return "";
                final StringBuilder sb = new StringBuilder();
                for (final Holder h : a.holders()) {
                    sb.append(' ')
                        .append(
                            Text.get()
                                .format("gtnhplanner.notice.holder." + h.kind(), h.name()));
                }
                return sb.toString();
            }
            default:
                return a.text() == null ? "" : a.text();
        }
    }

    // endregion

    // region JSON

    private static void writeArg(final Json j, final Arg a) {
        j.begin().str("kind", a.kind());
        switch (a.kind()) {
            case "rate" -> j.num("value", a.value()).str("unit", a.unit());
            case "holders" -> j.list("holders", a.holders(), (jj, h) -> jj.begin().str("kind", h.kind()).str("name", h.name()).end());
            case "names" -> j.strings("list", a.list());
            default -> j.str("text", a.text() == null ? "" : a.text());
        }
        j.end();
    }

    /** The list as JSON, keys in a fixed order. */
    public static String toJson(final List<Notice> notices) {
        final Json j = new Json();
        j.array(notices, (jj, n) -> {
            jj.begin().str("id", n.id()).str("severity", n.severity()).str("key", n.key());
            jj.list("args", n.args(), Notices::writeArg);
            jj.strings("focus", n.focus());
            jj.opt("tag", n.tag()).opt("loop", n.loop());
            jj.end();
        });
        return j.toString();
    }

    // endregion
}
