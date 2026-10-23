package me.cortex.voxy.client.compat.pingwheel;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.picking.LocalLodSource;
import me.cortex.voxy.client.core.picking.LodRaycaster;
import me.cortex.voxy.client.core.picking.PickingDistance;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.core.GameContext;
import nx.pingwheel.common.integration.ModContext;
import nx.pingwheel.common.math.Raycast;
import nx.pingwheel.common.network.PingLocationC2SPacket;
import nx.pingwheel.common.platform.IPlatformNetworkService;

import java.util.Objects;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Resolved only for the supported optional client mod. No world/entity reads on the worker. */
public final class PingWheelBridge {
    public static final long BUDGET_NANOS = 250_000_000L;
    public static final int MAX_SECTION_READS = 256, MAX_NODES = 32768;
    private static final PingRequestGuard GUARD = new PingRequestGuard();
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "Voxy PingWheel local ray");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
    private static Request pending;

    private record Settings(boolean enabled, boolean rendering, boolean picking, int curve, float radius, int cap,
                            int raycastDistance, int pingDistance, String channel) {
        static Settings capture(ClientConfig pw) {
            var cfg = VoxyConfig.CONFIG;
            return new Settings(cfg.enabled, cfg.enableRendering, cfg.pingwheelLodPicking, cfg.earthCurveRatio,
                    cfg.sectionRenderDistance, cfg.pingwheelMaxDistanceBlocks, pw.getRaycastDistance(), pw.getPingDistance(), pw.getChannel());
        }
        boolean usable() { return this.enabled && this.rendering && this.picking && this.curve == 0; }
    }
    private record Request(long token, ClientLevel level, ClientPacketListener connection, Entity camera,
                           VoxyRenderSystem renderer, WorldEngine engine, ClientConfig config, Settings settings,
                           String channel, int sequence, int dimension, float partialTick, LodRaycaster.Ray ray) {}

    public static long beginOperation() { pending = null; return GUARD.begin(); }

    public static boolean tryPick(long token, Vec3 direction, float partialTick, double originalDistance,
                                  boolean crouching, ClientConfig config, int sequence) {
        var mc = Minecraft.getInstance();
        Settings settings = Settings.capture(config);
        var renderer = IGetVoxyRenderSystem.getNullable();
        Entity camera = mc.getCameraEntity();
        if (!settings.usable() || !ClientSessionEvents.inSession || mc.level == null || mc.getConnection() == null
                || renderer == null || camera == null || camera.level() != mc.level || !Float.isFinite(partialTick)) return false;
        WorldEngine engine = renderer.getEngine();
        if (!engine.isLive()) return false;
        double distance = PickingDistance.limit(originalDistance, settings.radius, settings.cap);
        if (!Double.isFinite(distance) || distance <= 0 || !GUARD.start(token)) return false;
        var border = mc.level.getWorldBorder();
        var bounds = new LodRaycaster.Bounds(Math.max(-30_000_000, border.getMinX()), mc.level.getMinBuildHeight(),
                Math.max(-30_000_000, border.getMinZ()), Math.min(30_000_000, border.getMaxX()), mc.level.getMaxBuildHeight(),
                Math.min(30_000_000, border.getMaxZ()));
        Request request = new Request(token, mc.level, mc.getConnection(), camera, renderer, engine, config, settings,
                settings.channel, sequence, GameContext.getDimension(), partialTick,
                new LodRaycaster.Ray(camera.getEyePosition(partialTick), direction, distance, bounds));
        engine.acquireRef();
        pending = request;
        try {
            WORKER.execute(() -> query(request, crouching));
            return true;
        } catch (RuntimeException rejected) {
            pending = null;
            engine.releaseRef();
            GUARD.workerDone();
            return false;
        }
    }

    private static void query(Request request, boolean crouching) {
        LodRaycaster.Result result = null;
        try (var source = new LocalLodSource(request.engine, crouching, () -> !GUARD.current(request.token),
                System.nanoTime() + BUDGET_NANOS, MAX_SECTION_READS)) {
            result = LodRaycaster.trace(request.ray, source, () -> !GUARD.current(request.token), BUDGET_NANOS, MAX_NODES);
        } catch (Exception | LinkageError failure) {
            Logger.warn("PingWheel local LOD query failed: " + failure);
        } finally {
            request.engine.releaseRef();
            GUARD.workerDone();
        }
        LodRaycaster.Result completed = result;
        if (completed != null) org.slf4j.LoggerFactory.getLogger("voxy").debug(
                "PingWheel LOD pick: {} / {} ms / {} section lookups / {} nodes / LOD{}",
                completed.status(), completed.elapsedNanos() / 1_000_000.0, completed.sectionReads(), completed.visitedNodes(), completed.lod());
        Minecraft.getInstance().execute(() -> complete(request, completed));
    }

    private static boolean valid(Request request) {
        var mc = Minecraft.getInstance();
        return GUARD.current(request.token) && ClientSessionEvents.inSession && mc.level == request.level
                && mc.getConnection() == request.connection && request.connection.getConnection().isConnected()
                && mc.getCameraEntity() == request.camera && IGetVoxyRenderSystem.getNullable() == request.renderer
                && request.engine.isLive() && request.renderer.getEngine() == request.engine
                && GameContext.getDimension() == request.dimension
                && Objects.equals(request.settings, Settings.capture(request.config));
    }

    private static void complete(Request request, LodRaycaster.Result result) {
        if (!valid(request)) return;
        if (result != null && result.status() == LodRaycaster.Status.HIT) {
            send(request, result.position());
            if (pending == request) pending = null;
            return;
        }
        // Resume the original DH fallback directly, never re-enter performPingAction.
        // Its API captures the current camera origin; only continue if that origin still matches.
        if (ModContext.HasDistantHorizons
                && request.camera.getEyePosition(request.partialTick).distanceToSqr(request.ray.origin()) < 1e-8) {
            try {
                Raycast.traceDistantAsync(request.ray.direction(), request.partialTick, hit ->
                        Minecraft.getInstance().execute(() -> {
                            if (hit != null && valid(request)) send(request, hit.getLocation());
                            if (pending == request) pending = null;
                        }));
            } catch (RuntimeException | LinkageError failure) {
                if (pending == request) pending = null;
                Logger.warn("PingWheel DH fallback failed: " + failure);
            }
        } else if (pending == request) pending = null;
    }

    private static void send(Request request, Vec3 position) {
        if (valid(request) && GUARD.claimSend(request.token)) {
            IPlatformNetworkService.INSTANCE.sendToServer(new PingLocationC2SPacket(
                    request.channel, position, null, request.sequence, request.dimension));
        }
    }

    public static void tick() {
        if (pending != null && !valid(pending)) invalidate();
    }
    public static void invalidate() { pending = null; GUARD.invalidate(); }
    private PingWheelBridge() {}
}
