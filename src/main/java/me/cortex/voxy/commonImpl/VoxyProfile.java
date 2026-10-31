package me.cortex.voxy.commonImpl;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class VoxyProfile {
    private VoxyProfile() {}

    //Read on every instrumented call, so it is the one thing that must stay cheap when off
    public static volatile boolean enabled;

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static volatile long windowStartNanos;
    private static Thread renderThread;
    private static me.cortex.voxy.common.thread.TaskTiming.Window workerTiming;

    private static final class Entry {
        final LongAdder nanos = new LongAdder();
        final LongAdder calls = new LongAdder();
        final LongAdder renderThreadNanos = new LongAdder();
    }

    //Call once from the render thread so the report can tell the two buckets apart without a
    //ThreadLocal on the hot path
    public static void markRenderThread() {
        renderThread = Thread.currentThread();
    }

    public static long begin() {
        return enabled ? System.nanoTime() : 0L;
    }

    public static void end(String name, long start) {
        if (start == 0L || !enabled) {
            return;
        }
        long elapsed = System.nanoTime() - start;
        var entry = ENTRIES.computeIfAbsent(name, k -> new Entry());
        entry.nanos.add(elapsed);
        entry.calls.increment();
        if (Thread.currentThread() == renderThread) {
            entry.renderThreadNanos.add(elapsed);
        }
    }

    private static final Map<String, Entry> GPU = new ConcurrentHashMap<>();
    private static volatile long gpuGeneration;
    private static final int[] gpuSamples = new int[2];
    private static final java.util.List<java.util.Set<Long>> gpuFrames =
            java.util.List.of(new java.util.HashSet<>(), new java.util.HashSet<>());

    public static long gpuCaptureId() { return enabled ? gpuGeneration : 0; }

    public static synchronized void recordGpuSample(long generation, long frameId, boolean main,
                                                    String[] labels, long[] nanos) {
        if (!enabled || generation == 0 || generation != gpuGeneration) return;
        int group = main ? 0 : 1;
        gpuSamples[group]++;
        gpuFrames.get(group).add(frameId);
        String prefix = main ? "main " : "secondary ";
        for (int i = 0; i < nanos.length; i++) {
            var entry = GPU.computeIfAbsent(prefix + labels[i], k -> new Entry());
            entry.nanos.add(nanos[i]);
            entry.calls.increment();
        }
    }

    public static synchronized void start() {
        workerTiming = me.cortex.voxy.common.thread.TaskTiming.startProfile();
        ENTRIES.clear();
        GPU.clear();
        gpuGeneration++;
        java.util.Arrays.fill(gpuSamples, 0);
        gpuFrames.forEach(java.util.Set::clear);
        windowStartNanos = System.nanoTime();
        enabled = true;
    }

    public static synchronized void stop() {
        enabled = false;
        me.cortex.voxy.common.thread.TaskTiming.stopProfile();
    }

    public static boolean isRunning() {
        return enabled;
    }

    public static synchronized String report() {
        double windowMs = (System.nanoTime() - windowStartNanos) / 1.0e6;
        if (windowMs <= 0) {
            return "profile window is empty";
        }
        var rows = new ArrayList<Map.Entry<String, Entry>>(ENTRIES.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue().nanos.sum(), a.getValue().nanos.sum()));

        var sb = new StringBuilder(String.format("voxy profile over %.1f s%n", windowMs / 1000.0));
        sb.append(String.format("  %-28s %9s %9s %9s %8s%n", "section", "calls", "total ms", "ms/s", "on rt"));
        long renderTotal = 0;
        for (var row : rows) {
            var e = row.getValue();
            long total = e.nanos.sum();
            long rt = e.renderThreadNanos.sum();
            renderTotal += rt;
            sb.append(String.format("  %-28s %,9d %9.1f %9.2f %7.0f%%%n",
                    row.getKey(),
                    e.calls.sum(),
                    total / 1.0e6,
                    (total / 1.0e6) / (windowMs / 1000.0),
                    total == 0 ? 0.0 : (100.0 * rt / total)));
        }
        if (rows.isEmpty()) {
            sb.append("  (nothing recorded - is voxy rendering, and did anything ingest?)\n");
        }
        //The only line that maps directly onto frame rate
        sb.append(String.format("  %-28s %9s %9.1f %9.2f%n", "== render thread total", "",
                renderTotal / 1.0e6, (renderTotal / 1.0e6) / (windowMs / 1000.0)));
        sb.append("  ms/s on the render thread is milliseconds lost per second of play; anything not\n");
        sb.append("  marked 'on rt' is worker time and only costs frames through contention.\n");

        if (workerTiming != null) sb.append('\n').append(workerTiming.report());
        if (GPU.isEmpty()) {
            sb.append("  (no GPU samples - the pipeline did not run, or timer queries are unsupported)");
            return sb.toString();
        }
        var gpuRows = new ArrayList<Map.Entry<String, Entry>>(GPU.entrySet());
        gpuRows.sort((a, b) -> Long.compare(b.getValue().nanos.sum(), a.getValue().nanos.sum()));
        sb.append("  GPU values use completed viewport queries; pending queries are excluded.\n");
        for (int group = 0; group < 2; group++) {
            String prefix = group == 0 ? "main " : "secondary ";
            sb.append(String.format("  GPU %s: %,d valid viewport samples from %,d game frames:%n",
                    prefix.trim(), gpuSamples[group], gpuFrames.get(group).size()));
            if (gpuSamples[group] == 0) continue;
            long totalNanos = 0;
            for (var row : gpuRows) {
                if (!row.getKey().startsWith(prefix)) continue;
                long nanos = row.getValue().nanos.sum();
                totalNanos += nanos;
                sb.append(String.format("  %-28s %19.3f ms/viewport%n", row.getKey(),
                        nanos / 1e6 / gpuSamples[group]));
            }
            sb.append(String.format("  %-28s %19.3f ms/viewport%n", "== " + prefix + "gpu total",
                    totalNanos / 1e6 / gpuSamples[group]));
        }
        return sb.toString();
    }
}
