package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.FrameProfiler;
import me.cortex.voxy.commonImpl.VoxyProfile;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Locale;
import java.util.function.Consumer;

import static org.lwjgl.opengl.GL33C.*;

/** Render-thread owned timestamp batches. Results retain the submitting frame and capture session. */
public class GPUTiming {
    public static final GPUTiming INSTANCE = new GPUTiming();
    static final int MAX_INFLIGHT = 64;
    static final int MAX_MARKERS = 64;

    interface QueryBackend {
        int create();
        void timestamp(int query);
        boolean available(int query);
        long result(int query);
        void delete(int query);
    }

    record Sample(long frameId, boolean main, long captureId, long profileId, String[] labels, long[] nanos) {}
    private record Batch(long frameId, boolean main, long captureId, long profileId, int[] queries, String[] labels) {}

    private final QueryBackend backend;
    private final Consumer<Sample> consumer;
    private final Deque<Integer> pool = new ArrayDeque<>();
    private final Deque<Batch> inflight = new ArrayDeque<>();
    private final int[] queries = new int[MAX_MARKERS];
    private final String[] labels = new String[MAX_MARKERS];
    private final String[][] rollingLabels = new String[2][];
    private final double[][] rolling = new double[2][];
    private int count;
    private long frameId, captureId, profileId;
    private boolean main, collecting, debugEnabled, closed;
    private long dropped;

    public GPUTiming() {
        this(new QueryBackend() {
            public int create() { return glGenQueries(); }
            public void timestamp(int query) { glQueryCounter(query, GL_TIMESTAMP); }
            public boolean available(int query) { return glGetQueryObjecti(query, GL_QUERY_RESULT_AVAILABLE) == GL_TRUE; }
            public long result(int query) { return glGetQueryObjecti64(query, GL_QUERY_RESULT); }
            public void delete(int query) { glDeleteQueries(query); }
        }, sample -> {
            FrameProfiler.onGpuSample(sample.captureId, sample.frameId, sample.main, sample.labels, sample.nanos);
            VoxyProfile.recordGpuSample(sample.profileId, sample.frameId, sample.main, sample.labels, sample.nanos);
        });
    }

    GPUTiming(QueryBackend backend, Consumer<Sample> consumer) {
        this.backend = backend;
        this.consumer = consumer;
    }

    /** Controls the F3 request only; captures and /profile hold their own independent requests. */
    public void setEnabled(boolean enabled) { this.debugEnabled = enabled; }

    public void beginViewport(long frameId, boolean main) {
        begin(frameId, main, FrameProfiler.captureId(), VoxyProfile.gpuCaptureId(),
                debugEnabled || FrameProfiler.isActive() || VoxyProfile.isRunning());
    }

    void begin(long frameId, boolean main, long captureId, long profileId, boolean enabled) {
        if (closed) throw new IllegalStateException("GPU timer freed");
        if (collecting) throw new IllegalStateException("Nested GPU timing batch");
        if (!enabled) return;
        if (inflight.size() >= MAX_INFLIGHT) { dropped++; return; }
        this.frameId = frameId;
        this.main = main;
        this.captureId = captureId;
        this.profileId = profileId;
        collecting = true;
        marker();
    }

    public void marker() { marker(null); }

    public void marker(String label) {
        if (!collecting) return;
        if (count == MAX_MARKERS) {
            // A truncated pass would fabricate a GPU total. Drop and recycle the entire batch.
            recycleCurrent();
            dropped++;
            return;
        }
        int query = pool.isEmpty() ? backend.create() : pool.removeFirst();
        backend.timestamp(query);
        queries[count] = query;
        labels[count++] = label;
    }

    public void endViewport() {
        if (!collecting) return;
        if (count >= 2) {
            inflight.addLast(new Batch(frameId, main, captureId, profileId,
                    Arrays.copyOf(queries, count), Arrays.copyOf(labels, count)));
            Arrays.fill(labels, 0, count, null);
            count = 0;
            collecting = false;
        } else recycleCurrent();
    }

    private void recycleCurrent() {
        for (int i = 0; i < count; i++) pool.addLast(queries[i]);
        Arrays.fill(labels, 0, count, null);
        count = 0;
        collecting = false;
    }

    /** No blocking result reads: the last timestamp must be available before reading the batch. */
    public void poll() {
        while (!inflight.isEmpty()) {
            Batch batch = inflight.getFirst();
            if (!backend.available(batch.queries[batch.queries.length - 1])) break;
            inflight.removeFirst();
            long[] nanos = new long[batch.queries.length - 1];
            String[] names = new String[nanos.length];
            long previous = backend.result(batch.queries[0]);
            pool.addLast(batch.queries[0]);
            boolean valid = true;
            for (int i = 0; i < nanos.length; i++) {
                long next = backend.result(batch.queries[i + 1]);
                pool.addLast(batch.queries[i + 1]);
                nanos[i] = next - previous;
                valid &= nanos[i] >= 0;
                names[i] = batch.labels[i] == null ? "pass" + i : batch.labels[i];
                previous = next;
            }
            if (!valid) { dropped++; continue; }
            int group = batch.main ? 0 : 1;
            if (!Arrays.equals(rollingLabels[group], names)) {
                rollingLabels[group] = names;
                rolling[group] = new double[nanos.length];
            }
            for (int i = 0; i < nanos.length; i++) {
                double ms = nanos[i] / 1e6;
                rolling[group][i] = Math.max(rolling[group][i] * .99 + ms * .01, ms);
            }
            consumer.accept(new Sample(batch.frameId, batch.main, batch.captureId, batch.profileId, names, nanos));
        }
    }

    public long droppedSamples() { return dropped; }

    public String getDebug() {
        if (!(debugEnabled || FrameProfiler.isActive() || VoxyProfile.isRunning())) return "";
        var out = new StringBuilder("GpuTime: ");
        for (int group = 0; group < 2; group++) {
            if (rolling[group] == null) continue;
            out.append(group == 0 ? "main[" : "secondary[");
            for (int i = 0; i < rolling[group].length; i++) {
                if (i != 0) out.append(", ");
                out.append(rollingLabels[group][i]).append(':').append(String.format(Locale.ROOT, "%.2f", rolling[group][i]));
            }
            out.append("] ");
        }
        return out.toString();
    }

    public void free() {
        if (closed) return;
        closed = true;
        recycleCurrent();
        while (!pool.isEmpty()) backend.delete(pool.removeFirst());
        while (!inflight.isEmpty()) for (int query : inflight.removeFirst().queries) backend.delete(query);
    }
}
