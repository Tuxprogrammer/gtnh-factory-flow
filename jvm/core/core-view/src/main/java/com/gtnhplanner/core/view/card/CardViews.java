package com.gtnhplanner.core.view.card;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.ExactMath;
import com.gtnhplanner.core.base.Format;
import com.gtnhplanner.core.base.NumberText;
import com.gtnhplanner.core.base.RateUnit;
import com.gtnhplanner.core.base.Text;
import com.gtnhplanner.core.view.TextMeasure;
import com.gtnhplanner.core.view.TextStyle;
import com.gtnhplanner.core.view.Tokens;
import com.gtnhplanner.core.view.Tones;
import com.gtnhplanner.core.view.card.CardInput.Dials;
import com.gtnhplanner.core.view.card.CardInput.Port;
import com.gtnhplanner.core.view.card.CardView.ChipView;
import com.gtnhplanner.core.view.card.CardView.CountView;
import com.gtnhplanner.core.view.card.CardView.Figure;
import com.gtnhplanner.core.view.card.CardView.FootFacts;
import com.gtnhplanner.core.view.card.CardView.FootView;
import com.gtnhplanner.core.view.card.CardView.PortView;
import com.gtnhplanner.core.view.rail.RailMath;
import com.gtnhplanner.data.flowchart.PinRule;
import com.gtnhplanner.data.flowchart.balancer.UpToLoop;
import com.gtnhplanner.data.flowchart.balancer.UpToReason;
import com.gtnhplanner.machines.web.Tiers;

/**
 * The recipe card's view rules: the website's clean-model.ts builders ({@code buildPortView}, {@code layoutStrip},
 * {@code cardCountState} and {@code buildCountView}, {@code wheelCount}, {@code parseCountText}, {@code wheelDraft},
 * {@code buildFootView}, {@code frameTone}, {@code withReasonPorts}, {@code parallelReading}, the assembly in
 * {@code useCleanCardModel}), glance.ts, loops.ts ({@code cardLoop}), idle-trade.ts and the card menu's rows
 * (menus/card-recipe-rows.ts), translated function for function. Both hosts' cards are bound to the same cases
 * ({@code conformance/view/card/}); a rule changes on both sides in one commit.
 *
 * <p>
 * Words for players go through core-base's {@link Text} (keys {@code gtnhplanner.card.*}), numbers through
 * {@link Format}, widths through the host's {@link TextMeasure}. Where the two hosts' cards differ by design (the head's
 * room for the name, the strip's chip widths), the rule takes the {@link Host}.
 */
public final class CardViews {

    private CardViews() {}

    /** Whose card: the geometry and fit rules that differ by host by design (tokens' {@code _WEB} / {@code _MOD}). */
    public enum Host {
        WEB,
        MOD
    }

    /** Below this a count is nothing (the solver's round-off). */
    public static final double NOTHING = 0.0000005;

    /** The bar's width (the card's inner width), its track (2 px in from each side) and the room its words have. */
    public static final int FOOT_WIDTH = Tokens.Card.CARD_WIDTH - 2 * Tokens.Card.RAIL_INSET;
    public static final int FOOT_TRACK = FOOT_WIDTH - 4;
    public static final int FOOT_TEXT = FOOT_WIDTH - 14;
    public static final int FOOT_GAP = 6;
    /** The game font's room for a port's name at full size: the mod steps the name down past it, the website too. */
    public static final int GAME_NAME_ROOM = Tokens.Card.WORDS_WIDTH_MOD;

    static String t(final String key, final Object... args) {
        return args.length == 0 ? Text.get()
            .translate("gtnhplanner.card." + key)
            : Text.get()
                .format("gtnhplanner.card." + key, args);
    }

    // region The card

    /** Everything the card shows, from its facts, the board's dials and the host's text widths. */
    public static CardView card(final CardInput in, final Dials dials, final TextMeasure measure, final Host host) {
        final CardInput.PowerReport report = in.power();
        final CardInput.SteamReport steam = in.steam();
        final String name = in.name();
        // The head: a multiblock's tier is its report's (its hatch's), else the ladder's; amps only on a hatch chip.
        final String tierLabel = report != null && report.multiblock() ? report.tier() : in.tier();
        final String ampsLabel = report != null && report.multiblock() && in.hatchControl()
            ? report.hatchChip() != null ? report.hatchChip() : Format.compact(report.amps()) + "A"
            : null;
        final int nameStep = nameStep(name, tierLabel != null, ampsLabel != null, in.hasPicker(), measure, host);

        // Count: what runs, big; what you set, small, under it (docs/up-to.md).
        final CountView count = count(in.count(), in.count()
            .rule() == PinRule.UP_TO ? in.upTo() : null, false, null, null);
        final CardInput.UpTo upTo = in.count()
            .rule() == PinRule.UP_TO ? in.upTo() : null;

        final CardView.PowerView power = powerRow(in, count, upTo, dials);

        // Settings strip: the settings pinned to this machine's cards, then the Parallel reading.
        final List<ChipSpec> raw = new ArrayList<>();
        for (final CardInput.Setting s : in.pinned()) raw.add(
            new ChipSpec(s.id(), s.label(), s.value(), s.icon(), false, null, s.warn(),
                t("chip.title", s.label(), s.value())));
        final double running = report != null ? report.parallels() : steam != null ? steam.parallels() : in.parallel();
        final ChipSpec parallel = parallelReading(running, in.structuralParallel(), report != null && report.ok());
        if (parallel != null) raw.add(parallel);
        final Strip strip = layoutStrip(raw, measure, host);

        // The rows an Up to card's reason names are lit in its colour (only while the card is Up to).
        final List<PortView> inputs = withReasonPorts(ports(in, in.inputs(), dials, measure, host), upTo);
        final List<PortView> outputs = withReasonPorts(ports(in, in.outputs(), dials, measure, host), upTo);
        final UpToLoop.Kind loop = in.loop();
        // On Any and running none: is it because its input is sent out instead of its output?
        Idle idle = null;
        if (count.rule() == PinRule.ANY && count.zero()) {
            final Set<String> keys = new HashSet<>();
            for (final PortView p : inputs) keys.add(p.resourceKey());
            idle = idleTrade(in.leftovers(), in.nodeId(), keys);
        }
        final FootView foot = foot(count.rule(), count.set(), upTo, inputs, outputs, in.cardNames(), loop, idle,
            in.turbine(), in.powerTier(), dials, measure);

        String tierTitle = null;
        if (tierLabel != null) tierTitle = in.tierFixed() ? t("tier.fixed") : t("tier.title", tierLabel);
        return new CardView(
            in.nodeId(),
            name,
            nameStep,
            in.hasPicker(),
            tierLabel == null ? null : new CardView.TierChip(tierLabel, in.tierFixed(), tierTitle),
            ampsLabel != null ? new CardView.AmpsChip(ampsLabel, report.amps(), true) : null,
            inputs,
            outputs,
            power,
            in.art(),
            in.glanceArt(),
            in.product(),
            count,
            foot,
            loop,
            glanceBar(upTo, loop),
            in.circuit(),
            strip.chips,
            strip.height,
            in.selected(),
            report != null && !report.ok(),
            count.prompt(),
            frameTone(foot),
            menu(in.menu()));
    }

