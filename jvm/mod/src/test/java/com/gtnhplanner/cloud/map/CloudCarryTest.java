package com.gtnhplanner.cloud.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.cloud.sync.LiveJson;
import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.importer.FakeGame;
import com.gtnhplanner.importer.FfImport;
import com.gtnhplanner.library.PlanExport;

/**
 * A plan synced through the cloud is the same plan as one exported to a file: a drawer's wire names the port it lands
 * on as the recipe does, a website plan new to the cloud goes up with what the board has no place for, and a plan an
 * older build wrote (the drawer's own spelling on its wires) is read and written back with nothing changed.
 */
class CloudCarryTest {

    private static List<Object[]> boards() {
        final List<Object[]> out = new ArrayList<>();
        for (final String name : Boards.CORPUS) {
            final Graph g = Boards.dress(Boards.corpus(name));
            out.add(new Object[] { "corpus " + name, g, new TestGame().knowing(g) });
        }
        for (final String name : Boards.WEBSITE) {
            final TestGame game = new TestGame();
            final Graph g = Boards.dress(Boards.imported(name, game.fake));
            out.add(new Object[] { "website " + name, g, game.knowing(g) });
        }
        return out;
    }

    // region Wires

    /** The ids a recipe side names: each slot's own and its alternatives, and a card's input picks. */
    private static Set<String> sideIds(final JsonObject recipe, final JsonObject node, final boolean output) {
        final Set<String> ids = new HashSet<>();
        if (recipe != null) for (final JsonObject slot : PlanRead.objects(recipe, output ? "outputs" : "inputs")) {
            final String id = PlanRead.str(slot, "id");
            if (id != null) ids.add(id);
            for (final JsonObject a : PlanRead.objects(slot, "alternatives")) {
                final String alt = PlanRead.str(a, "id");
                if (alt != null) ids.add(alt);
            }
        }
        if (!output && node.has("recipeInputOverrides"))
            for (final Map.Entry<String, JsonElement> o : node.getAsJsonObject("recipeInputOverrides")
                .entrySet()) {
                    final String id = PlanRead.str(
                        o.getValue()
                            .getAsJsonObject(),
                        "id");
                    if (id != null) ids.add(id);
                }
        return ids;
    }

    /** The wires that name no port their card's recipe has (what the website's own check finds, mirrored). */
    private static List<String> unresolved(final JsonObject plan) {
        final Map<String, JsonObject> recipes = new HashMap<>(), nodes = new HashMap<>();
        for (final JsonObject r : PlanRead.objects(plan, "recipes")) recipes.put(PlanRead.str(r, "id"), r);
        for (final JsonObject n : PlanRead.objects(plan, "nodes")) nodes.put(PlanRead.str(n, "id"), n);
        final List<String> out = new ArrayList<>();
        for (final JsonObject e : PlanRead.objects(plan, "edges")) {
            for (final boolean output : new boolean[] { true, false }) {
                final JsonObject node = nodes.get(PlanRead.str(e, output ? "source" : "target"));
                if (node == null) continue;
                final String handle = PlanRead.str(e, output ? "sourceHandle" : "targetHandle");
                final java.util.regex.Matcher m = handle == null ? null
                    : java.util.regex.Pattern.compile("^(?:r(\\d+):)?(.*)$")
                        .matcher(handle);
                String id = null;
                int section = 0;
                if (m != null && m.matches()) {
                    final String[] parts = m.group(2)
                        .split(":");
                    if (parts.length >= 3) id = URLDecoder.decode(parts[2], StandardCharsets.UTF_8);
                    if (m.group(1) != null) section = Integer.parseInt(m.group(1));
                }
                String recipeId = PlanRead.str(node, "recipeId");
                if (section > 0) {
                    final List<JsonObject> extra = PlanRead.objects(node, "extraRecipes");
                    recipeId = section <= extra.size() ? PlanRead.str(extra.get(section - 1), "recipeId") : null;
                }
                if (id == null || !sideIds(recipeId == null ? null : recipes.get(recipeId), node, output).contains(id))
                    out.add(PlanRead.str(e, "id") + " " + handle);
            }
        }
        return out;
    }

