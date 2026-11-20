package com.gtnhplanner.cloud.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Group;
import com.gtnhplanner.data.flowchart.GsonTrees;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Serializer;
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

    private record Trip(Map<String, Object> plan, Graph board, FfConverter.Result result, Map<String, Object> out) {}

    private static Trip trip(final String text, final String name, final Set<String> missing)
        throws ReflectiveOperationException {
        final TestGame game = new TestGame();
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
        return new Trip(PlanJson.parse(text), g, r, out);
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
        for (final String id : new String[] { "n-off", "n-gone", "n-norecipe" })
            assertTrue(nodes.containsKey(id), "card " + id + " kept");
        for (final String id : new String[] { "e-drawers", "e-cell", "e-rod", "e-off" })
            assertTrue(edges.containsKey(id), "wire " + id + " kept");
        for (final String id : new String[] { "s-lone", "s-x", "s-y" })
            assertTrue(storages.containsKey(id), "drawer " + id + " kept");
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
}
