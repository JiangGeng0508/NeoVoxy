package me.cortex.voxy.testing.control;

import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Independent mod: its launch classpath and mod folders exclude the Voxy outputs. */
@Mod("disconnect_control")
@EventBusSubscriber(modid = "disconnect_control", value = Dist.CLIENT)
public final class DisconnectControl {
    private static final Map<String, Object> report = new LinkedHashMap<>();
    private static volatile boolean stopped;
    private static boolean closing, disconnected, completed;
    private static int ticks, worldTicks;
    private static final ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        if (completed) return;
        if (++ticks > 4000) throw new AssertionError("Disconnect control timed out");
        if (ModList.get().isLoaded("voxy")) throw new AssertionError("Voxy must be absent in the control");
        var mc = Minecraft.getInstance();
        if (!closing && mc.player != null && ++worldTicks >= 180) {
            closing = true;
            report.put("voxyAbsent", true);
            report.put("worldTicks", worldTicks);
            report.put("exitMode", direct() ? "direct" : "disconnect");
            System.out.println("DISCONNECT_CONTROL_CLOSE_REQUESTED " + report);
            if (Boolean.getBoolean("voxy.forceLocalCloseRace")) forceLocalCloseRace(mc);
            if (direct()) {
                completed = true;
                mc.stop();
                return;
            }
            mc.level.disconnect();
            mc.disconnect(new TitleScreen());
            disconnected = true;
        }
        if (disconnected && stopped && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            completed = true;
            save();
            mc.stop();
        }
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) throws Exception {
        report.put("serverStopped", true);
        stopped = true;
        System.out.println("DISCONNECT_CONTROL_SERVER_STOPPED");
        if (direct()) save();
    }

    private static boolean direct() {
        return System.getProperty("voxy.distantGenExit", "disconnect").equals("direct");
    }

    private static void save() throws Exception {
        report.put("packetFailures", List.copyOf(failures));
        Files.writeString(Path.of(System.getProperty("voxy.distantGenSmoke")).resolve("complete.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("DISCONNECT_CONTROL_COMPLETE");
    }

    private static void forceLocalCloseRace(Minecraft mc) throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CompletableFuture<Void>();
        try {
            mc.getSingleplayerServer().submit(() -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                var connection = player.connection.getConnection();
                try {
                    var field = Connection.class.getDeclaredField("channel");
                    field.setAccessible(true);
                    var channel = (Channel) field.get(connection);
                    channel.pipeline().addLast("disconnect_control_trace", new ChannelOutboundHandlerAdapter() {
                        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                            var tracked = promise.unvoid();
                            tracked.addListener(future -> {
                                if (future.isSuccess()) return;
                                String failure = msg.getClass().getSimpleName() + ":" + future.cause().getClass().getName();
                                failures.add(failure);
                                System.out.println("DISCONNECT_CONTROL_PACKET_FAILURE " + failure);
                            });
                            super.write(ctx, msg, tracked);
                        }
                    });
                    channel.eventLoop().execute(() -> {
                        entered.complete(null);
                        try {
                            if (!release.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("Control peer-close gate timed out");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                    });
                    // Queue an ordinary vanilla packet before the peer closes. The event-loop
                    // gate makes that ordering deterministic without changing Connection code.
                    connection.send(new ClientboundSetTimePacket(0, 0, true));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }).get(10, TimeUnit.SECONDS);
            entered.get(10, TimeUnit.SECONDS);
            mc.level.disconnect();
            report.put("forcedLocalCloseRace", true);
        } finally {
            release.countDown();
        }
    }
}
