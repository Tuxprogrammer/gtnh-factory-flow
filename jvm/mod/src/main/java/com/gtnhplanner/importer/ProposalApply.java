package com.gtnhplanner.importer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Group;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Node;

/**
 * What the planner site's assistant proposes to add to a plan (a proposal, read from {@code /api/deliveries/p...}),
 * added to the open plan. Its new cards, drawers and the wires between them come in through the website-card import,
 * so recipes are found in the game as for a pasted plan; its wires to the plan's own cards and drawers are matched by
 * the ids the plan's snapshot gave them (their board ids); its target rates land on ports that have none. Adds only:
 * nothing the plan has is removed, moved or changed, so the whole of it is one undo step that puts the plan back
 * exactly.
 *
 * <p>
 * Two steps, so a proposal that does not read leaves no empty undo step: {@link #prepare} converts (NEI on the client
 * thread in game), {@link Prepared#addTo} changes the plan and is what runs inside the board's undoable edit.
 */
public final class ProposalApply {

    /** Room left between the plan's cards and the new ones when the proposal's places would put them on top. */
    static final int GAP = 100;
    /** A card's size for the overlap test: its width, and a height most cards stay under. */
    static final int CARD_W = com.gtnhplanner.ui.card.CardLayout.W, CARD_H = 240;

    private ProposalApply() {}

    /** A target rate the proposal sets on one of the plan's own cards. */
    record Target(String card, String port, double rate) {}

    /** A wire the proposal adds between one of its cards and one of the plan's own. */
    record Cross(String from, String to, @Nullable String fromPort, @Nullable String toPort, String kind,
        String resource) {}

    /**
     * What was added, and what was left out and why.
     *
     * @param added the board ids of the cards and drawers that came in, for the board to show
     */
    public record Outcome(int cards, int drawers, int wires, int targets, int cardsLeftOut, int wiresGone,
        int wiresNoPort, int targetsKept, int targetsNoPort, List<Uuid> added) {

        /** "Added 3 cards and 4 wires to Platline.", with a sentence for each kind of thing left out. */
        public String message(final String planName) {
            final List<String> parts = new ArrayList<>();
            if (cards > 0) parts.add(count(cards, "card"));
            if (drawers > 0) parts.add(count(drawers, "drawer"));
            if (wires > 0) parts.add(count(wires, "wire"));
            if (targets > 0) parts.add(count(targets, "target"));
            final StringBuilder out = new StringBuilder(
                parts.isEmpty() ? "Nothing was added to " + planName + "."
                    : "Added " + join(parts) + " to " + planName + ".");
            if (cardsLeftOut > 0) out.append(" ")
                .append(count(cardsLeftOut, "card"))
                .append(" could not be made in this game.");
            if (wiresGone > 0) out.append(" ")
                .append(count(wiresGone, "wire"))
                .append(" left out: a card it joins is gone.");
            if (wiresNoPort > 0) out.append(" ")
                .append(count(wiresNoPort, "wire"))
                .append(" left out: no port takes it.");
            if (targetsKept > 0) out.append(" ")
                .append(count(targetsKept, "target"))
                .append(" kept as you set ")
                .append(targetsKept == 1 ? "it." : "them.");
            if (targetsNoPort > 0) out.append(" ")
                .append(count(targetsNoPort, "target"))
                .append(" left out: its card is gone or has no such port.");
            return out.toString();
        }

        private static String count(final int n, final String thing) {
            return n + " " + thing + (n == 1 ? "" : "s");
        }

        private static String join(final List<String> parts) {
            if (parts.size() == 1) return parts.getFirst();
            return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
        }
    }

    /** A proposal converted against the game's recipes, not yet on any plan. */
    public static final class Prepared {

        private final String basePlanId;
        private final Graph incoming;
        /** The proposal's card and drawer ids to what they became on {@link #incoming}. */
        private final Map<String, Uuid> made;
        /** Where the proposal puts each of its cards and drawers. */
        private final Map<String, int[]> places;
        private final List<Cross> crosses;
        private final List<Target> targets;
        private final int cardsLeftOut;

        Prepared(final String basePlanId, final Graph incoming, final Map<String, Uuid> made,
            final Map<String, int[]> places, final List<Cross> crosses, final List<Target> targets,
            final int cardsLeftOut) {
            this.basePlanId = basePlanId;
            this.incoming = incoming;
            this.made = made;
            this.places = places;
            this.crosses = crosses;
            this.targets = targets;
            this.cardsLeftOut = cardsLeftOut;
        }

        /** The id of the plan the proposal was made from: it goes only on the plan with this id. */
        public String basePlanId() {
            return basePlanId;
        }