    /**
     * The power row: a machine's draw, or a steam machine's litres. An Up to card bills its count averaged by how full
     * it got of it; under Peak a card that runs at all bills every machine built (the count set, or what the solve
     * needs when that is more; with none, whole machines) times one machine's draw.
     */
    @Nullable
    public static CardView.PowerView powerRow(final CardInput in, final CountView count, @Nullable final CardInput.UpTo upTo,
        final Dials dials) {
        final CardInput.PowerReport report = in.power();
        final CardInput.SteamReport steam = in.steam();
        final double usage = upTo != null ? upTo.capacity() > 0 ? Math.min(1, upTo.reached() / upTo.capacity()) : 0
            : Math.min(
                1,
                Math.max(
                    0,
                    in.count()
                        .utilization() == null ? 1
                            : in.count()
                                .utilization()));
        final double drawScale = dials.averageDraw() ? usage : usage > 0 ? 1 : 0;
        // Averaged, the count set (fractions and all; else the solved count) by its use; at its peak, every machine
        // built, as the overview bills it (RailMath.peakCount).
        final double needed = in.count()
            .needed();
        final double machines = (dials.averageDraw() ? count.set() != null ? count.set() : needed
            : RailMath.peakCount(needed, count.set(), false))
            * Math.max(
                1,
                in.count()
                    .nodeParallel());
        if (report != null) {
            final boolean stalled = !report.ok();
            final String tier = report.tier();
            return new CardView.PowerView(
                stalled ? t("power.stalled")
                    : Format.powerValue(
                        RailMath.dialValue(report.drawEuT() * machines * drawScale, dials.power(), tier)),
                stalled ? "" : RailMath.dialSuffix(dials.power(), tier),
                t("power.name"),
                stalled,
                stalled ? report.gateReason() != null ? report.gateReason() : t("power.too_low")
                    : t("power.title", tier, Format.compact(report.amps())));
        }
        if (steam != null) {
            return new CardView.PowerView(
                Format.compact(steam.drawSteamPerTick() * 20 * machines * drawScale),
                "L/s",
                steam.highPressure() ? t("steam.hp") : t("steam.name"),
                false,
                t("steam.title"));
        }
        return null;
    }

    /**
     * Where the name stands on the host's ladder: the largest size that fits beside the head's keys and chips, else
     * the smallest (cut past it).
     */
    public static int nameStep(final String name, final boolean tier, final boolean amps, final boolean picker,
        final TextMeasure measure, final Host host) {
        final double room = nameRoom(tier, amps, picker, host);
        if (measure.width(TextStyle.NAME_LARGE, name) <= room) return 0;
        if (measure.width(TextStyle.NAME_MEDIUM, name) <= room) return 1;
        return 2;
    }

    /** The head's room for the name: the card less its chips and keys (the website's head; the mod's own). */
    public static double nameRoom(final boolean tier, final boolean amps, final boolean picker, final Host host) {
        if (host == Host.WEB) {
            final int chip = Tokens.Card.CHIP_WIDTH_WEB, key = Tokens.Card.KEY_SIZE_WEB;
            final int chipsLeft = Tokens.Card.CARD_WIDTH - 8 - (tier ? chip : 0) - (amps ? 2 + chip : 0);
            return Math.max(40, chipsLeft - 8 - 8 - 2 * (key + 2) - 8 - (picker ? 15 : 0));
        }
        // The mod's head (RecipeCard.drawHead): three keys and a gap (the name starts at 78), the name up to the chips
        // (a multiblock's amps and tier from 208, a single block's tier from 264, none: the card's right inset at
        // 314), less the chevron's 12 when the name is a menu.
        final int right = tier ? amps ? 208 : 264 : Tokens.Card.CARD_WIDTH - 6;
        return right - (picker ? 12 : 0) - 78;
    }

    // endregion

    // region Ports

