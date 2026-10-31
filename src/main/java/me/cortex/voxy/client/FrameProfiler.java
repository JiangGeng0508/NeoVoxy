package me.cortex.voxy.client;

import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.PerfStats;
import me.cortex.voxy.common.thread.TaskTiming;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class FrameProfiler {
    private FrameProfiler() {}

    private static final int SNAPSHOT_INTERVAL_MS = 500;
    private static final long STALL_THRESHOLD_MICROS = 40_000;
    private static final int MAX_STALL_CAPTURES = 40;
    private static volatile boolean active;
    private static volatile long captureId;
    private static long frameId;
    private static volatile long frameStartNanos;
    private static volatile Thread renderThread;
    private static volatile boolean insideVoxyRender;
    private static Thread watchdog;
    private static long startedAtMs;
    private static long endAtMs;
    private static long lastSnapshotMs;
    private static long initialForcedSyncs;
    private static long initialDroppedGpu;
    private static FrameCapture capture;
    private static TaskTiming.Window workerTiming;
    private static final List<String> snapshots = new ArrayList<>();
    private static final List<String> stalls = new ArrayList<>();

    public static boolean isActive() { return active; }
    public static long captureId() { return active ? captureId : 0; }
    public static long frameId() { return frameId; }

    public static synchronized String start(int seconds) {
        if (active) return "Frame capture already running, use /voxy debug capture again to stop it early";
        capture = new FrameCapture();
        workerTiming = TaskTiming.startCapture();
        snapshots.clear();
        stalls.clear();
        startedAtMs = System.currentTimeMillis();
        endAtMs = startedAtMs + seconds * 1000L;
        lastSnapshotMs = 0;
        initialForcedSyncs = PerfStats.uploadForcedSyncs.sum();
        initialDroppedGpu = GPUTiming.INSTANCE.droppedSamples();
        frameStartNanos = 0;
        insideVoxyRender = false;
        long generation = ++captureId;
        active = true;
        watchdog = new Thread(() -> runWatchdog(generation), "Voxy frame capture watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        return "Frame capture armed for " + seconds + "s - move around the way that drops frames. "
                + "It stops on its own, or run the command again to stop early.";
    }

    // Only RenderFrameEvent.Pre advances the game frame, including frames without Voxy terrain.
    public static void onFrameStart() {
        frameId++;
        if (!active) return;
        long now = System.nanoTime();
        renderThread = Thread.currentThread();
        frameStartNanos = now;
        insideVoxyRender = false;
        capture.begin(frameId, now);
    }

    public static void onViewportStart() {
        if (active) insideVoxyRender = true;
    }

    public static void onViewportEnd(boolean main) {
        if (!active) return;
        capture.viewport(main, TimingStatistics.snapshotNanos());
        insideVoxyRender = false;
    }

    public static void onGpuSample(long generation, long frame, boolean main, String[] labels, long[] nanos) {
        if (active && generation == captureId) capture.gpu(frame, main, labels, nanos);
    }

    // Stack walking happens on the watchdog, never on the measured render thread.
    private static void runWatchdog(long generation) {
        long capturedStart = 0;
        while (active && captureId == generation) {
            try { Thread.sleep(4); }
            catch (InterruptedException e) { return; }
            long start = frameStartNanos;
            Thread thread = renderThread;
            if (start == 0 || start == capturedStart || thread == null) continue;
            if (!thread.isAlive()) {
                synchronized (FrameProfiler.class) {
                    if (active && captureId == generation) Logger.info(stopAndDump());
                }
                return;
            }
            long elapsedMicros = (System.nanoTime() - start) / 1000;
            if (elapsedMicros < STALL_THRESHOLD_MICROS || elapsedMicros > 10_000_000) continue;
            synchronized (FrameProfiler.class) {
                if (!active || captureId != generation || stalls.size() >= MAX_STALL_CAPTURES) continue;
            }
            capturedStart = start;
            var sb = new StringBuilder();
            sb.append("=== IN-FLIGHT stall (").append(insideVoxyRender ? "inside voxy render" : "outside voxy render")
                    .append("), blocked ").append(elapsedMicros / 1000).append("ms so far\n");
            try {
                var trace = thread.getStackTrace();
                for (int i = 0; i < Math.min(trace.length, 22); i++) sb.append("      ").append(trace[i]).append('\n');
                sb.append(captureWorkerStacks(elapsedMicros));
            } catch (Throwable e) { sb.append("<stack capture failed: ").append(e).append(">\n"); }
            synchronized (FrameProfiler.class) {
                if (active && captureId == generation && stalls.size() < MAX_STALL_CAPTURES) stalls.add(sb.toString());
            }
        }
    }

    // Called once from RenderFrameEvent.Post. Polling never waits for the GPU.
    public static void onFrameEnd() {
        long now = active ? System.nanoTime() : 0;
        if (active) capture.end(now);
        GPUTiming.INSTANCE.poll();
        if (!active) return;
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastSnapshotMs >= SNAPSHOT_INTERVAL_MS) {
            lastSnapshotMs = nowMs;
            snapshots.add(snapshot(nowMs));
        }
        if (nowMs >= endAtMs) {
            String msg = stopAndDump();
            Logger.info(msg);
            var player = Minecraft.getInstance().player;
            if (player != null) player.displayClientMessage(net.minecraft.network.chat.Component.literal(msg), false);
        }
    }

    //Voxy's own threads plus the render thread. Anything blocking the render thread on a GL fence
    //usually has a partner thread doing the work, so both sides are needed to read a stall.
    static boolean isInterestingThread(Thread t) {
        String name = t.getName();
        return name.startsWith("Voxy")
                || name.startsWith("Dedicated Voxy Worker")
                || name.startsWith("Async Node Manager")
                || name.contains("Ingest")
                || name.contains("Section saving")
                || name.contains("Model factory")
                || name.contains("Render gen");
    }

    private static String captureWorkerStacks(long frameMicros) {
        var sb = new StringBuilder();
        sb.append("=== stall ").append(String.format("%.1fms", frameMicros / 1000.0))
                .append(" at t+").append(System.currentTimeMillis() - startedAtMs).append("ms\n");
        try {
            for (var entry : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                if (!isInterestingThread(thread)) {
                    continue;
                }
                StackTraceElement[] trace = entry.getValue();
                if (trace.length == 0) {
                    continue;
                }
                sb.append("  [").append(thread.getName()).append(" ").append(thread.getState()).append("]\n");
                //Deep enough to cross the mod boundary into whatever is actually blocking
                int limit = Math.min(trace.length, 18);
                for (int i = 0; i < limit; i++) {
                    sb.append("      ").append(trace[i]).append('\n');
                }
            }
        } catch (Throwable e) {
            sb.append("  <stack capture failed: ").append(e).append(">\n");
        }
        return sb.toString();
    }

    private static String snapshot(long nowMs) {
        var lines = new ArrayList<String>();
        try {
            var levelRenderer = Minecraft.getInstance().levelRenderer;
            if (levelRenderer instanceof me.cortex.voxy.client.core.IGetVoxyRenderSystem holder) {
                var vrs = holder.voxy$getRenderSystem();
                if (vrs != null) {
                    vrs.addDebugInfo(lines);
                }
            }
        } catch (Throwable e) {
            lines.add("<snapshot failed: " + e + ">");
        }
        return "[t+" + (nowMs - startedAtMs) + "ms] " + String.join(" | ", lines);
    }

    public static synchronized String stopAndDump() {
        if (!active) return "No frame capture running";
        active = false;
        TaskTiming.stopCapture();
        frameStartNanos = 0;
        insideVoxyRender = false;
        renderThread = null;
        if (watchdog != null) { watchdog.interrupt(); watchdog = null; }
        if (capture.size() == 0) {
            capture = null;
            snapshots.clear();
            stalls.clear();
            return "Frame capture produced no frames";
        }
        var report = new StringBuilder(capture.report());
        report.append('\n').append(workerTiming.report());
        report.append('\n').append(configLine()).append('\n');
        report.append("Upload stream forced glFinish calls: ")
                .append(PerfStats.uploadForcedSyncs.sum() - initialForcedSyncs).append('\n');
        report.append("GPU query batches dropped (capacity/invalid batch): ")
                .append(GPUTiming.INSTANCE.droppedSamples() - initialDroppedGpu).append('\n');
        report.append("GPU results still unavailable at stop are excluded, never replaced by zero.\n");
        long[] intervals = capture.intervals();
        Arrays.sort(intervals);
        long median = FrameCapture.percentile(intervals, 50);
        long stallCount = 0, stallNanos = 0;
        for (long interval : intervals) {
            if (interval >= STALL_THRESHOLD_MICROS * 1000) { stallCount++; stallNanos += interval; }
        }
        report.append(String.format(Locale.ROOT, "Stalls (>=40ms): %d intervals, %.3fs total%n", stallCount, stallNanos / 1e9));
        report.append("\nstall stacks (").append(stalls.size()).append(" captured, cap ").append(MAX_STALL_CAPTURES).append("):\n");
        for (String stall : stalls) report.append(stall);
        report.append("\nsubsystem snapshots (every ").append(SNAPSHOT_INTERVAL_MS).append("ms):\n");
        for (String snapshot : snapshots) report.append(snapshot).append('\n');
        Path out = Minecraft.getInstance().gameDirectory.toPath().resolve("voxy-frame-capture.txt");
        try {
            Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
            return "Frame capture written to " + out.toAbsolutePath()
                    + (intervals.length == 0 ? " (no complete frame intervals)"
                    : String.format(Locale.ROOT, " (median %.1fms/frame)", median / 1e6));
        } catch (IOException e) {
            Logger.error("Failed to write frame capture", e);
            return "Frame capture failed to write: " + e;
        } finally {
            capture = null;
            snapshots.clear();
            stalls.clear();
        }
    }

    private static String configLine() {
        var cfg = me.cortex.voxy.client.config.VoxyConfig.CONFIG;
        var mc = Minecraft.getInstance();
        return "config: sectionRenderDistance=" + cfg.sectionRenderDistance
                + " subDivisionSize=" + cfg.subDivisionSize
                + " renderPressure=" + cfg.renderPressure
                + " leafMode=" + cfg.leafLodMode
                + " vanillaRenderDistance=" + mc.options.getEffectiveRenderDistance()
                + " shaders=" + me.cortex.voxy.client.core.util.IrisUtil.irisShaderPackEnabled()
                + " window=" + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight();
    }
}
