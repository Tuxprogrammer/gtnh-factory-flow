package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Groups;

/**
 * What deleting a selection takes, and whether the board asks first: a delete that takes more than one card waits
 * for the player to say so, since a stray Ctrl+A and Delete would otherwise empty the plan in one key. Notes, drawers
 * and empty groups never ask.
 */
public final class DeleteAsk {

    private DeleteAsk() {}

    /**
     * Every id a delete of {@code selection} removes: a shared machine goes whole (every recipe on its card, as
     * {@code sectionsOf} lists them), and a group goes with everything in it.
     */
    public static List<Uuid> takes(final Graph graph, final Collection<Uuid> selection,
        final Function<Uuid, List<Uuid>> sectionsOf) {
        final List<Uuid> ids = new ArrayList<>();
        for (final Uuid id : selection) for (final Uuid s : sectionsOf.apply(id)) if (!ids.contains(s)) ids.add(s);
        for (final Uuid id : new ArrayList<>(ids)) if (graph.boardGroups.containsKey(id))
            for (final Uuid inside : Groups.descendants(graph, id)) if (!ids.contains(inside)) ids.add(inside);
        return ids;
    }

    /** The cards among {@code ids}: each recipe node counted once by the card it is drawn on ({@code hostOf}). */
    public static int cards(final Graph graph, final Collection<Uuid> ids, final Function<Uuid, Uuid> hostOf) {
        final List<Uuid> hosts = new ArrayList<>();
        for (final Uuid id : ids) {
            if (!graph.nodes.containsKey(id)) continue;
            final Uuid host = hostOf.apply(id);
            if (!hosts.contains(host)) hosts.add(host);
        }
        return hosts.size();
    }

    /** Whether a delete of this many cards asks first. */
    public static boolean asks(final int cards) {
        return cards > 1;
    }

    /** The question it asks. */
    public static String question(final int cards) {
        return "Delete " + cards + " cards?";
    }
}
