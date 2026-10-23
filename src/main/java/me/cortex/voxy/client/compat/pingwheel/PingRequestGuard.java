package me.cortex.voxy.client.compat.pingwheel;

/** Latest operation wins, with one terminal send and one worker; independent of optional APIs. */
public final class PingRequestGuard {
    private volatile long generation;
    private boolean busy, sent;
    public synchronized long begin() { this.sent = false; return ++this.generation; }
    public synchronized boolean start(long token) {
        if (token != this.generation || this.busy) return false;
        this.busy = true;
        return true;
    }
    public synchronized void workerDone() { this.busy = false; }
    public boolean current(long token) { return token == this.generation; }
    public synchronized boolean claimSend(long token) {
        if (!current(token) || this.sent) return false;
        this.sent = true;
        return true;
    }
    public synchronized void invalidate() { ++this.generation; this.sent = false; }
}
