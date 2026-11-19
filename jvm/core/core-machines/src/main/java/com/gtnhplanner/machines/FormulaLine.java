package com.gtnhplanner.machines;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.NumberText;

/**
 * One line of a machine's worked formulas, as the website's formula-lines.ts has it: a label, the working with the
 * card's own settings plugged in, and the answer. A term that is a setting's number names that setting (its colour);
 * a superscript is raised. Green or red when the line is a requirement met or not.
 */
public record FormulaLine(String label, List<Term> math, String result, Tone tone) {

    public enum Tone {
        PLAIN,
        GOOD,
        BAD
    }

    /** A piece of the working: text, a setting's number ({@code knob} its key), or a superscript. */
    public record Term(String text, @Nullable String knob, boolean sup) {}

    public static Term text(final String text) {
        return new Term(text, null, false);
    }

    public static Term knob(final Object text, final String knob) {
        return new Term(String.valueOf(text), knob, false);
    }

    public static Term sup(final String text) {
        return new Term(text, null, true);
    }

    /** A line from its terms: strings are text, terms are themselves. */
    public static FormulaLine of(final String label, final String result, final Tone tone, final Object... terms) {
        final List<Term> math = new ArrayList<>();
        for (final Object t : terms) math.add(t instanceof final Term term ? term : text(String.valueOf(t)));
        return new FormulaLine(label, math, result, tone);
    }

    public static FormulaLine of(final String label, final String result, final Object... terms) {
        return of(label, result, Tone.PLAIN, terms);
    }

    /** The working as plain text ({@code ^} before a superscript), as the website's formulaText writes it. */
    public String mathText() {
        final StringBuilder b = new StringBuilder();
        for (final Term t : math) b.append(t.sup() ? "^" + t.text() : t.text());
        return b.toString();
    }

    /** A number as the formulas show it (formulaNumber): whole numbers grouped, fractions to three places. */
    public static String number(final double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "-";
        final boolean negative = value < 0 || value == 0 && 1 / value < 0;
        final double abs = Math.abs(value);
        if (value == Math.rint(value) && abs < 1e15) return (negative ? "-" : "") + group(NumberText.fixed(abs, 0));
        final int digits = abs >= 100 ? 1 : abs >= 1 ? 2 : 3;
        String text = NumberText.fixed(abs, digits);
        int end = text.length();
        while (text.charAt(end - 1) == '0') end--;
        if (text.charAt(end - 1) == '.') end--;
        text = text.substring(0, end);
        final int point = text.indexOf('.');
        final String whole = group(point < 0 ? text : text.substring(0, point));
        final String out = point < 0 ? whole : whole + text.substring(point);
        // A fraction that rounds to nothing is a plain zero.
        return negative && !"0".equals(out) ? "-" + out : out;
    }

    /** A whole number with a comma between thousands ({@code %,d}). */
    public static String grouped(final long value) {
        final String digits = Long.toString(value);
        return value < 0 ? "-" + group(digits.substring(1)) : group(digits);
    }

    private static String group(final String digits) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) out.append(',');
            out.append(digits.charAt(i));
        }
        return out.toString();
    }
}