    private static List<PortView> ports(final CardInput in, final List<Port> ports, final Dials dials,
        final TextMeasure measure, final Host host) {
        // Only this card's forced entries, and its spills into buffers, which it forces out the same way.
        final Map<String, Double> forcedAt = new LinkedHashMap<>();
        final Map<String, String> spillAt = new LinkedHashMap<>();
        for (final CardInput.Forced f : in.forced()) {
            forcedAt.merge(f.portKey(), f.perSecond(), Double::sum);
            if (f.drawerId() != null) spillAt.put(f.portKey(), f.drawerId());
        }
        final List<PortView> out = new ArrayList<>();
        for (final Port p : ports) out.add(port(in.nodeId(), p, forcedAt, spillAt, in.powerTier(), dials, measure, host));
        return out;
    }

    /** A port's key as the solve names it: {@code nodeId|i or o|handle}. */
    public static String portKey(final String nodeId, final boolean output, final String handle) {
        return nodeId + "|" + (output ? "o" : "i") + "|" + handle;
    }

    /**
     * The number on a port's row: what its wires carry. What the solve forces out of the plan at an output is the
     * forced row under it, not part of the row; an input's reading is its wires' already.
     */
    public static double rowPerSecond(final Port port, @Nullable final Double forced) {
        return port.output() && forced != null && forced > 0 ? Math.max(0, port.perSecond() - forced)
            : port.perSecond();
    }

    /** One port row, every string final (the website's {@code buildPortView}). */
    public static PortView port(final String nodeId, final Port port, final Map<String, Double> forcedAt,
        final Map<String, String> spillAt, @Nullable final String powerTier, final Dials dials,
        final TextMeasure measure, final Host host) {
        final String key = portKey(nodeId, port.output(), port.handleId());
        final Double forcedRate = forcedAt.get(key);
        final String spillTo = spillAt.get(key);
        final double shown = rowPerSecond(port, forcedRate);
        final boolean power = "power".equals(port.kind());
        final Figure figure;
        if (dials.rate() == RateUnit.EU && port.energyPerUnit() != null) {
            figure = energyFigure(port.energyPerUnit(), port.kind(), dials.power());
        } else if (power) {
            figure = powerFigure(shown, powerTier, dials);
        } else {
            figure = new Figure(
                Format.slotRateBare(shown, dials.rate(), port.kind()),
                Format.rateSuffix(dials.rate(), port.kind())
                    .trim());
        }
        final String chanceText = port.chance() != null && port.chance() < 1 ? percent(port.chance()) : null;
        final String consumeText = port.consumeChance() != null && port.consumeChance() < 1
            ? percent(port.consumeChance())
            : null;
        final String title = chanceText != null ? t("port.chance", port.name(), chanceText)
            : consumeText != null ? t("port.consume", port.name(), consumeText) : port.name();
        final boolean nameSmall = measure.width(TextStyle.GAME, port.name()) > GAME_NAME_ROOM;
        final int words = host == Host.WEB ? Tokens.Card.WORDS_WIDTH_WEB : Tokens.Card.WORDS_WIDTH_MOD;
        final boolean forced = forcedRate != null && forcedRate > 0;
        return new PortView(
            port.key(),
            port.handleId(),
            port.output(),
            figure.number(),
            figure.unit(),
            port.name(),
            title,
            !(shown > 0),
            port.setRate() != null,
            port.setRate(),
            rowFigureWidth(figure.number(), figure.unit(), measure) > words,
            nameSmall,
            measure.width(nameSmall ? TextStyle.ROW_NAME_SMALL : TextStyle.ROW_NAME, port.name()) > words,
            port.wired(),
            port.alternatives() > 1,
            port.kind() + ":" + port.resourceId(),
            port.free(),
            !forced ? null
                : power ? powerFigure(forcedRate, powerTier, dials)
                    : new Figure(
                        Format.slotRateBare(forcedRate, dials.rate(), port.kind()),
                        Format.rateSuffix(dials.rate(), port.kind())
                            .trim()),
            forced ? spillTo : null,
            null);
    }

    /** A row's number and unit as drawn: the number at figure size, a pixel, the unit after it. */
    public static double rowFigureWidth(final String number, final String unit, final TextMeasure measure) {
        return measure.width(TextStyle.ROW_FIGURE, number) + 1 + measure.width(TextStyle.ROW_UNIT, unit);
    }

    /** "42.5%": a share of one as a percent, two decimals at most (JavaScript's spelling). */
    static String percent(final double share) {
        return NumberText.of(ExactMath.round(share * 10000) / 100.0) + "%";
    }

    /** A per-second power flow under the power dial, in the card's own tier (a generator's EU row). */
    public static Figure powerFigure(final double perSecond, @Nullable final String tier, final Dials dials) {
        return new Figure(
            Format.powerValue(RailMath.dialValue(perSecond / 20, dials.power(), tier)),
            RailMath.dialSuffix(dials.power(), tier));
    }

    /**
     * An energy reading: EU per unit made, under the power dial ("200" "EU/Item"; "6.25" "A LV/Item" in a tier). The
     * website's {@code formatEnergyPerUnitParts}.
     */
    public static Figure energyFigure(final double euPerUnit, final String kind, final String powerUnit) {
        final boolean plain = powerUnit.equals("eu") || powerUnit.equals("amps");
        final double value = plain ? euPerUnit : euPerUnit / Tiers.maxEuT(powerUnit);
        final String per = "fluid".equals(kind) ? "L" : "Item";
        return new Figure(Format.powerValue(value), plain ? "EU/" + per : "A " + powerUnit + "/" + per);
    }

