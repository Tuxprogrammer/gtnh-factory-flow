package com.gtnhplanner.core.layout.arrange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

import javax.annotation.Nullable;

import com.gtnhplanner.core.layout.JsSort;
import com.gtnhplanner.core.layout.GridEdgeRouter;
import com.gtnhplanner.core.layout.GridPoint;
import com.gtnhplanner.core.layout.JsMath;
import com.gtnhplanner.core.layout.RouterTuning;

/**
 * The column pass of the website's board-arrange.ts ({@code arrangeBoardOnce} and everything below it), line for line.
 *
 * <p>
 * A plan is treated as almost a tree: flow runs left to right, the TRUNK (the chain behind the biggest final product)
 * runs through the middle, and every feeder chain joining it is a SECTION kept as one contiguous band, so a wire's two
 * ends usually share a band. Recycles stay beside their own section, and a vertical pass lines ports up with the ports
 * they feed, weighted by flow. Disconnected webs become separate islands; unwired cards go on a shelf at the bottom.
 *
 * <p>
 * The website keeps the taste dials, the arrange's prices, its air term and the search's progress at module level; here
 * they are the fields of one object per arrange call, so two arranges may run at once on two threads.
 */
final class ColumnArrange {

    static final double BOARD_GRID = GridEdgeRouter.BOARD_GRID;

    /** {@code n} cells, in pixels. */
    static double cells(final double n) {
        return n * BOARD_GRID;
    }

    /** Snap a scalar to the nearest grid line. */
    static double snapToGrid(final double value) {
        return JsMath.round(value / BOARD_GRID) * BOARD_GRID;
    }

    // region Spacing taste, all in whole cells

    /** A column corridor grows one cell per this many wires crossing it... */
    static final double COLUMN_GAP_WIRES_PER_CELL = 3;
    /** ...up to this much. */
    static final double COLUMN_GAP_MAX = cells(10);
    /** Island columns wrap to the next line past this width: plans read like text, not like one endless ribbon. */
    static final double ISLAND_ROW_MAX_WIDTH = cells(240);
    /** Air between parked, unwired cards on the shelf. */
    static final double SHELF_GAP = cells(2);
    /** Ink counts as "written over" a card up to this far away from it. */
    static final double INK_REACH = cells(2);

    /*
     * The taste dials, set once per arrange from the taste. applyTaste always assigns EVERY one, so a pass can never
     * inherit another's mood.
     */
    /** The narrowest corridor between two columns. */
    double columnGapMin = cells(3);
    /** Vertical air between stacked cards of one section. */
    double rowGap = cells(2);
    /** Vertical air between two different sections sharing a column. */
    double sectionGap = cells(4);
    /** Air around a whole island. Generous: separateness is the point. */
    double islandGap = cells(12);
    /** The router's prices for this arrange, a crossing at the arrange's price (set by the entry points). */
    @Nullable
    RouterTuning arrangePrices;
    /** The air owed between strangers at a set of positions (set by the entry points). */
    ToDoubleFunction<Map<String, GridPoint>> arrangeAir = positions -> 0;
    /** Where the optimiser's search is, for the loader (set by the entry points). */
    @Nullable
    Steps arrangeSearchProgress;
    /** Air between a satellite and the card it rides (horizontal). */
    double satellitePad = cells(2);
    /** Air between satellites stacked on one side. */
    double satelliteStackGap = cells(1);
    /** Island scale only: fold a too-wide run of columns back like text. */
    boolean pageFold = false;
    /** Whether layoutIsland runs the optimiser; set per arrangeBoardOnce call. */
    boolean optimise = false;

    /** The dials as the arrange prices layouts: a crossing costs {@code arrangeCrossing} (the judge scores the same). */
    static RouterTuning arrangePrices(final RouterTuning tuning) {
        return tuning.withCrossing(tuning.arrangeCrossing());
    }

    /**
     * The dev menu's Arrange dials override the taste's spacing only when moved off their defaults, so callers that ask
     * for a taste still get it.
     */
    void applyArrangeDials(@Nullable final RouterTuning dials) {
        if (dials == null) return;
        if (dials.arrangeRowGap() != RouterTuning.DEFAULT.arrangeRowGap()) rowGap = cells(dials.arrangeRowGap());
        if (dials.arrangeColumnGap() != RouterTuning.DEFAULT.arrangeColumnGap()) {
            columnGapMin = cells(dials.arrangeColumnGap());
        }
        if (dials.arrangeDrawerGap() != RouterTuning.DEFAULT.arrangeDrawerGap()) {
            satellitePad = cells(dials.arrangeDrawerGap());
        }
    }

    void applyTaste(@Nullable final String taste) {
        final String spacing = taste != null ? taste : "normal";
        final boolean compact = spacing.equals("compact");
        final boolean roomy = spacing.equals("roomy");
        // Compact matches hand-drawn boards: rows a cell apart, columns two. Drawers keep two cells from their machine
        // and a cell from each other in every spacing: closer and the wire between them, its arrowhead and the
        // drawer's own keys are hidden.
        rowGap = cells(compact ? 1 : roomy ? 3 : 2);
        sectionGap = cells(compact ? 2 : roomy ? 6 : 4);
        columnGapMin = cells(compact ? 2 : roomy ? 5 : 3);
        // Wide enough that two islands' grounds plus both their two-cell no-go collars fit between any pair with
        // room left over.
        islandGap = cells(compact ? 10 : roomy ? 16 : 12);
        satellitePad = cells(2);
        satelliteStackGap = cells(1);
    }

    /** Run the same layout engine at island scale: every gap swaps to the island gap for the duration of run. */
    <T> T atIslandScale(final Supplier<T> run) {
        final double savedRowGap = rowGap;
        final double savedSectionGap = sectionGap;
        final double savedColumnGapMin = columnGapMin;
        final double savedSatellitePad = satellitePad;
        final double savedSatelliteStackGap = satelliteStackGap;
        // A touch tighter than the outer island gap: islands should cluster, staying close enough to read as one
        // factory while every ground keeps room for a wire to walk around it.
        final double metaGap = Math.max(islandGap - cells(2), cells(8));
        rowGap = metaGap;
        sectionGap = metaGap;
        columnGapMin = metaGap;
        satellitePad = metaGap;
        satelliteStackGap = metaGap;
        pageFold = true;
        try {
            return run.get();
        } finally {
            rowGap = savedRowGap;
            sectionGap = savedSectionGap;
            columnGapMin = savedColumnGapMin;
            satellitePad = savedSatellitePad;
            satelliteStackGap = savedSatelliteStackGap;
            pageFold = false;
        }
    }

    // endregion

    // region Internal graph model

    static final class CardSlot {

        final ArrangeCard card;
        /** Position in the input array: the deterministic tiebreak everywhere. */
        final int index;
        int layer;
        /** Global vertical theme: cards sort within their column by this. */
        double seq;
        /** The band this card belongs to; a boundary between bands adds air. */
        int section;
        /** On the main line. Trunk-to-trunk wires get the straightest runs. */
        boolean trunk;
        double y;

        CardSlot(final ArrangeCard card, final int index) {
            this.card = card;
            this.index = index;
        }

        /** {@code { ...slot, y }}: a copy at another height. */
        CardSlot withY(final double newY) {
            final CardSlot copy = new CardSlot(card, index);
            copy.layer = layer;
            copy.seq = seq;
            copy.section = section;
            copy.trunk = trunk;
            copy.y = newY;
            return copy;
        }
    }

    static final class WireLink {

        @Nullable
        final String id;
        final CardSlot from;
        final CardSlot to;
        final double fromAnchor;
        final double toAnchor;
        final double weight;
        @Nullable
        final Double width;

        WireLink(@Nullable final String id, final CardSlot from, final CardSlot to, final double fromAnchor,
            final double toAnchor, final double weight, @Nullable final Double width) {
            this.id = id;
            this.from = from;
            this.to = to;
            this.fromAnchor = fromAnchor;
            this.toAnchor = toAnchor;
            this.weight = weight;
            this.width = width;
        }
    }

    static final class Placement {

        double x;
        double y;

        Placement(final double x, final double y) {
            this.x = x;
            this.y = y;
        }
    }

    /** Where a satellite storage rides: which machine, which side ("left" when left), the offset (ports aligned). */
    record SatellitePlan(CardSlot anchor, boolean left, double offsetY) {

        String side() {
            return left ? "left" : "right";
        }
    }

    /** One laid-out block (an island), positions relative to its own top-left. */
    static final class Block {

        List<String> ids;
        List<Placement> places;
        double width;
        double height;
        int size;
        double minIndex;
        /** The parked-strays shelf: always takes the bottom row of the page. */
        boolean shelf;
        /** No background wanted: an interchange buffer standing between islands. */
        boolean plain;
    }

    static final class IslandGroup {

        List<CardSlot> members;
        List<WireLink> links;
        final Map<CardSlot, SatellitePlan> satellites;
        /** A buffer standing alone between islands; drawn without a ground. */
        final boolean interchange;

        IslandGroup(final List<CardSlot> members, final List<WireLink> links,
            final Map<CardSlot, SatellitePlan> satellites, final boolean interchange) {
            this.members = members;
            this.links = links;
            this.satellites = satellites;
            this.interchange = interchange;
        }
    }

    /** A wire between two islands, still holding the card-level link behind it. */
    record BridgeLink(int from, int to, double weight, WireLink link) {}

    /** A bridge's pull on an exit card: a fixed point in island space its port is drawn toward. */
    record Pull(double y, double weight, double anchor) {}

    record PartnerEntry(CardSlot other, double own, double their, double weight) {}

    record Ground(double left, double top, double right, double bottom) {}

    record GroundPair(Ground tight, Ground lane) {}

    /** {@code foldBigCycles}' answer: the cards on top decks, and every card of a folded ring. */
    record Fold(Set<CardSlot> topDeck, Set<CardSlot> pinned) {}

    static <K, V> void push(final Map<K, List<V>> map, final K key, final V value) {
        final List<V> list = map.get(key);
        if (list != null) {
            list.add(value);
        } else {
            final List<V> fresh = new ArrayList<>();
            fresh.add(value);
            map.put(key, fresh);
        }
    }

    /** {@code x || y} for a comparator's numeric terms: 0 and NaN fall through. */
    static double or(final double x, final double y) {
        return x != 0 && !Double.isNaN(x) ? x : y;
    }

    // endregion

    // region arrangeBoardOnce

