package com.gtnhplanner.core.view.drawer;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.core.base.Format;
import com.gtnhplanner.core.base.NumberText;
import com.gtnhplanner.core.view.Json;
import com.gtnhplanner.core.view.TextMeasure;
import com.gtnhplanner.core.view.TextStyle;
import com.gtnhplanner.core.view.TipView;

/**
 * The drawer's rules, once: which figures it prints and in what shape, the rule it shows, the progress and the tips'
 * words. Both hosts follow it ({@code conformance/view/drawer/cases.json}); a host adds only its pixels.
 */
public final class DrawerBuilder {

    /** The words column of the drawer: 76 px at the row's figure and name sizes. */
    static final double WORDS = 76;
    /** The name's own box, which cuts what runs past it. */
    static final double NAME_BOX = 71;
    /** The words beside a spill's Forced out tile. */
    static final double SPILL_WORDS = 41;
    /** A figure under this reads as nothing. */
    static final double NOTHING = 0.000005;

    private DrawerBuilder() {}

    /** The rule's mark: Any, At least, Exactly, At most. */
    public static String ruleMark(final String rule) {
        return switch (rule) {
            case "at-least" -> "≥";
            case "exact" -> "=";
            case "at-most" -> "≤";
            default -> "~";
        };
    }

    public static String ruleName(final String rule) {
        return switch (rule) {
            case "at-least" -> "At least";
            case "exact" -> "Exactly";
            case "at-most" -> "At most";
            default -> "Any";
        };
    }

    /** A flow's figure with at most three significant figures: whole from 100, one place from 10, else two. */
    static String trimFlow(final double value) {
        final double abs = Math.abs(value);
        return Format.trimZeros(NumberText.fixed(value, abs >= 100 ? 0 : abs >= 10 ? 1 : 2));
    }

    /** A rate in the board's unit as the drawer's own figure: below a thousand as is, then k and M. */
    static String rateField(final double perSecond, final DrawerInput in) {
        final double scaled = Math.abs(perSecond * in.multiplier());
        if ("power".equals(in.resource())) return Format.powerValue(scaled);
        if (scaled >= 1_000_000) return trimFlow(scaled / 1_000_000) + "M";
        if (scaled >= 1_000) return trimFlow(scaled / 1_000) + "k";
        return scaled >= 1 ? trimFlow(scaled) : Format.compact(scaled);
    }

    private static String kindName(final String kind) {
        return switch (kind) {
            case "source" -> "Source";
            case "trash" -> "Trash";
            case "buffer" -> "Buffer";
            default -> "Product";
        };
    }

    /** What a rule asks of the plan, in a sentence. */
    private static String ruleMeaning(final String kind, final String rule) {
        if ("buffer".equals(kind)) {
            return switch (rule) {
                case "at-least" -> "The plan passes at least the rate through it.";
                case "exact" -> "The plan passes exactly the rate through it.";
                case "at-most" -> "The plan passes no more than the rate through it.";
                default -> "No target: the plan passes whatever its users take.";
            };
        }
        final String flow = "source".equals(kind) ? "draws from it" : "puts in it";
        return switch (rule) {
            case "at-least" -> "The plan " + flow + " at least the rate.";
            case "exact" -> "The plan " + flow + " exactly the rate.";
            case "at-most" -> "The plan " + flow + " no more than the rate.";
            default -> "No target: the plan " + flow + " whatever it needs.";
        };
    }

    private static TipView.Action action(final String input, final String label) {
        return new TipView.Action(input, label);
    }

    private static TipView.Note muted(final String text) {
        return new TipView.Note(text, "muted");
    }

