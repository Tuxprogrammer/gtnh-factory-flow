package com.gtnhplanner.pattern;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * The mod's one network channel, for "Pattern this": the client asks, the server encodes an AE2 pattern from one of the
 * player's blank patterns and answers. The server says at login whether it can; a server that never says cannot.
 * Messages arrive on the network thread, so each is handed to its side's tick to run.
 */
public final class PatternNet {

    /** What the server said at login: null until it says (and again after leaving it). */
    @Nullable
    static volatile Boolean serverReady;

    static final Queue<Runnable> SERVER = new ConcurrentLinkedQueue<>();
    static final Queue<Runnable> CLIENT = new ConcurrentLinkedQueue<>();

    /** Asks waiting for the server's tick are capped, so a client cannot queue without end. */
    static final int MAX_WAITING = 256;

    /** Asks run on one server tick at most: a big plan spreads over ticks rather than holding one up. */
    static final int PER_TICK = 32;

    static SimpleNetworkWrapper net;

    private PatternNet() {}

    /** Runs {@code task} on the server's next tick (the dev game's comparison drives the server through this). */
    public static void onServerTick(final Runnable task) {
        SERVER.add(task);
    }

    /** Both sides, in pre-init: the channel, its messages and the ticks that run them. */
    public static void init() {
        net = NetworkRegistry.INSTANCE.newSimpleChannel("gtnhplanner");
        net.registerMessage(Hello.Handler.class, Hello.class, 0, Side.CLIENT);
        net.registerMessage(Ask.Handler.class, Ask.class, 1, Side.SERVER);
        net.registerMessage(Reply.Handler.class, Reply.class, 2, Side.CLIENT);
        FMLCommonHandler.instance()
            .bus()
            .register(new Events());
    }

    public static final class Events {

        @SubscribeEvent
        public void onLogin(final PlayerEvent.PlayerLoggedInEvent e) {
            if (e.player instanceof final EntityPlayerMP p) net.sendTo(new Hello(PatternServer.ready()), p);
        }

        @SubscribeEvent
        public void onServerTick(final TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            for (int i = 0; i < PER_TICK; i++) {
                final Runnable r = SERVER.poll();
                if (r == null) break;
                r.run();
            }
        }

        @SubscribeEvent
        public void onClientTick(final TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            for (Runnable r; (r = CLIENT.poll()) != null;) r.run();
            PatternThis.tick();
        }

        @SubscribeEvent
        public void onLeave(final FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
            serverReady = null;
        }
    }

    /** Server to client at login: whether "Pattern this" can make patterns here. */
    public static final class Hello implements IMessage {

        boolean ready;

        public Hello() {}

        Hello(final boolean ready) {
            this.ready = ready;
        }

        @Override
        public void fromBytes(final ByteBuf buf) {
            ready = buf.readBoolean();
        }

        @Override
        public void toBytes(final ByteBuf buf) {
            buf.writeBoolean(ready);
        }

        public static final class Handler implements IMessageHandler<Hello, IMessage> {

            @Override
            public IMessage onMessage(final Hello m, final MessageContext ctx) {
                serverReady = m.ready;
                return null;
            }
        }
    }

    /**
     * Client to server: one recipe as Not Enough Energistics packs it for a pattern terminal ({@code in} keyed
     * {@code #0}, {@code #1} ...; {@code out} keyed {@code Outputs0} ..., null for a crafting-table recipe).
     */
    public static final class Ask implements IMessage {

        int id;
        NBTTagCompound in;
        @Nullable
        NBTTagCompound out;

        public Ask() {}

        Ask(final int id, final NBTTagCompound in, @Nullable final NBTTagCompound out) {
            this.id = id;
            this.in = in;
            this.out = out;
        }

        boolean crafting() {
            return out == null;
        }

        @Override
        public void fromBytes(final ByteBuf buf) {
            id = buf.readInt();
            in = ByteBufUtils.readTag(buf);
            out = buf.readBoolean() ? ByteBufUtils.readTag(buf) : null;
        }

        @Override
        public void toBytes(final ByteBuf buf) {
            buf.writeInt(id);
            ByteBufUtils.writeTag(buf, in);
            buf.writeBoolean(out != null);
            if (out != null) ByteBufUtils.writeTag(buf, out);
        }

        public static final class Handler implements IMessageHandler<Ask, IMessage> {

            @Override
            public IMessage onMessage(final Ask m, final MessageContext ctx) {
                final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                if (m.in == null || SERVER.size() >= MAX_WAITING) return new Reply(m.id, PatternRules.CANNOT, 0);
                SERVER.add(() -> PatternServer.answer(player, m));
                return null;
            }
        }
    }

    /** Server to client: what came of one ask ({@link PatternRules} outcomes) and how many stacks did not fit. */
    public static final class Reply implements IMessage {

        int id;
        byte outcome;
        int dropped;

        public Reply() {}

        Reply(final int id, final byte outcome, final int dropped) {
            this.id = id;
            this.outcome = outcome;
            this.dropped = dropped;
        }

        @Override
        public void fromBytes(final ByteBuf buf) {
            id = buf.readInt();
            outcome = buf.readByte();
            dropped = buf.readInt();
        }

        @Override
        public void toBytes(final ByteBuf buf) {
            buf.writeInt(id);
            buf.writeByte(outcome);
            buf.writeInt(dropped);
        }

        public static final class Handler implements IMessageHandler<Reply, IMessage> {

            @Override
            public IMessage onMessage(final Reply m, final MessageContext ctx) {
                CLIENT.add(() -> PatternThis.answered(m.id, m.outcome, m.dropped));
                return null;
            }
        }
    }
}