    ArrangeResult arrangeBoardOnce(final Arrange.Input input, final boolean optimise) {
        this.optimise = optimise;
        final List<ArrangeCard> cards = input.cards();
        final List<ArrangeWire> wires = input.wires();
        if (cards.isEmpty()) {
            return new ArrangeResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
        applyTaste(input.spacing());
        applyArrangeDials(arrangePrices);

        final Map<String, CardSlot> slotById = new LinkedHashMap<>();
        for (int index = 0; index < cards.size(); index++) {
            final ArrangeCard card = cards.get(index);
            slotById.put(card.id(), new CardSlot(card, index));
        }

        // Wires with a missing end or both ends on one card place nothing.
        final List<WireLink> links = new ArrayList<>();
        for (final ArrangeWire wire : wires) {
            final CardSlot from = slotById.get(wire.source());
            final CardSlot to = slotById.get(wire.target());
            if (from == null || to == null || from == to) {
                continue;
            }
            links.add(
                new WireLink(
                    wire.id(),
                    from,
                    to,
                    wire.sourcePortY() != null ? wire.sourcePortY() : from.card.height() / 2,
                    wire.targetPortY() != null ? wire.targetPortY() : to.card.height() / 2,
                    Math.max(wire.weight() != null ? wire.weight() : 1, 0.01),
                    wire.width()));
        }

        // Drawers that serve exactly one machine leave the graph here and come back at the very end, pinned against
        // that machine's side. The layout then only has to solve the machines and the true multi-way buffers.
        final Map<CardSlot, SatellitePlan> satellitePlans = planSatellites(slotById, links);
        final List<WireLink> mainLinks = new ArrayList<>();
        for (final WireLink link : links) {
            if (!satellitePlans.containsKey(link.from) && !satellitePlans.containsKey(link.to)) mainLinks.add(link);
        }
        final Set<CardSlot> anchors = new HashSet<>();
        for (final SatellitePlan plan : satellitePlans.values()) anchors.add(plan.anchor());

        // Split into islands: weakly-connected components of the wire graph.
        final IntUnaryOperator componentOf = unionComponents(cards.size(), mainLinks);
        final Map<Integer, List<CardSlot>> componentSlots = new LinkedHashMap<>();
        for (final CardSlot slot : slotById.values()) {
            if (satellitePlans.containsKey(slot)) {
                continue;
            }
            final int root = componentOf.applyAsInt(slot.index);
            final List<CardSlot> members = componentSlots.get(root);
            if (members != null) {
                members.add(slot);
            } else {
                final List<CardSlot> fresh = new ArrayList<>();
                fresh.add(slot);
                componentSlots.put(root, fresh);
            }
        }

        final List<CardSlot> parked = new ArrayList<>();
        final List<IslandGroup> islandGroups = new ArrayList<>();
        for (final List<CardSlot> members : componentSlots.values()) {
            // A card with no wires parks on the shelf - unless satellites ride on it, which makes it a one-card island
            // that keeps its drawers.
            if (members.size() == 1 && !touches(mainLinks, members.get(0)) && !anchors.contains(members.get(0))) {
                parked.add(members.get(0));
                continue;
            }
            JsSort.sort(members, (a, b) -> a.index - b.index);
            final Set<CardSlot> componentSet = new HashSet<>(members);
            final List<WireLink> componentLinks = new ArrayList<>();
            for (final WireLink link : mainLinks) {
                if (componentSet.contains(link.from)) componentLinks.add(link);
            }
            // One connected web is one island. Loose clusters part from the main body inside the search instead,
            // through the stranger air (board-arrange-air.ts).
            final List<CardSlot> group = members;
            final Set<CardSlot> groupSet = new HashSet<>(group);
            final List<WireLink> groupLinks = new ArrayList<>();
            for (final WireLink link : componentLinks) {
                if (groupSet.contains(link.from) && groupSet.contains(link.to)) groupLinks.add(link);
            }
            final Map<CardSlot, SatellitePlan> groupSatellites = new LinkedHashMap<>();
            for (final Map.Entry<CardSlot, SatellitePlan> e : satellitePlans.entrySet()) {
                if (groupSet.contains(
                    e.getValue()
                        .anchor())) {
                    groupSatellites.put(e.getKey(), e.getValue());
                }
            }
            islandGroups.add(new IslandGroup(group, groupLinks, groupSatellites, false));
        }
        // A storage that two or more OTHER islands trade through steps out to stand alone as an interchange island,
        // which the island graph places between its users. A plain pass-through between two islands stays put.
        {
            final Map<CardSlot, Integer> prelim = new HashMap<>();
            for (int index = 0; index < islandGroups.size(); index++) {
                for (final CardSlot slot : islandGroups.get(index).members) {
                    prelim.put(slot, index);
                }
            }
            for (final IslandGroup group : new ArrayList<>(islandGroups)) {
                for (final CardSlot slot : new ArrayList<>(group.members)) {
                    if (group.members.size() < 2 || !slot.card.storage()) {
                        continue;
                    }
                    final Integer ownGroup = prelim.get(slot);
                    final Set<Integer> otherGroups = new HashSet<>();
                    for (final WireLink link : mainLinks) {
                        final CardSlot other = link.from == slot ? link.to : link.to == slot ? link.from : null;
                        if (other == null) {
                            continue;
                        }
                        final Integer otherGroup = prelim.get(other);
                        if (otherGroup != null && !Objects.equals(otherGroup, ownGroup)) {
                            otherGroups.add(otherGroup);
                        }
                    }
                    if (otherGroups.size() >= 2) {
                        final List<CardSlot> kept = new ArrayList<>();
                        for (final CardSlot member : group.members) if (member != slot) kept.add(member);
                        group.members = kept;
                        final List<WireLink> keptLinks = new ArrayList<>();
                        for (final WireLink link : group.links)
                            if (link.from != slot && link.to != slot) keptLinks.add(link);
                        group.links = keptLinks;
                        final Map<CardSlot, SatellitePlan> ownSatellites = new LinkedHashMap<>();
                        for (final Iterator<Map.Entry<CardSlot, SatellitePlan>> it = group.satellites.entrySet()
                            .iterator(); it.hasNext();) {
                            final Map.Entry<CardSlot, SatellitePlan> e = it.next();
                            if (e.getValue()
                                .anchor() == slot) {
                                ownSatellites.put(e.getKey(), e.getValue());
                                it.remove();
                            }
                        }
                        final List<CardSlot> alone = new ArrayList<>();
                        alone.add(slot);
                        islandGroups.add(new IslandGroup(alone, new ArrayList<>(), ownSatellites, true));
                        prelim.put(slot, islandGroups.size() - 1);
                    }
                }
            }
        }

        JsSort.sort(islandGroups, 
            (a, b) -> {
                final int bySize = b.members.size() + b.satellites.size() - (a.members.size() + a.satellites.size());
                return bySize != 0 ? bySize : a.members.get(0).index - b.members.get(0).index;
            });

        // The island flow BEFORE any island is laid out: which island stands upstream of which. Each island's internal
        // layout then knows which side its bridge cards should lean toward, so a bridge leaves the FACING edge instead
        // of dragging across its own island.
        final Map<String, Integer> groupOfCard = new HashMap<>();
        for (int index = 0; index < islandGroups.size(); index++) {
            final IslandGroup group = islandGroups.get(index);
            for (final CardSlot slot : group.members) {
                groupOfCard.put(slot.card.id(), index);
            }
            for (final CardSlot sat : group.satellites.keySet()) {
                groupOfCard.put(sat.card.id(), index);
            }
        }
        final List<BridgeLink> bridgeLinks = new ArrayList<>();
        for (final WireLink link : mainLinks) {
            final Integer fromGroup = groupOfCard.get(link.from.card.id());
            final Integer toGroup = groupOfCard.get(link.to.card.id());
            if (fromGroup == null || toGroup == null || fromGroup.intValue() == toGroup.intValue()) {
                continue;
            }
            bridgeLinks.add(new BridgeLink(fromGroup, toGroup, link.weight, link));
        }
        final List<Integer> groupIndices = new ArrayList<>();
        for (int index = 0; index < islandGroups.size(); index++) groupIndices.add(index);
        final Map<Integer, Integer> islandLayer = islandFlowLayers(groupIndices, bridgeLinks);
        final List<Map<String, Double>> exitsByGroup = new ArrayList<>();
        for (int index = 0; index < islandGroups.size(); index++) exitsByGroup.add(new LinkedHashMap<>());
        for (final BridgeLink bridge : bridgeLinks) {
            final Integer toLayer = islandLayer.get(bridge.to());
            final Integer fromLayer = islandLayer.get(bridge.from());
            final int direction = Integer.signum((toLayer != null ? toLayer : 0) - (fromLayer != null ? fromLayer : 0));
            if (direction == 0) {
                continue;
            }
            final Map<String, Double> fromExits = exitsByGroup.get(bridge.from());
            final String fromId = bridge.link().from.card.id();
            final Double fromHad = fromExits.get(fromId);
            fromExits.put(fromId, (fromHad != null ? fromHad : 0) + direction * bridge.weight());
            final Map<String, Double> toExits = exitsByGroup.get(bridge.to());
            final String toId = bridge.link().to.card.id();
            final Double toHad = toExits.get(toId);
            toExits.put(toId, (toHad != null ? toHad : 0) - direction * bridge.weight());
        }

        final Arrange.Judge judge = input.judge();
        final java.util.function.Function<List<Map<String, List<Pull>>>, List<Block>> layoutAll = pullsByGroup -> {
            final List<Block> built = new ArrayList<>();
            for (int index = 0; index < islandGroups.size(); index++) {
                final IslandGroup group = islandGroups.get(index);
                final Map<String, List<Pull>> pulls = pullsByGroup != null ? pullsByGroup.get(index) : null;
                final Block block = layoutIsland(
                    group.members,
                    group.links,
                    group.satellites,
                    exitsByGroup.get(index),
                    pulls != null && !pulls.isEmpty() ? pulls : null,
                    links,
                    judge);
                if (group.interchange) {
                    block.plain = true;
                }
                built.add(block);
            }
            if (!parked.isEmpty()) {
                built.add(layoutShelf(parked, !built.isEmpty() ? built.get(0).width : 0));
            }
            return built;
        };
        List<Block> blocks = layoutAll.apply(null);

        final GridPoint origin = input.origin() != null ? input.origin() : boundingTopLeft(cards);
        final double originX = snapToGrid(origin.x());
        final double originY = snapToGrid(origin.y());

        // The islands trade with each other, so WHERE each island stands follows the same rules as the cards inside
        // one - literally: the blocks go through the same engine as meta-cards.
        Map<String, Placement> localPlaceOfCard = assemble(blocks);
        List<Placement> offsets = placeIslands(blocks, bridgeLinks, localPlaceOfCard);

        // Second pass: with every island placed, each bridge's far end is a known point, so every island lays itself
        // out again with its exit cards pulled toward their partners, and the islands are placed again.
        if (!bridgeLinks.isEmpty()) {
            final List<Map<String, List<Pull>>> pullsByGroup = new ArrayList<>();
            for (int index = 0; index < islandGroups.size(); index++) pullsByGroup.add(new LinkedHashMap<>());
            for (final BridgeLink bridge : bridgeLinks) {
                final Placement fromLocal = localPlaceOfCard.get(bridge.link().from.card.id());
                final Placement toLocal = localPlaceOfCard.get(bridge.link().to.card.id());
                // Heavy on purpose: the exit card goes to the partner's level even when its own island's wires would
                // rather keep it.
                final double weight = bridge.weight() * 5;
                push(
                    pullsByGroup.get(bridge.from()),
                    bridge.link().from.card.id(),
                    new Pull(
                        offsets.get(bridge.to()).y + toLocal.y
                            + bridge.link().toAnchor
                            - offsets.get(bridge.from()).y,
                        weight,
                        bridge.link().fromAnchor));
                push(
                    pullsByGroup.get(bridge.to()),
                    bridge.link().to.card.id(),
                    new Pull(
                        offsets.get(bridge.from()).y + fromLocal.y
                            + bridge.link().fromAnchor
                            - offsets.get(bridge.to()).y,
                        weight,
                        bridge.link().toAnchor));
            }
            blocks = layoutAll.apply(pullsByGroup);
            localPlaceOfCard = assemble(blocks);
            offsets = placeIslands(blocks, bridgeLinks, localPlaceOfCard);
        }

        final List<ArrangeResult.Move> moves = new ArrayList<>();
        final List<ArrangeResult.Island> islands = new ArrayList<>();
        final Map<String, GridPoint> newById = new LinkedHashMap<>();
        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
            final Block block = blocks.get(blockIndex);
            final double blockX = snapToGrid(originX + offsets.get(blockIndex).x);
            final double blockY = snapToGrid(originY + offsets.get(blockIndex).y);
            for (int i = 0; i < block.ids.size(); i++) {
                final String id = block.ids.get(i);
                final GridPoint position = new GridPoint(blockX + block.places.get(i).x,
                    blockY + block.places.get(i).y);
                moves.add(new ArrangeResult.Move(id, position));
                newById.put(id, position);
            }
            islands.add(
                new ArrangeResult.Island(blockX, blockY, block.width, block.height, !block.shelf && !block.plain));
        }

        followInk(input, newById, moves);

        // A bridge that would pass over a third island gets waypoints walking it around that island's ground, since
        // the router avoids cards, not island grounds. The hit is judged against the island's own rectangle; the
        // detour lane runs on a collar four cells clear of it.
        final List<ArrangeResult.WireRoute> wireRoutes = new ArrayList<>();
        {
            final double collar = cells(4);
            final List<GroundPair> grounds = new ArrayList<>();
            for (final ArrangeResult.Island island : islands) {
                grounds.add(
                    new GroundPair(
                        new Ground(island.x(), island.y(), island.x() + island.width(), island.y() + island.height()),
                        new Ground(
                            island.x() - collar,
                            island.y() - collar,
                            island.x() + island.width() + collar,
                            island.y() + island.height() + collar)));
            }
            for (final BridgeLink bridge : bridgeLinks) {
                if (bridge.link().id == null) {
                    continue;
                }
                final GridPoint fromPosition = newById.get(bridge.link().from.card.id());
                final GridPoint toPosition = newById.get(bridge.link().to.card.id());
                if (fromPosition == null || toPosition == null) {
                    continue;
                }
                final List<GroundPair> others = new ArrayList<>();
                for (int index = 0; index < grounds.size(); index++) {
                    if (index != bridge.from() && index != bridge.to()) others.add(grounds.get(index));
                }
                final List<GridPoint> stops = routeAroundGrounds(
                    new GridPoint(
                        fromPosition.x() + bridge.link().from.card.width(),
                        fromPosition.y() + bridge.link().fromAnchor),
                    new GridPoint(toPosition.x(), toPosition.y() + bridge.link().toAnchor),
                    others);
                if (!stops.isEmpty()) {
                    wireRoutes.add(new ArrangeResult.WireRoute(bridge.link().id, stops));
                }
            }
        }

        return new ArrangeResult(moves, islands, wireRoutes);
    }

    private static boolean touches(final List<WireLink> links, final CardSlot slot) {
        for (final WireLink l : links) if (l.from == slot || l.to == slot) return true;
        return false;
    }

    private static Map<String, Placement> assemble(final List<Block> blocks) {
        final Map<String, Placement> local = new LinkedHashMap<>();
        for (final Block block : blocks) {
            for (int i = 0; i < block.ids.size(); i++) {
                local.put(block.ids.get(i), block.places.get(i));
            }
        }
        return local;
    }

    /**
     * Ink moves by the average displacement of the cards it overlapped (within INK_REACH). Ink over empty canvas stays
     * put.
     */
    static void followInk(final Arrange.Input input, final Map<String, GridPoint> newById,
        final List<ArrangeResult.Move> moves) {
        if (input.ink() == null) return;
        for (final ArrangeInk ink : input.ink()) {
            double deltaX = 0;
            double deltaY = 0;
            double touched = 0;
            for (final ArrangeCard card : input.cards()) {
                final GridPoint moved = newById.get(card.id());
                if (moved == null) {
                    continue;
                }
                if (ink.x() - INK_REACH < card.x() + card.width() && ink.x() + ink.width() + INK_REACH > card.x()
                    && ink.y() - INK_REACH < card.y() + card.height()
                    && ink.y() + ink.height() + INK_REACH > card.y()) {
                    deltaX += moved.x() - card.x();
                    deltaY += moved.y() - card.y();
                    touched += 1;
                }
            }
            if (touched > 0) {
                moves.add(
                    new ArrangeResult.Move(
                        ink.id(),
                        new GridPoint(snapToGrid(ink.x() + deltaX / touched), snapToGrid(ink.y() + deltaY / touched))));
            }
        }
    }

    /** Liang-Barsky's clip step; {@code t} holds t0 and t1. */
    private static boolean clip(final double[] t, final double p, final double q) {
        if (p == 0) {
            return q >= 0;
        }
        final double r = q / p;
        if (p < 0) {
            if (r > t[1]) {
                return false;
            }
            if (r > t[0]) {
                t[0] = r;
            }
        } else {
            if (r < t[0]) {
                return false;
            }
            if (r < t[1]) {
                t[1] = r;
            }
        }
        return true;
    }

    /** Whether the segment from a to b passes through the box (Liang-Barsky). */
    static boolean segmentHitsGround(final GridPoint a, final GridPoint b, final Ground box) {
        final double[] t = { 0, 1 };
        final double dx = b.x() - a.x();
        final double dy = b.y() - a.y();
        // STRICT: a wire riding exactly along a ground's edge is not a hit - counting touches turns every bridge
        // through a shared corridor into a detour for nothing.
        return clip(t, -dx, a.x() - box.left()) && clip(t, dx, box.right() - a.x())
            && clip(t, -dy, a.y() - box.top())
            && clip(t, dy, box.bottom() - a.y())
            && t[0] < t[1];
    }

