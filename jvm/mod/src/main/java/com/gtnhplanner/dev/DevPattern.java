package com.gtnhplanner.dev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnhplanner.pattern.PatternRules;
import com.gtnhplanner.pattern.PatternServer;
import com.gtnhplanner.pattern.PatternThis;

import appeng.api.AEApi;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPatternTerminal;
import appeng.container.implementations.ContainerPatternTerm;
import appeng.parts.reporting.PartPatternTerminal;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.ICraftingHandler;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.RecipeHandlerRef;

/**
 * "Pattern this" against a pattern made by hand, in the dev game with AE2 and Not Enough Energistics
 * ({@code PLANNH_GTNH=1 PLANNH_AE=1}). {@code pattern?setup=1} places a pattern terminal and a processing pattern
 * terminal beside the player, puts blanks in them and the player's inventory and switches to survival. Then
 * {@code pattern?output=&handler=&input=&nth=&term=ex|regular} finds a recipe in NEI and makes it twice: by hand (the
 * terminal opened as a right click opens it, NEE's own transfer from the open terminal's screen as NEI's overlay button
 * runs it, and the terminal container's own Encode) and by the button (the card's network path, or with
 * {@code direct=1} the server entry point, which {@code term=regular} needs for a processing recipe). It answers both
 * patterns' NBT without {@code encodedAt} (wall time) and whether they are the same. {@code card=1} with the planner
 * open puts the recipe ({@code join=N} recipes, joined as one shared machine) on the board and runs the card's row.
 */
final class DevPattern {

    /** The harness's client-thread runner. */
    interface OnClient {

        <T> T run(Supplier<T> task) throws Exception;
    }

    private static final String NEE_TERMINAL = "com.github.vfyjxf.nee.nei.NEEPatternTerminalHandler";

    private static int[] regularAt, exAt;

    private DevPattern() {}

    static Map<String, Object> route(final Map<String, String> q, final OnClient client) throws Exception {
        if (q.containsKey("setup")) return setup();
        if (q.containsKey("noblank")) return noBlank();
        if (q.containsKey("card")) return card(q, client);
        if (q.containsKey("look")) return client.run(DevPattern::look);
        if (q.containsKey("remote")) return remote(q, client);
        if (q.containsKey("plan")) return plan(q, client);
        return compare(q, client);
    }

    private static Map<String, Object> setup() throws Exception {
        final Map<String, Object> out = server(() -> {
            final EntityPlayerMP p = player();
            final int x = (int) Math.floor(p.posX) + 3, y = (int) Math.floor(p.posY), z = (int) Math.floor(p.posZ);
            regularAt = place(p, x, y, z, false);
            exAt = place(p, x, y, z + 2, true);
            p.setGameType(WorldSettings.GameType.SURVIVAL);
            p.inventory.addItemStackToInventory(blanks(16));
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("regular", regularAt);
            m.put("ex", exAt);
            m.put("blanks", blankCount(p));
            return m;
        });
        Thread.sleep(1500);
        // The parts' grid nodes are made a tick or more after placing; their pattern slots take blanks once they are.
        out.put(
            "terminals",
            server(() -> List.of(String.valueOf(part(player(), regularAt)), String.valueOf(part(player(), exAt)))));
        return out;
    }

    /** The button with no blank in the inventory: it must say so and make nothing. */
    private static Map<String, Object> noBlank() throws Exception {
        return server(() -> {
            final EntityPlayerMP p = player();
            final List<ItemStack> kept = new ArrayList<>();
            for (int i = 0; i < p.inventory.mainInventory.length; i++) {
                final ItemStack s = p.inventory.mainInventory[i];
                if (s != null && IPatternTerminal.isBlankPattern(s)) {
                    kept.add(s);
                    p.inventory.mainInventory[i] = null;
                }
            }
            final NBTTagCompound in = new NBTTagCompound();
            in.setTag("#0", new ItemStack(Blocks.cobblestone).writeToNBT(new NBTTagCompound()));
            final NBTTagCompound outs = new NBTTagCompound();
            outs.setTag("Outputs0", new ItemStack(Blocks.gravel).writeToNBT(new NBTTagCompound()));
            final int encodedBefore = encodedCount(p);
            final PatternServer.Made made = PatternServer.make(p, in, outs, false);
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("outcome", made.outcome());
            m.put("noBlank", made.outcome() == PatternRules.NO_BLANK);
            m.put("encodedMade", encodedCount(p) - encodedBefore);
            for (final ItemStack s : kept) p.inventory.addItemStackToInventory(s);
            m.put("blanksBack", blankCount(p));
            return m;
        });
    }

