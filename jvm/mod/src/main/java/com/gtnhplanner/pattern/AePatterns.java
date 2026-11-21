package com.gtnhplanner.pattern;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import appeng.api.AEApi;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.parts.IPatternTerminal;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.StorageName;
import appeng.api.storage.data.IAEStack;
import appeng.parts.reporting.PartPatternTerminal;
import appeng.parts.reporting.PartPatternTerminalEx;
import appeng.tile.inventory.IAEStackInventory;
import appeng.tile.inventory.InvOperation;
import appeng.util.item.AEItemStack;

/**
 * The AE2 half of "Pattern this", on the server's tick. It fills a real AE2 pattern terminal the way Not Enough
 * Energistics fills one from an NEI transfer (its own packet handler's {@code processRecipe} and {@code craftingRecipe}
 * loops, with that handler's {@code getMatrix} and {@code getAEStack}), then runs the terminal's own {@code encode} and
 * hands the pattern over by AE2's rule for an encode to the inventory: into the inventory, else dropped at the player's
 * feet. Only the slot-filling loop is written here; everything that decides what the pattern holds is AE2's or NEE's.
 *
 * <p>
 * Processing recipes go through the processing pattern terminal (32 inputs, 32 outputs, not inverted, as a fresh one
 * is placed); crafting-table recipes through the pattern terminal in crafting mode. Substitution is off, as on a fresh
 * terminal. The terminal is never placed in a world: its saves and its pattern-slot reload are switched off, as they
 * need a host.
 */
public final class AePatterns {

    private static final String NEE_HANDLER = "com.github.vfyjxf.nee.network.packet.PacketNEIPatternRecipe$Handler";

    private static Object nee;
    private static Method matrix, aeStack;

    private AePatterns() {}

    /** Resolves everything {@link #make} calls, so a missing or changed AE2 or NEE fails here and not on a click. */
    static void link() throws ReflectiveOperationException {
        final Class<?> handler = Class.forName(NEE_HANDLER);
        nee = handler.getConstructor()
            .newInstance();
        matrix = handler.getDeclaredMethod("getMatrix", NBTTagCompound.class, String.class, int.class);
        matrix.setAccessible(true);
        aeStack = handler.getDeclaredMethod("getAEStack", ItemStack.class);
        aeStack.setAccessible(true);
        PartPatternTerminal.class.getMethod(
            "encode",
            IEnergySource.class,
            IMEMonitor.class,
            BaseActionSource.class,
            String.class,
            World.class);
        IAEStackInventory.class.getMethod("putAEStackInSlot", int.class, IAEStack.class);
        IPatternTerminal.class.getMethod("isBlankPattern", ItemStack.class);
        if (blank() == null || terminal(false) == null || terminal(true) == null)
            throw new ClassNotFoundException("AE2 has no blank pattern or pattern terminal");
        Term.class.getName();
        TermEx.class.getName();
    }

    /** See {@link PatternServer#make}. */
    static PatternServer.Made make(final EntityPlayerMP player, final NBTTagCompound packedIn,
        @Nullable final NBTTagCompound packedOut, final boolean regular) {
        final boolean crafting = packedOut == null;
        if (!wellFormed(packedIn, PatternRules.IN) || !crafting && !wellFormed(packedOut, PatternRules.OUT))
            return PatternServer.Made.not(PatternRules.CANNOT);

        final boolean creative = player.capabilities.isCreativeMode;
        final int blankSlot = creative ? -1 : blankSlot(player);
        if (!creative && blankSlot < 0) return PatternServer.Made.not(PatternRules.NO_BLANK);

        final PartPatternTerminal part = crafting || regular ? new Term(terminal(false)) : new TermEx(terminal(true));
        part.setCraftingRecipe(crafting);
        final IAEStackInventory inputs = part.getAEInventoryByName(StorageName.CRAFTING_INPUT);
        final IAEStackInventory outputs = part.getAEInventoryByName(StorageName.CRAFTING_OUTPUT);
        final int inSize = inputs.getSizeInventory(), outSize = outputs.getSizeInventory();
        final int dropped = PatternRules.beyond(keys(packedIn), PatternRules.IN, inSize)
            + (crafting ? 0 : PatternRules.beyond(keys(packedOut), PatternRules.OUT, outSize));

        // NEE's craftingRecipe: every grid slot as a plain item stack, each of one (the container's updateVirtualSlot
        // sets a crafting slot's size to 1). Its processRecipe: each slot through getAEStack (a fluid's display item
        // becomes the fluid), empty and unreadable stacks skipped, inputs and outputs alike.
        final ItemStack[] in = matrix(packedIn, PatternRules.IN, crafting ? 9 : inSize);
        if (!crafting && !counted(in)) return PatternServer.Made.not(PatternRules.CANNOT);
        for (int i = 0; i < in.length; i++) {
            if (crafting) {
                final IAEStack<?> aes = AEItemStack.create(in[i]);
                if (aes != null) aes.setStackSize(1);
                inputs.putAEStackInSlot(i, aes);
            } else if (in[i] != null) {
                final IAEStack<?> aes = aeStack(in[i]);
                if (aes != null) inputs.putAEStackInSlot(i, aes);
            }
        }
        if (!crafting) {
            final ItemStack[] out = matrix(packedOut, PatternRules.OUT, outSize);
            if (!counted(out)) return PatternServer.Made.not(PatternRules.CANNOT);
            for (int i = 0; i < out.length; i++) {
                if (out[i] == null) continue;
                final IAEStack<?> aes = aeStack(out[i]);
                if (aes != null) outputs.putAEStackInSlot(i, aes);
            }
        }

        // The terminal's own encode, from one blank in its pattern slot: it decides the pattern's kind and NBT, and
        // refuses a recipe with no inputs, or no output (a crafting grid that matches no recipe).
        final IInventory slots = part.getInventoryByName("pattern");
        slots.setInventorySlotContents(0, blank());
        if (!part.encode(null, null, null, player.getCommandSenderName(), player.worldObj))
            return PatternServer.Made.not(PatternRules.CANNOT);
        final ItemStack made = slots.getStackInSlot(1);
        if (made == null) return PatternServer.Made.not(PatternRules.CANNOT);

        if (!creative) player.inventory.decrStackSize(blankSlot, 1);
        final ItemStack given = made.copy();
        // AE2's hand-over for an encode to the inventory (ContainerPatternTerm.encodeAndMoveToInventory).
        if (!player.inventory.addItemStackToInventory(made)) player.entityDropItem(made, 0);
        player.inventoryContainer.detectAndSendChanges();
        // The button is pressed on the planner's board, a screen of the client's own: while it is open the client puts
        // slot updates outside the hotbar into the board's container, so the pattern and the blank taken would not
        // show until the next login. The whole inventory, sent as a window of its own, always lands in the inventory.
        player.sendContainerToPlayer(player.inventoryContainer);
        return new PatternServer.Made(PatternRules.MADE, dropped, given);
    }

