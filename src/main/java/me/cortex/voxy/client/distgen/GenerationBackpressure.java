package me.cortex.voxy.client.distgen;

/** Admission only: an overloaded downstream must still be allowed to finish existing work. */
final class GenerationBackpressure {
    // Task backstops remain alongside the measured network and mesh result budgets.
    static final int INGEST_TASKS = 2000;
    static final int SAVE_TASKS = 1200;
    static final int MESH_TASKS = 2000;
    private volatile boolean paused;

    boolean allow(int ingest, int save, int mesh, double network, long tickNanos, long maxTickNanos) {
        return allow(ingest, save, mesh, network, 0, tickNanos, maxTickNanos);
    }

    boolean allow(int ingest, int save, int mesh, double network, double meshMemory, long tickNanos, long maxTickNanos) {
        return allow(ingest, save, mesh, network, meshMemory, 0, tickNanos, maxTickNanos);
    }

    boolean allow(int ingest, int save, int mesh, double network, double meshMemory, double ingestMemory,
                  long tickNanos, long maxTickNanos) {
        double pressure = Math.max(Math.max(ingestMemory, Math.max(network, meshMemory)), Math.max((double) ingest / INGEST_TASKS,
                Math.max((double) save / SAVE_TASKS, (double) mesh / MESH_TASKS)));
        if (this.paused) {
            // MSPT keeps its configured ceiling and gets a 10% recovery margin.
            if (pressure < 0.50 && tickNanos < maxTickNanos * 0.90) this.paused = false;
        } else if (pressure > 0.75 || tickNanos >= maxTickNanos) this.paused = true;
        return !this.paused;
    }

    boolean paused() { return this.paused; }
    void reset() { this.paused = false; }
}