    /**
     * Walk a straight run around every ground it would cut through: for each offender in travel order, stops trace
     * the nearer collar side. Stops are clamped inside the run's own span, and a walk still blocked after two detours
     * returns no stops, leaving the router to find its own way.
     */
    static List<GridPoint> routeAroundGrounds(final GridPoint start, final GridPoint end,
        final List<GroundPair> grounds) {
        final List<GridPoint> stops = new ArrayList<>();
        GridPoint current = start;
        for (int guard = 0; guard <= 2; guard += 1) {
            final List<GroundPair> blockers = new ArrayList<>();
            for (final GroundPair ground : grounds) {
                if (segmentHitsGround(current, end, ground.tight())) blockers.add(ground);
            }
            if (blockers.isEmpty()) {
                break;
            }
            if (guard == 2) {
                // Still blocked after two detours: this walk is lost. No stops.
                return new ArrayList<>();
            }
            final boolean horizontal = Math.abs(end.x() - current.x()) >= Math.abs(end.y() - current.y());
            final GridPoint next;
            if (horizontal) {
                final boolean rightward = end.x() >= current.x();
                JsSort.sort(blockers, 
                    (a, b) -> JsMath.sign(
                        rightward ? a.tight()
                            .left()
                            - b.tight()
                                .left()
                            : b.tight()
                                .right()
                                - a.tight()
                                    .right()));
                final GroundPair ground = blockers.get(0);
                final double midY = (current.y() + end.y()) / 2;
                final double laneY = snapToGrid(
                    Math.abs(
                        midY - ground.lane()
                            .top())
                        <= Math.abs(
                            midY - ground.lane()
                                .bottom())
                                    ? ground.lane()
                                        .top()
                                    : ground.lane()
                                        .bottom());
                final double low = Math.min(current.x(), end.x());
                final double high = Math.max(current.x(), end.x());
                final double enterX = snapToGrid(
                    Math.min(
                        high,
                        Math.max(
                            low,
                            rightward ? ground.lane()
                                .left()
                                : ground.lane()
                                    .right())));
                final double exitX = snapToGrid(
                    Math.min(
                        high,
                        Math.max(
                            low,
                            rightward ? ground.lane()
                                .right()
                                : ground.lane()
                                    .left())));
                if (enterX != current.x() || laneY != current.y()) {
                    stops.add(new GridPoint(enterX, laneY));
                }
                if (exitX != enterX) {
                    stops.add(new GridPoint(exitX, laneY));
                }
                next = new GridPoint(exitX, laneY);
            } else {
                final boolean downward = end.y() >= current.y();
                JsSort.sort(blockers, 
                    (a, b) -> JsMath.sign(
                        downward ? a.tight()
                            .top()
                            - b.tight()
                                .top()
                            : b.tight()
                                .bottom()
                                - a.tight()
                                    .bottom()));
                final GroundPair ground = blockers.get(0);
                final double midX = (current.x() + end.x()) / 2;
                final double laneX = snapToGrid(
                    Math.abs(
                        midX - ground.lane()
                            .left())
                        <= Math.abs(
                            midX - ground.lane()
                                .right())
                                    ? ground.lane()
                                        .left()
                                    : ground.lane()
                                        .right());
                final double low = Math.min(current.y(), end.y());
                final double high = Math.max(current.y(), end.y());
                final double enterY = snapToGrid(
                    Math.min(
                        high,
                        Math.max(
                            low,
                            downward ? ground.lane()
                                .top()
                                : ground.lane()
                                    .bottom())));
                final double exitY = snapToGrid(
                    Math.min(
                        high,
                        Math.max(
                            low,
                            downward ? ground.lane()
                                .bottom()
                                : ground.lane()
                                    .top())));
                if (laneX != current.x() || enterY != current.y()) {
                    stops.add(new GridPoint(laneX, enterY));
                }
                if (exitY != enterY) {
                    stops.add(new GridPoint(laneX, exitY));
                }
                next = new GridPoint(laneX, exitY);
            }
            if (next.x() == current.x() && next.y() == current.y()) {
                return new ArrayList<>();
            }
            current = next;
        }
        return stops;
    }

    static GridPoint boundingTopLeft(final List<ArrangeCard> cards) {
        double x = Double.POSITIVE_INFINITY;
        double y = Double.POSITIVE_INFINITY;
        for (final ArrangeCard card : cards) {
            x = Math.min(x, card.x());
            y = Math.min(y, card.y());
        }
        return new GridPoint(x, y);
    }

    /** Union-find over card indices, unioned along the wires. */
    static IntUnaryOperator unionComponents(final int count, final List<WireLink> links) {
        final int[] parent = new int[count];
        for (int i = 0; i < count; i++) parent[i] = i;
        final IntUnaryOperator find = start -> {
            int i = start;
            while (parent[i] != i) {
                parent[i] = parent[parent[i]];
                i = parent[i];
            }
            return i;
        };
        for (final WireLink link : links) {
            final int a = find.applyAsInt(link.from.index);
            final int b = find.applyAsInt(link.to.index);
            if (a != b) {
                parent[Math.max(a, b)] = Math.min(a, b);
            }
        }
        return find;
    }

    /**
     * Which storages ride as satellites: a drawer whose every wire meets one other card pins to that card (supply
     * left, catch right) at the port row it serves. Two storages that would pin to each other both stay ordinary
     * cards: a satellite cannot anchor on another satellite.
     */
    static Map<CardSlot, SatellitePlan> planSatellites(final Map<String, CardSlot> slotById,
        final List<WireLink> links) {
        final Map<CardSlot, List<WireLink>> bySlot = new HashMap<>();
        for (final WireLink link : links) {
            push(bySlot, link.from, link);
            push(bySlot, link.to, link);
        }
        final Map<CardSlot, SatellitePlan> plans = new LinkedHashMap<>();
        for (final CardSlot slot : slotById.values()) {
            if (!slot.card.storage()) {
                continue;
            }
            final List<WireLink> myLinks = bySlot.getOrDefault(slot, List.of());
            if (myLinks.isEmpty()) {
                continue;
            }
            final Set<CardSlot> partners = new LinkedHashSet<>();
            for (final WireLink l : myLinks) partners.add(l.from == slot ? l.to : l.from);
            if (partners.size() != 1) {
                continue;
            }
            final CardSlot anchor = partners.iterator()
                .next();
            boolean feeds = false;
            boolean fed = false;
            for (final WireLink l : myLinks) {
                if (l.from == slot) feeds = true;
                if (l.to == slot) fed = true;
            }
            // Ports aligned: the satellite's port row meets the machine's port row.
            double sum = 0;
            double weight = 0;
            for (final WireLink l : myLinks) {
                final double anchorPort = l.from == slot ? l.toAnchor : l.fromAnchor;
                final double ownPort = l.from == slot ? l.fromAnchor : l.toAnchor;
                sum += (anchorPort - ownPort) * l.weight;
                weight += l.weight;
            }
            plans.put(slot, new SatellitePlan(anchor, feeds && !fed, sum / weight));
        }
        final List<CardSlot> keys = new ArrayList<>(plans.keySet());
        final List<SatellitePlan> values = new ArrayList<>(plans.values());
        for (int i = 0; i < keys.size(); i++) {
            if (plans.containsKey(
                values.get(i)
                    .anchor())) {
                plans.remove(keys.get(i));
                plans.remove(
                    values.get(i)
                        .anchor());
            }
        }
        return plans;
    }

    /** The wires of every two-card loop (A feeds B, B feeds A). Such pairs stack in one column. */
    static Set<WireLink> twoCycleLinks(final List<WireLink> links) {
        final Set<String> directions = new HashSet<>();
        for (final WireLink link : links) {
            directions.add(link.from.index + ">" + link.to.index);
        }
        final Set<WireLink> pairs = new LinkedHashSet<>();
        for (final WireLink link : links) {
            if (directions.contains(link.to.index + ">" + link.from.index)) {
                pairs.add(link);
            }
        }
        return pairs;
    }

    // endregion

    // region One island: trunk, sections, columns

