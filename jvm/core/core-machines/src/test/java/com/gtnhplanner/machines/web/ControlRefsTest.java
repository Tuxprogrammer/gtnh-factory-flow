package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.core.machines.GameRates;

/**
 * A recipe sent with its control tables as references ({@link MachineReadout#addControl}, {"$ref": name}), and also
 * with its handlers as references ({@link MachineReadout#addHandler}), reads exactly as the same recipe sent whole:
 * every machine golden's recipe, three ways, the same card and readout text on its node.
 */
class ControlRefsTest {

    @Test
    void referencesReadAsTheTables() throws Exception {
        final MachineReadout whole = new MachineReadout();
        final MachineReadout byRef = new MachineReadout();
        final MachineReadout byHandler = new MachineReadout();
        final Map<String, String> names = new HashMap<>(), handlerNames = new HashMap<>();
        int recipes = 0, cards = 0, referenced = 0, handlers = 0;
        try (BufferedReader in = new BufferedReader(
            new InputStreamReader(
                new GZIPInputStream(Conformance.open("machines/machine-goldens.jsonl.gz")),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) continue;
                final Map<String, Object> c = JsonTree.object(JsonTree.parse(line));
                final String id = JsonTree.str(c, "recipeId");
                GameRates.install(GameRates.NONE);
                if (c.containsKey("recipe")) {
                    whole.addRecipe(id, JsonTree.write(c.get("recipe")));
                    final Map<String, Object> recipe = JsonTree.object(c.get("recipe"));
                    referenced += refer(recipe, names, byRef, byHandler);
                    for (final Object h : JsonTree.array(recipe.get("machineHandlers")))
                        if (h instanceof Map) referenced += refer(JsonTree.object(h), names, byRef, byHandler);
                    byRef.addRecipe(id, JsonTree.write(recipe));
                    final List<Object> list = JsonTree.array(recipe.get("machineHandlers"));
                    for (int i = 0; i < list.size(); i++) {
                        final String text = JsonTree.write(list.get(i));
                        String name = handlerNames.get(text);
                        if (name == null) {
                            name = "h" + handlerNames.size();
                            handlerNames.put(text, name);
                            byHandler.addHandler(name, text);
                        }
                        final Map<String, Object> ref = new LinkedHashMap<>();
                        ref.put("$ref", name);
                        list.set(i, ref);
                        handlers++;
                    }
                    byHandler.addRecipe(id, JsonTree.write(recipe));
                    recipes++;
                }
                final String node = JsonTree.write(c.get("node"));
                assertEquals(whole.card(id, node), byRef.card(id, node), id);
                assertEquals(whole.read(id, node), byRef.read(id, node), id);
                assertEquals(whole.card(id, node), byHandler.card(id, node), id);
                assertEquals(whole.read(id, node), byHandler.read(id, node), id);
                cards++;
            }
        }
        System.out.println(recipes + " recipes, " + cards + " cards, " + referenced + " tables sent as " + names.size()
            + " references, " + handlers + " handlers as " + handlerNames.size());
        assertTrue(recipes > 500 && referenced > 0 && names.size() < referenced, recipes + " / " + referenced);
        assertTrue(handlers > 0 && handlerNames.size() < handlers, handlers + " / " + handlerNames.size());
    }

    /** Swaps each control of the owner's list for a reference, keeping each distinct table once. */
    private static int refer(final Map<String, Object> owner, final Map<String, String> names,
        final MachineReadout... to) {
        if (!(owner.get("machineConfigControls") instanceof List)) return 0;
        final List<Object> list = JsonTree.array(owner.get("machineConfigControls"));
        for (int i = 0; i < list.size(); i++) {
            final String text = JsonTree.write(list.get(i));
            String name = names.get(text);
            if (name == null) {
                name = "c" + names.size();
                names.put(text, name);
                for (final MachineReadout r : to) r.addControl(name, text);
            }
            final Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("$ref", name);
            list.set(i, ref);
        }
        return list.size();
    }

    @Test
    void anUnknownReferenceIsAnError() {
        final MachineReadout r = new MachineReadout();
        assertThrows(
            IllegalArgumentException.class,
            () -> r.addRecipe(
                "x",
                "{\"id\":\"x\",\"name\":\"x\",\"machineType\":\"Bender\",\"minimumTier\":\"LV\",\"durationTicks\":20,"
                    + "\"eut\":8,\"inputs\":[],\"outputs\":[],\"machineConfigControls\":[{\"$ref\":\"nope\"}]}"));
    }

    @Test
    void anUnknownHandlerReferenceIsAnError() {
        final MachineReadout r = new MachineReadout();
        assertThrows(
            IllegalArgumentException.class,
            () -> r.addRecipe(
                "x",
                "{\"id\":\"x\",\"name\":\"x\",\"machineType\":\"Bender\",\"minimumTier\":\"LV\",\"durationTicks\":20,"
                    + "\"eut\":8,\"inputs\":[],\"outputs\":[],\"machineHandlers\":[{\"$ref\":\"nope\"}]}"));
    }

    @Test
    void warmComputesCards() {
        assertTrue(MachineReadout.warm(20) > 10);
    }
}
