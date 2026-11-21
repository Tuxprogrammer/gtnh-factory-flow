package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.gtnhplanner.core.base.RateUnit;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.core.view.card.CardInput;
import com.gtnhplanner.core.view.card.CardViews;
import com.gtnhplanner.data.flowchart.PinRule;
import com.gtnhplanner.data.flowchart.balancer.PortRef;
import com.gtnhplanner.data.flowchart.balancer.UpToReadout;
import com.gtnhplanner.machines.web.Tiers;
import com.gtnhplanner.ui.BoardSession;

/**
 * The mod's side of the shared card view: a card's facts as core-view's {@link CardInput}, from its game-free
 * {@link com.gtnhplanner.core.view.card.CardModel} and the board session (its rule, count, Up to read-out, ring,
 * dials).
 * A port's handle is {@code i<n>} / {@code o<n>} (its place on its side), so port keys are
 * {@code <node>|i|i<n>}; the solver's {@link PortRef} becomes that key.
 */
public final class CardInputs {

    private CardInputs() {}

    /** The board's dials: the rate key (EU reads energy per unit), the power key, Peak or Avg. */
    public static CardInput.Dials dials(final BoardSession session) {
        return new CardInput.Dials(
            session.energyView() ? RateUnit.EU
                : session.rateUnit()
                    .core(),
            session.powerKey() == BoardSession.PowerKey.AMPS ? "amps" : "eu",
            !session.peakPower());
    }

    /** A port's handle on the card: its side and its place. */
    public static String handle(final boolean output, final int index) {
        return (output ? "o" : "i") + index;
    }

    /** The solver's port as the view's port key. */
    public static String key(final PortRef ref) {
        return CardViews.portKey(
            ref.nodeId()
                .toString(),
            !ref.input(),
            handle(!ref.input(), ref.portIndex()));
    }

    /**
     * The tier a generator's EU counts in under the Amps dial: the lowest whose one amp carries one machine's output
     * (the game's GTUtility.getTier); null for a card that makes no EU.
     */
    @Nullable
    public static String powerTier(final CardModel m) {
        if (!m.isPower() || m.power == null || m.power.model() == null) return null;
        final double made = m.power.model()
            .euPerTick();
        return made > 0 ? Tiers.forEuT(made) : null;
    }

    /** An Up to read-out as the view reads it (its rows by port key, its upstream by id). */
    @Nullable
    public static CardInput.UpTo upTo(@Nullable final UpToReadout r) {
        if (r == null) return null;
        final List<String> tied = new ArrayList<>();
        for (final PortRef p : r.tied()) tied.add(key(p));
        final List<String> upstream = new ArrayList<>();
        for (final Uuid id : r.upstream()) upstream.add(id.toString());
        return new CardInput.UpTo(
            r.capacity(),
            r.reached(),
            r.reason(),
            r.port() == null ? null : key(r.port()),
            null,
            tied,
            r.wants(),
            upstream);
    }

    /** One of the card's rows. */
    static CardInput.Port port(final com.gtnhplanner.core.view.card.CardModel.Port p) {
        final String kind = p.isPower() ? "power" : p.fluid() ? "fluid" : "item";
        final int colon = p.key()
            .indexOf(':');
        return new CardInput.Port(
            (p.output() ? "out:" : "in:") + p.index(),
            handle(p.output(), p.index()),
            p.output(),
            p.name(),
            kind,
            colon < 0 ? p.key()
                : p.key()
                    .substring(colon + 1),
            p.perSecond(),
            null,
            p.wired(),
            p.chance() < 1 ? p.chance() : null,
            p.consumeChance() < 1 ? p.consumeChance() : null,
            p.choice() ? 2 : 1,
            p.free(),
            p.setRate());
    }

