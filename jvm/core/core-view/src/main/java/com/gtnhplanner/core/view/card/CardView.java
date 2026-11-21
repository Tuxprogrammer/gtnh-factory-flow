package com.gtnhplanner.core.view.card;

import java.util.List;

import javax.annotation.Nullable;

import com.gtnhplanner.core.view.Json;
import com.gtnhplanner.core.view.Tones;
import com.gtnhplanner.data.flowchart.PinRule;
import com.gtnhplanner.data.flowchart.balancer.UpToLoop;

/**
 * Everything a recipe card shows, every string final: the website's {@code CleanCardView} (clean-model.ts) in Java.
 * Each host draws it and does no formatting; where a rule fits text, it measured with the host's
 * {@link com.gtnhplanner.core.view.TextMeasure}. Built by {@link CardViews#card}. A wire record (see
 * {@link CardInput}); {@link #write} is its one JSON spelling.
 *
 * @param nameStep   the name's size on the host's ladder: 0 the largest, 2 the smallest (cut past it)
 * @param frame      the frame's tone for a problem the bar shows; null keeps the normal edge
 * @param menu       the card menu's rows, in order (a host adds its own: the mod's world rows)
 */
public record CardView(String nodeId, String name, int nameStep, boolean hasPicker, @Nullable TierChip tier,
    @Nullable AmpsChip amps, List<PortView> inputs, List<PortView> outputs, @Nullable PowerView power,
    @Nullable String art, @Nullable String glanceArt, @Nullable CardInput.Product glanceProduct, CountView count,
    @Nullable FootView foot, @Nullable UpToLoop.Kind loop, @Nullable GlanceBar glance,
    @Nullable CardInput.Circuit circuit, List<ChipView> chips, int stripHeight, boolean selected, boolean cannotRun,
    boolean unset, @Nullable Tones.Role frame, List<MenuRow> menu) {

    /** The tier chip: its words, fixed or not, its title. */
    public record TierChip(String label, boolean fixed, String title) {}

    /** The amps chip: its words and the amps it stands for. */
    public record AmpsChip(String label, double value, boolean editable) {}

    /**
     * One port row: its number and unit as printed, its name and title, and the decisions the row draws by.
     *
     * @param zero        nothing runs through it
     * @param gold        the rate on it is one the player set ({@code rate}, per second)
     * @param numberSmall number and unit do not fit the words column at figure size
     * @param nameSmall   the name does not fit one line at its normal size (decided in the game font)
     * @param nameClipped the name does not fit even small
     * @param choice      more than one resource fills the slot
     * @param forced      the forced row under it ("720", "L/s"); null for none
     * @param spillTo     the buffer its forced row spills into
     * @param why         lit in an Up to reason's tone; null for none
     */
    public record PortView(String key, String handleId, boolean output, String number, String unit, String name,
        String title, boolean zero, boolean gold, @Nullable Double rate, boolean numberSmall, boolean nameSmall,
        boolean nameClipped, boolean wired, boolean choice, String resourceKey, boolean free, @Nullable Figure forced,
        @Nullable String spillTo, @Nullable Tones.Role why) {}

    /** A number and its unit, as printed. */
    public record Figure(String number, String unit) {}

    /**
     * A setting on the strip, laid out: card-local x and y, its width. A reading has no box; a power cap carries both
     * counts for its tip.
     */
    public record ChipView(String id, String label, String value, @Nullable String icon, boolean reading,
        @Nullable PowerCap powerCap, boolean warn, String title, int x, int y, int width) {}

    /** Fewer run at once than the structure allows, because the supply pays for no more. */
    public record PowerCap(double running, double structure) {}

    /** The power row: a machine's draw, or a steam machine's litres; "TIER!" when it cannot run. */
    public record PowerView(String number, String unit, String name, boolean stalled, String title) {}

    /**
     * The count under the machine: what runs, big ({@code text}), what it counts when not machines ({@code unit}),
     * the rule and its word, the count set ({@code set}, {@code setText}), what the set box shows ({@code box}: the
     * count, or "Set"), whether it asks for a count ({@code prompt}) and whether nothing runs.
     */
    public record CountView(String text, @Nullable String unit, double value, boolean pinned, PinRule rule,
        String ruleWord, boolean ruleFixed, @Nullable Double set, String setText, String box, boolean prompt,
        boolean zero, String title) {}

    /**
     * The bar along the foot: its tone, one word, one plain line fitted beside them, the fraction, the fill in px
     * inside the track, a bottleneck's striped run-on, and the facts for its tip.
     */
    public record FootView(Tones.Role tone, String word, String say, String frac, int fill, @Nullable Ghost ghost,
        FootFacts facts) {}

    /** A bottleneck's run-on: where it starts in the track and how wide. */
    public record Ghost(int left, int width) {}

    /**
     * The bar's facts: {@code reason} is the Up to reason's name, or FORCED, IDLE or FLOW; null when the reason was not
     * worked out.
     */
    public record FootFacts(@Nullable String reason, @Nullable UpToLoop.Kind loop, PinRule rule, double running,
        double capacity, @Nullable Double wants, @Nullable String resource, List<String> tied, List<String> upstream,
        List<String> forced, @Nullable String sentOut, @Nullable CardInput.Turbine turbine,
        @Nullable String turbineTier) {}

    /** The zoomed-out face's bar: its tone, how full (0 to 1), its word. */
    public record GlanceBar(Tones.Role tone, double fill, String word) {}

    /** A row of the card's menu: what it does ({@code id}), its words, red or not. */
    public record MenuRow(String id, String label, boolean danger) {}

    // region JSON

    /** The record's one JSON spelling (component order; an optional left out when null). */
    public String toJson() {
        final Json j = new Json();
        write(j, this);
        return j.toString();
    }

    public static void write(final Json j, final CardView v) {
        j.begin()
            .str("nodeId", v.nodeId)
            .str("name", v.name)
            .num("nameStep", v.nameStep)
            .bool("hasPicker", v.hasPicker);
        if (v.tier != null) j.field(
            "tier",
            x -> x.begin()
                .str("label", v.tier.label)
                .bool("fixed", v.tier.fixed)
                .str("title", v.tier.title)
                .end());
        if (v.amps != null) j.field(
            "amps",
            x -> x.begin()
                .str("label", v.amps.label)
                .num("value", v.amps.value)
                .bool("editable", v.amps.editable)
                .end());
        j.list("inputs", v.inputs, CardView::writePort);
        j.list("outputs", v.outputs, CardView::writePort);
        if (v.power != null) j.field(
            "power",
            x -> x.begin()
                .str("number", v.power.number)
                .str("unit", v.power.unit)
                .str("name", v.power.name)
                .bool("stalled", v.power.stalled)
                .str("title", v.power.title)
                .end());
        j.opt("art", v.art)
            .opt("glanceArt", v.glanceArt);
        if (v.glanceProduct != null) j.field(
            "glanceProduct",
            x -> x.begin()
                .str("icon", v.glanceProduct.icon())
                .bool("fluid", v.glanceProduct.fluid())
                .end());
        j.field("count", x -> writeCount(x, v.count));
        if (v.foot != null) j.field("foot", x -> writeFoot(x, v.foot));
        if (v.loop != null) j.str("loop", v.loop.name());
        if (v.glance != null) j.field(
            "glance",
            x -> x.begin()
                .str("tone", v.glance.tone.name())
                .num("fill", v.glance.fill)
                .str("word", v.glance.word)
                .end());
        if (v.circuit != null) j.field(
            "circuit",
            x -> x.begin()
                .opt("icon", v.circuit.icon())
                .opt("number", v.circuit.number())
                .end());
        j.list("chips", v.chips, CardView::writeChip)
            .num("stripHeight", v.stripHeight)
            .bool("selected", v.selected)
            .bool("cannotRun", v.cannotRun)
            .bool("unset", v.unset);
        if (v.frame != null) j.str("frame", v.frame.name());
        j.list(
            "menu",
            v.menu,
            (x, r) -> x.begin()
                .str("id", r.id)
                .str("label", r.label)
                .bool("danger", r.danger)
                .end());
        j.end();
    }

    /** One port row's JSON (a special card's sections write theirs the same way). */
    public static void writePort(final Json j, final PortView p) {
        j.begin()
            .str("key", p.key)
            .str("handleId", p.handleId)
            .bool("output", p.output)
            .str("number", p.number)
            .str("unit", p.unit)
            .str("name", p.name)
            .str("title", p.title)
            .bool("zero", p.zero)
            .bool("gold", p.gold)
            .opt("rate", p.rate)
            .bool("numberSmall", p.numberSmall)
            .bool("nameSmall", p.nameSmall)
            .bool("nameClipped", p.nameClipped)
            .bool("wired", p.wired)
            .bool("choice", p.choice)
            .str("resourceKey", p.resourceKey)
            .bool("free", p.free);
        if (p.forced != null) j.field(
            "forced",
            x -> x.begin()
                .str("number", p.forced.number)
                .str("unit", p.forced.unit)
                .end());
        j.opt("spillTo", p.spillTo);
        if (p.why != null) j.str("why", p.why.name());
        j.end();
    }

    /** One chip's JSON. */
    public static void writeChip(final Json j, final ChipView c) {
        j.begin()
            .str("id", c.id)
            .str("label", c.label)
            .str("value", c.value)
            .opt("icon", c.icon)
            .bool("reading", c.reading);
        if (c.powerCap != null) j.field(
            "powerCap",
            x -> x.begin()
                .num("running", c.powerCap.running)
                .num("structure", c.powerCap.structure)
                .end());
        j.bool("warn", c.warn)
            .str("title", c.title)
            .num("x", c.x)
            .num("y", c.y)
            .num("width", c.width)
            .end();
    }

    /** The count's JSON. */
    public static void writeCount(final Json j, final CountView c) {
        j.begin()
            .str("text", c.text)
            .opt("unit", c.unit)
            .num("value", c.value)
            .bool("pinned", c.pinned)
            .str("rule", c.rule.name())
            .str("ruleWord", c.ruleWord)
            .bool("ruleFixed", c.ruleFixed)
            .opt("set", c.set)
            .str("setText", c.setText)
            .str("box", c.box)
            .bool("prompt", c.prompt)
            .bool("zero", c.zero)
            .str("title", c.title)
            .end();
    }

    /** The foot bar's JSON. */
    public static void writeFoot(final Json j, final FootView f) {
        j.begin()
            .str("tone", f.tone.name())
            .str("word", f.word)
            .str("say", f.say)
            .str("frac", f.frac)
            .num("fill", f.fill);
        if (f.ghost != null) j.field(
            "ghost",
            x -> x.begin()
                .num("left", f.ghost.left)
                .num("width", f.ghost.width)
                .end());
        final FootFacts a = f.facts;
        j.field("facts", x -> {
            x.begin()
                .opt("reason", a.reason);
            if (a.loop != null) x.str("loop", a.loop.name());
            x.str("rule", a.rule.name())
                .num("running", a.running)
                .num("capacity", a.capacity)
                .opt("wants", a.wants)
                .opt("resource", a.resource)
                .strings("tied", a.tied)
                .strings("upstream", a.upstream)
                .strings("forced", a.forced)
                .opt("sentOut", a.sentOut);
            if (a.turbine != null) x.field(
                "turbine",
                t -> t.begin()
                    .num("turbines", a.turbine.turbines())
                    .num("flow", a.turbine.flow())
                    .num("setFlow", a.turbine.setFlow())
                    .num("optimal", a.turbine.optimal())
                    .str("unit", a.turbine.unit())
                    .num("euMade", a.turbine.euMade())
                    .num("euCounted", a.turbine.euCounted())
                    .end());
            x.opt("turbineTier", a.turbineTier)
                .end();
        });
        j.end();
    }

    // endregion
}
