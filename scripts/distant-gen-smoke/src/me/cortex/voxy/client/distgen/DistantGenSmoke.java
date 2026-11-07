package me.cortex.voxy.client.distgen;

import com.google.gson.GsonBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

/** Real background generation followed by an ordinary client disconnect/exit. */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public final class DistantGenSmoke {
    private static final Map<String, Object> report = new LinkedHashMap<>();
    private static volatile boolean ready, stopped;
    private static boolean closing, disconnected, completed;
    private static int ticks;
    private static volatile long closeRequestedNanos;
    private static final Set<Channel> watched = new HashSet<>();
    private static final ConcurrentLinkedQueue<Map<String, Object>> packetFailures = new ConcurrentLinkedQueue<>();

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void serverTick(ServerTickEvent.Post event) throws Exception {
        if (System.getProperty("voxy.distantGenSmoke") == null || ready) return;
        for (var player : event.getServer().getPlayerList().getPlayers()) {
            Channel channel = field(Connection.class, "channel", player.connection.getConnection());
            if (!watched.add(channel)) continue;
            channel.pipeline().addLast("distant_gen_failure_probe", new ChannelOutboundHandlerAdapter() {
                @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    ChannelPromise tracked = promise.unvoid();
                    tracked.addListener(future -> {
                        if (future.isSuccess()) return;
                        String packet = msg.getClass().getSimpleName();
                        if (msg instanceof ClientboundCustomPayloadPacket custom)
                            packet += ":" + custom.payload().type().id();
                        long close = closeRequestedNanos;
                        Map<String, Object> failure = Map.of("packet", packet,
                                "cause", future.cause().getClass().getName(),
                                "afterCloseRequest", close != 0,
                                "nanosAfterCloseRequest", close == 0 ? -1L : System.nanoTime() - close);
                        packetFailures.add(failure);
                        System.out.println("DISTANT_GEN_PACKET_FAILURE " + failure);
                    });
                    super.write(ctx, msg, tracked);
                }
            });
        }
        check(VoxyConfig.CONFIG.distantGenEnabled, "background generation must be enabled");
        Map<ServerLevel, DistantWorldGenerator> generators = field(DistantGenerationManager.class, "GENERATORS", null);
        int generated = 0, pending = 0, inFlight = 0;
        for (var generator : generators.values()) {
            generated += (int) field(DistantWorldGenerator.class, "sessionGenerated", generator);
            Long2ObjectOpenHashMap<CompletableFuture<?>> tasks = field(DistantWorldGenerator.class, "inFlight", generator);
            inFlight += tasks.size();
            for (var future : tasks.values()) if (!future.isDone()) pending++;
        }
        boolean cold = System.getProperty("voxy.distantGenPhase", "warm").equals("cold");
        if (pending > 0 && (cold || generated >= 128)) {
            report.put("generatedBeforeExit", generated);
            report.put("inFlightBeforeExit", inFlight);
            report.put("pendingBeforeExit", pending);
            report.put("phase", cold ? "cold" : "warm");
            if (Boolean.getBoolean("voxy.distantGenRetry")) verifyUnloadRetry(event.getServer().overworld());
            System.out.println("DISTANT_GEN_EXIT_READY " + report);
            ready = true;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void stopping(ServerStoppingEvent event) throws Exception {
        if (System.getProperty("voxy.distantGenSmoke") == null) return;
        Map<?, ?> generators = field(DistantGenerationManager.class, "GENERATORS", null);
        check(generators.isEmpty(), "generator ownership must be cleared before vanilla shutdown");
        TicketType<?> ticketType = field(DistantWorldGenerator.class, "TICKET", null);
        for (var level : event.getServer().getAllLevels()) {
            Long2ObjectMap<? extends Iterable<Ticket<?>>> tickets = field(DistanceManager.class, "tickets",
                    level.getChunkSource().distanceManager);
            for (var set : tickets.values()) for (var ticket : set)
                check(ticket.getType() != ticketType, "distant generation ticket survived shutdown: " + ticket);
        }
        report.put("generatorsCleared", true);
        report.put("ticketsReleased", true);
        System.out.println("DISTANT_GEN_SERVER_STOPPING");
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) throws Exception {
        String output = System.getProperty("voxy.distantGenSmoke");
        if (output == null) return;
        report.put("serverStopped", true);
        stopped = true;
        System.out.println("DISTANT_GEN_SERVER_STOPPED");
        if (directExit()) writeReport(output);
    }

    @SubscribeEvent public static void clientTick(ClientTickEvent.Post event) throws Exception {
        String output = System.getProperty("voxy.distantGenSmoke");
        if (output == null || completed) return;
        if (++ticks > 4000) throw new AssertionError("Distant generation probe timed out");
        var mc = Minecraft.getInstance();
        if (ready && !closing && mc.player != null && IGetVoxyRenderSystem.getNullable() != null) {
            Object pipeline = field(VoxyRenderSystem.class, "pipeline", IGetVoxyRenderSystem.getNullable());
            report.put("pipeline", pipeline.getClass().getSimpleName());
            report.put("exitMode", directExit() ? "direct" : "disconnect");
            closing = true;
            closeRequestedNanos = System.nanoTime();
            System.out.println("DISTANT_GEN_CLOSE_REQUESTED mode=" + report.get("exitMode"));
            if (directExit()) {
                completed = true;
                mc.stop();
                return;
            }
            mc.level.disconnect();
            mc.disconnect(new TitleScreen());
            disconnected = true;
        }
        if (disconnected && stopped && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            check(IGetVoxyRenderSystem.getNullable() == null, "renderer must be released");
            writeReport(output);
            completed = true;
            mc.stop();
        }
    }

    private static boolean directExit() {
        return System.getProperty("voxy.distantGenExit", "direct").equals("direct");
    }

    private static void writeReport(String output) throws Exception {
        report.put("packetFailures", List.copyOf(packetFailures));
        Files.writeString(Path.of(output).resolve("complete.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("DISTANT_GEN_SMOKE COMPLETE");
    }

    private static void verifyUnloadRetry(ServerLevel level) throws Exception {
        // Deterministically reproduce a holder reacquired after unload scheduling. Invoke
        // the real vanilla queue and callback, with an already-completed save dependency.
        var holder = new ChunkHolder(new ChunkPos(100000, 100000), 33, level, level.getLightEngine(), null, null);
        holder.increaseGenerationRefCount();
        var schedule = ChunkMap.class.getDeclaredMethod("scheduleUnload", long.class, ChunkHolder.class);
        var process = ChunkMap.class.getDeclaredMethod("processUnloads", BooleanSupplier.class);
        schedule.setAccessible(true);
        process.setAccessible(true);
        var map = level.getChunkSource().chunkMap;
        Long2ObjectMap<ChunkHolder> pending = field(ChunkMap.class, "pendingUnloads", map);
        long pos = holder.getPos().toLong();
        check(!pending.containsKey(pos), "fixture position already in use");
        pending.put(pos, holder);
        schedule.invoke(map, pos, holder);
        System.out.println("DISTANT_GEN_RETRY_BEGIN refs=1 saveDone=" + holder.getSaveSyncFuture().isDone());
        process.invoke(map, (BooleanSupplier) () -> true);
        check(holder.getGenerationRefCount() == 1, "unload must not release generation ownership");
        check(pending.get(pos) == holder, "retry must keep pending unload ownership");
        holder.decreaseGenerationRefCount();
        process.invoke(map, (BooleanSupplier) () -> true);
        check(!pending.containsKey(pos), "retry did not complete after generation reference release");
        report.put("unloadRetryYielded", true);
        System.out.println("DISTANT_GEN_RETRY_PASS");
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Class<?> type, String name, Object owner) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
