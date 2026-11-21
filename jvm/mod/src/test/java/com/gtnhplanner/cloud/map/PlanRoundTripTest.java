package com.gtnhplanner.cloud.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.codec.Canon;
import com.gtnhplanner.core.codec.PlanJson;
import com.gtnhplanner.core.model.ResourceKey;
import com.gtnhplanner.core.model.Shown;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Group;
import com.gtnhplanner.data.flowchart.GsonTrees;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.GraphSolve;
import com.gtnhplanner.data.flowchart.balancer.SolveInput;
import com.gtnhplanner.importer.FakeGame;
import com.gtnhplanner.importer.FfConverter;
import com.gtnhplanner.importer.FfImport;
import com.gtnhplanner.library.PlanExport;

/**
 * A plan saved by either side opens on the other with nothing lost. A website plan comes into the mod (the importer),
 * is saved and read back, and goes out again (the export): every card, drawer and wire of it is either on the board or
 * kept as it came, every field the board does not model comes back, and so do its view, notes, pins, splits and task
 * board. A board the mod made goes out and comes back with its shared machines and set counts.
 */
class PlanRoundTripTest {

    /** Website plan fields the import turns into board things (old Build and Pool plans): not written back. */
    private static final Set<String> CONVERTED = Set.of(
        "id",
        "gtnhplanner",
        "productionGroups",
        "poolResourceRules",
        "poolCellRatios",
        "targetRate",
        "setupRules",
        "assumeBoundaries",
        "poolMode");

    /** A card's fields the board writes itself (SiteCarry's list): the board's word for them stands. */
    private static final Set<String> NODE_OWNED = Set.of(
        "id",
        "recipeId",
        "machineCount",
        "parallel",
        "overclockTier",
        "enabled",
        "customRate",
        "machineConfigTiers",
        "hatchVoltageTier",
        "hatchAmps",
        "powerInputMode",
        "powerEuT",
        "energyHatches",
        "energyHatchType",
        "coilTier",
        "machineHandlerId",
        "hatchSupplies",
        "recipeInputOverrides",
        "extraRecipes",
        "solvePin",
        "solvePinExact",
        "portRates",
        "targetOutput",
        "pocketId",
        "position",
        "productionGroupId",
        "gtnhplanner");

    private record Trip(Map<String, Object> plan, Graph board, FfConverter.Result result, Map<String, Object> out,
        TestGame game) {}

    private static Trip trip(final String text, final String name, final Set<String> missing)
        throws ReflectiveOperationException {
        return trip(text, name, missing, false);
    }

    /**
     * A plan in and out. {@code routed}: the game looks recipes up only where the game's index would, so a recipe kind
     * it has no page for (an ore vein, Thaumcraft) has none.
     */
    static Trip trip(final String text, final String name, final Set<String> missing, final boolean routed)
        throws ReflectiveOperationException {
        final TestGame game = routed(new TestGame(), routed);
        if (!missing.isEmpty()) {
            final Field f = FakeGame.class.getDeclaredField("missing");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            final Set<String> m = (Set<String>) f.get(game.fake);
            m.addAll(missing);
        }
        final FfConverter.Result r = FfImport.importFromText(text, game.fake, game.fake);
        final Graph g = r.graph();
        game.knowing(g);
        // What the board keeps survives its own save.
        assertEquals(
            Canon.of(g.site),
            Canon.of(
                GsonTrees.tree(
                    Serializer.toJson(g)
                        .get("site"))),
            name + ": the save holds what the board keeps");
        final Map<String, Object> out = PlanJson.parse(
            PlanExport.project(g, name, game)
                .toString());
        return new Trip(PlanJson.parse(text), g, r, out, game);
    }

    /** A test game that looks recipes up only where the game's index would ({@code FakeGame.routed}). */
    static TestGame routed(final TestGame game, final boolean routed) throws ReflectiveOperationException {
        final Field f = FakeGame.class.getDeclaredField("routed");
        f.setAccessible(true);
        f.setBoolean(game.fake, routed);
        return game;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(final Object v) {
        final List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List)
            for (final Object o : (List<?>) v) if (o instanceof Map) out.add((Map<String, Object>) o);
        return out;
    }

