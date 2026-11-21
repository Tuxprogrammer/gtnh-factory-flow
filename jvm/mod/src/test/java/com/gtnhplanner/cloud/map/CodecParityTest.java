package com.gtnhplanner.cloud.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.CoreIds;
import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.BoardGroup;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Group;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.data.flowchart.balancer.ChoiceKey;
import com.gtnhplanner.data.flowchart.balancer.PortRef;

/**
 * The core's codec against what it replaces. Its save JSON of every note, group, board group, drawer and the plan's
 * own fields is the text Gson's reflective binding wrote, and reads back to what Gson read; its Canon spells and hashes
 * as the cloud map's does (and writes conformance/codec/canon.json with -PwriteVectors).
 */
class CodecParityTest {

    /** The binding every save was written with until the codec: reflective, ids as text. */
    private static final Gson REFLECT = new GsonBuilder().enableComplexMapKeySerialization()
        .registerTypeAdapter(Uuid.class, CoreIds.GSON)
        .create();

    private static final boolean WRITE = System.getProperty("gtnh.writeVectors") != null;

    /** Every board the corpus and the website plans make, dressed with notes, drawings, groups and boards. */
    private static List<Graph> boards() {
        final List<Graph> out = new ArrayList<>();
        for (final String name : Boards.CORPUS) out.add(Boards.dress(Boards.corpus(name)));
        for (final String name : Boards.WEBSITE) {
            final TestGame game = new TestGame();
            out.add(Boards.dress(Boards.imported(name, game.fake)));
        }
        return out;
    }

    private static void sanitize(final Drawer d) throws ReflectiveOperationException {
        final Method m = Drawer.class.getDeclaredMethod("sanitize");
        m.setAccessible(true);
        m.invoke(d);
    }

    /** A few things the boards do not have: every note kind's fields, a fractional exact shared machine. */
    private static Graph odd() {
        final Graph g = new Graph("odd");
        final Note arrow = new Note();
        arrow.setKind(Note.ARROW);
        arrow.setArrowDirection("up-left");
        arrow.setArrowHeads("both");
        arrow.setArrowWidth(4);
        arrow.setPoints(new ArrayList<>(List.of(new int[] { 1, 2 }, new int[] { 3, 4 })));
        arrow.setImageUrl("https://example.com/a.png");
        arrow.setBorder("dashed");
        arrow.setBorderColor("lime");
        arrow.setFillColor("pink");
        arrow.setFill("solid");
        arrow.setMarks("grid");
        arrow.setGroup(Uuid.randomUUID());
        arrow.setText(new ArrayList<>(List.of("a", "", "c")));
        g.notes.put(arrow.getId(), arrow);
        final MachineGroup m = new MachineGroup();
        m.setMachineCount(2.5);
        m.setPinned(true);
        m.setExact(true);
        m.addSection(Uuid.randomUUID());
        m.addSection(Uuid.randomUUID());
        g.groups.put(m.getId(), m);
        final Drawer d = new Drawer(Drawer.Kind.BUFFER, "item:minecraft:iron_ingot:0");
        d.setRate(2.5);
        g.drawers.put(d.getId(), d);
        final BoardGroup b = new BoardGroup();
        b.setParent(Uuid.randomUUID());
        b.setTheme("kraft");
        b.setPattern("dots");
        b.setFfId("pocket-1");
        g.boardGroups.put(b.getId(), b);
        return g;
    }

    @Test
    void theSavesThingsAreTheBytesGsonWroteAndReadTheSame() throws ReflectiveOperationException {
        int things = 0;
        final List<Graph> all = boards();
        all.add(odd());
        for (final Graph g : all) {
            for (final Note n : g.getNotes()) {
                final JsonElement gson = REFLECT.toJsonTree(n);
                assertEquals(
                    gson.toString(),
                    Serializer.noteToJson(n)
                        .toString(),
                    "note of " + g.getName());
                final Note byGson = REFLECT.fromJson(gson, Note.class);
                assertEquals(
                    Serializer.noteToJson(byGson)
                        .toString(),
                    Serializer.noteToJson(Serializer.readNote(gson))
                        .toString(),
                    "note read back, " + g.getName());
                things++;
            }
            for (final Group grp : g.getGroups()) {
                final JsonElement gson = REFLECT.toJsonTree(grp);
                assertEquals(
                    gson.toString(),
                    Serializer.groupToJson(grp)
                        .toString(),
                    "group of " + g.getName());
                final Group byGson = REFLECT
                    .fromJson(gson, grp instanceof MachineGroup ? MachineGroup.class : Group.class);
                assertEquals(
                    Serializer.groupToJson(byGson)
                        .toString(),
                    Serializer.groupToJson(Serializer.readGroup(gson))
                        .toString(),
                    "group read back, " + g.getName());
                things++;
            }
            for (final Drawer d : g.getDrawers()) {
                final JsonElement gson = REFLECT.toJsonTree(d, Drawer.class);
                assertEquals(
                    gson.toString(),
                    Serializer.drawerToJson(d)
                        .toString(),
                    "drawer of " + g.getName());
                final Drawer byGson = REFLECT.fromJson(gson, Drawer.class);
                sanitize(byGson);
                assertEquals(
                    Serializer.drawerToJson(byGson)
                        .toString(),
                    Serializer.drawerToJson(Serializer.readDrawer(gson))
                        .toString(),
                    "drawer read back, " + g.getName());
                things++;
            }
            for (final BoardGroup b : g.boardGroups.values()) {
                final JsonElement gson = REFLECT.toJsonTree(b);
                assertEquals(
                    gson.toString(),
                    Serializer.boardGroupToJson(b)
                        .toString(),
                    "board of " + g.getName());
                assertEquals(
                    Serializer.boardGroupToJson(REFLECT.fromJson(gson, BoardGroup.class))
                        .toString(),
                    Serializer.boardGroupToJson(Serializer.readBoardGroup(gson))
                        .toString());
                things++;
            }
        }
        assertTrue(things > 100, "too few things compared: " + things);
    }

