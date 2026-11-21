package com.gtnhplanner.client;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

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
        read(id, "proposal", Deliveries::applied);
    }

    /** Reads what is under the id in the background, then hands it to {@code then} on the client thread. */
    private static void read(final String id, final String kind, final Consumer<JsonObject> then) {
        if (Tutorial.active()) {
            say(DeliveryText.TOUR, true);
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
                    if (why != null) say(why, true);
                    else then.accept(got);
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

    /** A change arrived: added to the open plan when it was made for it, as one undo step. */
    private static void applied(final JsonObject body) {
        final JsonElement p = body.get("proposal");
        final Plan plans = Plan.getInstance();
        if (plans.openSlots()
            .isEmpty()) {
            say(DeliveryText.NO_PLAN, true);
            return;
        }
        if (p == null || !p.isJsonObject()) {
            say(DeliveryText.CHANGE_UNREADABLE, true);
            return;
        }
        final ProposalApply.Prepared prepared;
        try {
            prepared = ProposalApply.prepare(p.getAsJsonObject(), FactoryFlowImport::importFromText);
        } catch (final FfImportException e) {
            say(e.getMessage(), true);
            return;
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("Could not read the change the planner site delivered", e);
            say(DeliveryText.CHANGE_UNREADABLE, true);
            return;
        }
        if (!prepared.fits(Plan.getActiveGraph())) {
            say(DeliveryText.OTHER_PLAN, true);
            return;
        }
        final BoardSession session = board().session();
        final Graph graph = session.graph();
        if (!prepared.fits(graph)) {
            say(DeliveryText.OTHER_PLAN, true);
            return;
        }
        final ProposalApply.Outcome[] out = new ProposalApply.Outcome[1];
        final FfCards.Ports ports = new FfCards.Ports(new GameNodeMaker());
        session.edit(() -> out[0] = prepared.addTo(graph, ports));
        com.gtnhplanner.ui.sound.Sfx.PLACE.play();
        final String message = out[0].message(graph.getName());
        say(message, false);
        session.flash(Severity.INFO, message);
    }

    /** The planner screen, opened when it is not. */
    private static BoardScreen board() {
        final BoardScreen open = (BoardScreen) Planner.screenOf(Minecraft.getMinecraft().currentScreen);
        return open != null ? open : Planner.open();
    }

    /** One chat line: red for a refusal. */
    static void say(@Nullable final String text, final boolean problem) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || text == null) return;
        mc.thePlayer
            .addChatMessage(new ChatComponentText((problem ? EnumChatFormatting.RED : EnumChatFormatting.GRAY) + text));
    }
}