        /** Whether this goes on {@code plan}: the plan's id is the one the proposal was made from. */
        public boolean fits(final Graph plan) {
            return plan.isPlan(basePlanId);
        }

        /**
         * Adds everything to {@code plan}: the new cards and drawers where the proposal puts them (moved right, clear
         * of the plan's cards, when that would put them on top), their wires, the wires to the plan's own cards, and
         * the target rates on ports that have none. Call once, inside one undoable edit.
         *
         * @param ports what the ports of the plan's cards and the new ones hold
         */
        public Outcome addTo(final Graph plan, final FfCards.Ports ports) {
            final int[] shift = shift(plan);
            final List<Uuid> added = new ArrayList<>();
            for (final Node n : incoming.getNodes()) {
                n.x += shift[0];
                n.y += shift[1];
                plan.addNode(n);
                added.add(n.id);
            }
            for (final Group g : incoming.groups.values()) {
                g.setX(g.getX() + shift[0]);
                g.setY(g.getY() + shift[1]);
                plan.groups.put(g.getId(), g);
            }
            for (final Drawer d : incoming.getDrawers()) {
                d.setX(d.getX() + shift[0]);
                d.setY(d.getY() + shift[1]);
                plan.addDrawer(d);
                added.add(d.getId());
            }
            int wires = 0;
            for (final Edge e : incoming.getEdges()) {
                final List<int[]> stops = new ArrayList<>();
                for (final int[] p : e.waypoints) stops.add(new int[] { p[0] + shift[0], p[1] + shift[1] });
                e.waypoints = stops;
                plan.addEdge(e);
                wires++;
            }
            int gone = 0, noPort = 0;
            for (final Cross c : crosses) {
                final Node from = node(plan, c.from(), c.fromPort()), to = node(plan, c.to(), c.toPort());
                // A wire with a drawer at one end is that drawer holding the card's port, as the website reads it.
                final Drawer toDrawer = from != null && to == null ? drawer(plan, c.to()) : null,
                    fromDrawer = from == null && to != null ? drawer(plan, c.from()) : null;
                if (toDrawer != null || fromDrawer != null) {
                    final boolean input = fromDrawer != null;
                    final Node card = input ? to : from;
                    final FfHandle h = FfHandle.parse(input ? c.toPort() : c.fromPort());
                    final int at = ports.find(
                        card,
                        !input,
                        h != null ? h.kind() : c.kind(),
                        List.of(h != null ? h.resourceId() : c.resource()));
                    if (at >= 0 && plan
                        .linkDrawerPort((input ? fromDrawer : toDrawer).getId(), new Drawer.Link(card.id, at), input))
                        wires++;
                    else noPort++;
                    continue;
                }
                if (from == null || to == null) {
                    gone++;
                    continue;
                }
                final FfHandle fh = FfHandle.parse(c.fromPort()), th = FfHandle.parse(c.toPort());
                final int out = ports.find(
                    from,
                    true,
                    fh != null ? fh.kind() : c.kind(),
                    List.of(fh != null ? fh.resourceId() : c.resource()));
                int in = out < 0 ? -1
                    : ports.find(
                        to,
                        false,
                        th != null ? th.kind() : c.kind(),
                        List.of(th != null ? th.resourceId() : c.resource()));
                if (out >= 0 && in < 0) in = plan.findCompatibleInput(from, out, to);
                if (out < 0 || in < 0) {
                    noPort++;
                    continue;
                }
                plan.addEdge(new Edge(Uuid.randomUUID(), from.id, to.id, out, in));
                wires++;
            }
            int set = 0, kept = 0, lost = 0;
            for (final Node n : incoming.getNodes()) set += n.targetOutputRates.size() + n.targetInputRates.size();
            for (final Target t : targets) {
                final Node n = node(plan, t.card(), t.port());
                final FfHandle h = FfHandle.parse(t.port());
                final int at = n == null || h == null ? -1
                    : ports.find(n, h.output(), h.kind(), List.of(h.resourceId()));
                if (at < 0) {
                    lost++;
                    continue;
                }
                final Map<Integer, Double> rates = h.output() ? n.targetOutputRates : n.targetInputRates;
                if (rates.containsKey(at)) {
                    kept++;
                    continue;
                }
                rates.put(at, t.rate());
                set++;
            }
            int cards = 0;
            for (final Map.Entry<String, Uuid> e : made.entrySet())
                if (incoming.nodes.containsKey(e.getValue())) cards++;
            return new Outcome(
                cards,
                incoming.drawers.size(),
                wires,
                set,
                cardsLeftOut,
                gone,
                noPort,
                kept,
                lost,
                added);
        }