    public static DrawerView build(final DrawerInput in, final TextMeasure measure) {
        final String kind = in.kind();
        final boolean buffer = "buffer".equals(kind), trash = "trash".equals(kind);
        final String id = in.id();
        final double scaled = Math.abs(in.moved() * in.multiplier());
        final String number = Double.isNaN(scaled) || Double.isInfinite(scaled) || scaled < NOTHING ? "0"
            : rateField(in.moved(), in);
        final boolean zero = !(in.moved() > NOTHING);
        final String unit = in.unit();
        final boolean hasRule = !trash;
        final Double target = in.target();
        final boolean hasTarget = hasRule && !in.inert() && target != null && !"ignore".equals(in.rule())
            && !Double.isNaN(target) && !Double.isInfinite(target)
            && (target != 0 || "exact".equals(in.rule()) || "at-most".equals(in.rule()));
        final String rule = hasTarget ? in.rule() : "ignore";
        final DrawerView.Fig targetFig = hasTarget ? new DrawerView.Fig(rateField(target, in), unit) : null;
        final boolean prompt = hasRule && !hasTarget && in.unanchored();
        final Double share = hasTarget && Math.abs(target) > 0 ? Math.min(1, in.moved() / Math.abs(target)) : null;
        final boolean unmet = hasTarget && in.unmet();
        final String tone = unmet ? "bad" : "at-most".equals(rule) ? "calm" : "ok";
        final String sign = zero || buffer ? "" : "source".equals(kind) ? "-" : "+";
        final String reaches = unmet ? rateField(in.moved(), in) + " " + unit : null;
        final String flowWord = "source".equals(kind) ? "Supplies" : buffer ? "Passes" : "Takes";

        final TipView ruleTip = trash
            ? new TipView(id + "|rule", "Trash", null, List.of(),
                List.of(muted("It voids what comes in: it leaves the plan without being one of its outputs.")),
                List.of(action("left", "Keep it instead")))
            : new TipView(id + "|rule", "Rule", ruleName(rule), List.of(), List.of(muted(ruleMeaning(kind, rule))),
                List.of(action("left", "Pick"), action("wheel", "Next"), action("middle", "Clear")));
        final List<TipView.Row> rateRows = new ArrayList<>();
        if (hasTarget) rateRows.add(new TipView.Row(ruleName(rule), targetFig.number() + " " + unit, null));
        if (unmet) rateRows.add(new TipView.Row("Reaches", reaches, "red"));
        final TipView rateTip = new TipView(id + "|rate", hasTarget ? "Target rate" : "No rate yet", null, rateRows,
            List.of(), List.of(action("left", "Type (2.5k, 1/3)"), action("wheel", "+1, Ctrl 10, Shift 100"),
                action("middle", "Clear")));

        final boolean spilling = buffer && in.spill() != null && in.spill() > NOTHING;
        final String spillText = spilling ? rateField(in.spill(), in) : null;
        final List<TipView.Note> bodyNotes = new ArrayList<>();
        if (buffer) bodyNotes.add(muted("Its makers put in what its users take out."));
        if (!in.linked()) bodyNotes.add(new TipView.Note("Unconnected", "warn"));
        if (unmet) bodyNotes.add(new TipView.Note(
            "Reaches only " + reaches + " of its " + targetFig.number() + " " + unit + " target", "red"));
        final TipView bodyTip = new TipView(id + "|body", in.label(), kindName(kind),
            List.of(new TipView.Row(flowWord, number + " " + unit, null)), bodyNotes,
            List.of(action("drag", "Wire to a card"), action("key", "R, U")));

        final boolean nameSmall = measure.width(TextStyle.GAME, in.label()) > 71;
        final double room = spilling ? SPILL_WORDS : WORDS;
        final double figure = measure.width(TextStyle.ROW_FIGURE, number) + 1 + (unit.isEmpty() ? 0
            : measure.width(TextStyle.ROW_UNIT, unit));
        final double name = in.label().isEmpty() ? 0
            : measure.width(nameSmall ? TextStyle.ROW_NAME_SMALL : TextStyle.ROW_NAME, in.label());

        final TipView flip = buffer ? null
            : new TipView(id + "|flip", "source".equals(kind) ? "Make it an output" : "Make it an input", null,
                List.of(), List.of(muted("Its wires come off.")), List.of(action("left", "Switch")));
        final DrawerView.Spill spill = spilling
            ? new DrawerView.Spill(spillText, unit,
                new TipView(id + "|spill", "Forced out", null,
                    List.of(new TipView.Row("At its makers", spillText + " " + unit, null)),
                    List.of(muted("Its makers make " + spillText + " " + unit
                        + " more than its users take. They force it out themselves; this adds it up.")),
                    List.of()))
            : null;
        final DrawerView.Tips tips = new DrawerView.Tips(
            new TipView(id + "|bar", in.label(), kindName(kind), List.of(), List.of(),
                List.of(action("drag", "Move"), action("left", "Select"))),
            new TipView(id + "|del", "Delete this drawer", null, List.of(), List.of(),
                List.of(action("left", "Delete"))),
            ruleTip, rateTip, bodyTip);
        return new DrawerView(id, kind, in.label(), number, unit, zero, figure > room, nameSmall, name > (spilling ? SPILL_WORDS : NAME_BOX),
            hasRule, rule, trash ? "×" : ruleMark(rule), targetFig, trash ? "Voided" : "Set", prompt, unmet,
            reaches, share, tone, in.linked(), sign + number, spill, tips, flip);
    }

    /** The view as the corpus and the hosts' tests read it: keys in the record's order, optionals left out. */
    public static String toJson(final DrawerView v) {
        final Json j = new Json();
        j.begin()
            .str("id", v.id())
            .str("kind", v.kind())
            .str("label", v.label())
            .str("number", v.number())
            .str("unit", v.unit())
            .bool("zero", v.zero())
            .bool("numberSmall", v.numberSmall())
            .bool("nameSmall", v.nameSmall())
            .bool("nameClipped", v.nameClipped())
            .bool("hasRule", v.hasRule())
            .str("rule", v.rule())
            .str("ruleMark", v.ruleMark());
        if (v.target() != null) j.field("target", jj -> jj.begin().str("number", v.target().number()).str("unit", v.target().unit()).end());
        j.str("boxText", v.boxText())
            .bool("prompt", v.prompt())
            .bool("unmet", v.unmet())
            .opt("reaches", v.reaches())
            .opt("progress", v.progress())
            .str("tone", v.tone())
            .bool("linked", v.linked())
            .str("glanceRate", v.glanceRate());
        if (v.spill() != null) j.field("spill", jj -> {
            jj.begin().str("number", v.spill().number()).str("unit", v.spill().unit());
            jj.field("tip", v.spill().tip()::write);
            jj.end();
        });
        j.field("tips", jj -> {
            jj.begin();
            jj.field("bar", v.tips().bar()::write);
            jj.field("del", v.tips().del()::write);
            jj.field("rule", v.tips().rule()::write);
            jj.field("rate", v.tips().rate()::write);
            jj.field("body", v.tips().body()::write);
            jj.end();
        });
        if (v.flip() != null) j.field("flip", v.flip()::write);
        j.end();
        return j.toString();
    }
}
