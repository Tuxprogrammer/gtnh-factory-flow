package com.gtnhplanner.client;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.balancer.Severity;
import com.gtnhplanner.importer.FfCards;
import com.gtnhplanner.importer.FfConverter;
import com.gtnhplanner.importer.FfImportException;
import com.gtnhplanner.importer.ImportReport;
import com.gtnhplanner.importer.ProposalApply;
import com.gtnhplanner.importer.game.FactoryFlowImport;
import com.gtnhplanner.importer.game.GameNodeMaker;
import com.gtnhplanner.library.CommunityApi;
import com.gtnhplanner.library.SiteOff;
import com.gtnhplanner.nei.PlanBookmarks;
import com.gtnhplanner.ui.BoardScreen;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.Planner;
import com.gtnhplanner.ui.tutorial.Tutorial;

/**
 * What the site's assistant hands a player by a short id ({@code GET <site>/api/deliveries/<id>}, through the site
 * client, so its address, its guard header and "no site" behave as for the library): a plan to open as a new plan, or
 * a change to add to the open plan. The read runs in the background; what it brings is opened or added on the client
 * thread. Every outcome is one chat line.
 */
public final class Deliveries {

    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "GTNH Planner deliveries");
        t.setDaemon(true);
        return t;
    });

    private Deliveries() {}

    /** {@code open <id>}: the plan under the id, opened as a new plan and shown. Never over the open plan. */
    public static void open(final String id) {
        read(id, "delivery", Deliveries::opened);
    }

    /**
     * {@code apply <id>}: the change under the id added to the open plan as one undo step, if it was made for that
     * plan. The entry point for anything else that adds a proposal (the Ask Dave panel's Add to plan).
     */
    public static void apply(final String id) {
        apply(id, (text, problem) -> {});
    }

    /** As {@link #apply(String)}, and {@code told} gets the line the player is told, and whether it is a refusal. */
    public static void apply(final String id, final BiConsumer<String, Boolean> told) {
        read(id, "proposal", body -> applied(body, told), told);
    }

    /**
     * {@code bookmark <id>}: what the plan under the id takes in and gives out, added to NEI's bookmarks (its shopping
     * list). The plan is read, not opened: no plan tab changes.
     */
    public static void bookmark(final String id) {
        read(id, "delivery", Deliveries::bookmarked);
    }

    /** Reads what is under the id in the background, then hands it to {@code then} on the client thread. */
    private static void read(final String id, final String kind, final Consumer<JsonObject> then) {
        read(id, kind, then, (text, problem) -> {});
    }

    private static void read(final String id, final String kind, final Consumer<JsonObject> then,
        final BiConsumer<String, Boolean> told) {
        if (Tutorial.active()) {
            say(DeliveryText.TOUR, true, told);
            return;
        }
        NET.execute(() -> {
            String problem = null;
            JsonObject body = null;
            try {
                final JsonElement e = new JsonParser().parse(CommunityApi.delivery(id));
                if (e.isJsonObject() && e.getAsJsonObject()
                    .has("kind")
                    && kind.equals(
                        e.getAsJsonObject()
                            .get("kind")
                            .getAsString()))
                    body = e.getAsJsonObject();
                else problem = unreadable(kind);
            } catch (final SiteOff e) {
                problem = SiteOff.MESSAGE;
            } catch (final CommunityApi.Refused e) {
                problem = DeliveryText.refused(e.status, e.getMessage());
            } catch (final IOException e) {
                GtnhPlanner.LOG.info("The planner site could not be reached for {}: {}", id, e.toString());
                problem = DeliveryText.UNREACHABLE;
            } catch (final RuntimeException e) {
                problem = unreadable(kind);
            }
            final String why = problem;
            final JsonObject got = body;
            Minecraft.getMinecraft()
                .func_152344_a(() -> {
                    if (why != null) say(why, true, told);
                    // The client thread's task queue keeps a failure to itself: it is logged and told here.
                    else try {
                        then.accept(got);
                    } catch (final RuntimeException | LinkageError e) {
                        GtnhPlanner.LOG.warn("Could not use what the planner site delivered under {}", id, e);
                        say(unreadable(kind), true, told);
                    }
                });
        });
    }

    private static String unreadable(final String kind) {
        return "delivery".equals(kind) ? DeliveryText.PLAN_UNREADABLE : DeliveryText.CHANGE_UNREADABLE;
    }

    /** A plan arrived: added as a new plan through the website-plan import, and the planner shown on it. */
    private static void opened(final JsonObject body) {
        final JsonElement plan = body.get("plan");
        if (plan == null || !plan.isJsonObject()) {
            say(DeliveryText.PLAN_UNREADABLE, true);
            return;
        }
        final FfConverter.Result result;
        try {
            result = FactoryFlowImport.importAsSlot(plan.toString());
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("Could not open the plan the planner site delivered", e);
            say(DeliveryText.PLAN_UNREADABLE, true);
            return;
        }
        // On the board already, it follows the new plan by itself (as a paste does); else the planner opens on it.
        final BoardScreen board = board();
        com.gtnhplanner.ui.sound.Sfx.SWEEP.play();
        final String name = result.graph()
            .getName();
        say(DeliveryText.opened(name), false);
        if (!result.report()
            .entries(ImportReport.Kind.UNMATCHED)
            .isEmpty())
            board.session()
                .flash(
                    Severity.WARN,
                    "Opened '" + name
                        + "': "
                        + result.report()
                            .summary());
    }

    /** A plan arrived to bookmark: converted against the game's recipes, solved, its inputs and outputs bookmarked. */
    private static void bookmarked(final JsonObject body) {
        final JsonElement plan = body.get("plan");
        if (plan == null || !plan.isJsonObject()) {
            say(DeliveryText.PLAN_UNREADABLE, true);
            return;
        }
        final Graph graph;
        try {
            graph = FactoryFlowImport.importFromText(plan.toString())
                .graph();
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("Could not read the plan the planner site delivered", e);
            say(DeliveryText.PLAN_UNREADABLE, true);
            return;
        }
        final PlanBookmarks.Outcome outcome = PlanBookmarks.addShoppingList(graph);
        say(outcome.message(), outcome.failed());
    }

    /** A change read and checked against the open plan: ready to add, or the refusal the player is told. */
    record Checked(@Nullable ProposalApply.Prepared prepared, @Nullable String refusal) {}

    /**
     * Reads the change in a delivery and checks it was made for {@code open} (null when no plan is open). Changes
     * nothing.
     */
    static Checked check(final JsonObject body, @Nullable final Graph open,
        final Function<String, FfConverter.Result> convert) {
        final JsonElement p = body.get("proposal");
        if (open == null) return new Checked(null, DeliveryText.NO_PLAN);
        if (p == null || !p.isJsonObject()) return new Checked(null, DeliveryText.CHANGE_UNREADABLE);
        final ProposalApply.Prepared prepared;
        try {
            prepared = ProposalApply.prepare(p.getAsJsonObject(), convert);
        } catch (final FfImportException e) {
            return new Checked(null, e.getMessage());
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("Could not read the change the planner site delivered", e);
            return new Checked(null, DeliveryText.CHANGE_UNREADABLE);
        }
        if (!prepared.fits(open)) return new Checked(null, DeliveryText.OTHER_PLAN);
        return new Checked(prepared, null);
    }

    /** A change arrived: added to the open plan when it was made for it, as one undo step. */
    private static void applied(final JsonObject body, final BiConsumer<String, Boolean> told) {
        final Checked checked = check(
            body,
            Plan.getInstance()
                .openSlots()
                .isEmpty() ? null : Plan.getActiveGraph(),
            FactoryFlowImport::importFromText);
        if (checked.refusal() != null) {
            say(checked.refusal(), true, told);
            return;
        }
        final ProposalApply.Prepared prepared = checked.prepared();
        final BoardScreen board = board();
        final BoardSession session = board.session();
        final Graph graph = session.graph();
        if (!prepared.fits(graph)) {
            say(DeliveryText.OTHER_PLAN, true, told);
            return;
        }
        final ProposalApply.Outcome[] out = new ProposalApply.Outcome[1];
        final FfCards.Ports ports = new FfCards.Ports(new GameNodeMaker());
        session.edit(() -> out[0] = prepared.addTo(graph, ports));
        // The board goes to what came in, as after a paste: panned the least it takes, framed when it is bigger.
        if (!out[0].added()
            .isEmpty())
            board.canvas()
                .revealWhenBuilt(out[0].added());
        com.gtnhplanner.ui.sound.Sfx.PLACE.play();
        final String message = out[0].message(graph.getName());
        say(message, false, told);
        session.flash(Severity.INFO, message);
    }

    /** The planner screen, opened when it is not. */
    private static BoardScreen board() {
        final BoardScreen open = (BoardScreen) Planner.screenOf(Minecraft.getMinecraft().currentScreen);
        return open != null ? open : Planner.open();
    }

    /** One chat line, also handed to {@code told}. */
    private static void say(@Nullable final String text, final boolean problem,
        final BiConsumer<String, Boolean> told) {
        say(text, problem);
        if (text != null) told.accept(text, problem);
    }

    /** One chat line: red for a refusal. */
    static void say(@Nullable final String text, final boolean problem) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || text == null) return;
        mc.thePlayer
            .addChatMessage(new ChatComponentText((problem ? EnumChatFormatting.RED : EnumChatFormatting.GRAY) + text));
    }
}
