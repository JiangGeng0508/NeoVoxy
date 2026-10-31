package me.cortex.voxy.common.thread;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Bounded raw worker samples. Capture and profile own independent recording windows. */
public final class TaskTiming {
    private TaskTiming() {}
    private static volatile Window capture;
    private static volatile Window profile;

    public static final class Window {
        private static final int CAPACITY = 8192;
        private final long start = System.nanoTime();
        private final Map<String, Samples> stages = new TreeMap<>();
        private boolean stopped;

        private static final class Samples {
            final long[] nanos = new long[CAPACITY];
            long count;
            long total;
            long max;
        }

        synchronized void record(String name, long start, long end) {
            if (stopped || start < this.start || end < start) return;
            var samples = stages.computeIfAbsent(name, k -> new Samples());
            long elapsed = end - start;
            samples.nanos[(int) (samples.count++ % CAPACITY)] = elapsed;
            samples.total += elapsed;
            samples.max = Math.max(samples.max, elapsed);
        }

        public synchronized String report() {
            var out = new StringBuilder("Worker timings (ms): queue = enqueue-to-start; work = service invocation; model wait = dependency registration-to-ready.\n");
            out.append("Percentiles use the most recent 8192 completed samples per stage; count/mean/max cover the window.\n");
            out.append("Only samples starting inside this window are included; unfinished waits are excluded.\n");
            for (var row : stages.entrySet()) {
                var sample = row.getValue();
                long[] values = Arrays.copyOf(sample.nanos, (int) Math.min(CAPACITY, sample.count));
                Arrays.sort(values);
                out.append(String.format(Locale.ROOT, "%s: count=%d retained=%d p50=%.3f p95=%.3f p99=%.3f mean=%.3f max=%.3f%n",
                        row.getKey(), sample.count, values.length, percentile(values, 50) / 1e6,
                        percentile(values, 95) / 1e6, percentile(values, 99) / 1e6,
                        sample.total / (double) sample.count / 1e6, sample.max / 1e6));
            }
            if (stages.isEmpty()) out.append("(no completed worker samples)\n");
            return out.toString();
        }

        private synchronized void stop() { stopped = true; }
    }

    private static long percentile(long[] values, int rank) {
        return values[Math.max(0, (int) Math.ceil(values.length * rank / 100.0) - 1)];
    }

    public static synchronized Window startCapture() {
        if (capture != null) capture.stop();
        return capture = new Window();
    }
    public static synchronized void stopCapture() {
        if (capture != null) capture.stop();
        capture = null;
    }
    public static synchronized Window startProfile() {
        if (profile != null) profile.stop();
        return profile = new Window();
    }
    public static synchronized void stopProfile() {
        if (profile != null) profile.stop();
        profile = null;
    }

    public static void record(String name, long start, long end) {
        Window a = capture, b = profile;
        if (a != null) a.record(name, start, end);
        if (b != null) b.record(name, start, end);
    }
}