    /**
     * The rows an Up to card's reason names, each lit in the reason's tone (STARVED's short input, CLOGGED's or UNUSED's
     * output, and the rows tied with it). Matched by port key, a shared machine's recipe by its handle's section
     * prefix; a read-out with no port key falls back to the row of its resource on the reason's side. Never a free row.
     */
    public static List<PortView> withReasonPorts(final List<PortView> ports, @Nullable final CardInput.UpTo upTo) {
        final UpToReason reason = upTo == null ? null : upTo.reason();
        if (reason != UpToReason.STARVED && reason != UpToReason.CLOGGED && reason != UpToReason.UNUSED) return ports;
        final Tones.Role tone = tone(reason);
        final boolean outputSide = reason != UpToReason.STARVED;
        final Set<String> named = new HashSet<>();
        final List<String> keys = new ArrayList<>();
        if (upTo.portKey() != null) keys.add(upTo.portKey());
        keys.addAll(upTo.tied());
        for (final String key : keys) {
            final String[] at = parsePortKey(key);
            if (at == null) continue;
            final int section = sectionOf(at[0]);
            named.add(("o".equals(at[1]) ? "output" : "input") + "|" + sectionHandle(section, at[2]));
        }
        final List<PortView> out = new ArrayList<>();
        for (final PortView p : ports) {
            final boolean lit = named.contains((p.output() ? "output" : "input") + "|" + p.handleId())
                || (upTo.portKey() == null && p.output() == outputSide
                    && p.resourceKey()
                        .equals(upTo.resourceKey()));
            out.add(lit && !p.free() ? lit(p, tone) : p);
        }
        return out;
    }

    private static PortView lit(final PortView p, final Tones.Role why) {
        return new PortView(
            p.key(),
            p.handleId(),
            p.output(),
            p.number(),
            p.unit(),
            p.name(),
            p.title(),
            p.zero(),
            p.gold(),
            p.rate(),
            p.numberSmall(),
            p.nameSmall(),
            p.nameClipped(),
            p.wired(),
            p.choice(),
            p.resourceKey(),
            p.free(),
            p.forced(),
            p.spillTo(),
            why);
    }

    /** {nodeId, side "i"/"o", handle} of a port key, or null when it is not one. */
    @Nullable
    public static String[] parsePortKey(final String key) {
        final int first = key.indexOf('|');
        if (first <= 0) return null;
        final int second = key.indexOf('|', first + 1);
        if (second < 0) return null;
        final String side = key.substring(first + 1, second);
        if (!side.equals("i") && !side.equals("o")) return null;
        return new String[] { key.substring(0, first), side, key.substring(second + 1) };
    }

    /** The recipe a solve node stands for on a shared machine ({@code card#rN}), 0 for the card itself. */
    static int sectionOf(final String id) {
        final int at = id.lastIndexOf("#r");
        if (at <= 0) return 0;
        final String tail = id.substring(at + 2);
        if (tail.isEmpty()) return 0;
        for (int i = 0; i < tail.length(); i++) if (tail.charAt(i) < '0' || tail.charAt(i) > '9') return 0;
        return Integer.parseInt(tail);
    }

    static String sectionHandle(final int section, final String handle) {
        return section > 0 ? "r" + section + ":" + handle : handle;
    }

    /** A handle without its section prefix ({@code r<n>:}). */
    static String bareHandle(final String handle) {
        if (handle.length() < 3 || handle.charAt(0) != 'r') return handle;
        int i = 1;
        while (i < handle.length() && handle.charAt(i) >= '0' && handle.charAt(i) <= '9') i++;
        return i > 1 && i < handle.length() && handle.charAt(i) == ':' ? handle.substring(i + 1) : handle;
    }

    // endregion

    // region The strip

    /** A chip before it is laid out. */
    public record ChipSpec(String id, String label, String value, @Nullable String icon, boolean reading,
        @Nullable CardView.PowerCap powerCap, boolean warn, String title) {}

    /** The strip's chips laid out, and its height (0 with none). */
    public record Strip(List<ChipView> chips, int height) {}

    /**
     * The Parallel reading: what runs at once, when that is more than one or fewer than the structure allows. Fewer on
     * a powered machine means the supply pays for no more (the game's ParallelHelper caps parallels at what the supply
     * covers); the chip then carries both numbers for its tip.
     */
    @Nullable
    public static ChipSpec parallelReading(final double running, final double structure, final boolean powered) {
        final CardView.PowerCap cap = powered && running < structure ? new CardView.PowerCap(running, structure)
            : null;
        if (!(running > 1) && cap == null) return null;
        return new ChipSpec("parallel", t("parallel.label"), "×" + Format.compact(running), null, true, cap, false,
            t("parallel.title"));
    }