        /** A wire end: one of the proposal's cards as it came in, else one of the plan's own by its board id. */
        @Nullable
        private Node node(final Graph plan, final String id) {
            final Uuid made = this.made.get(id);
            if (made != null) return plan.nodes.get(made);
            final Uuid own = uuid(id);
            return own == null ? null : plan.nodes.get(own);
        }

        /**
         * A card end with its port's handle: a handle on recipe n of a shared machine ({@code r<n>:}) names that
         * recipe's card, numbered as the snapshot and the export number them (the machine's recipes still on the
         * board, the card named first); else the card itself.
         */
        @Nullable
        private Node node(final Graph plan, final String id, @Nullable final String handle) {
            final Node card = node(plan, id);
            final int section = FfHandle.sectionOf(handle);
            if (card == null || section == 0) return card;
            for (final Group g : plan.getGroups()) {
                if (!(g instanceof final MachineGroup m) || !m.isShared()) continue;
                final List<Uuid> recipes = new ArrayList<>();
                for (final Uuid s : m.getSections()) if (plan.nodes.containsKey(s)) recipes.add(s);
                if (recipes.size() > 1 && recipes.get(0)
                    .equals(card.id)) return section < recipes.size() ? plan.nodes.get(recipes.get(section)) : null;
            }
            return null;
        }

        /** A wire end: one of the proposal's drawers as it came in, else one of the plan's own by its board id. */
        @Nullable
        private Drawer drawer(final Graph plan, final String id) {
            final Uuid made = this.made.get(id);
            if (made != null) return plan.drawers.get(made);
            final Uuid own = uuid(id);
            return own == null ? null : plan.drawers.get(own);
        }

        /**
         * How far to move what came in: the import moves a plan to the board's corner, so first back to where the
         * proposal puts its first card; then, when that lies on the plan's cards, right past the plan's last card.
         */
        private int[] shift(final Graph plan) {
            int dx = 0, dy = 0;
            for (final Map.Entry<String, int[]> e : places.entrySet()) {
                final Uuid id = made.get(e.getKey());
                final Node n = id == null ? null : incoming.nodes.get(id);
                final Drawer d = id == null ? null : incoming.drawers.get(id);
                if (n == null && d == null) continue;
                dx = e.getValue()[0] - (n != null ? n.x : d.getX());
                dy = e.getValue()[1] - (n != null ? n.y : d.getY());
                break;
            }
            final int[] mine = box(incoming, dx, dy), theirs = box(plan, 0, 0);
            if (mine == null || theirs == null) return new int[] { dx, dy };
            if (overlaps(incoming, dx, dy, plan)) dx += theirs[2] + GAP - mine[0];
            return new int[] { dx, dy };
        }
    }