    @Test
    void everyWireOfASyncedPlanNamesAPortItsCardHas() {
        int wires = 0;
        for (final Object[] b : boards()) {
            final JsonObject plan = CloudPlans.write((Graph) b[1], (TestGame) b[2], null, null)
                .plan();
            assertEquals(List.of(), unresolved(plan), b[0] + ": wires no port answers to");
            wires += PlanRead.objects(plan, "edges")
                .size();
            // The file export names them the same.
            assertEquals(
                List.of(),
                unresolved(PlanExport.project((Graph) b[1], "x", (TestGame) b[2])),
                b[0] + " (export)");
        }
        assertTrue(wires > 100, "wires checked: " + wires);
    }

    @Test
    void aDrawersWireAndItsStorageNameTheSameResourceAsTheExport() {
        int drawers = 0;
        for (final Object[] b : boards()) {
            final Graph g = (Graph) b[1];
            final TestGame game = (TestGame) b[2];
            final Map<String, JsonObject> cloud = byId(
                CloudPlans.write(g, game, null, null)
                    .plan(),
                "edges"), file = byId(PlanExport.project(g, "x", game), "edges");
            for (final Map.Entry<String, JsonObject> e : file.entrySet()) {
                final JsonObject c = cloud.get(e.getKey());
                if (c == null) continue;
                assertEquals(
                    PlanRead.str(e.getValue(), "resourceId"),
                    PlanRead.str(c, "resourceId"),
                    b[0] + ": wire " + e.getKey());
                drawers++;
            }
            final Map<String, JsonObject> cloudStorages = byId(
                CloudPlans.write(g, game, null, null)
                    .plan(),
                "storages"), fileStorages = byId(PlanExport.project(g, "x", game), "storages");
            for (final Map.Entry<String, JsonObject> e : fileStorages.entrySet()) {
                final JsonObject c = cloudStorages.get(e.getKey());
                if (c == null) continue;
                assertEquals(
                    PlanRead.str(e.getValue(), "resourceId"),
                    PlanRead.str(c, "resourceId"),
                    b[0] + ": storage " + e.getKey());
            }
        }
        assertTrue(drawers > 20, "wires compared: " + drawers);
    }

    private static Map<String, JsonObject> byId(final JsonObject plan, final String c) {
        final Map<String, JsonObject> out = new HashMap<>();
        for (final JsonObject o : PlanRead.objects(plan, c)) out.put(PlanRead.str(o, "id"), o);
        return out;
    }

    // endregion

    // region What the board cannot hold, in the cloud

    private record Imported(Graph g, TestGame game) {}

    private static Imported importedWithGaps() throws IOException, ReflectiveOperationException {
        final TestGame game = new TestGame();
        final Field f = FakeGame.class.getDeclaredField("missing");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        final Set<String> missing = (Set<String>) f.get(game.fake);
        missing.add("r-gone");
        final Graph g = FfImport
            .importFromText(Conformance.text("codec/round-trip/website-only-things.json"), game.fake, game.fake)
            .graph();
        game.knowing(g);
        assertFalse(g.site.isEmpty(), "the board keeps what it has no place for");
        return new Imported(g, game);
    }

