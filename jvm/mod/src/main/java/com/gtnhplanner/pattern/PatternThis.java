package com.gtnhplanner.pattern;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.nbt.NBTTagCompound;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.balancer.Severity;
import com.gtnhplanner.nei.NeiRecipes;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.CardRecipes;
import com.gtnhplanner.ui.world.WorldView;

import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.RecipeHandlerRef;
import cpw.mods.fml.common.Loader;

/**
 * The client's half of "Pattern this": each recipe is packed exactly as Not Enough Energistics packs an NEI transfer
 * into a pattern terminal (its own {@code packRecipe}, so the player's NEE settings apply: preferred and blacklisted
 * items, merged inputs, non-consumed items left out), sent to the server, and the answers are summed into one sentence.
 */
public final class PatternThis {

    private static final String NEE_TERMINAL = "com.github.vfyjxf.nee.nei.NEEPatternTerminalHandler";
    private static final String NEE_PACKET = "com.github.vfyjxf.nee.network.packet.PacketNEIPatternRecipe";

    /** A batch the server has not finished answering after this long is reported as not answered. */
    static final long TIMEOUT_MS = 10_000;

    private static Boolean linked;
    private static Object nee;
    private static Method pack;
    private static Field packedIn, packedOut;

    private static int nextId;
    /** The batch waiting for answers: ask id to nothing, in the order sent. */
    private static final Map<Integer, Boolean> waiting = new LinkedHashMap<>();
    private static PatternRules.Tally tally = PatternRules.NONE;
    private static long sentAt;
    private static String lastSummary = "";

    private PatternThis() {}

    /** Whether the card menu offers the row: AE2 and Not Enough Energistics are in this game. */
    public static boolean offered() {
        return Loader.isModLoaded(PatternServer.AE2) && Loader.isModLoaded(PatternServer.NEE) && link();
    }

    /** Why the row cannot work here, or null when it can: the server must have said it makes patterns. */
    @Nullable
    public static String unavailable() {
        return Boolean.TRUE.equals(PatternNet.serverReady) ? null : "Not on this server";
    }

    /** "Pattern this" on a card: one pattern for its recipe, or for each recipe of its shared machine. */
    public static void card(final BoardSession session, final Node node) {
        final Graph graph = session.graph();
        final MachineGroup group = CardRecipes.machineOf(graph, node.id);
        final List<RecipeHandlerRef> refs = new ArrayList<>();
        int missing = 0;
        if (group == null) {
            final RecipeHandlerRef ref = NeiRecipes.page(node);
            if (ref != null) refs.add(ref);
            else missing++;
        } else {
            for (final Uuid id : group.getSections()) {
                final Node section = graph.nodes.get(id);
                final RecipeHandlerRef ref = section == null ? null : NeiRecipes.page(section);
                if (ref != null) refs.add(ref);
                else missing++;
            }
        }
        pattern(refs, missing);
    }

    /**
     * One pattern for each recipe, made by the server from the player's blank patterns. {@code missing} recipes that
     * NEI could not find again are counted as ones that cannot be patterned.
     */
    public static void pattern(final List<RecipeHandlerRef> refs, final int missing) {
        if (!waiting.isEmpty()) {
            report(Severity.WARN, "Still making the last patterns");
            return;
        }
        tally = PatternRules.NONE;
        for (int i = 0; i < missing; i++) tally = tally.plus(PatternRules.CANNOT, 0);
        if (unavailable() != null) {
            for (int i = 0; i < refs.size(); i++) tally = tally.plus(PatternRules.NO_SERVER, 0);
            done();
            return;
        }
        for (final RecipeHandlerRef ref : refs) {
            final NBTTagCompound[] packed = pack(ref.handler, ref.recipeIndex);
            if (packed == null || packed[0].hasNoTags()) {
                tally = tally.plus(PatternRules.CANNOT, 0);
                continue;
            }
            final int id = ++nextId;
            waiting.put(id, Boolean.TRUE);
            PatternNet.net.sendToServer(new PatternNet.Ask(id, packed[0], packed[1]));
        }
        sentAt = System.currentTimeMillis();
        if (waiting.isEmpty()) done();
    }

    /** The server's answer to one ask. */
    static void answered(final int id, final byte outcome, final int dropped) {
        if (waiting.remove(id) == null) return;
        tally = tally.plus(outcome, dropped);
        if (waiting.isEmpty()) done();
    }

    /** Each client tick: a batch the server stopped answering is reported as such. */
    static void tick() {
        if (waiting.isEmpty() || System.currentTimeMillis() - sentAt < TIMEOUT_MS) return;
        for (int i = waiting.size(); i > 0; i--) tally = tally.plus(PatternRules.NO_SERVER, 0);
        waiting.clear();
        done();
    }

    /** The sentence the last batch ended with. */
    public static String lastSummary() {
        return lastSummary;
    }

    private static void done() {
        lastSummary = PatternRules.summary(tally);
        report(tally.clean() ? Severity.INFO : Severity.WARN, PatternRules.summary(tally));
    }

    private static void report(final Severity severity, final String text) {
        final BoardSession session = BoardSession.current();
        if (session != null) session.flash(severity, text);
        else WorldView.say(text);
    }

    /** NEE's packing of one recipe: {inputs, outputs}, outputs null for a crafting-table recipe; null if it failed. */
    @Nullable
    public static NBTTagCompound[] pack(final IRecipeHandler handler, final int recipeIndex) {
        if (!link()) return null;
        try {
            final Object packet = pack.invoke(nee, handler, recipeIndex, 1);
            return new NBTTagCompound[] { (NBTTagCompound) packedIn.get(packet),
                (NBTTagCompound) packedOut.get(packet) };
        } catch (final ReflectiveOperationException | RuntimeException e) {
            GtnhPlanner.LOG.warn("Not Enough Energistics could not pack a recipe for a pattern", e);
            return null;
        }
    }

    private static boolean link() {
        if (linked == null) {
            try {
                final Class<?> terminal = Class.forName(NEE_TERMINAL);
                nee = terminal.getField("instance")
                    .get(null);
                pack = terminal.getDeclaredMethod("packRecipe", IRecipeHandler.class, int.class, int.class);
                pack.setAccessible(true);
                final Class<?> packet = Class.forName(NEE_PACKET);
                packedIn = packet.getDeclaredField("input");
                packedIn.setAccessible(true);
                packedOut = packet.getDeclaredField("output");
                packedOut.setAccessible(true);
                linked = true;
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                GtnhPlanner.LOG.warn("Pattern this is off: Not Enough Energistics is not as expected", e);
                linked = false;
            }
        }
        return linked;
    }
}
