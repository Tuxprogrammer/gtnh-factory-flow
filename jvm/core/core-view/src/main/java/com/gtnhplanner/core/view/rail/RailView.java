package com.gtnhplanner.core.view.rail;

import java.util.List;

import javax.annotation.Nullable;

/**
 * What the overview rail shows, worked out: the three resource sections and the machine list with the numbers they
 * print, under the filter, the peak or average choice and the power dial. A host draws it and adds only what is its own
 * (pixels, fonts, folding, the icon of a resource). Numbers are plain doubles with the unit they are in; each host
 * spells them with its own number formatter, because the two differ in rounding and in the top of the unit ladder.
 * Words the player reads are keys (see {@link RailBuilder#EMPTY_INPUTS}), never sentences.
 */
public record RailView(Dial dial, List<Section> sections, Machines machines) {

    /** The power dial as the totals read it: {@code unit} is eu, amps or a tier; {@code suffix} what a total wears. */
    public record Dial(String unit, String suffix, @Nullable Double divisor) {}

    /** A resource section: {@code count} lines kept in it (spare groups included), {@code shown} those the filter keeps. */
    public record Section(String id, int count, int shown, @Nullable String empty, List<Line> lines,
        List<Spare> spares) {}

    public record Line(String key, String kind, String id, String label, double amount, boolean energy,
        List<Drawer> drawers, List<Pin> pins, List<Open> open) {}

    public record Drawer(String id, boolean source, String role, String rule, double rate, boolean set,
        boolean unmet) {}

    public record Pin(String nodeId, String handle, boolean output, double rate, String machine) {}

    public record Open(String nodeId, String handle, boolean output, double perSecond, String machine) {}

    public record Bearer(String key, String nodeId, String handle, boolean output, String name, String machine,
        String resourceKey, double max, boolean solverPick, boolean energy, List<RailInput.PortRef> ports) {}

    public record Spare(String key, boolean importing, boolean custom, List<Bearer> bearers, List<Double> solved,
        List<Double> shares) {}

    /** A figure and the unit it is in. */
    public record Fig(double value, String suffix) {}

    /**
     * A machine's power figure: {@code kind} is tooLow (the card's tier is too low: the host writes the tier with a
     * bang), made (a generator, +value), steam (L/s) or used (EU/t or amps); none when it has no figure.
     */
    public record Eu(String kind, @Nullable Double value, @Nullable String suffix) {}

    public record Entry(String nodeId, String label, double count, boolean pinned, String rule, @Nullable Double pin,
        @Nullable RailInput.UpToIn upTo, @Nullable String tier, @Nullable Integer amps, boolean multiblock,
        String state, Eu eu) {}

    public record Group(String name, List<Entry> entries) {}

    public record Machines(int count, int shown, Fig total, @Nullable Fig made, @Nullable Fig net,
        @Nullable String empty, List<Group> groups) {}

    // region JSON

    /** The view as JSON text, keys in the order of the records, numbers as {@code JSON.stringify} spells them. */
    public String toJson() {
        final Json j = new Json();
        j.begin();
        j.field("dial", d -> dial(d, dial));
        j.list("sections", sections, RailView::section);
        j.field("machines", m -> machines(m, machines));
        j.end();
        return j.toString();
    }

    private static void dial(final Json j, final Dial d) {
        j.begin().str("unit", d.unit).str("suffix", d.suffix).opt("divisor", d.divisor).end();
    }

    private static void section(final Json j, final Section s) {
        j.begin().str("id", s.id).num("count", s.count).num("shown", s.shown).opt("empty", s.empty);
        j.list("lines", s.lines, RailView::line);
        j.list("spares", s.spares, RailView::spare);
        j.end();
    }

    private static void line(final Json j, final Line l) {
        j.begin()
            .str("key", l.key)
            .str("kind", l.kind)
            .str("id", l.id)
            .str("label", l.label)
            .num("amount", l.amount)
            .bool("energy", l.energy);
        j.list("drawers", l.drawers, (jj, d) -> jj.begin()
            .str("id", d.id)
            .bool("source", d.source)
            .str("role", d.role)
            .str("rule", d.rule)
            .num("rate", d.rate)
            .bool("set", d.set)
            .bool("unmet", d.unmet)
            .end());
        j.list("pins", l.pins, (jj, p) -> jj.begin()
            .str("nodeId", p.nodeId)
            .str("handle", p.handle)
            .bool("output", p.output)
            .num("rate", p.rate)
            .str("machine", p.machine)
            .end());
        j.list("open", l.open, (jj, o) -> jj.begin()
            .str("nodeId", o.nodeId)
            .str("handle", o.handle)
            .bool("output", o.output)
            .num("perSecond", o.perSecond)
            .str("machine", o.machine)
            .end());
        j.end();
    }

    private static void spare(final Json j, final Spare s) {
        j.begin().str("key", s.key).bool("importing", s.importing).bool("custom", s.custom);
        j.list("bearers", s.bearers, (jj, b) -> {
            jj.begin()
                .str("key", b.key)
                .str("nodeId", b.nodeId)
                .str("handle", b.handle)
                .bool("output", b.output)
                .str("name", b.name)
                .str("machine", b.machine)
                .str("resourceKey", b.resourceKey)
                .num("max", b.max)
                .bool("solverPick", b.solverPick)
                .bool("energy", b.energy);
            jj.list("ports", b.ports, (j3, p) -> j3.begin().str("nodeId", p.nodeId()).str("handle", p.handle()).end());
            jj.end();
        });
        j.numbers("solved", s.solved);
        j.numbers("shares", s.shares);
        j.end();
    }

    private static void fig(final Json j, final Fig f) {
        j.begin().num("value", f.value).str("suffix", f.suffix).end();
    }

    private static void machines(final Json j, final Machines m) {
        j.begin().num("count", m.count).num("shown", m.shown);
        j.field("total", t -> fig(t, m.total));
        if (m.made != null) j.field("made", t -> fig(t, m.made));
        if (m.net != null) j.field("net", t -> fig(t, m.net));
        j.opt("empty", m.empty);
        j.list("groups", m.groups, (jj, g) -> {
            jj.begin().str("name", g.name);
            jj.list("entries", g.entries, RailView::entry);
            jj.end();
        });
        j.end();
    }

    private static void entry(final Json j, final Entry e) {
        j.begin()
            .str("nodeId", e.nodeId)
            .str("label", e.label)
            .num("count", e.count)
            .bool("pinned", e.pinned)
            .str("rule", e.rule)
            .opt("pin", e.pin);
        if (e.upTo != null) {
            j.field("upTo", u -> u.begin().opt("reason", e.upTo.reason()).num("reached", e.upTo.reached())
                .num("capacity", e.upTo.capacity()).end());
        }
        j.opt("tier", e.tier);
        if (e.amps != null) j.num("amps", e.amps);
        j.bool("multiblock", e.multiblock).str("state", e.state);
        j.field("eu", u -> u.begin().str("kind", e.eu.kind).opt("value", e.eu.value).opt("suffix", e.eu.suffix).end());
        j.end();
    }

    // endregion
}