    Block layoutIsland(final List<CardSlot> members, final List<WireLink> links,
        final Map<CardSlot, SatellitePlan> satellites, final Map<String, Double> exits,
        @Nullable final Map<String, List<Pull>> pulls, @Nullable final List<WireLink> allLinks,
        @Nullable final Arrange.Judge judge) {
        // The layout may run twice over the same slots; everything derived is recomputed from scratch, so clear the
        // one flag that only ever gets set.
        for (final CardSlot slot : members) {
            slot.trunk = false;
        }
        // Bridge pulls become phantom partners: a fixed point in island space the exit card's port is drawn toward,
        // so a wire leaving for another island exits from the corner facing it instead of crossing its own island.
        Map<CardSlot, List<PartnerEntry>> extraPartners = null;
        if (pulls != null && !pulls.isEmpty()) {
            extraPartners = new LinkedHashMap<>();
            for (final CardSlot slot : members) {
                final List<Pull> entries = pulls.get(slot.card.id());
                if (entries == null) {
                    continue;
                }
                final List<PartnerEntry> list = new ArrayList<>();
                for (final Pull entry : entries) {
                    list.add(new PartnerEntry(slot.withY(entry.y()), entry.anchor(), 0, entry.weight()));
                }
                extraPartners.put(slot, list);
            }
        }
        // -- Cycles. Ranking needs an acyclic graph, so a DFS in input order marks the wires that close each loop.
        // Two-card loops are lifted out first and stack in one column.
        final Set<WireLink> pairs = twoCycleLinks(links);
        final List<WireLink> forward = splitCoFeeders(breakCycles(members, links, pairs), members);

        assignLayers(members, forward, pairs, exits);

        // -- The fold. A big recycle ring flattened into one line leaves its closure wire spanning the whole board;
        // folding the ring's far half back over the top makes a loop.
        final Fold fold = foldBigCycles(members, links, pairs);
        final Set<CardSlot> topDeck = fold.topDeck();
        final Set<CardSlot> pinned = fold.pinned();
        // The fold moved the ring; everything hanging off it re-slides to its new wires (the ring itself is pinned),
        // pairs re-stack, and a drawer shared by several machines sits between them rather than to the right of all.
        slideTowardWires(members, forward, pinned, exits);
        pullPairsTogether(pairs, forward);
        relaxSharedStorages(members, links, pinned);
        packLayers(members);

        // The page fold (island scale only): a run of columns wider than the page folds back on itself like text
        // lines, odd bands reading right to left.
        final Map<CardSlot, Integer> bandOf = pageFold ? pageFold(members) : new LinkedHashMap<>();

        buildBands(members, links, forward);
        if (!topDeck.isEmpty()) {
            // The return deck rides ABOVE the line it feeds: its cards keep their relative order but outrank every
            // bottom-deck seq. Each CONNECTED run of deck cards is its own band.
            final double shift = members.size() * 4;
            final Map<CardSlot, Integer> deckChunk = new HashMap<>();
            int nextChunk = 0;
            for (final CardSlot slot : members) {
                if (!topDeck.contains(slot) || deckChunk.containsKey(slot)) {
                    continue;
                }
                deckChunk.put(slot, nextChunk);
                final List<CardSlot> queue = new ArrayList<>();
                queue.add(slot);
                for (int head = 0; head < queue.size(); head += 1) {
                    for (final WireLink link : links) {
                        final CardSlot other = link.from == queue.get(head) ? link.to
                            : link.to == queue.get(head) ? link.from : null;
                        if (other != null && topDeck.contains(other) && !deckChunk.containsKey(other)) {
                            deckChunk.put(other, nextChunk);
                            queue.add(other);
                        }
                    }
                }
                nextChunk += 1;
            }
            for (final CardSlot slot : members) {
                if (topDeck.contains(slot)) {
                    slot.seq -= shift;
                    final Integer chunk = deckChunk.get(slot);
                    slot.section = -1 - (chunk != null ? chunk : 0);
                }
            }
        }

        // A pass-through buffer is its own little band: free to sit wherever its two partners pull it.
        {
            final Map<CardSlot, Integer> wireDegree = new HashMap<>();
            for (final WireLink link : links) {
                wireDegree.merge(link.from, 1, Integer::sum);
                wireDegree.merge(link.to, 1, Integer::sum);
            }
            for (int index = 0; index < members.size(); index++) {
                final CardSlot slot = members.get(index);
                if (slot.card.storage() && wireDegree.getOrDefault(slot, 0) == 2 && !topDeck.contains(slot)) {
                    slot.section = -1000 - index;
                }
            }
        }

        // Deal the page-fold bands out as stacked rows: band by band down the page, each keeping its own seq range
        // and its own section namespace.
        if (!bandOf.isEmpty()) {
            final double stride = Math.max(members.size(), 1) * 8;
            for (final CardSlot slot : members) {
                final int band = bandOf.getOrDefault(slot, 0);
                slot.seq += band * stride;
                slot.section += band * 1000003;
            }
        }

        // A provisional vertical pass, then the anti-crossing polish: cards are reordered within their column and
        // section to follow their wires' pull, then the column settles again.
        placeRows(collectLayers(members), links, extraPartners);
        for (int pass = 0; pass < 3; pass += 1) {
            polishColumnOrder(collectLayers(members), links, extraPartners);
            placeRows(collectLayers(members), links, extraPartners);
        }
        // The barycenter passes get the order close; this pass flips neighbour pairs whose actual wires cross, until
        // no flip helps.
        for (int pass = 0; pass < 3; pass += 1) {
            if (!transposeToUncross(collectLayers(members), links)) {
                break;
            }
            placeRows(collectLayers(members), links, extraPartners);
        }
        straightenRows(collectLayers(members), links, extraPartners);
        final List<List<CardSlot>> layers = collectLayers(members);

        // -- Columns. Every column is as wide as its widest card; the corridor between two columns grows with the
        // number of wires that must cross it.
        final double[] columnWidth = new double[layers.size()];
        for (int i = 0; i < layers.size(); i++) {
            double max = 0;
            for (final CardSlot slot : layers.get(i)) max = Math.max(max, slot.card.width());
            columnWidth[i] = max;
        }
        final double[] crossings = new double[Math.max(layers.size() - 1, 0)];
        for (final WireLink link : links) {
            final int lo = Math.min(link.from.layer, link.to.layer);
            final int hi = Math.max(link.from.layer, link.to.layer);
            for (int i = lo; i < hi; i += 1) {
                crossings[i] += 1;
            }
        }
        // Corridors widen where satellites ride: supply drawers need room on the left, catch drawers on the right.
        final double[] leftPad = new double[layers.size()];
        final double[] rightPad = new double[layers.size()];
        for (final Map.Entry<CardSlot, SatellitePlan> e : satellites.entrySet()) {
            final CardSlot sat = e.getKey();
            final SatellitePlan plan = e.getValue();
            final double need = sat.card.width() + satellitePad;
            if (plan.left()) {
                leftPad[plan.anchor().layer] = Math.max(leftPad[plan.anchor().layer], need);
            } else {
                rightPad[plan.anchor().layer] = Math.max(rightPad[plan.anchor().layer], need);
            }
        }
        final double[] columnX = new double[layers.size()];
        double x = 0;
        for (int i = 0; i < layers.size(); i++) {
            x += leftPad[i];
            columnX[i] = x;
            x += columnWidth[i] + rightPad[i];
            if (i < layers.size() - 1) {
                final double gap = columnGapMin + cells(Math.floor(crossings[i] / COLUMN_GAP_WIRES_PER_CELL));
                x += Math.min(gap, COLUMN_GAP_MAX);
            }
        }

        // Normalise the island to its own top-left and centre each card in its column, snapped so a narrow drawer
        // between wide machine columns still sits on a cell corner.
        double top = Double.POSITIVE_INFINITY;
        for (final CardSlot slot : members) {
            top = Math.min(top, slot.y);
        }
        final List<String> ids = new ArrayList<>();
        final List<Placement> places = new ArrayList<>();
        final Map<CardSlot, Placement> placeBySlot = new HashMap<>();
        for (final CardSlot slot : members) {
            final Placement place = new Placement(
                snapToGrid(columnX[slot.layer] + (columnWidth[slot.layer] - slot.card.width()) / 2),
                snapToGrid(slot.y - top));
            ids.add(slot.card.id());
            places.add(place);
            placeBySlot.put(slot, place);
        }

        // Satellites, pinned to their machine's side at the port row they serve and stacked apart when several share
        // a side.
        final Map<String, CardSlot> slotIndex = new HashMap<>();
        for (final CardSlot slot : members) {
            slotIndex.put(slot.card.id(), slot);
        }
        for (final CardSlot sat : satellites.keySet()) {
            slotIndex.put(sat.card.id(), sat);
        }
        final Map<String, List<Map.Entry<CardSlot, SatellitePlan>>> satGroups = new LinkedHashMap<>();
        for (final Map.Entry<CardSlot, SatellitePlan> e : satellites.entrySet()) {
            push(
                satGroups,
                e.getValue()
                    .anchor().index + ":"
                    + e.getValue()
                        .side(),
                Map.entry(e.getKey(), e.getValue()));
        }
        for (final List<Map.Entry<CardSlot, SatellitePlan>> group : satGroups.values()) {
            JsSort.sort(group, 
                (a, b) -> JsMath.sign(
                    or(
                        a.getValue()
                            .offsetY()
                            - b.getValue()
                                .offsetY(),
                        a.getKey().index - b.getKey().index)));
            double previousBottom = Double.NEGATIVE_INFINITY;
            for (final Map.Entry<CardSlot, SatellitePlan> e : group) {
                final CardSlot sat = e.getKey();
                final SatellitePlan plan = e.getValue();
                final Placement anchorPlace = placeBySlot.get(plan.anchor());
                final Placement place = new Placement(
                    snapToGrid(
                        plan.left() ? anchorPlace.x - satellitePad - sat.card.width()
                            : anchorPlace.x + plan.anchor().card.width() + satellitePad),
                    snapToGrid(Math.max(anchorPlace.y + plan.offsetY(), previousBottom + satelliteStackGap)));
                previousBottom = place.y + sat.card.height();
                ids.add(sat.card.id());
                places.add(place);
            }
        }

        // The optimiser (board-arrange-optimize.ts) starts from the column layout and moves cards to lower a
        // router-shaped score. Every wire inside the island counts, satellite wires included; satellites keep their
        // side.
        if (optimise && allLinks != null && ids.size() >= 2) {
            final Set<String> idSet = new HashSet<>(ids);
            final Map<String, Optimize.Satellite> satelliteOf = new HashMap<>();
            for (final Map.Entry<CardSlot, SatellitePlan> e : satellites.entrySet()) {
                satelliteOf.put(
                    e.getKey().card.id(),
                    new Optimize.Satellite(
                        e.getValue()
                            .anchor().card.id(),
                        e.getValue()
                            .left()));
            }
            final List<Optimize.OptimizeCard> optimizeCards = new ArrayList<>();
            for (final String id : ids) {
                final CardSlot slot = slotIndex.get(id);
                final SatellitePlan plan = satellites.get(slot);
                final CardSlot anchor = plan != null ? plan.anchor() : null;
                optimizeCards.add(
                    new Optimize.OptimizeCard(
                        id,
                        slot.card.width(),
                        slot.card.height(),
                        slot.card.storage(),
                        anchor != null ? anchor.layer : slot.layer,
                        anchor != null ? anchor.seq : slot.y,
                        (double) (anchor != null ? anchor.section : slot.section),
                        satelliteOf.get(id)));
            }
            final List<Optimize.OptimizeWire> optimizeWires = new ArrayList<>();
            for (final WireLink link : allLinks) {
                if (idSet.contains(link.from.card.id()) && idSet.contains(link.to.card.id())) {
                    optimizeWires
                        .add(new Optimize.OptimizeWire(link.from.card.id(), link.to.card.id(), link.weight, link.width));
                }
            }
            if (!optimizeWires.isEmpty()) {
                // The host's judge sees the whole board; the island's finalists are laid where the island stands
                // today (its cards' top-left corner) so the rest of the board keeps its distance from them.
                double originX = Double.POSITIVE_INFINITY;
                double originY = Double.POSITIVE_INFINITY;
                for (final String id : ids) {
                    final CardSlot slot = slotIndex.get(id);
                    originX = Math.min(originX, slot.card.x());
                    originY = Math.min(originY, slot.card.y());
                }
                final double shiftX = originX;
                final double shiftY = originY;
                final Arrange.Judge hostJudge = judge;
                final ToDoubleFunction<Map<String, GridPoint>> optimizeJudge = hostJudge != null ? positions -> {
                    final Map<String, GridPoint> shifted = new LinkedHashMap<>();
                    for (final Map.Entry<String, GridPoint> p : positions.entrySet()) {
                        shifted.put(
                            p.getKey(),
                            new GridPoint(
                                p.getValue()
                                    .x() + shiftX,
                                p.getValue()
                                    .y() + shiftY));
                    }
                    return hostJudge.judge(shifted, false, null)
                        .points();
                } : null;
                // The proxy searches; the real router judges the finalists (the host's judge when there is one, the
                // optimiser's stand-in otherwise).
                final Optimize.OptimizeResult optimized = Optimize.optimizeIslandLayout(
                    optimizeCards,
                    optimizeWires,
                    new Optimize.OptimizeOptions(
                        JsMath.round(rowGap / BOARD_GRID),
                        JsMath.round(sectionGap / BOARD_GRID),
                        JsMath.round(columnGapMin / BOARD_GRID),
                        JsMath.round(satellitePad / BOARD_GRID),
                        null,
                        false,
                        optimizeJudge,
                        arrangePrices,
                        arrangeAir,
                        arrangeSearchProgress));
                for (int i = 0; i < optimized.positions()
                    .size(); i++) {
                    final GridPoint place = optimized.positions()
                        .get(i);
                    places.get(i).x = place.x();
                    places.get(i).y = place.y();
                }
            }
        }

        // Satellites can poke past the island's top or left edge; pull the whole block back to its own origin, then
        // measure it around everything it owns.
        double minX = 0;
        double minY = 0;
        for (final Placement place : places) {
            minX = Math.min(minX, place.x);
            minY = Math.min(minY, place.y);
        }
        double width = 0;
        double height = 0;
        for (final Placement place : places) {
            place.x -= minX;
            place.y -= minY;
        }
        for (int i = 0; i < ids.size(); i++) {
            final CardSlot slot = slotIndex.get(ids.get(i));
            width = Math.max(width, places.get(i).x + slot.card.width());
            height = Math.max(height, places.get(i).y + slot.card.height());
        }
        final Block block = new Block();
        block.ids = ids;
        block.places = places;
        block.width = width;
        block.height = height;
        block.size = members.size() + satellites.size();
        block.minIndex = members.get(0).index;
        return block;
    }

    /**
     * The serpentine: columns wider than the page fold back like text lines. Contiguous groups of columns become
     * BANDS, odd bands reading right to left. Returns each card's band, empty when no fold.
     */
    Map<CardSlot, Integer> pageFold(final List<CardSlot> members) {
        final Map<CardSlot, Integer> bandOf = new LinkedHashMap<>();
        int layerCount = 0;
        for (final CardSlot slot : members) {
            layerCount = Math.max(layerCount, slot.layer + 1);
        }
        if (layerCount < 2) {
            return bandOf;
        }
        final double[] columnWidth = new double[layerCount];
        for (final CardSlot slot : members) {
            columnWidth[slot.layer] = Math.max(columnWidth[slot.layer], slot.card.width());
        }
        double total = -columnGapMin;
        for (final double width : columnWidth) {
            total += width + columnGapMin;
        }
        if (total <= ISLAND_ROW_MAX_WIDTH) {
            return bandOf;
        }
        final int[] bandOfLayer = new int[layerCount];
        final int[] positionInBand = new int[layerCount];
        final int[] bandLength = new int[layerCount];
        {
            double x = 0;
            int band = 0;
            int position = 0;
            for (int layer = 0; layer < layerCount; layer += 1) {
                if (x > 0 && x + columnWidth[layer] > ISLAND_ROW_MAX_WIDTH) {
                    band += 1;
                    x = 0;
                    position = 0;
                }
                bandOfLayer[layer] = band;
                positionInBand[layer] = position;
                bandLength[band] = position + 1;
                x += columnWidth[layer] + columnGapMin;
                position += 1;
            }
            if (band == 0) {
                return bandOf;
            }
        }
        for (final CardSlot slot : members) {
            final int band = bandOfLayer[slot.layer];
            final int position = positionInBand[slot.layer];
            slot.layer = band % 2 == 0 ? position : bandLength[band] - 1 - position;
            bandOf.put(slot, band);
        }
        return bandOf;
    }

    /** Mark the links that close a cycle. Returns the FORWARD links (the DAG). */
    static List<WireLink> breakCycles(final List<CardSlot> members, final List<WireLink> links,
        final Set<WireLink> pairs) {
        final Map<CardSlot, List<WireLink>> outgoing = new HashMap<>();
        for (final WireLink link : links) {
            if (!pairs.contains(link)) {
                push(outgoing, link.from, link);
            }
        }
        final int visiting = 1;
        final int done = 2;
        final Map<CardSlot, Integer> state = new HashMap<>();
        final Set<WireLink> reversed = new HashSet<>();
        for (final CardSlot start : members) {
            if (state.containsKey(start)) {
                continue;
            }
            // Iterative DFS: a recursive one blows the stack on thousand-card boards.
            final List<CardSlot> stackSlot = new ArrayList<>();
            final List<int[]> stackNext = new ArrayList<>();
            stackSlot.add(start);
            stackNext.add(new int[] { 0 });
            state.put(start, visiting);
            while (!stackSlot.isEmpty()) {
                final CardSlot frameSlot = stackSlot.get(stackSlot.size() - 1);
                final int[] frameNext = stackNext.get(stackNext.size() - 1);
                final List<WireLink> list = outgoing.getOrDefault(frameSlot, List.of());
                if (frameNext[0] >= list.size()) {
                    state.put(frameSlot, done);
                    stackSlot.remove(stackSlot.size() - 1);
                    stackNext.remove(stackNext.size() - 1);
                    continue;
                }
                final WireLink link = list.get(frameNext[0]);
                frameNext[0] += 1;
                final Integer seen = state.get(link.to);
                if (seen != null && seen == visiting) {
                    reversed.add(link);
                } else if (seen == null) {
                    state.put(link.to, visiting);
                    stackSlot.add(link.to);
                    stackNext.add(new int[] { 0 });
                }
            }
        }
        final List<WireLink> out = new ArrayList<>();
        for (final WireLink link : links) if (!pairs.contains(link) && !reversed.contains(link)) out.add(link);
        return out;
    }

    /**
     * Rank every card into a column: longest path from the sources, then sweeps that slide cards toward whichever
     * side holds more of their wire weight.
     */
    static void assignLayers(final List<CardSlot> members, final List<WireLink> forward, final Set<WireLink> pairs,
        @Nullable final Map<String, Double> exits) {
        final Map<CardSlot, List<WireLink>> incoming = new HashMap<>();
        final Map<CardSlot, List<WireLink>> outgoing = new LinkedHashMap<>();
        for (final WireLink link : forward) {
            push(outgoing, link.from, link);
            push(incoming, link.to, link);
        }

        final List<CardSlot> order = topologicalOrder(members, outgoing);
        for (final CardSlot slot : order) {
            int layer = 0;
            for (final WireLink link : incoming.getOrDefault(slot, List.of())) {
                layer = Math.max(layer, link.from.layer + 1);
            }
            slot.layer = layer;
        }

        slideTowardWires(members, forward, null, exits);
        pullPairsTogether(pairs, forward);
        packLayers(members);
    }