    /** Blank patterns in the player's own inventory, by the rule {@link #make} takes them by (either side's player). */
    static int blanks(final EntityPlayer player) {
        int n = 0;
        for (final ItemStack s : player.inventory.mainInventory)
            if (s != null && s.stackSize > 0 && IPatternTerminal.isBlankPattern(s)) n += s.stackSize;
        return n;
    }

    /** The first slot of the player's own inventory holding a blank pattern, or -1. */
    private static int blankSlot(final EntityPlayerMP player) {
        final ItemStack[] main = player.inventory.mainInventory;
        for (int i = 0; i < main.length; i++)
            if (main[i] != null && main[i].stackSize > 0 && IPatternTerminal.isBlankPattern(main[i])) return i;
        return -1;
    }

    /** A packed side: only compounds under its own keys, a bounded number of them. */
    private static boolean wellFormed(@Nullable final NBTTagCompound tag, final String prefix) {
        if (tag == null) return false;
        final Set<?> keys = keys(tag);
        if (keys.size() > PatternRules.MAX_KEYS) return false;
        for (final Object k : keys) {
            final String key = String.valueOf(k);
            if (PatternRules.slot(key, prefix) < 0) return false;
            final NBTBase value = tag.getTag(key);
            if (!(value instanceof NBTTagCompound)) return false;
        }
        return true;
    }

    /** No stack the encoder reads may count less than one: the client says what the recipe is, never how little. */
    private static boolean counted(final ItemStack[] stacks) {
        for (final ItemStack s : stacks) if (s != null && s.stackSize < 1) return false;
        return true;
    }

    private static Set<?> keys(final NBTTagCompound tag) {
        return tag.func_150296_c();
    }

    private static ItemStack[] matrix(final NBTTagCompound tag, final String prefix, final int size) {
        return (ItemStack[]) call(matrix, tag, prefix, size);
    }

    @Nullable
    private static IAEStack<?> aeStack(final ItemStack stack) {
        return (IAEStack<?>) call(aeStack, stack);
    }

    private static Object call(final Method m, final Object... args) {
        try {
            return m.invoke(nee, args);
        } catch (final InvocationTargetException e) {
            throw new IllegalStateException(
                "Not Enough Energistics failed reading a packed recipe",
                e.getCause() != null ? e.getCause() : e);
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @Nullable
    private static ItemStack blank() {
        return AEApi.instance()
            .definitions()
            .materials()
            .blankPattern()
            .maybeStack(1)
            .orNull();
    }

    @Nullable
    private static ItemStack terminal(final boolean processing) {
        return (processing ? AEApi.instance()
            .definitions()
            .parts()
            .patternTerminalEx()
            : AEApi.instance()
                .definitions()
                .parts()
                .patternTerminal()).maybeStack(1)
                    .orNull();
    }

    /** AE2's pattern terminal off the world: no host to save to, no pattern slot to reload from. */
    static final class Term extends PartPatternTerminal {

        Term(final ItemStack is) {
            super(is);
        }

        @Override
        public void onChangeInventory(final IInventory inv, final int slot, final InvOperation mc,
            final ItemStack removedStack, final ItemStack newStack) {}

        @Override
        public void saveAEStackInv() {}
    }

    /** AE2's processing pattern terminal off the world, as {@link Term}. */
    static final class TermEx extends PartPatternTerminalEx {

        TermEx(final ItemStack is) {
            super(is);
        }

        @Override
        public void onChangeInventory(final IInventory inv, final int slot, final InvOperation mc,
            final ItemStack removedStack, final ItemStack newStack) {}

        @Override
        public void saveAEStackInv() {}
    }
}