    private static Map<String, Map<String, Object>> byId(final Object v) {
        final Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (final Map<String, Object> o : objects(v)) out.put(String.valueOf(o.get("id")), o);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    /** Everything of the plan is on the board or kept, and comes back out. */
    private static void nothingLost(final Trip t, final String name) {
        final Map<String, Object> in = t.plan(), out = t.out();
        final var placed = t.result()
            .placed();
        for (final Map.Entry<String, Object> e : in.entrySet()) {
            if (CONVERTED.contains(e.getKey()) || e.getValue() == null) continue;
            assertTrue(out.containsKey(e.getKey()), name + ": the plan's " + e.getKey() + " is lost");
        }
        final Map<String, Object> viewIn = object(in.get("view")), viewOut = object(out.get("view"));
        for (final Map.Entry<String, Object> e : viewIn.entrySet()) if (!"rateUnit".equals(e.getKey()))
            assertEquals(Canon.of(e.getValue()), Canon.of(viewOut.get(e.getKey())), name + ": view." + e.getKey());
        for (final String key : new String[] { "settingPins", "spareShares" })
            for (final String k : object(in.get(key)).keySet())
                assertTrue(object(out.get(key)).containsKey(k), name + ": " + key + " " + k + " is lost");
        for (final String key : new String[] { "tasks", "taskItems", "taskComments" })
            assertEquals(objects(in.get(key)).size(), objects(out.get(key)).size(), name + ": " + key);

        final Map<String, Map<String, Object>> nodesOut = byId(out.get("nodes")),
            storagesOut = byId(out.get("storages")), edgesOut = byId(out.get("edges"));
        for (final Map<String, Object> n : objects(in.get("nodes"))) {
            final String id = String.valueOf(n.get("id"));
            final String card = placed.cards()
                .get(id),
                drawer = placed.storages()
                    .get(id);
            if (card != null) {
                final Map<String, Object> o = nodesOut.get(card);
                assertTrue(o != null, name + ": card " + id + " placed but not written");
                for (final Map.Entry<String, Object> e : n.entrySet())
                    if (!NODE_OWNED.contains(e.getKey()) && e.getValue() != null) assertEquals(
                        Canon.of(e.getValue()),
                        Canon.of(o.get(e.getKey())),
                        name + ": card " + id + " field " + e.getKey());
            } else if (drawer != null) assertTrue(
                storagesOut.containsKey(drawer) || nodesOut.containsKey(drawer),
                name + ": custom rate card " + id + " lost");
            else assertEquals(Canon.of(n), Canon.of(nodesOut.get(id)), name + ": card " + id + " not kept as it came");
        }
        for (final Map<String, Object> s : objects(in.get("storages"))) {
            final String id = String.valueOf(s.get("id"));
            if (placed.converted()
                .contains(id)) continue;
            final String drawer = placed.storages()
                .get(id);
            if (drawer == null) {
                assertEquals(Canon.of(s), Canon.of(storagesOut.get(id)), name + ": drawer " + id + " not kept");
                continue;
            }
            final Map<String, Object> o = storagesOut.get(drawer);
            assertTrue(o != null, name + ": drawer " + id + " placed but not written");
            if ("byproduct".equals(s.get("drainMode")) && !s.containsKey("targetMode"))
                assertEquals("byproduct", o.get("drainMode"), name + ": drawer " + id + " is a byproduct drawer");
            for (final String k : new String[] { "colorTag", "iconPath", "dominantColor" }) if (s.containsKey(k))
                assertEquals(Canon.of(s.get(k)), Canon.of(o.get(k)), name + ": drawer " + id + " " + k);
        }
        final Set<String> ends = new HashSet<>(nodesOut.keySet());
        ends.addAll(storagesOut.keySet());
        for (final Map<String, Object> e : objects(in.get("edges"))) {
            final String id = String.valueOf(e.get("id"));
            final boolean onBoard = !placed.droppedEdges()
                .contains(id) && placedEnd(placed, String.valueOf(e.get("source")))
                && placedEnd(placed, String.valueOf(e.get("target")));
            if (onBoard) continue;
            final Map<String, Object> o = edgesOut.get(id);
            assertTrue(o != null, name + ": wire " + id + " lost");
            assertTrue(
                ends.contains(String.valueOf(o.get("source"))) && ends.contains(String.valueOf(o.get("target"))),
                name + ": wire " + id + " comes back to things the plan has");
        }
    }

    /** A wire end the board holds: a card or drawer it made, or a buffer it turned into wires. */
    private static boolean placedEnd(final com.gtnhplanner.core.codec.SiteCarry.Placed placed, final String id) {
        return placed.cards()
            .containsKey(id)
            || placed.storages()
                .containsKey(id)
            || placed.converted()
                .contains(id);
    }

    @Test
    void aPlanWithEverythingTheBoardCannotHoldComesBackWhole() throws IOException, ReflectiveOperationException {
        final String text = Conformance.text("codec/round-trip/website-only-things.json");
        final Trip t = trip(text, "website-only-things", Set.of("r-gone"));
        nothingLost(t, "website-only-things");
        final Map<String, Map<String, Object>> nodes = byId(
            t.out()
                .get("nodes")),
            storages = byId(
                t.out()
                    .get("storages")),
            edges = byId(
                t.out()
                    .get("edges"));
        // Cards the board cannot run are shown on it and go back as they came; the wires and drawers on them are the
        // board's now (the drawer to drawer wire joins its buffer, as any).
        for (final String id : new String[] { "n-off", "n-gone", "n-norecipe" })
            assertTrue(nodes.containsKey(id), "card " + id + " kept");
        assertTrue(edges.containsKey("e-cell"), "wire e-cell kept");
        for (final String id : new String[] { "s-lone", "s-y" }) assertTrue(
            storages.containsKey(
                t.result()
                    .placed()
                    .storages()
                    .get(id)),
            "drawer " + id + " on the board");
        assertTrue(
            t.result()
                .placed()
                .converted()
                .contains("s-x"),
            "buffer s-x joined into a wire");
        assertEquals(
            "lime",
            nodes.get(
                t.result()
                    .placed()
                    .cards()
                    .get("n-a"))
                .get("colorTag"));
        assertEquals(
            2.5,
            ((Number) nodes.get(
                t.result()
                    .placed()
                    .cards()
                    .get("n-b"))
                .get("solvePin")).doubleValue(),
            "a fractional pin, the player's own count");
        assertEquals(
            "Plan notes the mod has no place for.",
            t.out()
                .get("notes"));
        assertEquals(
            Canon.of(PlanJson.parse("{\"kept\":[1,2.5,\"x\"]}")),
            Canon.of(
                t.out()
                    .get("futureField")));
        assertEquals(
            "minute",
            object(
                t.out()
                    .get("view")).get("rateUnit"));
    }

    @Test
    void everyWebsitePlanComesBackWithNothingLost() throws IOException, ReflectiveOperationException {
        int plans = 0;
        for (final String name : Boards.WEBSITE) {
            final String text = Boards.json("/factory-flow/" + name + ".json")
                .toString();
            nothingLost(trip(text, name, Set.of()), name);
            plans++;
        }
        for (final Path p : Conformance.list("plans/website")) {
            final String text = new String(
                java.nio.file.Files.readAllBytes(p),
                java.nio.charset.StandardCharsets.UTF_8);
            nothingLost(
                trip(
                    text,
                    p.getFileName()
                        .toString(),
                    Set.of()),
                p.getFileName()
                    .toString());
            plans++;
        }
        assertTrue(plans >= 15, "plans: " + plans);
    }

    /** A board's shared machines and set counts, as a plan of it reads back. */
    private static List<String> shared(final Graph g) {
        final List<String> out = new ArrayList<>();
        for (final Group grp : g.getGroups()) if (grp instanceof final MachineGroup m && m.isShared())
            out.add(m.getSections().size() + " recipes, pinned " + m.isPinned() + " at " + m.machineCount() + (m.isExact() ? " exactly" : ""));
        out.sort(null);
        return out;
    }

    @Test
    void aBoardsSharedMachinesAndSetCountsGoOutAndComeBack() throws ReflectiveOperationException {
        int machines = 0;
        for (final String name : Boards.CORPUS) {
            final Graph g = Boards.dress(Boards.corpus(name));
            final TestGame game = new TestGame().knowing(g);
            final String text = PlanExport.project(g, name, game)
                .toString();
            final Graph back = FfImport.importFromText(text, game.fake, game.fake)
                .graph();
            assertEquals(shared(g), shared(back), name + ": shared machines");
            machines += shared(g).size();
            final List<Double> pins = new ArrayList<>(), pinsBack = new ArrayList<>();
            for (final var n : g.getNodes()) if (n.isMachineCountFixed()) pins.add(n.machineConfig.machineCount());
            for (final var n : back.getNodes())
                if (n.isMachineCountFixed()) pinsBack.add(n.machineConfig.machineCount());
            pins.sort(null);
            pinsBack.sort(null);
            assertEquals(pins, pinsBack, name + ": set counts");
        }
        assertTrue(machines > 0, "no corpus board has a shared machine");
    }

    // region Display-only cards

    private static final String DISPLAY = "codec/round-trip/display-cards.json";

    /** The plan's cards the board only shows, by plan id. */
    private static Map<String, Node> shown(final Graph g) {
        final Map<String, Node> out = new LinkedHashMap<>();
        for (final Node n : g.getNodes()) if (n.shown != null) out.put(n.shown.planId(), n);
        return out;
    }

    private static Trip display() throws IOException, ReflectiveOperationException {
        return trip(Conformance.text(DISPLAY), "display-cards", Set.of("r-gone"), true);
    }

    /**
     * A website plan's cards the game cannot run (an ore vein, Thaumcraft, a switched-off card, a recipe the game
     * lacks, one with no recipe in the plan) are on the board where the plan has them, with its ports and counts and
     * their wires and drawers, and go back out exactly as they came.
     */
    @Test
    void websiteOnlyCardsAreOnTheBoardAndComeBackAsTheyCame() throws IOException, ReflectiveOperationException {
        final Trip t = display();
        nothingLost(t, "display-cards");
        final Map<String, Node> shown = shown(t.board());
        assertEquals(Set.of("n-vein", "n-infusion", "n-off", "n-gone", "n-norecipe"), shown.keySet());
        assertEquals(Shown.Reason.KIND, shown.get("n-vein").shown.reason());
        assertEquals(Shown.Reason.KIND, shown.get("n-infusion").shown.reason());
        assertEquals(Shown.Reason.OFF, shown.get("n-off").shown.reason());
        assertEquals(Shown.Reason.MISSING, shown.get("n-gone").shown.reason());
        assertEquals(Shown.Reason.ABSENT, shown.get("n-norecipe").shown.reason());
        assertTrue(shown.get("n-vein").shown.solved(), "an ore vein runs by the website's numbers");
        assertFalse(shown.get("n-off").shown.solved(), "a switched-off card is outside the solve");
        assertFalse(shown.get("n-norecipe").shown.solved(), "no recipe, nothing to solve");

        // Name, ports and counts as the plan states them, at its place.
        final Node vein = shown.get("n-vein");
        assertEquals("Ore Vein", vein.machineName);
        assertEquals(3, vein.outputs.size());
        assertEquals(
            ResourceKey.item("gregtech:gt.blockores2@98"),
            vein.outputs.get(0)
                .resource());
        assertEquals(
            20.0,
            vein.outputs.get(0)
                .amount(),
            1e-9,
            "one ore a tick, a second's worth for one machine");
        assertEquals(2.0, vein.machineConfig.machineCount());
        assertTrue(vein.isMachineCountFixed() && vein.isMachineCountExact());
        assertEquals(FfConverterAt.x(0), vein.x);
        assertEquals(FfConverterAt.y(200), vein.y);
        assertEquals(
            ResourceKey.Kind.ASPECT,
            shown.get("n-infusion").inputs.get(1)
                .resource()
                .kind());
        assertEquals(3.0, shown.get("n-off").machineConfig.machineCount());

        // Its wires draw: the vein feeds the press, the drawers on the shown cards are the board's.
        final Node press = t.board().nodes.get(Uuid(t, "n-press"));
        assertTrue(
            t.board()
                .getEdges()
                .stream()
                .anyMatch(e -> e.sourceNodeId.equals(vein.id) && e.targetNodeId.equals(press.id)),
            "the vein's wire to the press is on the board");
        for (final String id : new String[] { "s-buf", "s-rod", "s-bolt" }) assertNotNull(
            t.result()
                .placed()
                .storages()
                .get(id),
            id + " is a drawer on the board");

        // Back out as they came, with their recipes.
        final Map<String, Map<String, Object>> in = byId(
            t.plan()
                .get("nodes")),
            out = byId(
                t.out()
                    .get("nodes"));
        for (final String id : shown.keySet())
            assertEquals(Canon.of(in.get(id)), Canon.of(out.get(id)), id + " goes back as it came");
        final Map<String, Map<String, Object>> recipesIn = byId(
            t.plan()
                .get("recipes")),
            recipesOut = byId(
                t.out()
                    .get("recipes"));
        for (final String id : new String[] { "r-vein", "r-infusion", "r-off", "r-gone" })
            assertEquals(Canon.of(recipesIn.get(id)), Canon.of(recipesOut.get(id)), id + " goes back as it came");
        // The board's wire from the vein names the port as the plan's recipe does.
        assertTrue(
            objects(
                t.out()
                    .get("edges")).stream()
                        .anyMatch(
                            e -> "n-vein".equals(e.get("source"))
                                && "output:item:gregtech%3Agt.blockores2%4098".equals(e.get("sourceHandle"))),
            "the vein's wire names its port");
        // A buffer fed and drawn from keeps the plan's drain setting for when it stops being one.
        assertEquals(
            "product",
            byId(
                t.out()
                    .get("storages")).get(
                        t.result()
                            .placed()
                            .storages()
                            .get("s-buf"))
                        .get("drainMode"),
            "the buffer's drain setting");
    }

    /** A display-only card moved on the board goes back where it stands; one deleted goes, with its recipe. */
    @Test
    void aMovedDisplayCardGoesBackWhereItStandsAndADeletedOneIsGone() throws IOException, ReflectiveOperationException {
        final Trip t = display();
        final Graph g = t.board();
        final Map<String, Node> shown = shown(g);
        final Node vein = shown.get("n-vein");
        vein.x += 100;
        vein.y += 60;
        g.removeNode(shown.get("n-off").id);
        final Map<String, Object> out = PlanJson.parse(
            PlanExport.project(g, "display-cards", t.game())
                .toString());
        final Map<String, Map<String, Object>> in = byId(
            t.plan()
                .get("nodes")),
            nodes = byId(out.get("nodes"));
        final Map<String, Object> moved = nodes.get("n-vein"), was = in.get("n-vein");
        assertEquals(
            Canon.of(PlanJson.parse("{\"x\":" + vein.x + ",\"y\":" + vein.y + "}")),
            Canon.of(moved.get("position")));
        for (final Map.Entry<String, Object> e : was.entrySet()) if (!"position".equals(e.getKey()))
            assertEquals(Canon.of(e.getValue()), Canon.of(moved.get(e.getKey())), "n-vein field " + e.getKey());
        assertNull(nodes.get("n-off"), "a deleted card is gone");
        assertFalse(byId(out.get("recipes")).containsKey("r-off"), "with its recipe");
        for (final Map<String, Object> e : objects(out.get("edges")))
            assertFalse("n-off".equals(e.get("source")) || "n-off".equals(e.get("target")), "and its wires");
        for (final String id : new String[] { "n-infusion", "n-gone", "n-norecipe" })
            assertEquals(Canon.of(in.get(id)), Canon.of(nodes.get(id)), id + " untouched");
    }

    /** A board with display-only cards saves and reads back with them where they stand, their wires on them. */
    @Test
    void displayCardsSurviveTheSave() throws IOException, ReflectiveOperationException {
        final Trip t = display();
        final Graph g = t.board();
        shown(g).get("n-gone").x += 40;
        final Graph back = Serializer.fromJson(Serializer.toJson(g));
        final Map<String, Node> before = shown(g), after = shown(back);
        assertEquals(before.keySet(), after.keySet());
        for (final Map.Entry<String, Node> e : before.entrySet()) {
            final Node a = e.getValue(), b = after.get(e.getKey());
            assertEquals(a.id, b.id, e.getKey());
            assertEquals(a.x, b.x, e.getKey());
            assertEquals(a.y, b.y, e.getKey());
            assertEquals(a.shown, b.shown, e.getKey());
            assertEquals(a.inputs.size(), b.inputs.size(), e.getKey());
            assertEquals(a.outputs.size(), b.outputs.size(), e.getKey());
        }
        assertEquals(g.edges.keySet(), back.edges.keySet());
        final Map<String, Object> was = PlanJson.parse(
            PlanExport.project(g, "x", t.game())
                .toString()),
            now = PlanJson.parse(
                PlanExport.project(back, "x", t.game())
                    .toString());
        // The display-only cards and the wires on them go out the same (the headless game reads no recipe back for the
        // board's own cards, so those are left out of the comparison).
        assertEquals(Canon.of(onShown(was, before.keySet())), Canon.of(onShown(now, before.keySet())));
        // A card deleted on the board is gone from the save too.
        back.removeNode(after.get("n-norecipe").id);
        assertFalse(
            shown(Serializer.fromJson(Serializer.toJson(back))).containsKey("n-norecipe"),
            "a deleted card stays deleted");
    }

    /**
     * A save from before display-only cards kept them only in its site: they are shown when it is read, and go back
     * out as they came.
     */
    @Test
    void anOlderSavesKeptCardsAreShownWhenRead() throws IOException, ReflectiveOperationException {
        final Trip t = display();
        final com.google.gson.JsonObject save = Serializer.toJson(t.board());
        final com.google.gson.JsonArray nodes = new com.google.gson.JsonArray();
        for (final com.google.gson.JsonElement n : save.getAsJsonArray("nodes")) if (!n.getAsJsonObject()
            .has("site")) nodes.add(n);
        save.add("nodes", nodes);
        final Graph back = Serializer.fromJson(save);
        assertEquals(shown(t.board()).keySet(), shown(back).keySet());
        final Map<String, Map<String, Object>> out = byId(
            PlanJson.parse(
                PlanExport.project(back, "x", t.game())
                    .toString())
                .get("nodes")),
            in = byId(
                t.plan()
                    .get("nodes"));
        for (final String id : shown(back).keySet())
            assertEquals(Canon.of(in.get(id)), Canon.of(out.get(id)), id + " goes back as it came");
    }

    /**
     * The solve as the website treats them: an ore vein runs at its set count by the website's numbers, a switched-off
     * card carries nothing.
     */
    @Test
    void aSwitchedOffCardIsOutsideTheSolveAndAVeinIsIn() throws IOException, ReflectiveOperationException {
        final Trip t = display();
        final Map<String, Node> shown = shown(t.board());
        final SolveInput in = GraphSolve.input(t.board(), BalanceMode.AUTO, null);
        final SolveInput.Machine vein = machine(in, shown.get("n-vein")), off = machine(in, shown.get("n-off"));
        assertTrue(vein.countFixed());
        assertEquals(2.0, vein.machineCount());
        assertEquals(20.0, vein.durationTicks());
        assertEquals(
            20.0,
            vein.outputs()
                .get(0)
                .amount()
                * vein.outputs()
                    .get(0)
                    .multiplier(),
            1e-9);
        assertTrue(off.countFixed());
        assertEquals(0.0, off.machineCount());
        for (final SolveInput.PortIn p : off.outputs()) assertEquals(0.0, p.amount() * p.multiplier());
        // The press is fed ore by the vein: two veins at twenty a second, more than one press takes.
        final var balance = GraphSolve.balance(t.board(), BalanceMode.AUTO);
        assertNotNull(balance);
    }

    /** A plan's cards of these ids and the wires with an end on one. */
    private static List<Object> onShown(final Map<String, Object> plan, final Set<String> ids) {
        final List<Object> out = new ArrayList<>();
        for (final Map<String, Object> n : objects(plan.get("nodes"))) if (ids.contains(n.get("id"))) out.add(n);
        for (final Map<String, Object> e : objects(plan.get("edges")))
            if (ids.contains(e.get("source")) || ids.contains(e.get("target"))) out.add(e);
        return out;
    }

    private static SolveInput.Machine machine(final SolveInput in, final Node n) {
        for (final SolveInput.Machine m : in.machines()) if (m.id()
            .equals(n.id)) return m;
        throw new AssertionError("no machine for " + n.machineName);
    }

    private static com.gtnhplanner.core.base.Uuid Uuid(final Trip t, final String planId) {
        return com.gtnhplanner.core.base.Uuid.fromString(
            t.result()
                .placed()
                .cards()
                .get(planId));
    }

    /** Where the importer puts a plan point (its plans keep their layout, shifted to the board's margin). */
    private static final class FfConverterAt {

        static int x(final double planX) {
            return (int) Math.round(planX) + 40;
        }

        static int y(final double planY) {
            return (int) Math.round(planY) + 40;
        }
    }

    // endregion
}