    /**
     * Reads a proposal and converts what it adds against the game's recipes. Changes no plan.
     *
     * @param convert a plan's JSON to the board's cards (the game's website-card import)
     * @throws FfImportException when the proposal does not read; the message is for the player
     */
    public static Prepared prepare(final JsonObject proposal, final Function<String, FfConverter.Result> convert) {
        final String base = string(proposal, "basePlanId");
        if (base == null || base.isEmpty()
            || !proposal.has("version")
            || proposal.get("version")
                .getAsDouble() != 1)
            throw new FfImportException("That change could not be read.");
        final JsonArray cards = array(proposal, "cards"), drawers = array(proposal, "drawers"),
            wires = array(proposal, "wires"), targets = array(proposal, "targets");
        final Map<String, JsonObject> byId = new LinkedHashMap<>();
        final Set<String> fresh = new LinkedHashSet<>();
        final Map<String, int[]> places = new LinkedHashMap<>();
        for (final JsonObject c : objects(cards)) {
            // A copy, as the target rates below are written onto it (this Gson has no public deepCopy).
            final JsonObject copy = new com.google.gson.JsonParser().parse(c.toString())
                .getAsJsonObject();
            final String id = string(copy, "id");
            if (id == null) throw new FfImportException("That change could not be read.");
            byId.put(id, copy);
            fresh.add(id);
            place(copy, id, places);
        }
        for (final JsonObject d : objects(drawers)) {
            final String id = string(d, "id");
            if (id == null) throw new FfImportException("That change could not be read.");
            fresh.add(id);
            place(d, id, places);
        }
        // A target on one of the new cards rides in on the card, as the website's own port rate; the rest wait.
        final List<Target> later = new ArrayList<>();
        for (final JsonObject t : objects(targets)) {
            final String card = string(t, "card"), port = string(t, "port");
            if (card == null || port == null || !t.has("rate")) continue;
            final double rate = t.get("rate")
                .getAsDouble();
            final JsonObject on = byId.get(card);
            if (on == null) {
                later.add(new Target(card, port, rate));
                continue;
            }
            if (!on.has("portRates") || !on.get("portRates")
                .isJsonObject()) on.add("portRates", new JsonObject());
            on.getAsJsonObject("portRates")
                .addProperty(port, rate);
        }
        final JsonArray inner = new JsonArray();
        final List<Cross> crosses = new ArrayList<>();
        for (final JsonObject w : objects(wires)) {
            final String from = string(w, "source"), to = string(w, "target");
            if (from == null || to == null) continue;
            if (fresh.contains(from) && fresh.contains(to)) inner.add(w);
            else crosses.add(
                new Cross(
                    from,
                    to,
                    string(w, "sourceHandle"),
                    string(w, "targetHandle"),
                    string(w, "resourceKind") == null ? "item" : string(w, "resourceKind"),
                    string(w, "resourceId") == null ? "" : string(w, "resourceId")));
        }
        if (fresh.isEmpty()) return new Prepared(base, new Graph(), Map.of(), places, crosses, later, 0);
        final JsonObject plan = new JsonObject();
        plan.addProperty("schemaVersion", 1);
        plan.addProperty("name", "Proposal");
        plan.add("recipes", proposal.has("recipes") ? proposal.get("recipes") : new JsonArray());
        final JsonArray nodes = new JsonArray();
        for (final JsonObject c : byId.values()) nodes.add(c);
        plan.add("nodes", nodes);
        plan.add("edges", inner);
        plan.add("storages", drawers);
        final FfConverter.Result r = convert.apply(plan.toString());
        // A card the game cannot run would come in view only, under the website's own id, which another view-only card
        // of the plan may hold already: it is left out, and the player told.
        for (final Node n : new ArrayList<>(
            r.graph()
                .getNodes()))
            if (n.shown != null) r.graph()
                .removeNode(n.id);
        final Map<String, Uuid> made = new LinkedHashMap<>();
        for (final Map.Entry<String, String> e : r.placed()
            .cards()
            .entrySet()) {
            final Uuid id = uuid(e.getValue());
            if (id != null && r.graph().nodes.containsKey(id)) made.put(e.getKey(), id);
        }
        for (final Map.Entry<String, String> e : r.placed()
            .storages()
            .entrySet()) {
            final Uuid id = uuid(e.getValue());
            if (id != null && r.graph().drawers.containsKey(id)) made.putIfAbsent(e.getKey(), id);
        }
        int leftOut = 0;
        for (final String id : byId.keySet()) if (!made.containsKey(id)) leftOut++;
        return new Prepared(base, r.graph(), made, places, crosses, later, leftOut);
    }

    /** Whether any of what came in, moved by (dx, dy), lies on any card or drawer of the plan. */
    static boolean overlaps(final Graph incoming, final int dx, final int dy, final Graph plan) {
        for (final int[] a : rects(incoming, dx, dy)) for (final int[] b : rects(plan, 0, 0))
            if (a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]) return true;
        return false;
    }

    /** The box round a graph's cards and drawers moved by (dx, dy): x0, y0, x1, y1; null when it has none. */
    @Nullable
    static int[] box(final Graph g, final int dx, final int dy) {
        int[] box = null;
        for (final int[] r : rects(g, dx, dy)) box = box == null ? r.clone()
            : new int[] { Math.min(box[0], r[0]), Math.min(box[1], r[1]), Math.max(box[2], r[2]),
                Math.max(box[3], r[3]) };
        return box;
    }

    private static List<int[]> rects(final Graph g, final int dx, final int dy) {
        final List<int[]> out = new ArrayList<>();
        for (final Node n : g.getNodes())
            out.add(new int[] { n.x + dx, n.y + dy, n.x + dx + CARD_W, n.y + dy + CARD_H });
        for (final Drawer d : g.getDrawers())
            out.add(new int[] { d.getX() + dx, d.getY() + dy, d.getX() + dx + 40, d.getY() + dy + 40 });
        return out;
    }

    private static void place(final JsonObject o, final String id, final Map<String, int[]> places) {
        final JsonElement p = o.get("position");
        if (p == null || !p.isJsonObject()) return;
        final JsonObject at = p.getAsJsonObject();
        if (!at.has("x") || !at.has("y")) return;
        places.put(
            id,
            new int[] { (int) Math.round(
                at.get("x")
                    .getAsDouble()),
                (int) Math.round(
                    at.get("y")
                        .getAsDouble()) });
    }

    @Nullable
    private static Uuid uuid(final String s) {
        try {
            return Uuid.fromString(s);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static String string(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static JsonArray array(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static List<JsonObject> objects(final JsonArray a) {
        final List<JsonObject> out = new ArrayList<>();
        for (final JsonElement e : a) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }
}
