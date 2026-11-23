package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Group;
import com.gtnhplanner.data.flowchart.MachineGroup;

/**
 * A proposal wire to a later recipe of the plan's shared machine: the snapshot and the export name it as the machine's
 * card with an {@code r<n>:} handle, so the wire lands on that recipe's ports, not the first recipe's.
 */
class ProposalApplySectionsTest {

    private static final String SHARED = "node-5f49041d-0bc1-4ef6-8cea-ad435b3d2add";
    private static final String SODIUM_ALUMINATE = "gregtech%3Agt.metaitem.01%402696";

    @Test
    void aWireToASharedMachinesLaterRecipeLandsOnThatRecipe() {
        final FakeGame game = new FakeGame();
        final JsonObject plan = new Gson()
            .fromJson(FakeGame.fixture("alumina-line-shared-reactor.json"), JsonObject.class);
        final FfConverter.Result r = FfImport.importFromText(plan.toString(), game, game);
        final Graph g = r.graph();
        final String host = r.placed()
            .cards()
            .get(SHARED);
        final List<Uuid> sections = sections(g, Uuid.fromString(host));
        assertEquals(4, sections.size());

        // A card running the machine's fourth recipe (it makes sodium aluminate), wired to the third, which takes it.
        JsonObject card = null;
        for (final JsonElement n : plan.getAsJsonArray("nodes")) if (SHARED.equals(
            n.getAsJsonObject()
                .get("id")
                .getAsString()))
            card = new Gson().fromJson(n.toString(), JsonObject.class);
        assertNotNull(card);
        final JsonObject fourth = card.getAsJsonArray("extraRecipes")
            .get(2)
            .getAsJsonObject();
        card.addProperty("id", "c1");
        card.add("recipeId", fourth.get("recipeId"));
        card.add("recipeInputOverrides", fourth.get("recipeInputOverrides"));
        card.remove("extraRecipes");
        final JsonObject at = new JsonObject();
        at.addProperty("x", 6000);
        at.addProperty("y", 0);
        card.add("position", at);
        final JsonObject wire = new JsonObject();
        wire.addProperty("id", "w1");
        wire.addProperty("source", "c1");
        wire.addProperty("target", host);
        wire.addProperty("sourceHandle", "output:item:" + SODIUM_ALUMINATE + ":0");
        wire.addProperty("targetHandle", "r2:input:item:" + SODIUM_ALUMINATE + ":0");
        wire.addProperty("resourceKind", "item");
        wire.addProperty("resourceId", "gregtech:gt.metaitem.01@2696");
        final JsonObject proposal = new JsonObject();
        proposal.addProperty("version", 1);
        proposal.addProperty("basePlanId", g.planId());
        proposal.add("recipes", plan.get("recipes"));
        final JsonArray cards = new JsonArray(), wires = new JsonArray();
        cards.add(card);
        wires.add(wire);
        proposal.add("cards", cards);
        proposal.add("drawers", new JsonArray());
        proposal.add("wires", wires);
        proposal.add("targets", new JsonArray());

        final List<Uuid> before = new ArrayList<>(g.edges.keySet());
        final ProposalApply.Outcome out = ProposalApply
            .prepare(proposal, text -> FfImport.importFromText(text, game, game))
            .addTo(g, new FfCards.Ports(game));

        assertEquals(1, out.cards());
        assertEquals(1, out.wires());
        assertEquals(0, out.wiresNoPort() + out.wiresGone());
        Edge added = null;
        for (final Edge e : g.getEdges()) if (!before.contains(e.id)) added = e;
        assertNotNull(added);
        assertEquals(sections.get(2), added.targetNodeId, "the third recipe takes it");
    }

    private static List<Uuid> sections(final Graph g, final Uuid host) {
        for (final Group grp : g.getGroups()) if (grp instanceof final MachineGroup m && m.getSections()
            .contains(host)) return m.getSections();
        return List.of();
    }
}