    /**
     * The strip's chips in rows of the card's inner width, laid out (never flowed), so the card's height is a pure
     * function of the data. The website's widths: label and value at the chips' size, 12 of box, 17 for a picture,
     * whole pixels; the mod's: its padding and picture, the font's whole widths.
     */
    public static Strip layoutStrip(final List<ChipSpec> chips, final TextMeasure measure, final Host host) {
        if (chips.isEmpty()) return new Strip(List.of(), 0);
        final List<ChipView> placed = new ArrayList<>();
        if (host == Host.WEB) {
            final int inset = Tokens.Card.STRIP_INSET_WEB, inner = Tokens.Card.CARD_WIDTH - 2 * inset;
            final int pitch = Tokens.Card.SETTINGS_ROW_PITCH_WEB;
            int x = 0, row = 0;
            for (final ChipSpec c : chips) {
                final double text = measure.width(TextStyle.LABEL, c.label())
                    + (c.value()
                        .isEmpty() ? 0 : 4 + measure.width(TextStyle.LABEL, c.value()));
                final int width = (int) Math
                    .min(inner, Math.ceil(text + (c.reading() ? 0 : 12) + (c.icon() != null ? 17 : 0)));
                if (x > 0 && x + width > inner) {
                    x = 0;
                    row++;
                }
                placed.add(chip(c, inset + x, Tokens.Card.STRIP_GAP_WEB + Tokens.Card.STRIP_PAD_WEB + row * pitch, width));
                x += width + Tokens.Card.STRIP_CHIP_GAP_WEB;
            }
            return new Strip(placed, (row + 1) * pitch + Tokens.Grid.BOARD_GRID);
        }
        // The mod's (CardLayout): from the card's padding, a new row past its edge, 4 apart; y from the hairline.
        final int pad = 6, w = Tokens.Card.CARD_WIDTH, pitch = Tokens.Card.SETTINGS_ROW_PITCH_MOD;
        int x = pad, y = 4;
        for (final ChipSpec c : chips) {
            final int width = 5 + (c.icon() != null ? 17 : 0) + (int) measure.width(TextStyle.LABEL, c.label()) + 4
                + (int) measure.width(TextStyle.LABEL, c.value()) + 5;
            if (x > pad && x + width > w - pad) {
                x = pad;
                y += pitch;
            }
            placed.add(chip(c, x, y, width));
            x += width + 4;
        }
        return new Strip(placed, y + Tokens.Card.SETTINGS_CHIP_HEIGHT + 4);
    }

    private static ChipView chip(final ChipSpec c, final int x, final int y, final int width) {
        return new ChipView(c.id(), c.label(), c.value(), c.icon(), c.reading(), c.powerCap(), c.warn(), c.title(), x, y,
            width);
    }

    // endregion

    // region The count

    /** The set count as the card prints it: up to three decimals, no trailing zeros. */
    public static String setText(final double value) {
        return NumberText.of(ExactMath.round(value * 1000) / 1000.0);
    }

    /** A rule's word on the card: "Any", "Up to", "Exactly". */
    public static String ruleWord(final PinRule rule) {
        return switch (rule) {
            case ANY -> t("rule.any");
            case UP_TO -> t("rule.up_to");
            case EXACT -> t("rule.exact");
        };
    }

    /**
     * The count view: what runs (an Up to card's reached count, else the pin when one is set, else the solved count),
     * the rule and its word, and the set box ("Set" with none). {@code noTimes}: a crop's count has no times sign;
     * {@code unit}, {@code title}: a special card's own.
     */
    public static CountView count(final CardInput.Count c, @Nullable final CardInput.UpTo upTo, final boolean noTimes,
        @Nullable final String unit, @Nullable final String title) {
        final PinRule rule = c.rule();
        final boolean pinned = rule != PinRule.ANY;
        final double pin = c.pin() == null ? 0 : c.pin();
        final CardInput.UpTo readout = rule == PinRule.UP_TO ? upTo : null;
        final double value = readout != null ? readout.reached() : pinned ? pin : c.needed();
        final Double set = pinned ? pin : null;
        final String setText = set != null ? setText(set) : "";
        final boolean unset = !pinned && c.unset();
        return new CountView(
            (noTimes ? "" : "×") + Format.solvedMachines(value),
            unit,
            value,
            pinned,
            rule,
            ruleWord(rule),
            c.customRate(),
            set,
            setText,
            pinned ? setText : t("count.set"),
            unset,
            readout != null ? readout.reached() <= NOTHING : !pinned && c.needed() <= NOTHING,
            title != null ? title : c.customRate() ? t("count.title.dial") : switch (rule) {
                case UP_TO -> t("count.title.up_to");
                case EXACT -> t("count.title.exact");
                case ANY -> t("count.title.any");
            });
    }

    /**
     * The count after one wheel step: with none set, what runs rounded up (at least one); else the set count rounded,
     * plus or minus one; below one, none (null).
     */
    @Nullable
    public static Double wheelCount(final PinRule rule, @Nullable final Double set, final double value,
        final int direction) {
        if (rule == PinRule.ANY || set == null) return Math.max(1, Math.ceil(value - 0.000001));
        final double next = Math.floor(set + 0.5) + direction;
        return next >= 1 ? next : null;
    }

    /**
     * A count typed into the box: a number, a leading times sign allowed. Zero and an empty box clear it (0); text that
     * is not a count, or a negative one, is null (nothing is set). JavaScript's {@code parseFloat}: the longest number
     * at the start.
     */
    @Nullable
    public static Double parseCountText(final String text) {
        String trimmed = jsTrim(text);
        if (!trimmed.isEmpty() && (trimmed.charAt(0) == '×' || trimmed.charAt(0) == 'x'))
            trimmed = jsTrim(trimmed.substring(1));
        if (trimmed.isEmpty()) return 0.0;
        final double value = leadingNumber(trimmed);
        return !Double.isNaN(value) && !Double.isInfinite(value) && value >= 0 ? value : null;
    }

    private static boolean space(final char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\f' || c == '\u00A0'
            || c == '\uFEFF' || c == '\u1680' || (c >= '\u2000' && c <= '\u200A') || c == '\u2028' || c == '\u2029'
            || c == '\u202F' || c == '\u205F' || c == '\u3000';
    }