    @Test
    void aWebsitePlanNewToTheCloudGoesUpWithWhatTheBoardCannotHold() throws IOException, ReflectiveOperationException {
        final Imported in = importedWithGaps();
        final JsonObject plan = CloudPlans.write(in.g(), in.game(), null, null)
            .plan();
        final Map<String, JsonObject> nodes = byId(plan, "nodes"), storages = byId(plan, "storages"),
            edges = byId(plan, "edges");
        for (final String id : new String[] { "n-off", "n-gone", "n-norecipe" })
            assertTrue(nodes.containsKey(id), "card " + id + " kept");
        for (final String id : new String[] { "s-lone", "s-x", "s-y" })
            assertTrue(storages.containsKey(id), "drawer " + id + " kept");
        for (final String id : new String[] { "e-drawers", "e-cell", "e-rod", "e-off" })
            assertTrue(edges.containsKey(id), "wire " + id + " kept");
        assertEquals(
            "Plan notes the mod has no place for.",
            plan.get("notes")
                .getAsString());
        assertTrue(plan.has("futureField"));
        assertTrue(plan.has("view"));
        // The export writes the same plan content the cloud does for what the board holds.
        final JsonObject file = PlanExport.project(in.g(), "x", in.game());
        for (final String key : new String[] { "notes", "futureField" })
            assertEquals(file.get(key), plan.get(key), key);
        assertEquals(
            byId(file, "nodes").keySet()
                .stream()
                .filter(id -> nodes.containsKey(id))
                .count(),
            nodes.keySet()
                .stream()
                .filter(id -> byId(file, "nodes").containsKey(id))
                .count(),
            "the same cards");
        // Every thing the board wrote has a sum over what it holds now: a colour tag the card carries is in it.
        assertSums(plan);
    }

    /** Each thing's stored sum is the sum of its website fields as the plan holds them, and the plan's own too. */
    private static void assertSums(final JsonObject plan) {
        for (final String c : List.of("nodes", "storages", "edges", "annotations", "pockets"))
            for (final JsonObject t : PlanRead.objects(plan, c)) {
                final JsonObject ext = t.has("gtnhplanner") ? t.getAsJsonObject("gtnhplanner") : null;
                if (ext != null) assertEquals(Fresh.sum(t), PlanRead.str(ext, "sum"), c + " " + PlanRead.str(t, "id"));
            }
        assertEquals(
            PlanRead.metaSum(plan),
            PlanRead.str(plan.getAsJsonObject("gtnhplanner"), "sum"),
            "the plan's own sum");
    }

    @Test
    void whatTheBoardCannotHoldStaysThroughSaves() throws IOException, ReflectiveOperationException {
        final Imported in = importedWithGaps();
        final JsonObject first = CloudPlans.write(in.g(), in.game(), null, null)
            .plan();
        // Read back and written again: nothing changes, twice.
        final CloudPlans.Read r = CloudPlans.read(first, in.game());
        final CloudPlans.Written again = CloudPlans.write(r.graph(), in.game(), first, r.basis());
        assertEquals(List.of(), CloudPlans.diff(first, again.plan()));
        // The board still holds the site it was made with: a later save against the plan puts nothing back, so a card
        // a website player deleted stays deleted.
        final JsonObject cut = LiveJson.copy(first);
        final JsonArray nodes = new JsonArray();
        for (final JsonElement n : first.getAsJsonArray("nodes")) if (!"n-off".equals(
            n.getAsJsonObject()
                .get("id")
                .getAsString()))
            nodes.add(n);
        cut.add("nodes", nodes);
        final JsonArray edges = new JsonArray();
        for (final JsonElement e : first.getAsJsonArray("edges")) if (!"e-off".equals(
            e.getAsJsonObject()
                .get("id")
                .getAsString()))
            edges.add(e);
        cut.add("edges", edges);
        final CloudPlans.Read rc = CloudPlans.read(cut, in.game());
        final CloudPlans.Written saved = CloudPlans.write(in.g(), in.game(), cut, rc.basis());
        assertFalse(byId(saved.plan(), "nodes").containsKey("n-off"), "a deleted card stays deleted");
        assertTrue(byId(saved.plan(), "nodes").containsKey("n-gone"), "the rest stays");
        assertTrue(
            saved.plan()
                .has("view")
                && saved.plan()
                    .has("notes"));
    }

