package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;

/**
 * The mod's adapter to core-view's game-free card model: the facts the mod's {@link CardModel} read from its node,
 * NEI and the solve, as names, icon keys ({@link IconKeys}) and amounts. Built once with each {@link CardModel}; the
 * card's view rules in core-view ({@code CardLayout}, {@code SettingControls}, {@code PowerTiles}) read only this.
 */
public final class CardModels {

    private CardModels() {}

    /** The core's card model of a mod card model. */
    public static com.gtnhplanner.core.view.card.CardModel core(final CardModel m) {
        final Node node = m.node;
        final List<String> choices = new ArrayList<>();
        for (final ItemStack s : m.catalysts) {
            final String key = IconKeys.of(s);
            if (key != null) choices.add(key);
        }
        return new com.gtnhplanner.core.view.card.CardModel(
            uuid(node.id),
            m.machineName == null ? "" : m.machineName,
            IconKeys.of(m.machineStack),
            choices,
            m.gregtech,
            m.steam(),
            com.gtnhplanner.machines.game.CropCards.isCrop(node),
            com.gtnhplanner.power.CustomRate.is(node),
            m.tier == null ? "" : m.tier,
            m.amps,
            m.multiblock,
            m.coilHeat,
            m.usesHeat,
            m.recipeHeat,
            m.parallels,
            ports(m.inputs, node.inputs, node.targetInputRates),
            ports(m.outputs, node.outputs, node.targetOutputRates),
            m.durationTicks,
            m.euPerTick,
            m.machines,
            m.pinned,
            IconKeys.of(m.circuit),
            m.circuit == null ? null : Integer.toString(m.circuit.getItemDamage()),
            m.steamPerTick,
            m.web,
            m.power == null ? null
                : new com.gtnhplanner.core.view.card.CardModel.Power(
                    node.powerSource == null ? "" : node.powerSource,
                    m.power.source(),
                    m.power.model(),
                    m.power.stats(),
                    node.powerSettings));
    }

    /** The core's id of a node's (the nil id for a node that has none yet). */
    public static Uuid uuid(@Nullable final Uuid id) {
        return id == null ? new Uuid(0, 0) : new Uuid(id.getMostSignificantBits(), id.getLeastSignificantBits());
    }

    private static List<com.gtnhplanner.core.view.card.CardModel.Port> ports(final List<CardModel.PortView> views,
        final List<Port<?>> ports, final Map<Integer, Double> rates) {
        final List<com.gtnhplanner.core.view.card.CardModel.Port> out = new ArrayList<>(views.size());
        for (final CardModel.PortView p : views) {
            final Port<?> port = p.index() < ports.size() ? ports.get(p.index()) : null;
            out.add(
                new com.gtnhplanner.core.view.card.CardModel.Port(
                    p.index(),
                    p.output(),
                    p.key() == null ? "" : p.key(),
                    p.name() == null ? "" : p.name(),
                    p.item() != null ? IconKeys.of(p.item()) : IconKeys.of(p.fluid()),
                    p.isFluid(),
                    p.chance(),
                    // GregTech's non-consumed inputs (amount 0) are never used up; every other input is, every run.
                    port != null && port.getAmount() == 0 ? 0 : 1,
                    p.perSecond(),
                    p.wired(),
                    p.extra(),
                    // What the game gives for nothing (the Rock Breaker's lava): the shared rule.
                    !p.output() && com.gtnhplanner.data.provider.FreeInputs.isFree(port),
                    false,
                    rate(rates.get(p.index()))));
        }
        return out;
    }

    @Nullable
    private static Double rate(@Nullable final Double rate) {
        return rate != null && rate > 0 && Double.isFinite(rate) ? rate : null;
    }
}
