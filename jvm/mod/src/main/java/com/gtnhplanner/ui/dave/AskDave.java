package com.gtnhplanner.ui.dave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.client.Deliveries;
import com.gtnhplanner.client.dave.DaveBridgeLink;
import com.gtnhplanner.client.dave.DaveChat;
import com.gtnhplanner.client.dave.DaveContext;
import com.gtnhplanner.client.dave.DaveLink;
import com.gtnhplanner.client.dave.DaveRecipes;
import com.gtnhplanner.client.dave.DaveTalk;
import com.gtnhplanner.client.dave.SlimSnapshot;
import com.gtnhplanner.core.base.Text;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.importer.FfConverter;
import com.gtnhplanner.importer.game.FactoryFlowImport;
import com.gtnhplanner.library.CommunityApi;
import com.gtnhplanner.library.Posting;
import com.gtnhplanner.library.SiteOff;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.card.CardModel;

/**
 * The game's side of Ask Dave: the conversation kept for the game session, the link (DaveBridge's, or the dev game's
 * stand-in), what an ask sends from the open plan, and the two ways an answer reaches the board.
 */
public final class AskDave {

    /** The conversation, kept while the game stays on the server; never saved. */
    public static final DaveTalk TALK = new DaveTalk(AskDave::link, System::currentTimeMillis);

    /** Whether the panel is open: kept while the game runs, so it is where it was when the planner opens again. */
    public static boolean open;

    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "GTNH Planner Ask Dave");
        t.setDaemon(true);
        return t;
    });

    private AskDave() {}

    @Nullable
    private static DaveLink link() {
        // The dev game has no bridge mod: its harness can stand one in. A release run never has the harness.
        if (com.gtnhplanner.dev.DevHarness.isEnabled()) {
            final DaveLink fake = com.gtnhplanner.dev.DevDave.link();
            if (fake != null) return fake;
        }
        return DaveBridgeLink.find();
    }

    /** The game left the server: the conversation goes. From any thread. */
    public static void forget() {
        Minecraft.getMinecraft()
            .func_152344_a(TALK::forget);
    }

    /** What an ask sends: the plan as the slim snapshot (left out over its cap), and the context naming it. */
    static DaveTalk.Ask ask(final BoardSession session) {
        final Graph g = session.graph();
        JsonObject snapshot = null;
        String attachment = null;
        try {
            snapshot = SlimSnapshot.of(g, Posting.world(id -> {
                final CardModel m = session.model(id);
                return m == null ? null : m.machines;
            }));
            attachment = SlimSnapshot.text(snapshot);
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("Ask Dave could not write the plan; it goes without it", e);
        }
        final Map<String, String> recipeIds = new HashMap<>();
        if (snapshot != null && snapshot.get("cards") instanceof final com.google.gson.JsonArray cards)
            for (final JsonElement c : cards) if (c instanceof final JsonObject o && o.has("id") && o.has("recipeId"))
                recipeIds.put(
                    o.get("id")
                        .getAsString(),
                    o.get("recipeId")
                        .getAsString());
        final List<String> tiers = new ArrayList<>();
        for (final CardModel m : session.models()
            .values()) if (m != null && m.tier != null) tiers.add(m.tier);
        final List<DaveContext.Card> selection = new ArrayList<>();
        for (final Uuid id : session.selection()) {
            final Node n = g.nodes.get(id);
            if (n == null) continue;
            final String key = id.toString();
            selection.add(new DaveContext.Card(key, recipeIds.getOrDefault(key, "gtnhplanner:" + key), label(session, n)));
        }
        final DaveContext.Plan plan = new DaveContext.Plan(
            g.planId(),
            g.getName(),
            Posting.packVersion(),
            g.getNodes()
                .size(),
            g.getCloudKey() != null,
            DaveContext.topTier(tiers),
            selection);
        return new DaveTalk.Ask(DaveContext.json(plan, attachment == null), attachment);
    }

    /** A card's name as the player knows it: its machine, and what it makes first. */
    private static String label(final BoardSession session, final Node n) {
        final CardModel m = session.model(n.id);
        if (m == null) return n.machineName == null ? "" : n.machineName;
        return m.outputs.isEmpty() ? m.machineName
            : m.machineName + " ("
                + m.outputs.get(0)
                    .name()
                + ")";
    }

    /** Add to plan: the deliveries path; its line also goes to the panel. */
    static void apply(final DaveChat.Turn turn, final String proposalId) {
        Deliveries.apply(proposalId, (text, problem) -> TALK.applied(turn, text, problem));
    }

    /**
     * A recipe row's Add: the site's recipe read in the background, then, on the client thread, a card on the game's
     * own recipe like it, put on the open board as one undo step.
     */
    static void addRecipe(final BoardSession session, final DaveChat.Recipe recipe,
        final BiConsumer<String, Boolean> done) {
        final String pack = Posting.packVersion();
        NET.execute(() -> {
            String body = null, problem = null;
            try {
                body = CommunityApi.recipe(pack, recipe.recipeId());
            } catch (final SiteOff e) {
                problem = SiteOff.MESSAGE;
            } catch (final CommunityApi.Refused e) {
                problem = e.status == 404 ? t("gtnhplanner.dave.recipe.unknown") : t("gtnhplanner.dave.recipe.unread");
            } catch (final java.io.IOException | RuntimeException e) {
                GtnhPlanner.LOG.info("Ask Dave could not read recipe {}: {}", recipe.recipeId(), e.toString());
                problem = t("gtnhplanner.dave.recipe.unread");
            }
            final String text = body, why = problem;
            Minecraft.getMinecraft()
                .func_152344_a(() -> {
                    if (why != null) done.accept(why, true);
                    // The client thread's task queue keeps a failure to itself: it is logged and told here.
                    else try {
                        place(session, recipe, text, done);
                    } catch (final RuntimeException | LinkageError e) {
                        GtnhPlanner.LOG.warn("Ask Dave could not add recipe {}", recipe.recipeId(), e);
                        done.accept(t("gtnhplanner.dave.recipe.unread"), true);
                    }
                });
        });
    }

    private static void place(final BoardSession session, final DaveChat.Recipe recipe, final String body,
        final BiConsumer<String, Boolean> done) {
        final String plan = DaveRecipes.plan(recipe.recipeId(), body);
        if (plan == null) {
            done.accept(t("gtnhplanner.dave.recipe.unread"), true);
            return;
        }
        final Node card;
        try {
            final FfConverter.Result r = FactoryFlowImport.importFromText(plan);
            card = DaveRecipes.card(r.graph());
        } catch (final RuntimeException | LinkageError e) {
            GtnhPlanner.LOG.warn("Ask Dave could not make a card of recipe {}", recipe.recipeId(), e);
            done.accept(t("gtnhplanner.dave.recipe.unread"), true);
            return;
        }
        if (card == null) {
            done.accept(t("gtnhplanner.dave.recipe.no_match"), true);
            return;
        }
        session.addCard(card);
        done.accept(t("gtnhplanner.dave.recipe.done"), false);
    }

    static String t(final String key) {
        return Text.get()
            .translate(key);
    }
}
