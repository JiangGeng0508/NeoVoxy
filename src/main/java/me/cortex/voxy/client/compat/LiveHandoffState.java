package me.cortex.voxy.client.compat;

/** Fixes ownership for a frame and waits for sustained readiness before returning to live rendering. */
public final class LiveHandoffState {
    private static final long REACQUIRE_NANOS = 250_000_000L;
    private long frame = Long.MIN_VALUE;
    private long readySince;
    private boolean known;
    private boolean waiting;
    private boolean live;

    public boolean evaluated(long frame) { return this.frame == frame; }

    public boolean liveOwns() { return this.live; }

    public boolean reacquiring() { return this.known && !this.live; }

    public double handoverRange(double liveRange) {
        return this.reacquiring() ? Math.max(16.0, liveRange - 16.0) : liveRange;
    }

    public boolean update(long frame, long now, boolean ready) {
        if (this.frame == frame) return this.live;
        this.frame = frame;
        if (!ready) {
            this.live = false;
            this.waiting = false;
        } else if (!this.known || this.live) {
            this.live = true;
        } else if (!this.waiting) {
            this.readySince = now;
            this.waiting = true;
        } else if (now - this.readySince >= REACQUIRE_NANOS) {
            this.live = true;
            this.waiting = false;
        }
        this.known = true;
        return this.live;
    }
}
