package com.gtnhplanner.core.view.special;

import java.util.List;

import javax.annotation.Nullable;

import com.gtnhplanner.core.view.Json;
import com.gtnhplanner.core.view.card.CardInput;
import com.gtnhplanner.core.view.card.CardView;

/**
 * What a special card shows beyond the parts it keeps of its recipe card's view, every string final: the website's
 * {@code SpecialView} (special/special-model.ts) in Java. A host draws the card's {@link CardView} (its rows, picture,
 * ring, zoomed-out bar) with these parts in place of the card's own: the head, the count, the power row, the strip,
 * the bar along the foot. Built by {@link SpecialViews#special}. A wire record ({@link CardInput}'s conventions);
 * {@link #write} is its one JSON spelling.
 *
 * @param nameStep  the name's size on the host's card ladder (the recipe card's: 0 the largest, 2 the smallest)
 * @param empty     the card shows no rails: an empty holder's sockets or an empty crop farm's key stand there
 * @param sections  a shared machine's recipes, top first (section 0's rows are the card's own); none on another kind
 * @param power     the card's one power row; null for none
 * @param sockets   an empty holder's two sockets; null for none
 * @param gauge     the picture is the custom rate card's gauge
 * @param count     the count under the picture; null on a card that has none (a fluid tank, an empty crop farm)
 * @param foot      the bar along the foot; null for none
 * @param chips     the strip's chips, laid out
 * @param warnings  lines under the strip
 * @param pick      an empty crop farm: its Pick a crop key stands in place of the rails
 * @param cropKey   a crop farm with a crop: the head's key that picks another
 */
public record SpecialView(SpecialInput.Kind kind, String name, int nameStep, NameTone nameTone, boolean hasPicker,
    @Nullable CardView.TierChip tier, @Nullable CardView.AmpsChip amps, boolean empty, List<Section> sections,
    @Nullable CardView.PowerView power, @Nullable Sockets sockets, boolean gauge, @Nullable CardView.CountView count,
    @Nullable CardView.FootView foot, List<Chip> chips, int stripHeight, List<String> warnings, boolean cannotRun,
    boolean unset, boolean pick, boolean cropKey) {

    /** The name's ink: the machine's, a generator's amber, a holder's blue. */
    public enum NameTone {
        INK,
        POWER,
        HOLDER
    }

    /** One recipe of a shared machine: its rows (none for section 0: the card's own) and the rule over them. */
    public record Section(int section, List<CardView.PortView> inputs, List<CardView.PortView> outputs,
        @Nullable Rule rule) {}

    /**
     * The rule row over a shared machine's recipe: its share of the machine's time ({@code pct}, "40%"), its own
     * machines or UNUSED ({@code word}), its circuit, and whether it can move up or down.
     */
    public record Rule(String pct, String word, boolean unused, @Nullable CardInput.Circuit circuit, boolean canUp,
        boolean canDown) {}

    /**
     * An empty holder's sockets: the words in the two dashed boxes ({@code take} on the input side, {@code give} on
     * the output side) and the hint under each.
     */
    public record Sockets(String take, String give, String takeHint, String giveHint) {}

    /** A chip of the strip, and how it is typed into when it is a number the card shows its own way. */
    public record Chip(CardView.ChipView chip, @Nullable Edit edit) {}

    /**
     * A chip typed into: the text the box opens with, what a typed number is divided by to be stored ({@code scale}:
     * the board's rate unit over the stored one), and notes for its tip.
     */
    public record Edit(String text, double scale, List<String> notes) {}

    // region JSON

    /** The record's one JSON spelling (component order; an optional left out when null). */
    public String toJson() {
        final Json j = new Json();
        write(j, this);
        return j.toString();
    }

    public static void write(final Json j, final SpecialView v) {
        j.begin()
            .str("kind", v.kind.name())
            .str("name", v.name)
            .num("nameStep", v.nameStep)
            .str("nameTone", v.nameTone.name())
            .bool("hasPicker", v.hasPicker);
        if (v.tier != null) j.field(
            "tier",
            x -> x.begin()
                .str("label", v.tier.label())
                .bool("fixed", v.tier.fixed())
                .str("title", v.tier.title())
                .end());
        if (v.amps != null) j.field(
            "amps",
            x -> x.begin()
                .str("label", v.amps.label())
                .num("value", v.amps.value())
                .bool("editable", v.amps.editable())
                .end());
        j.bool("empty", v.empty)
            .list("sections", v.sections, SpecialView::section);
        if (v.power != null) j.field(
            "power",
            x -> x.begin()
                .str("number", v.power.number())
                .str("unit", v.power.unit())
                .str("name", v.power.name())
                .bool("stalled", v.power.stalled())
                .str("title", v.power.title())
                .end());
        if (v.sockets != null) j.field(
            "sockets",
            x -> x.begin()
                .str("take", v.sockets.take)
                .str("give", v.sockets.give)
                .str("takeHint", v.sockets.takeHint)
                .str("giveHint", v.sockets.giveHint)
                .end());
        j.bool("gauge", v.gauge);
        if (v.count != null) j.field("count", x -> CardView.writeCount(x, v.count));
        if (v.foot != null) j.field("foot", x -> CardView.writeFoot(x, v.foot));
        j.list("chips", v.chips, (x, c) -> {
            x.begin()
                .field("chip", y -> CardView.writeChip(y, c.chip));
            if (c.edit != null) x.field(
                "edit",
                y -> y.begin()
                    .str("text", c.edit.text)
                    .num("scale", c.edit.scale)
                    .strings("notes", c.edit.notes)
                    .end());
            x.end();
        })
            .num("stripHeight", v.stripHeight)
            .strings("warnings", v.warnings)
            .bool("cannotRun", v.cannotRun)
            .bool("unset", v.unset)
            .bool("pick", v.pick)
            .bool("cropKey", v.cropKey)
            .end();
    }

    private static void section(final Json j, final Section s) {
        j.begin()
            .num("section", s.section)
            .list("inputs", s.inputs, CardView::writePort)
            .list("outputs", s.outputs, CardView::writePort);
        if (s.rule != null) j.field("rule", x -> {
            x.begin()
                .str("pct", s.rule.pct)
                .str("word", s.rule.word)
                .bool("unused", s.rule.unused);
            if (s.rule.circuit != null) x.field(
                "circuit",
                y -> y.begin()
                    .opt("icon", s.rule.circuit.icon())
                    .opt("number", s.rule.circuit.number())
                    .end());
            x.bool("canUp", s.rule.canUp)
                .bool("canDown", s.rule.canDown)
                .end();
        });
        j.end();
    }

    // endregion
}