    /**
     * Two machines that feed the same drawer stand on opposite sides of it: for each pair of co-feeders (or
     * co-consumers) with no forward path between them, one machine's drawer wires are reversed for the RANKING only.
     * The machine with more weight in its other rightward wires keeps the left.
     */
    static List<WireLink> splitCoFeeders(final List<WireLink> forward, final List<CardSlot> members) {
        final Map<CardSlot, List<WireLink>> byStorage = new LinkedHashMap<>();
        for (final WireLink link : forward) {
            if (link.to.card.storage()) push(byStorage, link.to, link);
            if (link.from.card.storage()) push(byStorage, link.from, link);
        }
        final Map<CardSlot, List<WireLink>> outgoing = new HashMap<>();
        for (final WireLink link : forward) {
            push(outgoing, link.from, link);
        }
        // Decide once per pair of machines which one turns round.
        final Set<CardSlot> turned = new HashSet<>();
        final Set<String> decided = new HashSet<>();
        for (final Map.Entry<CardSlot, List<WireLink>> entry : byStorage.entrySet()) {
            final CardSlot storage = entry.getKey();
            final List<WireLink> storageLinks = entry.getValue();
            final List<CardSlot> feeders = new ArrayList<>();
            final List<CardSlot> takers = new ArrayList<>();
            for (final WireLink l : storageLinks) if (l.to == storage) feeders.add(l.from);
            for (final WireLink l : storageLinks) if (l.from == storage) takers.add(l.to);
            for (final List<CardSlot> group : List.of(feeders, takers)) {
                final List<CardSlot> machines = new ArrayList<>();
                for (final CardSlot slot : new LinkedHashSet<>(group)) if (!slot.card.storage()) machines.add(slot);
                if (machines.size() != 2) continue;
                JsSort.sort(machines, (a, b) -> a.index - b.index);
                final CardSlot p = machines.get(0);
                final CardSlot q = machines.get(1);
                final String key = p.index + ":" + q.index;
                if (decided.contains(key)) continue;
                decided.add(key);
                if (turned.contains(p) || turned.contains(q) || reaches(outgoing, p, q) || reaches(outgoing, q, p))
                    continue;
                final Set<CardSlot> shared = new HashSet<>();
                for (final Map.Entry<CardSlot, List<WireLink>> otherEntry : byStorage.entrySet()) {
                    final CardSlot other = otherEntry.getKey();
                    final Set<CardSlot> ends = new HashSet<>();
                    for (final WireLink l : otherEntry.getValue()) ends.add(l.from == other ? l.to : l.from);
                    if (ends.contains(p) && ends.contains(q)) shared.add(other);
                }
                turned.add(rightward(outgoing, shared, p) >= rightward(outgoing, shared, q) ? q : p);
            }
        }
        if (turned.isEmpty()) return forward;
        // Cards wired to nothing but shared drawers are the classic case; a card with other forward wires keeps them,
        // and only its drawer wires turn.
        final List<WireLink> out = new ArrayList<>();
        for (final WireLink link : forward) {
            final CardSlot storageEnd = link.to.card.storage() ? link.to : link.from.card.storage() ? link.from : null;
            if (storageEnd == null) {
                out.add(link);
                continue;
            }
            final CardSlot machine = storageEnd == link.to ? link.from : link.to;
            if (!turned.contains(machine)) {
                out.add(link);
                continue;
            }
            out.add(new WireLink(link.id, link.to, link.from, link.toAnchor, link.fromAnchor, link.weight, link.width));
        }
        return out;
    }

    private static boolean reaches(final Map<CardSlot, List<WireLink>> outgoing, final CardSlot from,
        final CardSlot to) {
        final Set<CardSlot> seen = new HashSet<>();
        seen.add(from);
        final List<CardSlot> queue = new ArrayList<>();
        queue.add(from);
        for (int head = 0; head < queue.size(); head += 1) {
            for (final WireLink link : outgoing.getOrDefault(queue.get(head), List.of())) {
                if (link.to == to) return true;
                if (!seen.contains(link.to)) {
                    seen.add(link.to);
                    queue.add(link.to);
                }
            }
        }
        return false;
    }

    private static double rightward(final Map<CardSlot, List<WireLink>> outgoing, final Set<CardSlot> shared,
        final CardSlot slot) {
        double sum = 0;
        for (final WireLink l : outgoing.getOrDefault(slot, List.of())) {
            if (!shared.contains(l.to)) sum = sum + l.weight;
        }
        return sum;
    }

    /**
     * Slide every card toward whichever side holds more of its wire weight, within what its wires allow. Pinned cards
     * (a folded ring) hold still but still anchor their neighbours.
     */
    static void slideTowardWires(final List<CardSlot> members, final List<WireLink> forward,
        @Nullable final Set<CardSlot> pinned, @Nullable final Map<String, Double> exits) {
        final Map<CardSlot, List<WireLink>> incoming = new HashMap<>();
        final Map<CardSlot, List<WireLink>> outgoing = new HashMap<>();
        for (final WireLink link : forward) {
            push(outgoing, link.from, link);
            push(incoming, link.to, link);
        }
        for (int pass = 0; pass < 3; pass += 1) {
            int maxLayer = 0;
            for (final CardSlot slot : members) {
                maxLayer = Math.max(maxLayer, slot.layer);
            }
            for (final CardSlot slot : members) {
                if (pinned != null && pinned.contains(slot)) {
                    continue;
                }
                final List<WireLink> ins = incoming.getOrDefault(slot, List.of());
                final List<WireLink> outs = outgoing.getOrDefault(slot, List.of());
                // A card whose wire leaves for another island leans toward the edge it exits from, so the bridge
                // leaves the facing side. The lean is heavy on purpose.
                final Double exit = exits != null ? exits.get(slot.card.id()) : null;
                final double exitBias = (exit != null ? exit : 0) * 4;
                double lower = 0;
                for (final WireLink link : ins) {
                    lower = Math.max(lower, link.from.layer + 1);
                }
                double upper = Double.POSITIVE_INFINITY;
                for (final WireLink link : outs) {
                    upper = Math.min(upper, link.to.layer - 1);
                }
                if (upper == Double.POSITIVE_INFINITY) {
                    // No forward successors normally means "stay put", but a card exiting rightward may drift as far
                    // as the island reaches.
                    upper = exitBias > 0 ? maxLayer : slot.layer;
                }
                if (upper < lower) {
                    continue;
                }
                // Total weighted wire length is linear in this card's column, so the best spot is whichever end of
                // the feasible range the heavier side points to.
                double inSum = 0;
                for (final WireLink link : ins) inSum = inSum + link.weight;
                final double inWeight = inSum + Math.max(0, -exitBias);
                double outSum = 0;
                for (final WireLink link : outs) outSum = outSum + link.weight;
                final double outWeight = outSum + Math.max(0, exitBias);
                if (outWeight > inWeight) {
                    slot.layer = (int) upper;
                } else if (inWeight > outWeight) {
                    slot.layer = (int) lower;
                } else if (inWeight > 0 && outWeight > 0) {
                    // A tie is flat under linear cost, but long wires read worst, so split the difference.
                    slot.layer = (int) JsMath.round((lower + upper) / 2);
                }
            }
        }
    }

    /** Two-card loops share a column, stacked: the member with fewer other wires adopts the other's column. */
    static void pullPairsTogether(final Set<WireLink> pairs, final List<WireLink> forward) {
        final Map<CardSlot, Integer> degree = new HashMap<>();
        for (final WireLink link : forward) {
            degree.merge(link.from, 1, Integer::sum);
            degree.merge(link.to, 1, Integer::sum);
        }
        for (final WireLink link : pairs) {
            if (link.from.index < link.to.index) {
                final CardSlot a = link.from;
                final CardSlot b = link.to;
                if (degree.getOrDefault(a, 0) >= degree.getOrDefault(b, 0)) {
                    b.layer = a.layer;
                } else {
                    a.layer = b.layer;
                }
            }
        }
    }

    /**
     * A drawer serving several machines belongs between them, not right of them all. Its column becomes the
     * weight-averaged column of its partners.
     */
    static void relaxSharedStorages(final List<CardSlot> members, final List<WireLink> links,
        final Set<CardSlot> pinned) {
        final Map<CardSlot, List<WireLink>> bySlot = new HashMap<>();
        for (final WireLink link : links) {
            push(bySlot, link.from, link);
            push(bySlot, link.to, link);
        }
        for (final CardSlot slot : members) {
            if (!slot.card.storage() || pinned.contains(slot)) {
                continue;
            }
            final List<WireLink> myLinks = bySlot.getOrDefault(slot, List.of());
            final Set<CardSlot> partners = new HashSet<>();
            for (final WireLink l : myLinks) partners.add(l.from == slot ? l.to : l.from);
            if (partners.size() < 2) {
                continue;
            }
            double sum = 0;
            double weight = 0;
            for (final WireLink link : myLinks) {
                final CardSlot other = link.from == slot ? link.to : link.from;
                sum += other.layer * link.weight;
                weight += link.weight;
            }
            slot.layer = (int) JsMath.round(sum / weight);
        }
    }

    /** Layers come out sparse after tightening and folding; close the holes. */
    static void packLayers(final List<CardSlot> members) {
        final Set<Integer> distinct = new LinkedHashSet<>();
        for (final CardSlot slot : members) distinct.add(slot.layer);
        final List<Integer> used = new ArrayList<>(distinct);
        JsSort.sort(used, (a, b) -> Integer.compare(a, b));
        final Map<Integer, Integer> packed = new HashMap<>();
        for (int i = 0; i < used.size(); i++) packed.put(used.get(i), i);
        for (final CardSlot slot : members) {
            slot.layer = packed.getOrDefault(slot.layer, 0);
        }
    }

    /** Strongly connected components of the island, iterative Tarjan in input order. */
    static Map<CardSlot, Integer> strongComponents(final List<CardSlot> members, final List<WireLink> links) {
        final Map<CardSlot, List<CardSlot>> outgoing = new HashMap<>();
        for (final WireLink link : links) {
            push(outgoing, link.from, link.to);
        }
        final Map<CardSlot, Integer> component = new HashMap<>();
        final Map<CardSlot, Integer> indexOf = new HashMap<>();
        final Map<CardSlot, Integer> low = new HashMap<>();
        final Set<CardSlot> onStack = new HashSet<>();
        final List<CardSlot> tarjanStack = new ArrayList<>();
        int nextIndex = 0;
        int nextComponent = 0;
        for (final CardSlot start : members) {
            if (indexOf.containsKey(start)) {
                continue;
            }
            final List<CardSlot> workSlot = new ArrayList<>();
            final List<int[]> workNext = new ArrayList<>();
            workSlot.add(start);
            workNext.add(new int[] { 0 });
            indexOf.put(start, nextIndex);
            low.put(start, nextIndex);
            nextIndex += 1;
            tarjanStack.add(start);
            onStack.add(start);
            while (!workSlot.isEmpty()) {
                final CardSlot frameSlot = workSlot.get(workSlot.size() - 1);
                final int[] frameNext = workNext.get(workNext.size() - 1);
                final List<CardSlot> near = outgoing.getOrDefault(frameSlot, List.of());
                if (frameNext[0] < near.size()) {
                    final CardSlot other = near.get(frameNext[0]);
                    frameNext[0] += 1;
                    if (!indexOf.containsKey(other)) {
                        indexOf.put(other, nextIndex);
                        low.put(other, nextIndex);
                        nextIndex += 1;
                        tarjanStack.add(other);
                        onStack.add(other);
                        workSlot.add(other);
                        workNext.add(new int[] { 0 });
                    } else if (onStack.contains(other)) {
                        low.put(frameSlot, Math.min(low.get(frameSlot), indexOf.get(other)));
                    }
                    continue;
                }
                workSlot.remove(workSlot.size() - 1);
                workNext.remove(workNext.size() - 1);
                if (!workSlot.isEmpty()) {
                    final CardSlot parent = workSlot.get(workSlot.size() - 1);
                    low.put(parent, Math.min(low.get(parent), low.get(frameSlot)));
                }
                if (low.get(frameSlot)
                    .intValue()
                    == indexOf.get(frameSlot)
                        .intValue()) {
                    for (;;) {
                        final CardSlot popped = tarjanStack.remove(tarjanStack.size() - 1);
                        onStack.remove(popped);
                        component.put(popped, nextComponent);
                        if (popped == frameSlot) {
                            break;
                        }
                    }
                    nextComponent += 1;
                }
            }
        }
        return component;
    }

    /**
     * Fold each big ring back over itself. For every strongly connected group of four or more cards, try each fold
     * point: cards past it mirror onto a return deck heading back left. Keep the fold whose total wire span is
     * smallest, if it beats the unfolded line. Returns the cards on top decks and the pinned ring cards.
     */
    static Fold foldBigCycles(final List<CardSlot> members, final List<WireLink> links,
        final Set<WireLink> pairs) {
        final Map<CardSlot, Integer> component = strongComponents(members, links);
        final Map<Integer, List<CardSlot>> groups = new LinkedHashMap<>();
        for (final CardSlot slot : members) {
            push(groups, component.getOrDefault(slot, -1), slot);
        }
        final Set<CardSlot> topDeck = new LinkedHashSet<>();
        final Set<CardSlot> pinned = new LinkedHashSet<>();
        for (final List<CardSlot> group : groups.values()) {
            if (group.size() < 4) {
                continue;
            }
            final Set<CardSlot> inGroup = new HashSet<>(group);
            final List<WireLink> inner = new ArrayList<>();
            for (final WireLink link : links) {
                if (inGroup.contains(link.from) && inGroup.contains(link.to) && !pairs.contains(link)) inner.add(link);
            }
            int minLayer = Integer.MAX_VALUE;
            for (final CardSlot slot : group) minLayer = Math.min(minLayer, slot.layer);
            final Map<CardSlot, Integer> flat = new HashMap<>();
            for (final CardSlot slot : group) flat.put(slot, slot.layer - minLayer);
            int span = 0;
            for (final CardSlot slot : group) span = Math.max(span, flat.get(slot));
            // SQUARED span: one wire across four columns is far worse than four wires hopping one each. A same-column
            // link wraps around the cards, so it prices like a two-column hop.
            final double baseCost = foldCost(inner, flat::get);
            int bestM = -1;
            double bestCost = baseCost;
            for (int m = 0; m < span; m += 1) {
                final int fold = m;
                final ToIntFunction<CardSlot> layerOf = slot -> {
                    final int f = flat.get(slot);
                    return f <= fold ? f : 2 * fold + 1 - f;
                };
                boolean negative = false;
                for (final CardSlot slot : group) {
                    if (layerOf.applyAsInt(slot) < 0) {
                        negative = true;
                        break;
                    }
                }
                if (negative) {
                    continue;
                }
                final double folded = foldCost(inner, layerOf);
                if (folded < bestCost) {
                    bestCost = folded;
                    bestM = m;
                }
            }
            if (bestM < 0) {
                continue;
            }
            for (final CardSlot slot : group) {
                pinned.add(slot);
                final int f = flat.get(slot);
                if (f > bestM) {
                    slot.layer = minLayer + 2 * bestM + 1 - f;
                    topDeck.add(slot);
                }
            }
        }
        return new Fold(topDeck, pinned);
    }