    private static Map<String, Object> compare(final Map<String, String> q, final OnClient client) throws Exception {
        final boolean regular = "regular".equals(q.getOrDefault("term", "ex"));
        final boolean direct = q.containsKey("direct");
        final int[] at = regular ? regularAt : exAt;
        if (at == null) throw new IllegalArgumentException("run pattern?setup=1 first");
        final Map<String, Object> m = new LinkedHashMap<>();

        final RecipeHandlerRef ref = client.run(
            () -> find(
                q.getOrDefault("output", ""),
                q.getOrDefault("handler", ""),
                q.getOrDefault("input", ""),
                Integer.parseInt(q.getOrDefault("nth", "0"))));
        m.put(
            "recipe",
            ref.handler.getRecipeName()
                .trim() + " #"
                + ref.recipeIndex);

        // By hand: the terminal opened by a right click, with blanks in its pattern slot and its encoded slot empty.
        m.put("opened", server(() -> {
            final EntityPlayerMP p = player();
            final PartPatternTerminal t = part(p, at);
            final IInventory slots = t.getInventoryByName("pattern");
            slots.setInventorySlotContents(0, blanks(64));
            slots.setInventorySlotContents(1, null);
            t.onPartActivate(p, Vec3.createVectorHelper(0.5, 0.5, 0.5));
            return p.openContainer.getClass()
                .getSimpleName();
        }));
        String screen = "";
        for (int i = 0; i < 40 && !screen.contains("PatternTerm"); i++) {
            Thread.sleep(100);
            screen = client.run(() -> {
                final Object s = Minecraft.getMinecraft().currentScreen;
                return s == null ? "" : s.getClass()
                    .getSimpleName();
            });
        }
        m.put("screen", screen);
        if (!screen.contains("PatternTerm")) throw new IllegalStateException("the terminal did not open: " + m);
        // NEE's transfer, as NEI's overlay button on the recipe page calls it with this screen behind the page.
        client.run(() -> {
            try {
                final Class<?> terminal = Class.forName(NEE_TERMINAL);
                terminal
                    .getMethod("transferRecipe", GuiContainer.class, IRecipeHandler.class, int.class, int.class)
                    .invoke(
                        terminal.getField("instance")
                            .get(null),
                        Minecraft.getMinecraft().currentScreen,
                        ref.handler,
                        ref.recipeIndex,
                        1);
                return null;
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(1000);
        final ItemStack byHand = server(() -> {
            final EntityPlayerMP p = player();
            if (!(p.openContainer instanceof final ContainerPatternTerm c))
                throw new IllegalStateException("no terminal open on the server");
            c.encode();
            final IInventory slots = c.getPatternTerminal()
                .getInventoryByName("pattern");
            final ItemStack made = slots.getStackInSlot(1);
            slots.setInventorySlotContents(1, null);
            p.closeScreen();
            return made == null ? null : made.copy();
        });
        Thread.sleep(300);

        // By the button.
        final ItemStack byButton;
        if (direct) {
            final NBTTagCompound[] packed = client.run(() -> PatternThis.pack(ref.handler, ref.recipeIndex));
            if (packed == null) throw new IllegalStateException("NEE did not pack the recipe");
            byButton = server(() -> {
                final EntityPlayerMP p = player();
                final int before = blankCount(p);
                final PatternServer.Made made = PatternServer.make(p, packed[0], packed[1], regular);
                m.put("outcome", made.outcome());
                m.put("dropped", made.dropped());
                m.put("blanksTaken", before - blankCount(p));
                return made.pattern();
            });
        } else {
            final int before = server(() -> {
                removeEncoded(player());
                return blankCount(player());
            });
            client.run(() -> {
                PatternThis.pattern(List.of(ref), 0);
                return null;
            });
            Thread.sleep(1500);
            byButton = server(() -> {
                final EntityPlayerMP p = player();
                m.put("blanksTaken", before - blankCount(p));
                m.put("encodedGiven", encodedCount(p));
                ItemStack found = null;
                for (final ItemStack s : p.inventory.mainInventory)
                    if (s != null && IPatternTerminal.isEncodedPattern(s)) found = s.copy();
                return found;
            });
            m.put("said", client.run(PatternThis::lastSummary));
        }

        final NBTTagCompound hand = plain(byHand), button = plain(byButton);
        m.put("same", hand != null && hand.equals(button));
        m.put("byHand", String.valueOf(hand));
        m.put("byHandCanon", canon(hand));
        if (hand == null || !hand.equals(button)) m.put("byButton", String.valueOf(button));
        return m;
    }

    /** The card's row on a card of the board: one pattern per recipe of its shared machine. */
    private static Map<String, Object> card(final Map<String, String> q, final OnClient client) throws Exception {
        final int join = Integer.parseInt(q.getOrDefault("join", "1"));
        final int before = server(() -> {
            removeEncoded(player());
            return blankCount(player());
        });
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("offered", client.run(() -> {
            final com.gtnhplanner.ui.BoardSession session = com.gtnhplanner.ui.BoardSession.current();
            if (session == null) throw new IllegalStateException("open the planner first");
            com.gtnhplanner.data.flowchart.Node host = null;
            for (int n = 0; n < join; n++) {
                final RecipeHandlerRef ref = find(
                    q.getOrDefault("output", ""),
                    q.getOrDefault("handler", ""),
                    q.getOrDefault("input", ""),
                    n);
                final com.gtnhplanner.data.flowchart.Node node = session.addRecipe(ref.handler, ref.recipeIndex);
                if (host == null) host = node;
                else com.gtnhplanner.ui.CardRecipes.join(session.graph(), host, node, null);
            }
            final boolean offered = PatternThis.offered();
            PatternThis.card(session, host);
            return offered;
        }));
        Thread.sleep(2000);
        m.put("said", client.run(PatternThis::lastSummary));
        m.putAll(server(() -> {
            final Map<String, Object> r = new LinkedHashMap<>();
            r.put("blanksTaken", before - blankCount(player()));
            r.put("encodedGiven", encodedCount(player()));
            return r;
        }));
        return m;
    }

    /**
     * The button as a player on any server sees it, from the client only (a dedicated server's player has no server
     * object here): {@code remote=1} with the recipe's {@code output}, {@code handler}, {@code input}, {@code nth}.
     */
    private static Map<String, Object> remote(final Map<String, String> q, final OnClient client) throws Exception {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("before", client.run(DevPattern::look));
        client.run(() -> {
            final RecipeHandlerRef ref = find(
                q.getOrDefault("output", ""),
                q.getOrDefault("handler", ""),
                q.getOrDefault("input", ""),
                Integer.parseInt(q.getOrDefault("nth", "0")));
            m.put(
                "recipe",
                ref.handler.getRecipeName()
                    .trim() + " #" + ref.recipeIndex);
            // many=N asks for the one recipe N times, as a plan of N recipes is sent: in batches, over ticks.
            PatternThis.pattern(java.util.Collections.nCopies(Integer.parseInt(q.getOrDefault("many", "1")), ref), 0);
            return null;
        });
        Thread.sleep(Long.parseLong(q.getOrDefault("wait", "2500")));
        m.put("after", client.run(DevPattern::look));
        m.put("made", made(m));
        return m;
    }

    /** The Share menu's "Pattern the plan" on the open plan, read from the client as {@link #remote} is. */
    private static Map<String, Object> plan(final Map<String, String> q, final OnClient client) throws Exception {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("before", client.run(DevPattern::look));
        client.run(() -> {
            final com.gtnhplanner.ui.BoardSession session = com.gtnhplanner.ui.BoardSession.current();
            if (session == null) throw new IllegalStateException("open the planner first");
            m.put("cards", session.graph().nodes.size());
            PatternThis.plan(session.graph());
            return null;
        });
        Thread.sleep(Long.parseLong(q.getOrDefault("wait", "4000")));
        m.put("after", client.run(DevPattern::look));
        m.put("made", made(m));
        return m;
    }

    /** What the client's player holds and has at their feet: blanks, encoded patterns (NBT), the board's last line. */
    private static Map<String, Object> look() {
        final net.minecraft.entity.player.EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        final Map<String, Object> m = new LinkedHashMap<>();
        int blanks = 0, free = 0;
        final List<String> encoded = new ArrayList<>(), dropped = new ArrayList<>();
        for (final ItemStack s : p.inventory.mainInventory) {
            if (s == null) free++;
            else if (IPatternTerminal.isBlankPattern(s)) blanks += s.stackSize;
            else if (IPatternTerminal.isEncodedPattern(s)) for (int i = 0; i < s.stackSize; i++) encoded.add(canon(plain(s)));
        }
        for (final Object o : p.worldObj.loadedEntityList)
            if (o instanceof final net.minecraft.entity.item.EntityItem e && e.getDistanceToEntity(p) < 4
                && e.getEntityItem() != null
                && IPatternTerminal.isEncodedPattern(e.getEntityItem())) dropped.add(canon(plain(e.getEntityItem())));
        final ItemStack blank = blanks(1);
        m.put("creative", p.capabilities.isCreativeMode);
        m.put("blankItem", net.minecraft.item.Item.itemRegistry.getNameForObject(blank.getItem()) + " " + blank.getItemDamage());
        m.put("blanks", blanks);
        m.put("freeSlots", free);
        m.put("encoded", encoded);
        m.put("droppedAtFeet", dropped);
        m.put("offered", PatternThis.offered());
        m.put("unavailable", String.valueOf(PatternThis.unavailable()));
        m.put("said", PatternThis.lastSummary());
        return m;
    }

    /** The patterns held or at the player's feet after and not before (each once per copy). */
    @SuppressWarnings("unchecked")
    private static List<String> made(final Map<String, Object> m) {
        final List<String> made = new ArrayList<>();
        final Map<String, Object> before = (Map<String, Object>) m.get("before"),
            after = (Map<String, Object>) m.get("after");
        made.addAll((List<String>) after.get("encoded"));
        made.addAll((List<String>) after.get("droppedAtFeet"));
        for (final String s : (List<String>) before.get("encoded")) made.remove(s);
        for (final String s : (List<String>) before.get("droppedAtFeet")) made.remove(s);
        return made;
    }

    /** NBT as text with each compound's keys sorted: the same pattern reads the same whichever side built it. */
    private static String canon(final net.minecraft.nbt.NBTBase t) {
        if (t instanceof final NBTTagCompound c) {
            final StringBuilder b = new StringBuilder("{");
            for (final Object k : new java.util.TreeSet<>(c.func_150296_c())) b.append(k)
                .append(':')
                .append(canon(c.getTag((String) k)))
                .append(',');
            return b.append('}')
                .toString();
        }
        if (t instanceof final net.minecraft.nbt.NBTTagList l) {
            final net.minecraft.nbt.NBTTagList copy = (net.minecraft.nbt.NBTTagList) l.copy();
            final StringBuilder b = new StringBuilder("[");
            while (copy.tagCount() > 0) b.append(canon(copy.removeTag(0)))
                .append(',');
            return b.append(']')
                .toString();
        }
        return String.valueOf(t);
    }

    /** The stack's NBT as an item, without the pattern's wall-clock time. */
    private static NBTTagCompound plain(final ItemStack s) {
        if (s == null) return null;
        final NBTTagCompound t = s.writeToNBT(new NBTTagCompound());
        if (t.hasKey("tag")) t.getCompoundTag("tag")
            .removeTag("encodedAt");
        return t;
    }

    private static RecipeHandlerRef find(final String output, final String handlerFilter, final String inputFilter,
        final int nth) {
        final ItemStack stack = DevRecipes.resolveStack(output);
        if (stack == null) throw new IllegalArgumentException("no item for '" + output + "'");
        final String hf = handlerFilter.toLowerCase(Locale.ROOT), inf = inputFilter.toLowerCase(Locale.ROOT);
        int seen = 0;
        for (final ICraftingHandler handler : GuiCraftingRecipe.getCraftingHandlers("item", stack)) {
            if (!handler.getRecipeName()
                .toLowerCase(Locale.ROOT)
                .contains(hf)) continue;
            for (int i = 0; i < handler.numRecipes(); i++) {
                if (!inf.isEmpty() && !DevRecipes.hasIngredient(handler, i, inf)) continue;
                if (seen++ == nth) return RecipeHandlerRef.of(handler, i);
            }
        }
        throw new IllegalArgumentException("no matching recipe");
    }

    private static int[] place(final EntityPlayerMP p, final int x, final int y, final int z, final boolean ex) {
        final World w = p.worldObj;
        w.setBlock(x, y, z, Blocks.stone);
        w.setBlockToAir(x, y + 1, z);
        final ItemStack part = (ex ? AEApi.instance()
            .definitions()
            .parts()
            .patternTerminalEx()
            : AEApi.instance()
                .definitions()
                .parts()
                .patternTerminal()).maybeStack(1)
                    .orNull();
        // A cable bus with the terminal on its underside, facing the stone (what a right click on its top places).
        w.setBlock(
            x,
            y + 1,
            z,
            AEApi.instance()
                .definitions()
                .blocks()
                .multiPart()
                .maybeBlock()
                .get());
        if (!(w.getTileEntity(x, y + 1, z) instanceof final IPartHost host)
            || host.addPart(part, ForgeDirection.DOWN, p) == null)
            throw new IllegalStateException("AE2 did not place the terminal");
        return new int[] { x, y + 1, z };
    }

    private static PartPatternTerminal part(final EntityPlayerMP p, final int[] at) {
        final TileEntity te = p.worldObj.getTileEntity(at[0], at[1], at[2]);
        if (te instanceof final IPartHost host) for (final ForgeDirection d : ForgeDirection.values()) {
            final IPart part = host.getPart(d);
            if (part instanceof final PartPatternTerminal t) return t;
        }
        throw new IllegalStateException("no pattern terminal at " + at[0] + "," + at[1] + "," + at[2]);
    }

    private static ItemStack blanks(final int n) {
        final ItemStack s = AEApi.instance()
            .definitions()
            .materials()
            .blankPattern()
            .maybeStack(n)
            .orNull();
        return s;
    }

    private static int blankCount(final EntityPlayerMP p) {
        int n = 0;
        for (final ItemStack s : p.inventory.mainInventory)
            if (s != null && IPatternTerminal.isBlankPattern(s)) n += s.stackSize;
        return n;
    }

    private static int encodedCount(final EntityPlayerMP p) {
        int n = 0;
        for (final ItemStack s : p.inventory.mainInventory)
            if (s != null && IPatternTerminal.isEncodedPattern(s)) n += s.stackSize;
        return n;
    }

    private static void removeEncoded(final EntityPlayerMP p) {
        for (int i = 0; i < p.inventory.mainInventory.length; i++) {
            final ItemStack s = p.inventory.mainInventory[i];
            if (s != null && IPatternTerminal.isEncodedPattern(s)) p.inventory.mainInventory[i] = null;
        }
    }

    private static EntityPlayerMP player() {
        return (EntityPlayerMP) MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList.get(0);
    }

    private static <T> T server(final Callable<T> task) throws Exception {
        final FutureTask<T> f = new FutureTask<>(task);
        com.gtnhplanner.pattern.PatternNet.onServerTick(f);
        return f.get(20, TimeUnit.SECONDS);
    }
}
