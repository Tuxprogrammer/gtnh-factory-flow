package com.gtnhplanner.pattern;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnhplanner.GtnhPlanner;

import cpw.mods.fml.common.Loader;

/**
 * The server's half of "Pattern this", on the server's tick. It trusts the client for nothing but the packed recipe,
 * which AE2's own terminal and encoder read as they read an NEI transfer: one blank pattern from the asking player's
 * inventory makes one encoded pattern (none needed in creative), and the pattern goes to that player.
 */
public final class PatternServer {

    public static final String AE2 = "appliedenergistics2", NEE = "neenergistics";

    private static Boolean ready;

    private PatternServer() {}

    /** Whether this side can make patterns: AE2 and Not Enough Energistics are here, with what the encoder calls. */
    public static boolean ready() {
        if (ready == null) {
            boolean ok = Loader.isModLoaded(AE2) && Loader.isModLoaded(NEE);
            if (ok) {
                try {
                    AePatterns.link();
                } catch (final Throwable t) {
                    GtnhPlanner.LOG.warn("Pattern this is off: AE2 or Not Enough Energistics is not as expected", t);
                    ok = false;
                }
            }
            ready = ok;
        }
        return ready;
    }

    /** What came of one recipe: a {@link PatternRules} outcome, stacks left out, and a copy of the pattern given. */
    public record Made(byte outcome, int dropped, @Nullable ItemStack pattern) {

        static Made not(final byte outcome) {
            return new Made(outcome, 0, null);
        }
    }

    /**
     * The server's one entry point, on the server thread: one recipe as Not Enough Energistics packs it for a pattern
     * terminal ({@code in} keyed {@code #0} ..., {@code out} keyed {@code Outputs0} ..., null for a crafting-table
     * recipe) becomes one encoded pattern in {@code player}'s inventory, from one of their blank patterns (none in
     * creative). {@code regular} encodes a processing recipe on the 9-input pattern terminal rather than the
     * processing pattern terminal.
     */
    public static Made make(final EntityPlayerMP player, final NBTTagCompound in, @Nullable final NBTTagCompound out,
        final boolean regular) {
        if (!ready()) return Made.not(PatternRules.NO_SERVER);
        try {
            return AePatterns.make(player, in, out, regular);
        } catch (final RuntimeException | LinkageError e) {
            GtnhPlanner.LOG.warn("Pattern this could not encode a recipe", e);
            return Made.not(PatternRules.CANNOT);
        }
    }

    static void answer(final EntityPlayerMP player, final PatternNet.Ask ask) {
        if (player.playerNetServerHandler == null) return;
        final Made made = make(player, ask.in, ask.out, false);
        PatternNet.net.sendTo(new PatternNet.Reply(ask.id, made.outcome(), made.dropped()), player);
    }
}