    @Test
    void thePlansOwnFieldsAreTheBytesGsonWrote() {
        final Plan plan = Serializer.decodePlan("{}");
        plan.getSummary()
            .setExcessChoice(
                ChoiceKey
                    .of(List.of(new PortRef(Uuid.randomUUID(), 2, true), new PortRef(Uuid.randomUUID(), 0, false))));
        final JsonObject gson = REFLECT.toJsonTree(plan, Plan.class)
            .getAsJsonObject();
        final JsonObject codec = Serializer.planTree(plan);
        codec.remove("graphs");
        assertEquals(gson.toString(), codec.toString());
        final Plan again = Serializer.decodePlan(gson.toString());
        final JsonObject back = Serializer.planTree(again);
        back.remove("graphs");
        assertEquals(gson.toString(), back.toString(), "read back, the same fields");
        assertEquals(
            plan.getSummary()
                .getExcessChoice(),
            again.getSummary()
                .getExcessChoice());
    }

    /** Inputs for the canon vectors: spellings, escapes and key orders the two sides must hash alike. */
    private static final String[][] CANON = { { "object", "{\"b\":1,\"a\":[1.0,\"x\"],\"c\":{\"z\":null,\"y\":true}}" },
        { "numbers",
            "[0.1,1e-7,123456789012345678,1e15,999999999999999,1e21,-0,-0.0,0.001,0.0009999,1e7,9999999.5,1.5e300,"
                + "5e-324,2.2250738585072014e-308,3.141592653589793,100.25,1.0E-5,12345678.9,-2.5,1e0,4e-3]" },
        { "strings", "[\"a\\\"b\\\\c\",\"\\t\\b\\n\\r\\f\",\"\\u0001\\u001f\",\"\\u2028\\u2029\",\"<>&='\",\"/\"]" },
        { "unicode", "[\"\\u00e9\\u6f22\\ud83d\\ude00\"]" },
        { "key order", "{\"B\":1,\"a\":2,\"_\":3,\"\\u00e9\":4,\"aa\":5,\"Z\":6,\"\":7}" },
        { "empties", "{\"x\":[],\"y\":{},\"z\":[[[]]]}" }, { "null", "null" }, { "true", "true" },
        { "string", "\"s\"" }, { "whole", "42" },
        { "a card",
            "{\"id\":\"6bcdde86-54bd-44b7-935c-d857ee18e9a5\",\"recipeId\":\"gtnhplanner:x\",\"machineCount\":1.0,"
                + "\"solvePin\":2.5,\"position\":{\"x\":1351,\"y\":-368},\"portRates\":{\"input:item:Hydrogen\":2.5}}" } };

    @Test
    void aLoneSurrogateHashesAlikeToo() {
        final String text = "[\"\\ud800x\",\"y\\udc00\"]";
        assertEquals(
            Canon.sum(new JsonParser().parse(text)),
            com.gtnhplanner.core.codec.Canon.sum(JsonTree.parse(text)));
    }

    @Test
    void canonIsTheCloudMapsAndTheVectorsSayWhatBothWrite() throws IOException {
        final List<Object> cases = new ArrayList<>();
        for (final String[] c : CANON) {
            final JsonElement gson = new JsonParser().parse(c[1]);
            final Object tree = JsonTree.parse(c[1]);
            assertEquals(Canon.of(gson), com.gtnhplanner.core.codec.Canon.of(tree), "canon of " + c[0]);
            assertEquals(Canon.sum(gson), com.gtnhplanner.core.codec.Canon.sum(tree), "sum of " + c[0]);
            final Map<String, Object> k = new LinkedHashMap<>();
            k.put("name", c[0]);
            k.put("text", c[1]);
            k.put("canon", Canon.of(gson));
            k.put("sum", Canon.sum(gson));
            cases.add(k);
        }
        final Map<String, Object> file = new LinkedHashMap<>();
        file.put(
            "about",
            "Canon (core-codec) and the mod's cloud map Canon: the canonical spelling and 16-hex SHA-256 sum of each"
                + " text. Written by CodecParityTest -PwriteVectors from the mod's Canon.");
        file.put("cases", cases);
        final String text = new GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping()
            .create()
            .toJson(com.gtnhplanner.data.flowchart.GsonTrees.json(file)) + "\n";
        if (WRITE) Files.write(Conformance.path("codec/canon.json"), text.getBytes(StandardCharsets.UTF_8));
        else assertEquals(
            Conformance.text("codec/canon.json"),
            text,
            "codec/canon.json is out of date: -PwriteVectors");
    }
}
