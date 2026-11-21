package com.gtnhplanner.core.view.special;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.core.base.Format;
import com.gtnhplanner.core.base.JsMath;
import com.gtnhplanner.core.base.NumberText;
import com.gtnhplanner.core.base.Text;
import com.gtnhplanner.core.view.TextMeasure;
import com.gtnhplanner.core.view.Tokens;
import com.gtnhplanner.core.view.card.CardGeometry;
import com.gtnhplanner.core.view.card.CardInput;
import com.gtnhplanner.core.view.card.CardView;
import com.gtnhplanner.core.view.card.CardViews;
import com.gtnhplanner.machines.web.Tiers;

/**
 * The special cards' own rules: the website's special/use-special.ts, translated kind by kind, over the card's own
 * view ({@link CardViews#card}) and its kind's facts ({@link SpecialInput}). Words through core-base {@link Text} (keys
 * {@code gtnhplanner.card.*} in the mod's language file), numbers through {@link Format}, widths through the host's
 * {@link TextMeasure}. Both hosts' special cards are bound to the same cases ({@code conformance/view/special/}).
 *
 * <p>
 * Kinds translated so far: the custom rate card and the fluid tank. A shared machine, a generator and a crop card keep
 * each host's own rules ({@link #covers}).
 */
public final class SpecialViews {

    private SpecialViews() {}

    private static String t(final String key, final Object... args) {
        return args.length == 0 ? Text.get()
            .translate("gtnhplanner.card." + key)
            : Text.get()
                .format("gtnhplanner.card." + key, args);
    }

    /** Whether the shared rules draw this kind's own parts (else the host's own rules do, for now). */
    public static boolean covers(final SpecialInput.Kind kind) {
        return kind == SpecialInput.Kind.CUSTOM_RATE || kind == SpecialInput.Kind.TANK;
    }

    /**
     * A special card's own parts, from its kind's facts, its card's facts and the card's own view (what the host draws
     * the rest of the card from). Throws for a kind {@link #covers} says the rules do not draw yet.
     */
    public static SpecialView special(final SpecialInput in, final CardInput card, final CardView base,
        final CardInput.Dials dials, final TextMeasure measure, final CardViews.Host host) {
        return switch (in.kind()) {
            case CUSTOM_RATE, TANK -> holder(in, base, dials, measure, host);
            default -> throw new IllegalArgumentException("no shared rules yet for a " + in.kind() + " card");
        };
    }

    // region Holders

    /**
     * A custom rate card or a fluid tank: a blue name, two sockets until it holds something, the custom rate's gauge
     * and its dial on chips (mode and rate, the rate in the board's unit). A fluid tank has no count; a custom rate
     * card's count is always Exactly (its dial).
     */
    static SpecialView holder(final SpecialInput in, final CardView base, final CardInput.Dials dials,
        final TextMeasure measure, final CardViews.Host host) {
        final boolean tank = in.kind() == SpecialInput.Kind.TANK;
        final SpecialInput.Holder h = in.holder();
        final boolean empty = h == null || h.empty();
        final String name = tank ? t("holder.tank") : t("holder.custom_rate");
        final CardView.CountView count = tank ? null : base.count();
        final SpecialView.Sockets sockets = empty
            ? new SpecialView.Sockets(
                tank ? t("socket.take_cell") : t("socket.drain"),
                tank ? t("socket.give_cell") : t("socket.supply"),
                t("socket.takes"),
                t("socket.feeds"))
            : null;

        // The custom rate's dial: mode and rate, on chips like every other setting; the rate in the board's unit.
        final List<SpecialView.Chip> chips = new ArrayList<>();
        int stripHeight = 0;
        final SpecialInput.Dial dial = tank || h == null ? null : h.dial();
        if (dial != null) {
            final double multiplier = rateMultiplier(dial.kind(), dials);
            final double step = 1000 / Math.min(multiplier, 1);
            final String shown = NumberText.of(JsMath.round(dial.perSecond() * multiplier * step) / step);
            final String suffix = rateSuffix(dial.kind(), dials).trim();
            final List<CardViews.ChipSpec> specs = new ArrayList<>();
            if (dial.showMode()) specs.add(
                new CardViews.ChipSpec(
                    "mode",
                    t("dial.mode"),
                    dial.supply() ? t("dial.supply") : t("dial.request"),
                    null,
                    false,
                    null,
                    false,
                    t("dial.mode.title")));
            if (dial.showRate()) specs.add(
                new CardViews.ChipSpec(
                    "rate",
                    t("dial.rate", suffix.isEmpty() ? "/s" : suffix),
                    shown,
                    null,
                    false,
                    null,
                    false,
                    t("dial.rate.title")));
            final CardViews.Strip strip = CardViews.layoutStrip(specs, measure, host);
            stripHeight = strip.height();
            for (final CardView.ChipView c : strip.chips()) chips.add(
                new SpecialView.Chip(
                    c,
                    "rate".equals(c.id()) ? new SpecialView.Edit(shown, multiplier, List.of()) : null));
        }

        return new SpecialView(
            in.kind(),
            name,
            CardViews.nameStep(name, false, false, false, measure, host),
            SpecialView.NameTone.HOLDER,
            false,
            null,
            null,
            empty,
            List.of(),
            null,
            sockets,
            !tank,
            count,
            // An Exactly custom rate card fed by a forced input says so, as any Exactly card does.
            count != null && !empty ? base.foot() : null,
            chips,
            stripHeight,
            List.of(),
            false,
            !tank && base.unset(),
            false,
            false);
    }

