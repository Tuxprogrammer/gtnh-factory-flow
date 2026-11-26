package com.gtnhplanner.pattern;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.flowchart.balancer.Severity;
import com.gtnhplanner.nei.BookmarkPlan;
import com.gtnhplanner.nei.NeiRecipes;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.CardRecipes;
import com.gtnhplanner.ui.Resources;
import com.gtnhplanner.ui.card.CardDefaults;
import com.gtnhplanner.ui.world.WorldView;

import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.Recipe;
import codechicken.nei.recipe.RecipeHandlerRef;
import codechicken.nei.recipe.StackInfo;
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
    /** Asks sent and not answered yet, at most: a big plan goes to the server this many at a time. */
    static final int IN_FLIGHT = 32;

    private static Boolean linked;
    private static Object nee;
    private static Method pack;
    private static Field packedIn, packedOut;

    private static int nextId;
    /** The batch waiting for answers: ask id to nothing, in the order sent. */
    private static final Map<Integer, Boolean> waiting = new LinkedHashMap<>();
    /** Asks of the batch not sent yet, sent as answers come back. */
    private static final ArrayDeque<PatternNet.Ask> queued = new ArrayDeque<>();
    /** Cards a whole-plan batch left out, said at its end; -1 for a card's batch. */
    private static int skipped = -1;
    private static PatternRules.Tally tally = PatternRules.NONE;
    private static long sentAt;
    private static String lastSummary = "";

    /** Each ask of the batch's place in it (in the plan's run order for a plan's page). */
    private static final Map<Integer, Integer> atOf = new HashMap<>();
    /** Places that came back unmade for a reason that can pass (no room, no blank, no server). */
    private static final List<Integer> notMade = new ArrayList<>();
    /** Places that cannot be patterned, so a page that stops early tells only of those before where it stopped. */
    private static final List<Integer> cannotAt = new ArrayList<>();
    /** The plan's page being made, null for a card's batch. */
    @Nullable
    private static Page page;
    /**
     * Where each plan's run got to, by plan id, for this game: kept when the board closes (the player closes it to
     * free inventory slots), never saved, shown on the Share menu's row with a row that starts over.
     */
    private static final Map<String, PatternPages.Position<Object>> positions = new HashMap<>();

    /** A plan's page: the plan, the run's order and where the page starts and ends. */
    private record Page(String plan, PatternPages.Start<Object> start, int end) {}

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

    /**
     * "Pattern this" on a card: one pattern for its recipe, or for each recipe of its shared machine. A recipe with
     * more
     * than one product first asks which products go in ({@link PatternPick}), opening on the card's remembered choice,
     * else the first product alone; the choice is remembered for the card ({@link PatternMemory}).
     */
    public static void card(final BoardSession session, final Node node) {
        final Graph graph = session.graph();
        final MachineGroup group = CardRecipes.machineOf(graph, node.id);
        final List<Job> jobs = new ArrayList<>();
        int missing = 0;
        final List<Node> sections = new ArrayList<>();
        if (group == null) sections.add(node);
        else for (final Uuid id : group.getSections()) sections.add(graph.nodes.get(id));
        for (final Node section : sections) {
            final RecipeHandlerRef ref = section == null ? null : NeiRecipes.page(section);
            if (ref != null) jobs.add(new Job(ref, section, PatternMemory.get(graph, section)));
            else missing++;
        }
        // The box lists every recipe that has products to pick; it opens only when one has more than one.
        final List<PatternPick.Recipe> picks = new ArrayList<>();
        final int[] pickOf = new int[jobs.size()];
        boolean ask = false;
        for (int i = 0; i < jobs.size(); i++) {
            pickOf[i] = -1;
            final Job job = jobs.get(i);
            final NBTTagCompound[] packed = pack(job.ref().handler, job.ref().recipeIndex);
            if (packed == null || packed[1] == null) continue;
            final List<PatternPick.Row> rows = rows(packed[1], job.card());
            if (rows.isEmpty()) continue;
            final List<String> products = new ArrayList<>();
            for (final PatternPick.Row r : rows) products.add(r.key());
            ask |= PatternOutputs.asks(products);
            picks.add(
                new PatternPick.Recipe(
                    sections.size() > 1 ? PatternPick.t("recipe", sections.indexOf(job.card()) + 1) : null,
                    rows,
                    PatternOutputs.chosen(products, job.products())));
            pickOf[i] = picks.size() - 1;
        }
        if (!ask || session.panel() == null) {
            start(jobs, missing, -1);
            return;
        }
        final int lost = missing;
        PatternPick.open(session.panel(), picks, jobs.size() > 1, chosen -> {
            final List<Job> made = new ArrayList<>();
            for (int i = 0; i < jobs.size(); i++) {
                final Job job = jobs.get(i);
                final int at = pickOf[i];
                if (at < 0) {
                    made.add(job);
                    continue;
                }
                final List<String> included = chosen.get(at).included;
                PatternMemory.put(graph, job.card(), included);
                made.add(new Job(job.ref(), job.card(), included));
            }
            start(made, lost, -1);
        });
    }

    /**
     * One recipe to pattern: its NEI page, the card it is on (null for none; its order of products comes first), and
     * the products its pattern includes (null: the first product alone).
     */
    public record Job(RecipeHandlerRef ref, @Nullable Node card, @Nullable List<String> products) {}

    /**
     * One pattern for each recipe, made by the server from the player's blank patterns. {@code missing} recipes that
     * NEI could not find again are counted as ones that cannot be patterned. Each pattern holds the recipe's first
     * product alone.
     */
    public static void pattern(final List<RecipeHandlerRef> refs, final int missing) {
        final List<Job> jobs = new ArrayList<>();
        for (final RecipeHandlerRef ref : refs) jobs.add(new Job(ref, null, null));
        start(jobs, missing, -1);
    }

    /** As {@link #pattern}, each recipe with the products its job names. */
    public static void patterns(final List<Job> jobs, final int missing) {
        start(jobs, missing, -1);
    }

    /** The Share menu's "Pattern the plan" on the open plan. */
    public static void plan(final BoardSession session) {
        plan(session.graph(), false);
    }

    /** The Share menu's "Pattern the plan from the start": the plan's run made again from its first recipe. */
    public static void planFromStart(final BoardSession session) {
        plan(session.graph(), true);
    }

    /** The Share menu's row while the open plan's run is part made ("Pattern the plan (28 to 55 of 55)"), else null. */
    @Nullable
    public static String partMade(final BoardSession session) {
        final PatternPages.Position<Object> at = positions.get(
            session.graph()
                .planId());
        return at == null ? null : PatternPages.row(at);
    }

    /**
     * "Pattern the plan": one pattern for each distinct recipe of {@code plan} ({@link PatternPlan#pick}), each made by
     * the server from one of the player's blank patterns exactly as for one card, and one sentence when the last one
     * is answered (on the board, or in chat with no board open). A plan is made a page at a time
     * ({@link PatternPages}): as many as the player has free slots for now (and blank patterns, unless in creative), in
     * a fixed order, and running it again goes on from there; {@code fromStart} starts the run over. Each pattern holds
     * the products remembered for its card, else its first product alone; no box asks.
     */
    public static void plan(final Graph plan, final boolean fromStart) {
        if (busy()) return;
        final BookmarkPlan.Pick<Job> pick = PatternPlan.pick(plan.nodes.values(), n -> {
            final RecipeHandlerRef ref = refOf(n);
            return ref == null ? null : new Job(ref, n, PatternMemory.get(plan, n));
        }, job -> keyOf(job.ref()));
        final Map<Object, Job> byKey = new LinkedHashMap<>();
        for (final Job job : pick.recipes()) byKey.put(keyOf(job.ref()), job);
        if (fromStart) positions.remove(plan.planId());
        final PatternPages.Start<Object> at = PatternPages
            .start(positions.get(plan.planId()), new ArrayList<>(byKey.keySet()));
        final List<Job> jobs = new ArrayList<>();
        for (final Object key : at.order()) jobs.add(byKey.get(key));
        start(jobs, 0, pick.skipped(), plan.planId(), at);
    }

    /** Says so and is true while the last batch still waits for answers. */
    private static boolean busy() {
        if (waiting.isEmpty() && queued.isEmpty()) return false;
        report(Severity.WARN, "Still making the last patterns");
        return true;
    }

    private static void start(final List<Job> jobs, final int missing, final int skippedCards) {
        start(jobs, missing, skippedCards, null, null);
    }

    /**
     * Asks for the jobs' patterns: as many as the player has room for now ({@link AePatterns#room}). A card's batch
     * counts the rest as not made; a plan's page ({@code plan} and {@code at} given) ends there and the run remembers
     * the first recipe not made.
     */
    private static void start(final List<Job> jobs, final int missing, final int skippedCards,
        @Nullable final String plan, @Nullable final PatternPages.Start<Object> at) {
        if (busy()) return;
        tally = PatternRules.NONE;
        skipped = skippedCards;
        atOf.clear();
        notMade.clear();
        cannotAt.clear();
        page = null;
        for (int i = 0; i < missing; i++) tally = tally.plus(PatternRules.CANNOT, 0);
        final int from = at == null ? 0 : at.from();
        if (unavailable() != null) {
            for (int i = from; i < jobs.size(); i++) tally = tally.plus(PatternRules.NO_SERVER, 0);
            if (at != null) {
                page = new Page(plan, at, from);
                notMade.add(from);
            }
            done();
            return;
        }
        final NBTTagCompound[][] packed = new NBTTagCompound[jobs.size()][];
        for (int i = from; i < jobs.size(); i++) {
            final Job job = jobs.get(i);
            final NBTTagCompound[] p = pack(job.ref().handler, job.ref().recipeIndex);
            packed[i] = p == null || p[0].hasNoTags() ? null : p;
        }
        int makeable = 0;
        for (int i = from; i < jobs.size(); i++) if (packed[i] != null) makeable++;
        final EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        final PatternRules.Room room = player == null ? new PatternRules.Room(makeable, false)
            : AePatterns.room(player, makeable);
        final int end = at == null ? jobs.size()
            : PatternPages.end(from, jobs.size(), room.fit(), i -> packed[i] == null);
        if (at != null) page = new Page(plan, at, end);
        final byte rest = room.blanksShort() ? PatternRules.NO_BLANK : PatternRules.NO_ROOM;
        final List<PatternNet.Ask> asks = new ArrayList<>();
        for (int i = from; i < jobs.size(); i++) {
            if (packed[i] == null) {
                if (i < end) {
                    tally = tally.plus(PatternRules.CANNOT, 0);
                    cannotAt.add(i);
                }
                continue;
            }
            if (i >= end || asks.size() >= room.fit()) {
                tally = tally.plus(rest, 0);
                continue;
            }
            final PatternNet.Ask ask = new PatternNet.Ask(
                ++nextId,
                packed[i][0],
                packed[i][1] == null ? null : only(packed[i][1], jobs.get(i)));
            atOf.put(ask.id, i);
            asks.add(ask);
        }
        queued.addAll(asks);
        send();
        if (waiting.isEmpty()) done();
    }

    /** Sends queued asks until {@link #IN_FLIGHT} wait for answers. */
    private static void send() {
        while (waiting.size() < IN_FLIGHT && !queued.isEmpty()) {
            final PatternNet.Ask ask = queued.poll();
            waiting.put(ask.id, Boolean.TRUE);
            PatternNet.net.sendToServer(ask);
        }
        sentAt = System.currentTimeMillis();
    }

    /** The card's recipe page in NEI; null when NEI cannot find it. */
    @Nullable
    private static RecipeHandlerRef refOf(final Node node) {
        try {
            return NeiRecipes.page(node);
        } catch (final RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** What makes two cards one recipe: NEI's id for it, as the bookmark rows compare recipes. */
    private static Object keyOf(final RecipeHandlerRef ref) {
        try {
            return Recipe.of(ref)
                .getRecipeId();
        } catch (final RuntimeException | LinkageError e) {
            return ref.handler.getClass()
                .getName() + "#" + ref.recipeIndex;
        }
    }

    /** The server's answer to one ask. */
    static void answered(final int id, final byte outcome, final int dropped) {
        if (waiting.remove(id) == null) return;
        tally = tally.plus(outcome, dropped);
        if (outcome == PatternRules.NO_ROOM || outcome == PatternRules.NO_BLANK || outcome == PatternRules.NO_SERVER)
            notMade(id);
        else if (outcome != PatternRules.MADE && atOf.get(id) != null) cannotAt.add(atOf.get(id));
        // With no room for one pattern there is none for the rest: they are not asked for and count as not made.
        if (outcome == PatternRules.NO_ROOM) for (PatternNet.Ask ask; (ask = queued.poll()) != null;) {
            tally = tally.plus(PatternRules.NO_ROOM, 0);
            notMade(ask.id);
        }
        send();
        if (waiting.isEmpty()) done();
    }

    private static void notMade(final int id) {
        final Integer at = atOf.get(id);
        if (at != null) notMade.add(at);
    }

    /** Each client tick: a batch the server stopped answering for {@link #TIMEOUT_MS} is reported as such. */
    static void tick() {
        if (waiting.isEmpty() || System.currentTimeMillis() - sentAt < TIMEOUT_MS) return;
        for (int i = waiting.size() + queued.size(); i > 0; i--) tally = tally.plus(PatternRules.NO_SERVER, 0);
        for (final int id : waiting.keySet()) notMade(id);
        for (final PatternNet.Ask ask : queued) notMade(ask.id);
        waiting.clear();
        queued.clear();
        done();
    }

    /** The sentence the last batch ended with. */
    public static String lastSummary() {
        return lastSummary;
    }

    /**
     * The batch's sentence; a plan's page also moves the plan's run on to the first recipe it did not make, or ends
     * the run when the plan is done.
     */
    private static void done() {
        if (page != null) {
            final List<Object> order = page.start()
                .order();
            final int resume = PatternPages.resume(page.end(), notMade);
            final PatternPages.Position<Object> next = PatternPages.after(order, resume);
            if (next == null) positions.remove(page.plan());
            else positions.put(page.plan(), next);
            final int cannot = PatternPages.before(cannotAt, resume);
            final PatternRules.Tally told = new PatternRules.Tally(
                tally.made(),
                tally.noBlank(),
                cannot,
                tally.noServer(),
                tally.noRoom(),
                tally.dropped());
            lastSummary = PatternPlan.page(
                told,
                page.start()
                    .from(),
                resume,
                order.size(),
                page.start()
                    .restarted(),
                skipped);
        } else lastSummary = skipped < 0 ? PatternRules.summary(tally) : PatternPlan.summary(tally, skipped);
        report(tally.clean() ? Severity.INFO : Severity.WARN, lastSummary);
    }

    private static void report(final Severity severity, final String text) {
        final BoardSession session = BoardSession.current();
        if (session != null) session.flash(severity, text);
        else WorldView.say(text);
    }

    /**
     * The packed outputs with only the job's products left: the other output keys are taken out and the rest keep
     * their slots, as clearing those output slots in the terminal before encoding does. Inputs are never touched.
     */
    private static NBTTagCompound only(final NBTTagCompound out, final Job job) {
        final List<String> keys = packedKeys(out);
        final List<String> products = PatternOutputs.products(keys, cardKeys(job.card()));
        final List<Integer> keep = PatternOutputs.keep(keys, PatternOutputs.chosen(products, job.products()));
        final NBTTagCompound kept = new NBTTagCompound();
        for (final int slot : keep) {
            final String k = PatternRules.OUT + slot;
            if (out.hasKey(k)) kept.setTag(
                k,
                out.getTag(k)
                    .copy());
        }
        return kept;
    }

    /** The products the card's pattern can hold, in the card's order; none for a crafting-table recipe. */
    public static List<String> products(final Node card) {
        final RecipeHandlerRef ref = refOf(card);
        final NBTTagCompound[] packed = ref == null ? null : pack(ref.handler, ref.recipeIndex);
        if (packed == null || packed[1] == null) return List.of();
        return PatternOutputs.products(packedKeys(packed[1]), cardKeys(card));
    }

    /** The products remembered for the card in {@code plan}; null when never chosen. */
    @Nullable
    public static List<String> remembered(final Graph plan, final Node card) {
        return PatternMemory.get(plan, card);
    }

    /** Remembers the card's products in {@code plan}, as Make pattern in the box does. */
    public static void remember(final Graph plan, final Node card, final List<String> products) {
        PatternMemory.put(plan, card, products);
    }

    /** The product in each packed output slot, by slot ("" for a slot with nothing readable in it). */
    public static List<String> packedKeys(final NBTTagCompound out) {
        final List<String> keys = new ArrayList<>();
        for (final Object o : out.func_150296_c()) {
            final int slot = PatternRules.slot(String.valueOf(o), PatternRules.OUT);
            if (slot < 0 || slot >= PatternRules.MAX_KEYS) continue;
            while (keys.size() <= slot) keys.add("");
            keys.set(slot, productOf(out.getCompoundTag(String.valueOf(o))));
        }
        return keys;
    }

    /** A packed output's product, by Not Enough Energistics' rule: a fluid's display item is the fluid. */
    private static String productOf(final NBTTagCompound tag) {
        final ItemStack s = ItemStack.loadItemStackFromNBT(tag);
        if (s == null || s.getItem() == null) return "";
        final FluidStack fluid = fluidOf(s);
        if (fluid != null && fluid.getFluid() != null) return "fluid:" + fluid.getFluid()
            .getName();
        return CardDefaults.itemKey(s);
    }

    /**
     * The fluid a packed output's display item stands for, null for an item (Not Enough Energistics' rule). NEI before
     * 2.9.0's RC-1 has no {@code isFluidDisplayItem}: there {@code getFluid} alone reads it.
     */
    @Nullable
    private static FluidStack fluidOf(final ItemStack s) {
        try {
            return StackInfo.isFluidDisplayItem(s) ? StackInfo.getFluid(s) : null;
        } catch (final LinkageError e) {
            return StackInfo.getFluid(s);
        }
    }

    /** The card's products in the order it shows them; none for no card. */
    private static List<String> cardKeys(@Nullable final Node card) {
        final List<String> keys = new ArrayList<>();
        if (card != null) for (final Port<?> p : card.outputs) keys.add(Resources.key(p));
        return keys;
    }

    /** The pattern's products as the box lists them: in the card's order, each with its picture, name and amount. */
    private static List<PatternPick.Row> rows(final NBTTagCompound out, @Nullable final Node card) {
        final List<String> keys = packedKeys(out);
        final List<PatternPick.Row> rows = new ArrayList<>();
        for (final String key : PatternOutputs.products(keys, cardKeys(card))) {
            Port<?> port = null;
            if (card != null) for (final Port<?> p : card.outputs) if (key.equals(Resources.key(p))) {
                port = p;
                break;
            }
            ItemStack item = null;
            FluidStack fluid = null;
            String name;
            long amount = 0;
            float chance = 1;
            if (port != null) {
                if (port.getValue() instanceof final FluidStack f) fluid = f;
                else if (port.getValue() instanceof final ItemStack i) item = i;
                name = port.getDisplayName();
                amount = Math.round(port.amount());
                chance = port.getChance();
            } else {
                name = key;
                for (int slot = 0; slot < keys.size(); slot++) {
                    if (!key.equals(keys.get(slot))) continue;
                    final NBTTagCompound tag = out.getCompoundTag(PatternRules.OUT + slot);
                    final ItemStack s = ItemStack.loadItemStackFromNBT(tag);
                    if (s == null) continue;
                    final FluidStack f = fluidOf(s);
                    if (f != null) {
                        fluid = f;
                        name = f.getLocalizedName();
                        amount += f.amount;
                    } else {
                        item = s;
                        name = s.getDisplayName();
                        amount += tag.getInteger("Count");
                    }
                }
            }
            String text = fluid != null ? String.format(java.util.Locale.ROOT, "%,d L", amount)
                : String.format(java.util.Locale.ROOT, "%,d", amount);
            if (chance < 1) text += " (" + new java.math.BigDecimal(Float.toString(chance * 100)).stripTrailingZeros()
                .toPlainString() + "%)";
            rows.add(new PatternPick.Row(key, item, fluid, name, text));
        }
        return rows;
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