    /**
     * A one-recipe card's facts. {@code rule} and {@code set} are what its count box shows now (a count being typed or
     * wheeled shows at once), {@code machines} what runs, {@code tooLow} whether its tier cannot run the recipe,
     * {@code picker} whether more than one machine runs it, {@code tierFixed} whether its tier chip is fixed.
     */
    public static CardInput of(final BoardSession session, final CardModel m, final PinRule rule, final double set,
        final double machines, final boolean tooLow, final boolean picker, final boolean tierFixed,
        final boolean customRate, final boolean holder) {
        final com.gtnhplanner.core.view.card.CardModel core = m.core;
        final String id = m.node.id.toString();
        final boolean electric = m.gregtech && !m.steam() && m.euPerTick > 0;
        final List<CardInput.Port> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        final List<CardInput.Forced> forced = new ArrayList<>();
        for (final com.gtnhplanner.core.view.card.CardModel.Port p : core.inputs()) inputs.add(port(p));
        for (final com.gtnhplanner.core.view.card.CardModel.Port p : core.outputs()) outputs.add(port(p));
        for (final com.gtnhplanner.core.view.card.CardModel.Port p : core.inputs()) if (p.hasExtra())
            forced.add(new CardInput.Forced(CardViews.portKey(id, false, handle(false, p.index())), p.extra(), null));
        for (final com.gtnhplanner.core.view.card.CardModel.Port p : core.outputs()) if (p.hasExtra())
            forced.add(new CardInput.Forced(CardViews.portKey(id, true, handle(true, p.index())), p.extra(), null));
        final UpToReadout readout = rule == PinRule.UP_TO ? session.upTo(m.node.id) : null;
        final Map<String, String> names = new TreeMap<>();
        if (readout != null) for (final Uuid up : readout.upstream()) {
            final CardModel other = session.model(up);
            if (other != null) names.put(up.toString(), other.machineName);
        }
        final com.gtnhplanner.core.view.card.CardModel.Port product = core.outputs()
            .isEmpty() ? null
                : core.outputs()
                    .get(0);
        return new CardInput(
            id,
            m.machineName,
            picker,
            m.gregtech && !m.steam() ? m.tier : null,
            tierFixed,
            m.multiblock && electric,
            electric
                ? new CardInput.PowerReport(
                    !tooLow,
                    m.tier,
                    m.amps,
                    m.euPerTick,
                    Math.max(1, m.parallels),
                    null,
                    m.multiblock,
                    null)
                : null,
            m.steam() ? new CardInput.SteamReport(m.steamPerTick, highPressure(m), Math.max(1, m.parallels)) : null,
            Math.max(1, m.parallels),
            Math.max(1, m.parallels),
            List.of(),
            null,
            core.machineIcon(),
            product != null && product.icon() != null && !product.isPower()
                ? new CardInput.Product(product.icon(), product.fluid())
                : null,
            core.circuitIcon() == null && core.circuitNumber() == null ? null
                : new CardInput.Circuit(core.circuitIcon(), core.circuitNumber()),
            inputs,
            outputs,
            new CardInput.Count(
                rule,
                rule == PinRule.ANY ? null : set,
                machines,
                null,
                1,
                customRate,
                session.unset(m.node.id)),
            powerTier(m),
            upTo(readout),
            forced,
            session.loopOf(m.node.id),
            List.of(),
            null,
            names,
            session.isSelected(m.node.id),
            new CardInput.Menu(
                false,
                false,
                false,
                false,
                holder,
                com.gtnhplanner.power.CropFarm.is(m.node),
                true,
                m.node.shown != null),
            readOnly(m.node));
    }

    /** A card the board only shows (a website card it cannot run): why, and whether the solve counts it. */
    @Nullable
    static CardInput.ReadOnly readOnly(final com.gtnhplanner.data.flowchart.Node node) {
        return node.shown == null ? null
            : new CardInput.ReadOnly(
                node.shown.reason()
                    .word(),
                node.shown.solved());
    }

    /** A high-pressure steam machine: GregTech names its steam machines' second tier so. */
    private static boolean highPressure(final CardModel m) {
        return m.machineName != null && m.machineName.contains("High Pressure");
    }
}