    private static double foldCost(final List<WireLink> inner, final ToIntFunction<CardSlot> layerOf) {
        double sum = 0;
        for (final WireLink link : inner) {
            final double d = Math.abs(layerOf.applyAsInt(link.from) - layerOf.applyAsInt(link.to));
            sum = sum + (d == 0 ? 4 : d * d);
        }
        return sum;
    }

    static List<CardSlot> topologicalOrder(final List<CardSlot> members,
        final Map<CardSlot, List<WireLink>> outgoing) {
        final Map<CardSlot, Integer> indegree = new HashMap<>();
        for (final CardSlot slot : members) {
            indegree.put(slot, 0);
        }
        for (final List<WireLink> links : outgoing.values()) {
            for (final WireLink link : links) {
                indegree.put(link.to, indegree.getOrDefault(link.to, 0) + 1);
            }
        }
        // A plain queue seeded in input order keeps the walk deterministic.
        final List<CardSlot> queue = new ArrayList<>();
        for (final CardSlot slot : members) {
            final Integer d = indegree.get(slot);
            if (d != null && d == 0) queue.add(slot);
        }
        final List<CardSlot> order = new ArrayList<>();
        for (int head = 0; head < queue.size(); head += 1) {
            final CardSlot slot = queue.get(head);
            order.add(slot);
            for (final WireLink link : outgoing.getOrDefault(slot, List.of())) {
                final int remaining = indegree.getOrDefault(link.to, 0) - 1;
                indegree.put(link.to, remaining);
                if (remaining == 0) {
                    queue.add(link.to);
                }
            }
        }
        return order;
    }

    /**
     * Sections: grow a spanning tree from the main product, heaviest wires first. The TRUNK is the chain of largest
     * subtrees down from that root; every subtree hanging off it is a SECTION. One in-order walk assigns seq and
     * section; sections are balanced on both sides of the trunk so the main line runs through the middle.
     */
    void buildBands(final List<CardSlot> members, final List<WireLink> links, final List<WireLink> forward) {
        // How many wires actually touch each card. The spanning tree can leave a two-wire buffer as a tree LEAF, and
        // leaf alone must not make it a bud.
        final Map<CardSlot, Integer> wireDegree = new HashMap<>();
        for (final WireLink link : links) {
            wireDegree.merge(link.from, 1, Integer::sum);
            wireDegree.merge(link.to, 1, Integer::sum);
        }
        // Undirected adjacency, parallel wires merged, heaviest first.
        final Map<CardSlot, List<CardSlot>> adjacency = new HashMap<>();
        {
            final Map<CardSlot, Map<CardSlot, Double>> paired = new LinkedHashMap<>();
            for (final WireLink link : links) {
                addPaired(paired, link.from, link.to, link.weight);
                addPaired(paired, link.to, link.from, link.weight);
            }
            for (final Map.Entry<CardSlot, Map<CardSlot, Double>> e : paired.entrySet()) {
                final List<Map.Entry<CardSlot, Double>> row = new ArrayList<>(
                    e.getValue()
                        .entrySet());
                JsSort.sort(row, 
                    (a, b) -> JsMath.sign(
                        or(b.getValue() - a.getValue(), a.getKey().index - b.getKey().index)));
                final List<CardSlot> near = new ArrayList<>();
                for (final Map.Entry<CardSlot, Double> r : row) near.add(r.getKey());
                adjacency.put(e.getKey(), near);
            }
        }

        // The root: among cards nothing forward flows OUT of, the one with the most cards feeding it wins. A machine
        // outranks a drawer.
        final Set<CardSlot> hasForwardOut = new HashSet<>();
        for (final WireLink link : forward) hasForwardOut.add(link.from);
        final List<CardSlot> allSinks = new ArrayList<>();
        for (final CardSlot slot : members) if (!hasForwardOut.contains(slot)) allSinks.add(slot);
        final List<CardSlot> machineSinks = new ArrayList<>();
        for (final CardSlot slot : allSinks) if (!slot.card.storage()) machineSinks.add(slot);
        final List<CardSlot> sinks = !machineSinks.isEmpty() ? machineSinks : allSinks;
        final Map<CardSlot, List<WireLink>> feeders = new HashMap<>();
        for (final WireLink link : forward) {
            push(feeders, link.to, link);
        }
        CardSlot root = members.get(0);
        int rootReach = -1;
        for (final CardSlot sink : sinks) {
            final Set<CardSlot> seen = new HashSet<>();
            seen.add(sink);
            final List<CardSlot> queue = new ArrayList<>();
            queue.add(sink);
            for (int head = 0; head < queue.size(); head += 1) {
                for (final WireLink link : feeders.getOrDefault(queue.get(head), List.of())) {
                    if (!seen.contains(link.from)) {
                        seen.add(link.from);
                        queue.add(link.from);
                    }
                }
            }
            if (seen.size() > rootReach) {
                root = sink;
                rootReach = seen.size();
            }
        }

        // Spanning tree from the root, heaviest neighbours claimed first.
        final Map<CardSlot, List<CardSlot>> children = new HashMap<>();
        {
            final Set<CardSlot> visited = new HashSet<>();
            visited.add(root);
            final List<CardSlot> stack = new ArrayList<>();
            stack.add(root);
            while (!stack.isEmpty()) {
                final CardSlot slot = stack.remove(stack.size() - 1);
                // Reverse so the heaviest neighbour is popped (visited) first.
                final List<CardSlot> near = adjacency.getOrDefault(slot, List.of());
                for (int i = near.size() - 1; i >= 0; i -= 1) {
                    final CardSlot other = near.get(i);
                    if (!visited.contains(other)) {
                        visited.add(other);
                        push(children, slot, other);
                        stack.add(other);
                    }
                }
            }
        }

        // Subtree sizes and rough band heights, accumulated bottom-up without recursion, then the trunk: follow the
        // biggest child down.
        final Map<CardSlot, Integer> subtreeSize = new HashMap<>();
        final Map<CardSlot, Double> subtreeHeight = new HashMap<>();
        {
            final List<CardSlot> order = new ArrayList<>();
            final List<CardSlot> stack = new ArrayList<>();
            stack.add(root);
            while (!stack.isEmpty()) {
                final CardSlot slot = stack.remove(stack.size() - 1);
                order.add(slot);
                for (final CardSlot child : children.getOrDefault(slot, List.of())) {
                    stack.add(child);
                }
            }
            for (int i = order.size() - 1; i >= 0; i -= 1) {
                int size = 1;
                double height = order.get(i).card.height() + rowGap;
                for (final CardSlot child : children.getOrDefault(order.get(i), List.of())) {
                    size += subtreeSize.getOrDefault(child, 1);
                    height += subtreeHeight.getOrDefault(child, 0.0);
                }
                subtreeSize.put(order.get(i), size);
                subtreeHeight.put(order.get(i), height);
            }
        }
        final Set<CardSlot> onTrunk = new LinkedHashSet<>();
        onTrunk.add(root);
        {
            CardSlot slot = root;
            while (slot != null) {
                final List<CardSlot> kids = children.getOrDefault(slot, List.of());
                CardSlot next = null;
                for (final CardSlot child : kids) {
                    if (next == null || subtreeSize.getOrDefault(child, 1) > subtreeSize.getOrDefault(next, 1)) {
                        next = child;
                    }
                }
                if (next != null) {
                    onTrunk.add(next);
                }
                slot = next;
            }
        }
        for (final CardSlot slot : onTrunk) {
            slot.trunk = true;
        }

        // Which trunk-child subtree each off-trunk card hangs from, and how hard that branch holds onto the trunk.
        // Strongly coupled branches sit nearest the trunk; loose ones drift outward.
        final Map<CardSlot, CardSlot> branchRoot = new HashMap<>();
        for (final CardSlot trunkSlot : onTrunk) {
            for (final CardSlot child : children.getOrDefault(trunkSlot, List.of())) {
                if (onTrunk.contains(child)) {
                    continue;
                }
                final List<CardSlot> queue = new ArrayList<>();
                queue.add(child);
                branchRoot.put(child, child);
                for (int head = 0; head < queue.size(); head += 1) {
                    for (final CardSlot grand : children.getOrDefault(queue.get(head), List.of())) {
                        branchRoot.put(grand, child);
                        queue.add(grand);
                    }
                }
            }
        }
        final Map<CardSlot, Double> coupling = new HashMap<>();
        for (final WireLink link : links) {
            final boolean fromTrunk = onTrunk.contains(link.from);
            final boolean toTrunk = onTrunk.contains(link.to);
            if (fromTrunk == toTrunk) {
                continue;
            }
            final CardSlot branch = branchRoot.get(fromTrunk ? link.to : link.from);
            if (branch != null) {
                coupling.put(branch, coupling.getOrDefault(branch, 0.0) + link.weight);
            }
        }

        // The in-order walk. At a trunk card, side sections split above and below it, most coupled nearest the trunk;
        // elsewhere children keep claim order.
        int seqCounter = 0;
        int sectionCounter = 0;
        final List<Object[]> stack = new ArrayList<>();
        stack.add(new Object[] { root, 0, 0 });
        while (!stack.isEmpty()) {
            final Object[] frame = stack.remove(stack.size() - 1);
            final CardSlot slot = (CardSlot) frame[0];
            final int section = (Integer) frame[1];
            final int phase = (Integer) frame[2];
            if (phase == 1) {
                slot.seq = seqCounter;
                seqCounter += 1;
                slot.section = section;
                continue;
            }
            final List<CardSlot> kids = children.getOrDefault(slot, List.of());
            if (!onTrunk.contains(slot)) {
                // A section interior: the card, then its children in claim order, every one inheriting the section.
                slot.seq = seqCounter;
                seqCounter += 1;
                slot.section = section;
                for (int i = kids.size() - 1; i >= 0; i -= 1) {
                    stack.add(new Object[] { kids.get(i), section, 0 });
                }
                continue;
            }
            // A trunk card: the trunk continuation goes through the middle. BUDS (one-wire leaves) keep the trunk's
            // band beside their machine. Other branches become sections in coupling order, each dealt to whichever
            // side is currently shorter so the trunk stays vertically centred.
            CardSlot trunkChild = null;
            for (final CardSlot child : kids) {
                if (onTrunk.contains(child)) {
                    trunkChild = child;
                    break;
                }
            }
            final List<CardSlot> sides = new ArrayList<>();
            for (final CardSlot child : kids) if (child != trunkChild) sides.add(child);
            JsSort.sort(sides, 
                (a, b) -> JsMath.sign(
                    or(
                        coupling.getOrDefault(b, 0.0) - coupling.getOrDefault(a, 0.0),
                        or(subtreeSize.getOrDefault(b, 1) - subtreeSize.getOrDefault(a, 1), a.index - b.index))));
            final List<Object[]> above = new ArrayList<>();
            final List<Object[]> below = new ArrayList<>();
            double aboveHeight = 0;
            double belowHeight = 0;
            // A bud is a true one-wire leaf. A pass-through buffer can be a TREE leaf while carrying two wires; glued
            // here it would drag its other wire across the island, so it becomes a section instead.
            final List<CardSlot> buds = new ArrayList<>();
            final List<CardSlot> branches = new ArrayList<>();
            for (final CardSlot child : sides) {
                if (isLeafBud(subtreeSize, wireDegree, child)) buds.add(child);
            }
            for (final CardSlot child : sides) {
                if (!isLeafBud(subtreeSize, wireDegree, child)) branches.add(child);
            }
            // Buds first, so they hold the positions nearest the trunk card while the sections stack outward past
            // them.
            final List<CardSlot> dealt = new ArrayList<>(buds);
            dealt.addAll(branches);
            for (final CardSlot child : dealt) {
                final boolean isBud = isLeafBud(subtreeSize, wireDegree, child);
                int childSection = section;
                if (!isBud) {
                    sectionCounter += 1;
                    childSection = sectionCounter;
                }
                final double height = subtreeHeight.getOrDefault(child, 0.0);
                if (aboveHeight <= belowHeight) {
                    above.add(0, new Object[] { child, childSection });
                    aboveHeight += height;
                } else {
                    below.add(new Object[] { child, childSection });
                    belowHeight += height;
                }
            }
            // Pushed in reverse of the wanted visit order (it is a stack): below branches, then the trunk
            // continuation, then this card, then above.
            for (int i = below.size() - 1; i >= 0; i -= 1) {
                stack.add(new Object[] { below.get(i)[0], below.get(i)[1], 0 });
            }
            if (trunkChild != null) {
                stack.add(new Object[] { trunkChild, section, 0 });
            }
            stack.add(new Object[] { slot, section, 1 });
            for (int i = above.size() - 1; i >= 0; i -= 1) {
                stack.add(new Object[] { above.get(i)[0], above.get(i)[1], 0 });
            }
        }
    }

    private static void addPaired(final Map<CardSlot, Map<CardSlot, Double>> paired, final CardSlot a,
        final CardSlot b, final double weight) {
        Map<CardSlot, Double> row = paired.get(a);
        if (row == null) {
            row = new LinkedHashMap<>();
            paired.put(a, row);
        }
        final Double had = row.get(b);
        row.put(b, (had != null ? had : 0) + weight);
    }

