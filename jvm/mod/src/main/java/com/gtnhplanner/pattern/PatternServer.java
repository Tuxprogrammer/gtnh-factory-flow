package com.gtnhplanner.pattern;

import net.minecraft.entity.player.EntityPlayerMP;

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

    static void answer(final EntityPlayerMP player, final PatternNet.Ask ask) {
        if (player.playerNetServerHandler == null) return;
        PatternNet.Reply reply;
        if (!ready()) reply = new PatternNet.Reply(ask.id, PatternRules.NO_SERVER, 0);
        else {
            try {
                reply = AePatterns.make(player, ask);
            } catch (final RuntimeException | LinkageError e) {
                GtnhPlanner.LOG.warn("Pattern this could not encode a recipe", e);
                reply = new PatternNet.Reply(ask.id, PatternRules.CANNOT, 0);
            }
        }
        PatternNet.net.sendTo(reply, player);
    }
}
