package com.gtnhplanner.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * The verbs of {@code /gtnhplanner} and how a typed line is read. Anyone in chat can make a button that runs the
 * command on the clicker's client, so it is strict: a known verb, one id of the site's fixed alphabet whose first
 * letter is that verb's kind, and the whole command under {@value #MAX} characters. Anything else gets the usage line
 * and runs nothing. A verb is one entry in the command's table ({@link PlannerCommand#VERBS}).
 */
public final class PlannerVerbs {

    /** The command's name, as typed after the slash. */
    public static final String NAME = "gtnhplanner";
    /** The whole command, slash and spaces included, is shorter than this. */
    public static final int MAX = 100;
    /** An id the site hands out: its kind letter (d a plan, p a change to one), then 15 of an unambiguous alphabet. */
    public static final Pattern ID = Pattern.compile("^[dp][a-hjkmnp-z2-9]{15}$");

    /**
     * One verb: its word, the kind letter its id starts with, and what it does with the id (on the client thread).
     */
    public record Verb(String word, char kind, Consumer<String> run) {}

    /** A line read: the verb and its id. */
    public record Call(Verb verb, String id) {}

    private PlannerVerbs() {}

    /** The verb and id the line names, or null when it is not a call this command takes. */
    @Nullable
    public static Call read(final List<Verb> verbs, final String[] args) {
        if (args.length != 2) return null;
        if (("/" + NAME + " " + args[0] + " " + args[1]).length() >= MAX) return null;
        if (!ID.matcher(args[1])
            .matches()) return null;
        for (final Verb v : verbs) if (v.word()
            .equals(args[0])) return args[1].charAt(0) == v.kind() ? new Call(v, args[1]) : null;
        return null;
    }

    /** The verbs that start with what is typed so far, for tab completion; nothing past the verb. */
    public static List<String> complete(final List<Verb> verbs, final String[] args) {
        final List<String> words = new ArrayList<>();
        if (args.length != 1) return words;
        for (final Verb v : verbs) if (v.word()
            .startsWith(args[0])) words.add(v.word());
        return words;
    }

    /** "Use /gtnhplanner open <id> or /gtnhplanner apply <id>.", every verb named. */
    public static String usage(final List<Verb> verbs) {
        final List<String> forms = new ArrayList<>();
        for (final Verb v : verbs) forms.add("/" + NAME + " " + v.word() + " <id>");
        if (forms.size() == 1) return "Use " + forms.getFirst() + ".";
        return "Use " + String.join(", ", forms.subList(0, forms.size() - 1)) + " or " + forms.getLast() + ".";
    }
}
