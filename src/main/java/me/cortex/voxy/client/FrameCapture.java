package me.cortex.voxy.client;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.ToLongFunction;

/** Raw capture data. All timestamps/durations are nanoseconds; owned by the render thread. */
final class FrameCapture {
    static final String[] STAGES = {"voxy all", "voxy main", "voxy dynamic", "voxy postDyn",
            "E maskRaster", "H modelBake", "F traversal", "G buildDrawCalls", "D downloadTick",
            "A nodeUpload", "B scatterWrite", "C cleanerIds"};

    static final class Frame {
        final long id;
        final long start;
        final long interval;
        long render;
        final int[] viewports = new int[2];
        final long[][] stages = new long[2][STAGES.length];
        final List<Map<String, Long>> gpu = List.of(new LinkedHashMap<>(), new LinkedHashMap<>());
        final int[] gpuViewports = new int[2];

        Frame(long id, long start, long interval) {
            this.id = id;
            this.start = start;
            this.interval = interval;
        }
    }

    private final Map<Long, Frame> frames = new LinkedHashMap<>();
    private Frame current;
    private long previousStart;
    private boolean hasPreviousStart;

    void begin(long id, long now) {
        if ((current != null && current.id == id) || frames.containsKey(id)) return;
        current = new Frame(id, now, hasPreviousStart ? now - previousStart : -1);
        previousStart = now;
        hasPreviousStart = true;
    }

    void viewport(boolean main, long[] stages) {
        if (current == null) return; // Capture armed between Pre and Post: wait for the next Pre.
        int group = main ? 0 : 1;
        current.viewports[group]++;
        for (int i = 0; i < STAGES.length; i++) current.stages[group][i] += stages[i];
    }

    Frame end(long now) {
        if (current == null) return null;
        Frame frame = current;
        current = null;
        frame.render = now - frame.start;
        frames.put(frame.id, frame);
        return frame;
    }

    void gpu(long frameId, boolean main, String[] labels, long[] nanos) {
        Frame frame = frames.get(frameId);
        if (frame == null) return;
        int group = main ? 0 : 1;
        if (frame.gpuViewports[group] >= frame.viewports[group]) return;
        frame.gpuViewports[group]++;
        for (int i = 0; i < nanos.length; i++) frame.gpu.get(group).merge(labels[i], nanos[i], Long::sum);
    }

    int size() { return frames.size(); }

    long[] intervals() { return values(f -> f.interval); }

    private long[] values(ToLongFunction<Frame> value) {
        return frames.values().stream().mapToLong(value).filter(v -> v >= 0).toArray();
    }

    String report() {
        var out = new StringBuilder("voxy frame capture - ").append(size()).append(" frames\n");
        out.append("Raw nanosecond samples; durations below in ms. Frame interval = Pre-to-Pre; render = Pre-to-Post.\n")
                .append("Stage rows sum all visits of that viewport group per game frame, including zero-work frames.\n");
        out.append(String.format(Locale.ROOT, "%-28s %7s %8s %8s %8s %8s %8s %8s%n",
                "stage(ms)", "samples", "p50", "p90", "p95", "p99", "max", "mean"));
        row(out, "frame interval", intervals());
        row(out, "frame render", values(f -> f.render));
        for (int group = 0; group < 2; group++) {
            final int g = group;
            String name = group == 0 ? "main" : "secondary";
            int visits = frames.values().stream().mapToInt(f -> f.viewports[g]).sum();
            int gpuVisits = frames.values().stream().mapToInt(f -> f.gpuViewports[g]).sum();
            var ready = frames.values().stream()
                    .filter(f -> f.viewports[g] > 0 && f.gpuViewports[g] == f.viewports[g]).toList();
            out.append('\n').append(name).append(": ").append(visits).append(" viewport visits; GPU valid viewports=")
                    .append(gpuVisits).append(", complete frames=").append(ready.size())
                    .append(", unavailable viewports=").append(visits - gpuVisits).append('\n');
            for (int stage = 0; stage < STAGES.length; stage++) {
                final int s = stage;
                row(out, name + " " + STAGES[stage], values(f -> f.stages[g][s]));
            }
            var labels = new TreeSet<String>();
            ready.forEach(f -> labels.addAll(f.gpu.get(g).keySet()));
            row(out, name + " GPU total", ready.stream().mapToLong(f ->
                    f.gpu.get(g).values().stream().mapToLong(Long::longValue).sum()).toArray());
            for (String label : labels) row(out, name + " GPU " + label,
                    ready.stream().mapToLong(f -> f.gpu.get(g).getOrDefault(label, 0L)).toArray());
        }
        return out.toString();
    }

    static void row(StringBuilder out, String label, long[] values) {
        if (values.length == 0) {
            out.append(label).append(": no valid samples\n");
            return;
        }
        Arrays.sort(values);
        double mean = Arrays.stream(values).average().orElse(0);
        out.append(String.format(Locale.ROOT, "%-28s %7d %8.3f %8.3f %8.3f %8.3f %8.3f %8.3f%n", label,
                values.length, percentile(values, 50) / 1e6, percentile(values, 90) / 1e6,
                percentile(values, 95) / 1e6, percentile(values, 99) / 1e6,
                values[values.length - 1] / 1e6, mean / 1e6));
    }

    /** Nearest-rank percentile on sorted samples. */
    static long percentile(long[] sorted, int percentile) {
        if (sorted.length == 0) return 0;
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * percentile / 100.0) - 1)];
    }
}