    /** JavaScript's {@code trim}: its whitespace and line ends off both ends. */
    static String jsTrim(final String s) {
        int a = 0, b = s.length();
        while (a < b && space(s.charAt(a))) a++;
        while (b > a && space(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean digit(final String s, final int i) {
        return i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9';
    }

    /** {@code Number.parseFloat}: the longest prefix that is a decimal number, NaN for none. */
    static double leadingNumber(final String s) {
        int i = 0;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
        final int from = i;
        while (digit(s, i)) i++;
        boolean any = i > from;
        if (i < s.length() && s.charAt(i) == '.' && digit(s, i + 1)) {
            i++;
            while (digit(s, i)) i++;
            any = true;
        }
        if (!any) {
            if (s.startsWith("Infinity", from)) return s.charAt(0) == '-' ? Double.NEGATIVE_INFINITY
                : Double.POSITIVE_INFINITY;
            return Double.NaN;
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < s.length() && (s.charAt(j) == '+' || s.charAt(j) == '-')) j++;
            if (digit(s, j)) {
                while (digit(s, j)) j++;
                i = j;
            }
        }
        return NumberText.parse(s.substring(0, i));
    }

    /**
     * The box's text after one wheel step while it is typed in: the number in it rounded, plus or minus one (below one,
     * empty); with nothing in it, what the wheel gives a card with no count.
     */
    public static String wheelDraft(final String draft, final double value, final int direction) {
        final Double typed = parseCountText(draft);
        final Double next = typed != null && typed > 0 ? wheelCount(PinRule.UP_TO, typed, value, direction)
            : wheelCount(PinRule.ANY, null, value, direction);
        return next == null ? "" : setText(next);
    }

    /** The rule a typed or stepped count keeps: its own, Up to from Any; a custom rate card's is always Exactly. */
    public static PinRule ruleForCount(final PinRule rule, final boolean ruleFixed) {
        if (ruleFixed) return PinRule.EXACT;
        return rule == PinRule.ANY ? PinRule.UP_TO : rule;
    }

    /**
     * What picking {@code next} from the rule menu writes: the rule and the count (the set count, else what runs
     * rounded up), or null when nothing changes (the rule it already has with no count, or a fixed card's).
     */
    @Nullable
    public static Double ruleMenuCount(final PinRule current, @Nullable final Double set, final double value,
        final boolean ruleFixed, final PinRule next) {
        if (ruleFixed && next != PinRule.ANY) return null;
        if (next == current && set == null) return null;
        return set != null ? set : Math.max(1, Math.ceil(value - 0.000001));
    }

    // endregion

    // region The foot bar

    /** An Up to reason's tone. */
    public static Tones.Role tone(@Nullable final UpToReason reason) {
        if (reason == null) return Tones.Role.PLAIN;
        return switch (reason) {
            case FULL -> Tones.Role.FULL;
            case BOTTLENECK -> Tones.Role.BOTTLENECK;
            case STARVED -> Tones.Role.STARVED;
            case CLOGGED -> Tones.Role.CLOGGED;
            case UNUSED -> Tones.Role.UNUSED;
            case LIMITED -> Tones.Role.LIMITED;
        };
    }

    /** A ring's tone: a dead loop STARVED's amber, a clog lock CLOGGED's purple (never UNUSED's grey). */
    public static Tones.Role loopTone(final UpToLoop.Kind kind) {
        return kind == UpToLoop.Kind.DEAD_LOOP ? Tones.Role.STARVED : Tones.Role.CLOGGED;
    }

    /** What a ring is called: its notice's chip, and its cards' bar word. */
    public static String loopWord(final UpToLoop.Kind kind) {
        return kind == UpToLoop.Kind.DEAD_LOOP ? t("loop.dead") : t("loop.clog");
    }

    /** Why a card on Any runs none: its output that running would send out, the input sent out instead, its maker. */
    public record Idle(String output, String input, @Nullable String makerId) {}

    private static String cardOf(@Nullable final String nodeId) {
        if (nodeId == null) return null;
        final int at = nodeId.indexOf("#r");
        return at < 0 ? nodeId : nodeId.substring(0, at);
    }

    /**
     * The trade that keeps a card on Any at zero (idle-trade.ts), or null: a leftover with an output place on this card
     * that carries none, and a place on another card that carries something this card takes in.
     */
    @Nullable
    public static Idle idleTrade(final List<CardInput.Leftover> leftovers, final String nodeId,
        final Set<String> inputKeys) {
        for (final CardInput.Leftover leftover : leftovers) {
            CardInput.Bearer here = null;
            for (final CardInput.Bearer b : leftover.bearers()) {
                if ("out".equals(b.side()) && b.share() <= 0
                    && b.resourceKey() != null
                    && nodeId.equals(cardOf(b.nodeId()))) {
                    here = b;
                    break;
                }
            }
            if (here == null) continue;
            for (final CardInput.Bearer b : leftover.bearers()) {
                if ("out".equals(b.side()) && b.share() > 0
                    && b.resourceKey() != null
                    && inputKeys.contains(b.resourceKey())
                    && !nodeId.equals(cardOf(b.nodeId()))) {
                    return new Idle(here.resourceKey(), b.resourceKey(), cardOf(b.nodeId()));
                }
            }
        }
        return null;
    }

    /** The first of {@code lines} that fits {@code room} at the bar's size; none fits, nothing. */
    static String fitLine(final List<String> lines, final double room, final TextMeasure measure) {
        for (final String line : lines) if (measure.width(TextStyle.LABEL, line) <= room) return line;
        return "";
    }

    private static double label(final TextMeasure measure, final String text) {
        return measure.width(TextStyle.LABEL, text);
    }

    /** The resource a row key names, by its row's name when the card has it. */
    @Nullable
    static String resourceName(@Nullable final String resourceKey, final List<PortView> ports) {
        if (resourceKey == null || resourceKey.isEmpty()) return null;
        for (final PortView p : ports) if (p.resourceKey()
            .equals(resourceKey)) return p.name();
        return resourceKey.substring(resourceKey.indexOf(':') + 1);
    }

    /** "342,636 EU/t", or amps of the card's tier under the Amps dial. */
    public static String turbineEuText(final double eu, @Nullable final String tier, final Dials dials) {
        return Format.powerValue(RailMath.dialValue(eu, dials.power(), tier)) + " "
            + RailMath.dialSuffix(dials.power(), tier);
    }

    /**
     * The bar along a card's foot, or nothing (the website's {@code buildFootView}): a turbine off its flow, an Up to
     * card with its read-out, an Exactly card with a forced input, or a card on Any that runs none because its input is
     * sent out instead. A card in a ring says DEAD LOOP or CLOG LOCK in the ring's tone in place of its reason.
     */
    @Nullable
    public static FootView foot(final PinRule rule, @Nullable final Double set, @Nullable final CardInput.UpTo upTo,
        final List<PortView> inputs, final List<PortView> outputs, final Map<String, String> cardNames,
        @Nullable final UpToLoop.Kind loop, @Nullable final Idle idle, @Nullable final CardInput.Turbine turbine,
        @Nullable final String turbineTier, final Dials dials, final TextMeasure measure) {
        if (turbine != null && loop == null) {
            final String pct = ExactMath.round(100 * turbine.flow() / turbine.optimal()) + "%";
            final String eu = turbineEuText(turbine.euMade(), turbineTier, dials);
            final String many = turbine.turbines() > 1 ? t("foot.flow.many", Integer.toString(turbine.turbines())) + " "
                : "";
            final String word = t("foot.flow");
            final double room = FOOT_TEXT - label(measure, word) - FOOT_GAP;
            return new FootView(
                Tones.Role.STARVED,
                word,
                fitLine(
                    List.of(
                        many + (turbine.turbines() > 1 ? t("foot.flow.make", pct, eu) : t("foot.flow.makes", pct, eu)),
                        many + t("foot.flow.eu", pct, eu),
                        many + t("foot.flow.of", pct),
                        t("foot.flow.of", pct)),
                    room,
                    measure),
                "",
                0,
                null,
                new FootFacts(
                    "FLOW",
                    null,
                    rule,
                    turbine.turbines(),
                    turbine.turbines(),
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    turbine,
                    turbineTier));
        }
        if (rule == PinRule.UP_TO && upTo != null) {
            final double capacity = upTo.capacity() > 0 ? upTo.capacity() : set != null ? set : 0;
            final double reached = Math.max(0, upTo.reached());
            final UpToReason reason = upTo.reason();
            final Double wants = reason == UpToReason.BOTTLENECK && upTo.wants() != null && upTo.wants() > capacity
                ? upTo.wants()
                : null;
            // The bar's end is the count, or what the line wants when that is more.
            final double end = wants != null ? wants : capacity;
            final int fill = (int) ExactMath.round(FOOT_TRACK * share(reached, end));
            final int ghostLeft = (int) ExactMath.round(FOOT_TRACK * share(capacity, end));
            final String frac = Format.solvedMachines(reached) + " / " + Format.solvedMachines(end);
            final String resource = reason == UpToReason.STARVED ? resourceName(upTo.resourceKey(), inputs)
                : reason == UpToReason.CLOGGED || reason == UpToReason.UNUSED ? resourceName(
                    upTo.resourceKey(),
                    outputs) : null;
            final List<String> tied = new ArrayList<>();
            for (final String key : upTo.tied()) {
                final String[] at = parsePortKey(key);
                if (at == null) continue;
                for (final PortView p : "i".equals(at[1]) ? inputs : outputs) {
                    if (bareHandle(p.handleId()).equals(at[2])) {
                        tied.add(p.name());
                        break;
                    }
                }
            }
            final List<String> upstream = new ArrayList<>();
            for (final String id : upTo.upstream()) {
                final String n = cardNames.get(id);
                upstream.add(n != null ? n : t("foot.a_card"));
            }
            final String word = loop != null ? loopWord(loop) : reason == null ? "" : reason.name();
            final double room = FOOT_TEXT - (word.isEmpty() ? 0 : label(measure, word) + FOOT_GAP)
                - label(measure, frac)
                - FOOT_GAP;
            final String m = wants == null ? "" : Format.solvedMachines(wants);
            final List<String> lines;
            if (reason == UpToReason.FULL) lines = List.of(t("foot.full"));
            else if (reason == UpToReason.BOTTLENECK) lines = wants != null
                ? List.of(t("foot.bottleneck.wants", m), t("foot.bottleneck.wants_short", m))
                : List.of(t("foot.bottleneck"));
            else if (reason == UpToReason.STARVED) lines = resource != null
                ? List.of(t("foot.starved.of", resource), t("foot.starved"))
                : List.of(t("foot.starved"));
            else if (reason == UpToReason.CLOGGED || loop == UpToLoop.Kind.CLOG_LOCK) lines = resource != null
                ? List.of(t("foot.clogged.of", resource), t("foot.clogged.for", resource), t("foot.clogged"))
                : List.of(t("foot.clogged"));
            else if (reason == UpToReason.UNUSED) lines = resource != null
                ? List.of(t("foot.unused.of", resource), t("foot.unused"))
                : List.of(t("foot.unused"));
            else if (reason == UpToReason.LIMITED) lines = List.of(t("foot.limited"), t("foot.limited_short"));
            else lines = List.of();
            return new FootView(
                loop != null ? loopTone(loop) : tone(reason),
                word,
                fitLine(lines, room, measure),
                frac,
                fill,
                wants != null ? new CardView.Ghost(ghostLeft, FOOT_TRACK - ghostLeft) : null,
                new FootFacts(
                    reason == null ? null : reason.name(),
                    loop,
                    PinRule.UP_TO,
                    reached,
                    capacity,
                    wants,
                    resource,
                    tied,
                    upstream,
                    List.of(),
                    null,
                    null,
                    null));
        }
        if (rule == PinRule.EXACT) {
            final List<PortView> forced = new ArrayList<>();
            for (final PortView p : inputs) if (p.forced() != null) forced.add(p);
            if (forced.isEmpty()) return null;
            final PortView first = forced.get(0);
            final String rate = figureText(first.forced());
            final double s = set != null ? set : 0;
            final String keep = s == 1 ? t("foot.forced.keep_one") : t("foot.forced.keep_all", Format.solvedMachines(s));
            final String word = t("foot.forced");
            final double room = FOOT_TEXT - label(measure, word) - FOOT_GAP;
            final String more = Integer.toString(forced.size() - 1);
            final List<String> lines = forced.size() == 1
                ? List.of(t("foot.forced.of_keep", rate, first.name(), keep), t("foot.forced.of", rate, first.name()),
                    rate)
                : List.of(t("foot.forced.of_more", rate, first.name(), more), t("foot.forced.more", first.name(), more),
                    t("foot.forced.inputs", Integer.toString(forced.size())));
            final List<String> each = new ArrayList<>();
            for (final PortView p : forced) each.add(figureText(p.forced()) + " " + p.name());
            return new FootView(
                Tones.Role.FORCED,
                word,
                fitLine(lines, room, measure),
                "",
                0,
                null,
                new FootFacts("FORCED", null, PinRule.EXACT, s, s, null, null, List.of(), List.of(), each, null, null,
                    null));
        }
        if (rule == PinRule.ANY && idle != null) {
            final String input = resourceName(idle.input(), inputs);
            final String output = resourceName(idle.output(), outputs);
            final String word = t("foot.idle");
            final double room = FOOT_TEXT - label(measure, word) - FOOT_GAP;
            final List<String> upstream = new ArrayList<>();
            if (idle.makerId() != null && !idle.makerId()
                .isEmpty()) {
                final String n = cardNames.get(idle.makerId());
                upstream.add(n != null ? n : t("foot.a_card"));
            }
            return new FootView(
                Tones.Role.PLAIN,
                word,
                fitLine(
                    List.of(t("foot.idle.instead", input), t("foot.idle.sent", input), t("foot.idle.its")),
                    room,
                    measure),
                "",
                0,
                null,
                new FootFacts("IDLE", null, PinRule.ANY, 0, 0, null, output, List.of(), upstream, List.of(), input,
                    null, null));
        }
        return null;
    }

    private static String figureText(final Figure f) {
        return f.number() + (f.unit()
            .isEmpty() ? "" : " " + f.unit());
    }

    private static double share(final double value, final double end) {
        return end > 0 ? Math.min(1, Math.max(0, value / end)) : 0;
    }

    /** The frame's tone: a problem's, the bar's own; FULL, plain, Any and no bar keep the normal edge. */
    @Nullable
    public static Tones.Role frameTone(@Nullable final FootView foot) {
        return foot != null && foot.tone() != Tones.Role.FULL && foot.tone() != Tones.Role.PLAIN ? foot.tone() : null;
    }

    /** An Up to card's bar at a glance: filled to what runs over the count (a bottleneck's over what it wants). */
    @Nullable
    public static CardView.GlanceBar glanceBar(@Nullable final CardInput.UpTo upTo,
        @Nullable final UpToLoop.Kind loop) {
        if (upTo == null) return null;
        final double capacity = upTo.capacity();
        final Double wants = upTo.reason() == UpToReason.BOTTLENECK && upTo.wants() != null
            && upTo.wants() > capacity ? upTo.wants() : null;
        final double end = wants != null ? wants : capacity;
        final double fill = end > 0 ? Math.min(1, Math.max(0, upTo.reached() / end)) : 0;
        if (loop != null) return new CardView.GlanceBar(loopTone(loop), fill, loopWord(loop));
        return new CardView.GlanceBar(
            tone(upTo.reason()),
            fill,
            upTo.reason() == null ? ""
                : upTo.reason()
                    .name());
    }

    // endregion

    // region The menu

    /**
     * The card menu's rows, in the mod's order: Clone node, Add another recipe (not while calm or locked, and not on a
     * machine that owns its recipe), Replace the recipe (not while calm or locked, nor on a shared machine, a holder or
     * an empty crop farm), Add to a task (with task boards), and Delete node in red. A host adds its own rows.
     */
    public static List<CardView.MenuRow> menu(final CardInput.Menu m) {
        final List<CardView.MenuRow> rows = new ArrayList<>();
        rows.add(new CardView.MenuRow("clone", t("menu.clone"), false));
        final boolean open = !m.calm() && !m.locked();
        if (open && !m.ownsRecipe()) rows.add(new CardView.MenuRow("addRecipe", t("menu.add_recipe"), false));
        if (open && !m.shared() && !m.holder() && !m.emptyCropFarm())
            rows.add(new CardView.MenuRow("replace", t("menu.replace"), false));
        if (m.tasks()) rows.add(new CardView.MenuRow("addToTask", t("menu.add_to_task"), false));
        rows.add(new CardView.MenuRow("delete", t("menu.delete"), true));
        return rows;
    }

    // endregion
}