    // endregion

    // region Plans an older build wrote

    /** A plan as the build before this one wrote it: a drawer's wires and storage by the drawer's own spelling. */
    private static JsonObject oldSpelling(final JsonObject plan, final int[] changed) {
        final JsonObject old = LiveJson.copy(plan);
        for (final String c : new String[] { "storages", "edges" })
            for (final JsonObject t : PlanRead.objects(old, c)) {
                final String id = PlanRead.str(t, "resourceId");
                if (id == null || id.equals(id.toLowerCase(Locale.ROOT))) continue;
                final String kind = PlanRead.str(t, "resourceKind"), lower = id.toLowerCase(Locale.ROOT);
                t.addProperty("resourceId", lower);
                if (t.has("sourceHandle")) t.addProperty("sourceHandle", "output:" + kind + ":" + lower);
                if (t.has("targetHandle")) t.addProperty("targetHandle", "input:" + kind + ":" + lower);
                // The build that wrote it summed what it wrote.
                if (t.has("gtnhplanner")) t.getAsJsonObject("gtnhplanner")
                    .addProperty("sum", Fresh.sum(t));
                changed[0]++;
            }
        return old;
    }

    @Test
    void aPlanAnOlderBuildWroteSyncsWithNoChangeAndNoLoss() {
        int changed = 0, boardsWithSome = 0;
        for (final Object[] b : boards()) {
            final TestGame game = (TestGame) b[2];
            final int[] n = { 0 };
            final JsonObject old = oldSpelling(
                CloudPlans.write((Graph) b[1], game, null, null)
                    .plan(),
                n);
            changed += n[0];
            if (n[0] > 0) boardsWithSome++;
            final String text = old.toString();
            final CloudPlans.Read r = CloudPlans.read(old, game);
            final CloudPlans.Written back = CloudPlans.write(r.graph(), game, old, r.basis());
            assertEquals(List.of(), CloudPlans.diff(old, back.plan()), b[0] + ": first sync of an old plan");
            assertEquals(text, old.toString(), b[0] + ": the plan read was changed");
            for (final String c : List.of("nodes", "storages", "edges", "recipes")) assertTrue(
                LiveJson.equal(
                    old.get(c),
                    back.plan()
                        .get(c)),
                b[0] + ": " + c + " come back exactly as they were");
            final CloudPlans.Written again = CloudPlans.write(r.graph(), game, back.plan(), back.basis());
            assertEquals(List.of(), CloudPlans.diff(back.plan(), again.plan()), b[0] + " (second write)");
            // A change to a card leaves the old wires as they are.
            final Graph g = r.graph();
            if (!g.getNodes()
                .isEmpty()) {
                final Node first = g.getNodes()
                    .iterator()
                    .next();
                first.x += 40;
                final CloudPlans.Written moved = CloudPlans.write(g, game, old, r.basis());
                assertEquals(
                    LiveJson.copy(old.getAsJsonArray("edges")),
                    moved.plan()
                        .getAsJsonArray("edges"),
                    b[0] + ": wires untouched by a move");
            }
        }
        assertNotEquals(0, changed, "no plan had a wire in the old spelling");
        assertTrue(boardsWithSome >= 3, "boards with a wire in the old spelling: " + boardsWithSome);
    }

    // endregion

    // region Caps

    @Test
    void settingPinsAndSharesStayWithinTheWebsitesCaps() {
        final Graph g = new Graph("Many");
        for (int i = 0; i < 600; i++) g.settingPins.put("site:machine-" + i, new ArrayList<>(List.of("coil")));
        final JsonObject plan = CloudPlans.write(g, new TestGame().knowing(g), null, null)
            .plan();
        assertEquals(
            PlanExport.MAX_SETTING_PINS,
            plan.getAsJsonObject("settingPins")
                .entrySet()
                .size());
        assertSums(plan);
    }

    // endregion
}
