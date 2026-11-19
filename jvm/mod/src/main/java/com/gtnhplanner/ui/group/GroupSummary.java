package com.gtnhplanner.ui.group;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.core.view.group.GroupBuilder;
import com.gtnhplanner.core.view.group.GroupInput;
import com.gtnhplanner.core.view.group.GroupView;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Groups;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.card.CardModel;

/**
 * What a collapsed group's summary card says ({@code docs/design/groups.md}, "What the summary says"), from the
 * plan-wide solve over its members and the groups inside it: the machines to build (as the overview counts them), their
 * EU/t, and per resource what goes in (more used than made) and comes out (more made than used), busiest first.
 */
public record GroupSummary(int machines, double euPerTick, List<Flow> inputs, List<Flow> outputs) {

    /** One resource crossing the group's edge, a second. */
    public record Flow(String key, String name, ItemStack item, FluidStack fluid, double perSecond) {}

    public static final GroupSummary EMPTY = new GroupSummary(0, 0, List.of(), List.of());

    /** Rows the summary card draws: the longer list, one at least. */
    public int rows() {
        return Math.max(1, Math.max(inputs.size(), outputs.size()));
    }

    public static GroupSummary of(final BoardSession session, final Uuid group) {
        final Graph g = session.graph();
        final Set<Uuid> inside = new LinkedHashSet<>();
        for (final Uuid id : Groups.descendants(g, group)) if (g.nodes.containsKey(id)) inside.add(id);
        final Map<String, Flow> looks = new LinkedHashMap<>();
        final List<GroupInput.Member> members = new ArrayList<>();
        for (final Uuid id : inside) {
            final CardModel card = session.models()
                .get(id);
            if (card == null || com.gtnhplanner.power.CustomRate.is(card.node)) continue;
            // A shared machine is one machine, however many recipes are on it (the core counts a host once).
            final boolean machine = (card.machines > 0 || card.pinned)
                && !com.gtnhplanner.power.FluidTank.is(card.node);
            final List<GroupInput.Flow> flows = new ArrayList<>();
            for (final CardModel.PortView p : card.inputs) add(session, flows, looks, p, p.perSecond());
            for (final CardModel.PortView p : card.outputs) add(session, flows, looks, p, -p.perSecond());
            members.add(
                new GroupInput.Member(
                    session.hostOf(id)
                        .toString(),
                    machine ? com.gtnhplanner.machines.game.CropCards.builds(card.node, card.machines) : null,
                    session.power(card),
                    flows));
        }
        // The sum, the rounding up and the order are the core's (shared with the website, conformance/view/group).
        final GroupView view = GroupBuilder.build(new GroupInput(members));
        return new GroupSummary(
            view.machines(),
            view.euT(),
            flowsOf(view.inputs(), looks),
            flowsOf(view.outputs(), looks));
    }

    private static List<Flow> flowsOf(final List<GroupView.Flow> flows, final Map<String, Flow> looks) {
        final List<Flow> out = new ArrayList<>();
        for (final GroupView.Flow f : flows) {
            final Flow look = looks.get(f.key());
            out.add(new Flow(f.key(), f.name(), look.item(), look.fluid(), f.perSecond()));
        }
        return List.copyOf(out);
    }

    private static void add(final BoardSession session, final List<GroupInput.Flow> flows,
        final Map<String, Flow> looks, final CardModel.PortView p, final double amount) {
        if (p.key() == null) return;
        final String key = session.canonicalKey(p.key());
        flows.add(new GroupInput.Flow(key, p.name(), amount));
        looks.putIfAbsent(key, new Flow(key, p.name(), p.item(), p.fluid(), 0));
    }
}