    /** The website's holder well: special.css's 78 px picture and its 1 px margins (the plain card's is 120). */
    public static final int WEB_HOLDER_PICTURE = 80;
    /** An empty holder's socket on the website: 4 px down, a 36 px dashed box (its hint hangs below, in no row). */
    public static final int WEB_SOCKET_TOP = 4, WEB_SOCKET = 36;
    /** The anchors of an empty holder's two sockets: a host names its own socket handles by these. */
    public static final String TAKE = "take", GIVE = "give";

    /**
     * A holder card on the website, from its parts and its card's view: the head, the rails (the card's rows, or the
     * two sockets of an empty one) beside a well 80 px tall, the strip, the bar along the foot. Each row's anchor as the
     * plain card's ({@link CardGeometry}); a socket's in its middle.
     */
    public static CardGeometry webHolder(final SpecialView v, final CardView base) {
        if (!covers(v.kind())) throw new IllegalArgumentException("not a holder: " + v.kind());
        final int head = CardGeometry.WEB_HEAD, row = CardGeometry.WEB_ROW;
        final List<CardGeometry.Anchor> anchors = new ArrayList<>();
        int in = 0, out = 0;
        if (v.empty()) {
            if (v.sockets() != null) {
                final int y = head + WEB_SOCKET_TOP + WEB_SOCKET / 2;
                anchors.add(new CardGeometry.Anchor(TAKE, false, y));
                anchors.add(new CardGeometry.Anchor(GIVE, true, y));
                in = out = WEB_SOCKET_TOP + WEB_SOCKET;
            }
        } else {
            in = rail(base.inputs(), false, anchors, head, row) + (v.power() != null ? row : 0);
            out = rail(base.outputs(), true, anchors, head, row);
        }
        final int rails = Math.max(WEB_HOLDER_PICTURE, Math.max(in, out));
        final int stripTop = head + rails;
        final boolean foot = v.foot() != null;
        return new CardGeometry(Tokens.Card.CARD_WIDTH, stripTop + v.stripHeight() + (foot ? CardGeometry.WEB_FOOT : 0),
            head, rails, anchors, stripTop, v.stripHeight(), foot ? stripTop + v.stripHeight() + 1 : -1);
    }

    private static int rail(final List<CardView.PortView> ports, final boolean output,
        final List<CardGeometry.Anchor> anchors, final int head, final int row) {
        int y = 0;
        for (final CardView.PortView p : ports) {
            final int slot = p.forced() != null ? 2 * row : row;
            anchors.add(new CardGeometry.Anchor(p.handleId(), output, head + y + slot / 2));
            y += slot;
        }
        return y;
    }

    /**
     * What a per-second figure of a resource of {@code kind} is multiplied by on the board: the rate unit's, or for EU
     * a tick's worth, in amps of the power dial's tier when it names one (the website's {@code rateMultiplierForKind}).
     */
    static double rateMultiplier(final String kind, final CardInput.Dials dials) {
        if (!"power".equals(kind)) return Format.rateMultiplier(dials.rate(), kind);
        final double perTick = 1.0 / 20;
        return eu(dials.power()) ? perTick : perTick / Tiers.maxEuT(dials.power());
    }

    /** The unit such a figure wears ({@code rateSuffixForKind}): " EU/t", " A HV", " L/s", "/min". */
    static String rateSuffix(final String kind, final CardInput.Dials dials) {
        if (!"power".equals(kind)) return Format.rateSuffix(dials.rate(), kind);
        return eu(dials.power()) ? " EU/t" : " A " + dials.power();
    }

    private static boolean eu(final String power) {
        return "eu".equals(power) || "amps".equals(power);
    }

    // endregion
}