    private static boolean isLeafBud(final Map<CardSlot, Integer> subtreeSize, final Map<CardSlot, Integer> wireDegree,
        final CardSlot child) {
        return subtreeSize.getOrDefault(child, 1) == 1 && wireDegree.getOrDefault(child, 0) <= 1;
    }

    static List<List<CardSlot>> collectLayers(final List<CardSlot> members) {
        int max = 0;
        for (final CardSlot slot : members) max = Math.max(max, slot.layer);
        final int count = max + 1;
        final List<List<CardSlot>> layers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) layers.add(new ArrayList<>());
        for (final CardSlot slot : members) {
            layers.get(slot.layer)
                .add(slot);
        }
        for (final List<CardSlot> layer : layers) {
            JsSort.sort(layer, (a, b) -> JsMath.sign(or(a.seq - b.seq, a.index - b.index)));
        }
        return layers;
    }

    static Map<CardSlot, List<PartnerEntry>> buildPartners(final List<WireLink> links,
        @Nullable final Map<CardSlot, List<PartnerEntry>> extra) {
        final Map<CardSlot, List<PartnerEntry>> partners = new HashMap<>();
        for (final WireLink link : links) {
            // The main line is the one wire run that must read ruler-straight, so a trunk-to-trunk wire pulls several
            // times harder than its flow alone.
            final double emphasis = link.from.trunk && link.to.trunk ? 3 : 1;
            push(partners, link.from, new PartnerEntry(link.to, link.fromAnchor, link.toAnchor, link.weight * emphasis));
            push(partners, link.to, new PartnerEntry(link.from, link.toAnchor, link.fromAnchor, link.weight * emphasis));
        }
        // Phantom partners: fixed anchors (bridge pulls toward another island).
        if (extra != null) {
            for (final Map.Entry<CardSlot, List<PartnerEntry>> e : extra.entrySet()) {
                for (final PartnerEntry entry : e.getValue()) {
                    push(partners, e.getKey(), entry);
                }
            }
        }
        return partners;
    }

    /** Where a card's wires would put it, port to port, weighted by flow: {wish, weight}. */
    static double[] wishFor(final CardSlot slot, @Nullable final List<PartnerEntry> list) {
        if (list == null || list.isEmpty()) {
            return new double[] { slot.y, 0.1 };
        }
        double sum = 0;
        double total = 0;
        for (final PartnerEntry p : list) {
            sum += (p.other().y + p.their() - p.own()) * p.weight();
            total += p.weight();
        }
        return new double[] { sum / total, total };
    }

    /**
     * The anti-crossing pass, at two levels: within one column every SECTION moves as one block to its members' mean
     * pull, and members reorder inside their block by their own pull, so a band can move but never split.
     */
    static void polishColumnOrder(final List<List<CardSlot>> layers, final List<WireLink> links,
        @Nullable final Map<CardSlot, List<PartnerEntry>> extra) {
        final Map<CardSlot, List<PartnerEntry>> partners = buildPartners(links, extra);
        for (final List<CardSlot> layer : layers) {
            if (layer.size() < 2) {
                continue;
            }
            final Map<CardSlot, double[]> wish = new HashMap<>();
            for (final CardSlot slot : layer) {
                wish.put(slot, wishFor(slot, partners.get(slot)));
            }
            final Map<Integer, List<CardSlot>> groups = new LinkedHashMap<>();
            for (final CardSlot slot : layer) {
                push(groups, slot.section, slot);
            }
            final List<List<CardSlot>> blockCards = new ArrayList<>();
            final List<double[]> blockKeys = new ArrayList<>();
            for (final List<CardSlot> cardsInGroup : groups.values()) {
                JsSort.sort(cardsInGroup, (a, b) -> JsMath.sign(or(wish.get(a)[0] - wish.get(b)[0], a.seq - b.seq)));
                double sum = 0;
                double weight = 0;
                for (final CardSlot slot : cardsInGroup) {
                    sum += wish.get(slot)[0] * wish.get(slot)[1];
                    weight += wish.get(slot)[1];
                }
                double minSeq = Double.POSITIVE_INFINITY;
                for (final CardSlot slot : cardsInGroup) minSeq = Math.min(minSeq, slot.seq);
                blockCards.add(cardsInGroup);
                blockKeys.add(new double[] { weight > 0 ? sum / weight : 0, minSeq, blockCards.size() - 1 });
            }
            JsSort.sort(blockKeys, (a, b) -> JsMath.sign(or(a[0] - b[0], a[1] - b[1])));
            final List<Double> seqs = new ArrayList<>();
            for (final CardSlot slot : layer) seqs.add(slot.seq);
            JsSort.sort(seqs, (a, b) -> JsMath.sign(a - b));
            int next = 0;
            for (final double[] key : blockKeys) {
                for (final CardSlot slot : blockCards.get((int) key[2])) {
                    slot.seq = seqs.get(next);
                    next += 1;
                }
            }
        }
    }

    /**
     * The vertical pass: give every card a y that lines its ports up with its wire partners' ports, with no overlap in
     * a column and section air kept. Each sweep settles each column exactly (isotonic regression, see settleLine).
     */
    void placeRows(final List<List<CardSlot>> layers, final List<WireLink> links,
        @Nullable final Map<CardSlot, List<PartnerEntry>> extra) {
        final Map<CardSlot, List<PartnerEntry>> partners = buildPartners(links, extra);

        // First stacking: straight down in order, so every wish below starts from a legal picture.
        for (final List<CardSlot> layer : layers) {
            double y = 0;
            CardSlot previous = null;
            for (final CardSlot slot : layer) {
                if (previous != null) {
                    y += gapBetween(previous, slot);
                }
                slot.y = y;
                y += slot.card.height();
                previous = slot;
            }
        }

        for (int sweep = 0; sweep < 8; sweep += 1) {
            final boolean downward = sweep % 2 == 0;
            for (int i = 0; i < layers.size(); i += 1) {
                final List<CardSlot> layer = layers.get(downward ? i : layers.size() - 1 - i);
                final List<double[]> wishes = new ArrayList<>(layer.size());
                for (final CardSlot slot : layer) wishes.add(wishFor(slot, partners.get(slot)));
                settleColumn(layer, wishes);
            }
        }

        // Pull the whole island up so its top card sits at zero.
        double top = Double.POSITIVE_INFINITY;
        for (final List<CardSlot> layer : layers) {
            for (final CardSlot slot : layer) {
                top = Math.min(top, slot.y);
            }
        }
        for (final List<CardSlot> layer : layers) {
            for (final CardSlot slot : layer) {
                slot.y -= top;
            }
        }
    }

    /** The air owed between two vertically adjacent cards in one column. */
    double gapBetween(final CardSlot upper, final CardSlot lower) {
        return upper.section == lower.section ? rowGap : sectionGap;
    }

    /**
     * Snap every card to the grid, then onto the exact line of its heaviest wire when that is a nudge of two cells or
     * less and its column neighbours keep their air.
     */
    void straightenRows(final List<List<CardSlot>> layers, final List<WireLink> links,
        @Nullable final Map<CardSlot, List<PartnerEntry>> extra) {
        final Map<CardSlot, List<PartnerEntry>> partners = buildPartners(links, extra);
        for (final List<CardSlot> layer : layers) {
            for (final CardSlot slot : layer) {
                slot.y = snapToGrid(slot.y);
            }
        }
        for (int sweep = 0; sweep < 2; sweep += 1) {
            final boolean leftToRight = sweep % 2 == 0;
            for (int i = 0; i < layers.size(); i += 1) {
                final List<CardSlot> layer = layers.get(leftToRight ? i : layers.size() - 1 - i);
                for (int row = 0; row < layer.size(); row++) {
                    final CardSlot slot = layer.get(row);
                    double bestY = 0;
                    double bestWeight = 0;
                    boolean found = false;
                    for (final PartnerEntry p : partners.getOrDefault(slot, List.of())) {
                        final double aligned = p.other().y + p.their() - p.own();
                        if (com.gtnhplanner.core.base.JsMath.rem(aligned, BOARD_GRID) != 0) {
                            continue;
                        }
                        final double distance = Math.abs(aligned - slot.y);
                        if (distance == 0 || distance > cells(2)) {
                            continue;
                        }
                        if (!found || p.weight() > bestWeight) {
                            found = true;
                            bestY = aligned;
                            bestWeight = p.weight();
                        }
                    }
                    if (!found) {
                        continue;
                    }
                    final CardSlot previous = row > 0 ? layer.get(row - 1) : null;
                    final CardSlot next = row + 1 < layer.size() ? layer.get(row + 1) : null;
                    final double lowest = previous != null
                        ? previous.y + previous.card.height() + gapBetween(previous, slot)
                        : Double.NEGATIVE_INFINITY;
                    final double highest = next != null ? next.y - slot.card.height() - gapBetween(slot, next)
                        : Double.POSITIVE_INFINITY;
                    if (bestY >= lowest && bestY <= highest) {
                        slot.y = bestY;
                    }
                }
            }
        }
    }

    // region transposeToUncross

    /** Columns only exist later; for crossing tests each layer is a vertical line. */
    private static final double X_SPAN = 10000;

    private static double pointX(final WireLink link, final boolean from) {
        final CardSlot slot = from ? link.from : link.to;
        return slot.layer * X_SPAN + (from ? X_SPAN - 100 : 100);
    }

    private static double pointY(final WireLink link, final boolean from, final Map<CardSlot, Double> override) {
        final CardSlot slot = from ? link.from : link.to;
        final double anchor = from ? link.fromAnchor : link.toAnchor;
        final Double o = override.get(slot);
        return (o != null ? o : slot.y) + anchor;
    }

    private static double turn(final double px, final double py, final double qx, final double qy, final double rx,
        final double ry) {
        return (qx - px) * (ry - py) - (qy - py) * (rx - px);
    }

    private static boolean crossed(final WireLink first, final WireLink second, final Map<CardSlot, Double> override) {
        if (first.from == second.from || first.from == second.to
            || first.to == second.from
            || first.to == second.to) {
            return false;
        }
        final double a1x = pointX(first, true), a1y = pointY(first, true, override);
        final double a2x = pointX(first, false), a2y = pointY(first, false, override);
        final double b1x = pointX(second, true), b1y = pointY(second, true, override);
        final double b2x = pointX(second, false), b2y = pointY(second, false, override);
        final double d1 = turn(b1x, b1y, b2x, b2y, a1x, a1y);
        final double d2 = turn(b1x, b1y, b2x, b2y, a2x, a2y);
        final double d3 = turn(a1x, a1y, a2x, a2y, b1x, b1y);
        final double d4 = turn(a1x, a1y, a2x, a2y, b2x, b2y);
        return (d1 > 0 && d2 < 0 || d1 < 0 && d2 > 0) && (d3 > 0 && d4 < 0 || d3 < 0 && d4 > 0);
    }

    private static int countCrossings(final List<WireLink> involved, final List<WireLink> spanning,
        final Map<CardSlot, Double> override) {
        int total = 0;
        for (int a = 0; a < involved.size(); a += 1) {
            for (final WireLink other : spanning) {
                final int b = involved.indexOf(other);
                if (b >= 0 && b <= a) {
                    continue;
                }
                if (crossed(involved.get(a), other, override)) {
                    total += 1;
                }
            }
        }
        return total;
    }

    /**
     * Adjacent-pair transposition against the real wires: for every pair of vertical neighbours, count their wires'
     * crossings as placed and as swapped, and keep strictly better swaps. Returns whether any flip happened.
     */
    static boolean transposeToUncross(final List<List<CardSlot>> layers, final List<WireLink> links) {
        // Same-column links count too: a stacked pair's wrap-around wires cost nothing while stacked and cross
        // everything between them once a swap separates them, which keeps stacked pairs together.
        final List<WireLink> spanning = links;
        final Map<CardSlot, List<WireLink>> linksOf = new HashMap<>();
        for (final WireLink link : spanning) {
            push(linksOf, link.from, link);
            push(linksOf, link.to, link);
        }
        final Map<CardSlot, Double> still = new HashMap<>();
        boolean flippedAny = false;
        for (final List<CardSlot> layer : layers) {
            for (int i = 0; i + 1 < layer.size(); i += 1) {
                final CardSlot upper = layer.get(i);
                final CardSlot lower = layer.get(i + 1);
                final Set<WireLink> involvedSet = new LinkedHashSet<>(linksOf.getOrDefault(upper, List.of()));
                involvedSet.addAll(linksOf.getOrDefault(lower, List.of()));
                final List<WireLink> involved = new ArrayList<>(involvedSet);
                if (involved.isEmpty()) {
                    continue;
                }
                // The swap trades their centres; exact stacking is the settle's job.
                final double upperCentre = upper.y + upper.card.height() / 2;
                final double lowerCentre = lower.y + lower.card.height() / 2;
                final Map<CardSlot, Double> swapped = new HashMap<>();
                swapped.put(upper, lowerCentre - upper.card.height() / 2);
                swapped.put(lower, upperCentre - lower.card.height() / 2);
                if (countCrossings(involved, spanning, swapped) < countCrossings(involved, spanning, still)) {
                    final double seq = upper.seq;
                    upper.seq = lower.seq;
                    lower.seq = seq;
                    layer.set(i, lower);
                    layer.set(i + 1, upper);
                    upper.y = swapped.get(upper);
                    lower.y = swapped.get(lower);
                    flippedAny = true;
                }
            }
        }
        return flippedAny;
    }

    // endregion

    /** The card-column settle: order and section-aware gaps, solved exactly. */
    void settleColumn(final List<CardSlot> layer, final List<double[]> wishes) {
        if (layer.isEmpty()) {
            return;
        }
        final double[] spacing = new double[layer.size()];
        for (int i = 0; i < layer.size(); i++) {
            spacing[i] = i == 0 ? 0
                : layer.get(i - 1).card.height() + gapBetween(layer.get(i - 1), layer.get(i));
        }
        final double[] ys = settleLine(wishes, spacing);
        for (int i = 0; i < layer.size(); i++) {
            layer.get(i).y = ys[i];
        }
    }

    /**
     * Weighted isotonic regression with fixed spacings (pool adjacent violators): place a line of items as close to
     * their wishes as their order and least distances allow, exactly, in linear time. {@code spacing[i]} is the least
     * distance from item i-1's top to item i's top; spacing[0] is ignored.
     */
    static double[] settleLine(final List<double[]> wishes, final double[] spacing) {
        // Substitute out the spacings: z_i = y_i - start_i must merely be non-decreasing.
        final double[] starts = new double[wishes.size()];
        double acc = 0;
        for (int i = 0; i < wishes.size(); i += 1) {
            if (i > 0) {
                acc += spacing[i];
            }
            starts[i] = acc;
        }
        final double[] poolMean = new double[wishes.size()];
        final double[] poolWeight = new double[wishes.size()];
        final int[] poolCount = new int[wishes.size()];
        int pools = 0;
        for (int i = 0; i < wishes.size(); i++) {
            final double[] entry = wishes.get(i);
            double mean = entry[0] - starts[i];
            double weight = entry[1];
            int count = 1;
            while (pools > 0 && poolMean[pools - 1] >= mean) {
                pools -= 1;
                mean = (poolMean[pools] * poolWeight[pools] + mean * weight) / (poolWeight[pools] + weight);
                weight += poolWeight[pools];
                count += poolCount[pools];
            }
            poolMean[pools] = mean;
            poolWeight[pools] = weight;
            poolCount[pools] = count;
            pools += 1;
        }
        final double[] ys = new double[wishes.size()];
        int index = 0;
        for (int p = 0; p < pools; p++) {
            for (int i = 0; i < poolCount[p]; i += 1) {
                ys[index] = poolMean[p] + starts[index];
                index += 1;
            }
        }
        return ys;
    }

    // endregion

    // region Islands

    /** An island-graph edge: net flow between two islands. */
    private static final class IslandEdge {

        final int from;
        final int to;
        double weight;

        IslandEdge(final int from, final int to, final double weight) {
            this.from = from;
            this.to = to;
            this.weight = weight;
        }
    }

    /**
     * Which island stands upstream of which: net flow per pair picks the direction, a DFS drops ring-closing edges,
     * longest path deals columns, and two slide passes pull each island toward its heavier side.
     */
    static Map<Integer, Integer> islandFlowLayers(final List<Integer> indices, final List<BridgeLink> edges) {
        final Map<Integer, Integer> layerOf = new LinkedHashMap<>();
        for (final int index : indices) layerOf.put(index, 0);
        if (edges.isEmpty()) {
            return layerOf;
        }
        final Map<String, IslandEdge> net = new LinkedHashMap<>();
        for (final BridgeLink edge : edges) {
            final String key = Math.min(edge.from(), edge.to()) + ":" + Math.max(edge.from(), edge.to());
            final IslandEdge entry = net.get(key);
            if (entry == null) {
                net.put(key, new IslandEdge(edge.from(), edge.to(), edge.weight()));
            } else {
                entry.weight += entry.from == edge.from() ? edge.weight() : -edge.weight();
            }
        }
        final List<IslandEdge> forward = new ArrayList<>();
        for (final IslandEdge entry : net.values()) {
            forward.add(entry.weight >= 0 ? entry : new IslandEdge(entry.to, entry.from, -entry.weight));
        }
        JsSort.sort(forward, (a, b) -> a.from - b.from != 0 ? a.from - b.from : a.to - b.to);

        final Map<Integer, List<IslandEdge>> outgoing = new HashMap<>();
        for (final IslandEdge edge : forward) {
            push(outgoing, edge.from, edge);
        }
        final int visiting = 1;
        final int done = 2;
        final Map<Integer, Integer> state = new HashMap<>();
        final List<IslandEdge> kept = new ArrayList<>();
        for (final int start : indices) {
            if (state.containsKey(start)) {
                continue;
            }
            final List<Integer> stackIsland = new ArrayList<>();
            final List<int[]> stackNext = new ArrayList<>();
            stackIsland.add(start);
            stackNext.add(new int[] { 0 });
            state.put(start, visiting);
            while (!stackIsland.isEmpty()) {
                final int frameIsland = stackIsland.get(stackIsland.size() - 1);
                final int[] frameNext = stackNext.get(stackNext.size() - 1);
                final List<IslandEdge> list = outgoing.getOrDefault(frameIsland, List.of());
                if (frameNext[0] >= list.size()) {
                    state.put(frameIsland, done);
                    stackIsland.remove(stackIsland.size() - 1);
                    stackNext.remove(stackNext.size() - 1);
                    continue;
                }
                final IslandEdge edge = list.get(frameNext[0]);
                frameNext[0] += 1;
                final Integer seen = state.get(edge.to);
                if (seen == null) {
                    state.put(edge.to, visiting);
                    kept.add(edge);
                    stackIsland.add(edge.to);
                    stackNext.add(new int[] { 0 });
                } else if (seen == done) {
                    kept.add(edge);
                }
            }
        }

        {
            final Map<Integer, Integer> indegree = new HashMap<>();
            for (final int index : indices) indegree.put(index, 0);
            for (final IslandEdge edge : kept) {
                indegree.put(edge.to, indegree.getOrDefault(edge.to, 0) + 1);
            }
            final List<Integer> queue = new ArrayList<>();
            for (final int index : indices) if (indegree.getOrDefault(index, 0) == 0) queue.add(index);
            for (int head = 0; head < queue.size(); head += 1) {
                for (final IslandEdge edge : kept) {
                    if (edge.from != queue.get(head)) {
                        continue;
                    }
                    layerOf.put(edge.to, Math.max(layerOf.get(edge.to), layerOf.get(edge.from) + 1));
                    final int remaining = indegree.getOrDefault(edge.to, 0) - 1;
                    indegree.put(edge.to, remaining);
                    if (remaining == 0) {
                        queue.add(edge.to);
                    }
                }
            }
        }
        for (int pass = 0; pass < 2; pass += 1) {
            for (final int island : indices) {
                double lower = 0;
                double upper = Double.POSITIVE_INFINITY;
                double inWeight = 0;
                double outWeight = 0;
                for (final IslandEdge edge : kept) {
                    if (edge.to == island) {
                        lower = Math.max(lower, layerOf.get(edge.from) + 1);
                        inWeight += edge.weight;
                    }
                    if (edge.from == island) {
                        upper = Math.min(upper, layerOf.get(edge.to) - 1);
                        outWeight += edge.weight;
                    }
                }
                if (upper == Double.POSITIVE_INFINITY) {
                    upper = layerOf.get(island);
                }
                if (upper < lower) {
                    continue;
                }
                if (outWeight > inWeight) {
                    layerOf.put(island, (int) upper);
                } else if (inWeight > outWeight) {
                    layerOf.put(island, (int) lower);
                } else if (inWeight > 0 && outWeight > 0) {
                    layerOf.put(island, (int) JsMath.round((lower + upper) / 2));
                }
            }
        }
        return layerOf;
    }

    /**
     * Where each island stands: the blocks run through the same column engine as meta-cards (block sizes, bridge
     * endpoints as ports). Islands with no bridges pack in rows below; the shelf of strays keeps the last row. Returns
     * one offset per block.
     */
    List<Placement> placeIslands(final List<Block> blocks, final List<BridgeLink> bridges,
        final Map<String, Placement> localPlace) {
        final List<Placement> offsets = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) offsets.add(new Placement(0, 0));
        final List<CardSlot> metaSlots = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            final Block block = blocks.get(index);
            metaSlots.add(new CardSlot(new ArrangeCard("#" + index, 0, 0, block.width, block.height, block.plain),
                index));
        }
        final List<WireLink> metaLinks = new ArrayList<>();
        for (final BridgeLink bridge : bridges) {
            metaLinks.add(
                new WireLink(
                    null,
                    metaSlots.get(bridge.from()),
                    metaSlots.get(bridge.to()),
                    localPlace.get(bridge.link().from.card.id()).y + bridge.link().fromAnchor,
                    localPlace.get(bridge.link().to.card.id()).y + bridge.link().toAnchor,
                    bridge.weight(),
                    null));
        }

        return atIslandScale(() -> {
            final Map<String, CardSlot> slotById = new LinkedHashMap<>();
            for (final CardSlot slot : metaSlots) slotById.put(slot.card.id(), slot);
            final Map<CardSlot, SatellitePlan> plans = planSatellites(slotById, metaLinks);
            final List<WireLink> mainLinks = new ArrayList<>();
            for (final WireLink link : metaLinks) {
                if (!plans.containsKey(link.from) && !plans.containsKey(link.to)) mainLinks.add(link);
            }
            final Set<CardSlot> anchors = new HashSet<>();
            for (final SatellitePlan plan : plans.values()) anchors.add(plan.anchor());
            final Set<CardSlot> linked = new HashSet<>();
            for (final WireLink link : mainLinks) {
                linked.add(link.from);
                linked.add(link.to);
            }

            final IntUnaryOperator componentOf = unionComponents(metaSlots.size(), mainLinks);
            final Map<Integer, List<CardSlot>> componentSlots = new LinkedHashMap<>();
            final List<Integer> loose = new ArrayList<>();
            for (final CardSlot slot : metaSlots) {
                if (plans.containsKey(slot) || blocks.get(slot.index).shelf) {
                    continue;
                }
                if (!linked.contains(slot) && !anchors.contains(slot)) {
                    loose.add(slot.index);
                    continue;
                }
                final int root = componentOf.applyAsInt(slot.index);
                final List<CardSlot> members = componentSlots.get(root);
                if (members != null) {
                    members.add(slot);
                } else {
                    final List<CardSlot> fresh = new ArrayList<>();
                    fresh.add(slot);
                    componentSlots.put(root, fresh);
                }
            }
            final List<Block> metaBlocks = new ArrayList<>();
            for (final List<CardSlot> members : componentSlots.values()) {
                JsSort.sort(members, (a, b) -> a.index - b.index);
                final Set<CardSlot> memberSet = new HashSet<>(members);
                final List<WireLink> componentLinks = new ArrayList<>();
                for (final WireLink link : mainLinks) {
                    if (memberSet.contains(link.from) && memberSet.contains(link.to)) componentLinks.add(link);
                }
                final Map<CardSlot, SatellitePlan> componentSatellites = new LinkedHashMap<>();
                for (final Map.Entry<CardSlot, SatellitePlan> e : plans.entrySet()) {
                    if (memberSet.contains(
                        e.getValue()
                            .anchor())) {
                        componentSatellites.put(e.getKey(), e.getValue());
                    }
                }
                metaBlocks.add(
                    layoutIsland(members, componentLinks, componentSatellites, new LinkedHashMap<>(), null, null, null));
            }
            JsSort.sort(metaBlocks, (a, b) -> JsMath.sign(or(b.size - a.size, a.minIndex - b.minIndex)));

            // The trading constellations stack first, then the islands wired to nothing in rows, then the shelf.
            double cursorY = 0;
            double width = 0;
            for (final Block metaBlock : metaBlocks) {
                for (int i = 0; i < metaBlock.ids.size(); i++) {
                    offsets.set(
                        Integer.parseInt(
                            metaBlock.ids.get(i)
                                .substring(1)),
                        new Placement(metaBlock.places.get(i).x, cursorY + metaBlock.places.get(i).y));
                }
                width = Math.max(width, metaBlock.width);
                cursorY += metaBlock.height + islandGap;
            }
            final double rowTarget = Math.max(width, cells(120));
            double rowX = 0;
            double rowHeight = 0;
            for (final int index : loose) {
                if (rowX > 0 && rowX + blocks.get(index).width > rowTarget) {
                    rowX = 0;
                    cursorY += rowHeight + islandGap;
                    rowHeight = 0;
                }
                offsets.set(index, new Placement(rowX, cursorY));
                rowX += blocks.get(index).width + islandGap;
                rowHeight = Math.max(rowHeight, blocks.get(index).height);
            }
            if (rowHeight > 0) {
                cursorY += rowHeight + islandGap;
            }
            for (int index = 0; index < blocks.size(); index++) {
                if (blocks.get(index).shelf) {
                    offsets.set(index, new Placement(0, cursorY));
                    cursorY += blocks.get(index).height + islandGap;
                }
            }

            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            for (final Placement offset : offsets) {
                minX = Math.min(minX, offset.x);
                minY = Math.min(minY, offset.y);
            }
            for (final Placement offset : offsets) {
                offset.x -= minX;
                offset.y -= minY;
            }
            return offsets;
        });
    }

    // endregion

    // region The shelf: cards wired to nothing, parked in tidy rows

    Block layoutShelf(final List<CardSlot> parked, final double mainWidth) {
        // Sort by footprint so alike cards sit together, then by input order so the shelf is stable.
        final List<CardSlot> sorted = new ArrayList<>(parked);
        JsSort.sort(sorted, 
            (a, b) -> JsMath.sign(
                or(b.card.height() - a.card.height(), or(b.card.width() - a.card.width(), a.index - b.index))));
        final double targetWidth = Math.max(mainWidth, cells(60));
        final List<String> ids = new ArrayList<>();
        final List<Placement> places = new ArrayList<>();
        double x = 0;
        double y = 0;
        double rowHeight = 0;
        double width = 0;
        double height = 0;
        for (final CardSlot slot : sorted) {
            if (x > 0 && x + slot.card.width() > targetWidth) {
                x = 0;
                y += rowHeight + SHELF_GAP;
                rowHeight = 0;
            }
            ids.add(slot.card.id());
            places.add(new Placement(x, y));
            width = Math.max(width, x + slot.card.width());
            height = Math.max(height, y + slot.card.height());
            rowHeight = Math.max(rowHeight, slot.card.height());
            x += slot.card.width() + SHELF_GAP;
        }
        double minIndex = Double.POSITIVE_INFINITY;
        for (final CardSlot slot : sorted) minIndex = Math.min(minIndex, slot.index);
        final Block block = new Block();
        block.ids = ids;
        block.places = places;
        block.width = width;
        block.height = height;
        block.size = sorted.size();
        block.minIndex = minIndex;
        block.shelf = true;
        return block;
    }

    // endregion
}
