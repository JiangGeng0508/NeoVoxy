package me.cortex.voxy.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** One connection's pull requests. Storage workers enqueue; only the client tick sends. */
final class LodSectionRequests {
    static final int MAX_IN_FLIGHT = 512;
    static final int SENDS_PER_TICK = 64;
    static final long RETRY_NANOS = 5_000_000_000L;
    static final long TIMEOUT_NANOS = 15_000_000_000L;
    private static final int MAX_CONFIRMED_MISSING = 1 << 16;

    record Request(String dimension, long sectionKey) {}
    private record Pending(long since, long delay) {
        boolean ready(long now) { return this.delay == 0 || now - this.since >= this.delay; }
    }

    // Retain unsent demand until a reply or disconnect. Only keys, never section payloads,
    // are queued here; a full in-flight window must not silently discard a store miss.
    private final Map<String, LinkedHashMap<Long, Pending>> pending = new HashMap<>();
    private final Map<Request, Long> inFlight = new HashMap<>();
    private final Set<Request> held = new HashSet<>();
    private final LinkedHashSet<Request> confirmedMissing = new LinkedHashSet<>();
    private boolean closed;

    synchronized void request(String dimension, long key) {
        if (this.closed) return;
        var request = new Request(dimension, key);
        if (this.confirmedMissing.remove(request)) {
            this.confirmedMissing.add(request); // Keep frequently queried absences cached.
            return;
        }
        if (this.inFlight.containsKey(request) || this.held.contains(request)) return;
        this.pending.computeIfAbsent(dimension, ignored -> new LinkedHashMap<>())
                .putIfAbsent(key, new Pending(0, 0));
    }

    /** Call only after the hello response. Repeated hellos do not reset this state. */
    synchronized void pump(String dimension, long now, Predicate<Request> sender) {
        if (this.closed) return;
        var expired = this.inFlight.entrySet().iterator();
        while (expired.hasNext()) {
            var entry = expired.next();
            if (now - entry.getValue() >= TIMEOUT_NANOS) {
                var request = entry.getKey();
                expired.remove();
                this.enqueue(request, now, 0);
            }
        }
        var queued = this.pending.get(dimension);
        if (queued == null) return;
        var iterator = queued.entrySet().iterator();
        int attempts = 0;
        Request failed = null;
        while (iterator.hasNext() && attempts < SENDS_PER_TICK && this.inFlight.size() < MAX_IN_FLIGHT) {
            var entry = iterator.next();
            if (!entry.getValue().ready(now)) continue;
            var request = new Request(dimension, entry.getKey());
            iterator.remove();
            this.inFlight.put(request, now);
            attempts++;
            if (!sender.test(request)) {
                this.inFlight.remove(request);
                failed = request;
                break; // Transport failure: do not log/fail an entire window in one tick.
            }
        }
        if (failed != null) this.enqueue(failed, now, RETRY_NANOS);
        if (queued.isEmpty()) this.pending.remove(dimension);
    }

    /** Received data awaiting its mapping ids occupies no network slot and must not time out. */
    synchronized void hold(Request request) {
        if (this.closed) return;
        this.removePending(request);
        this.inFlight.remove(request);
        this.confirmedMissing.remove(request);
        this.held.add(request);
    }

    /** Retry a requested payload that could not be applied; ignore irrelevant broadcasts. */
    synchronized void retry(Request request, long now) {
        if (this.closed) return;
        boolean tracked = this.inFlight.remove(request) != null;
        tracked |= this.held.remove(request);
        var queued = this.pending.get(request.dimension());
        tracked |= queued != null && queued.containsKey(request.sectionKey());
        if (tracked) this.enqueue(request, now, RETRY_NANOS);
    }

    synchronized void complete(Request request, boolean missing) {
        if (this.closed) return;
        this.removePending(request);
        this.inFlight.remove(request);
        this.held.remove(request);
        this.confirmedMissing.remove(request);
        if (missing) {
            this.confirmedMissing.add(request);
            while (this.confirmedMissing.size() > MAX_CONFIRMED_MISSING) this.confirmedMissing.removeFirst();
        }
    }

    private void enqueue(Request request, long now, long delay) {
        this.pending.computeIfAbsent(request.dimension(), ignored -> new LinkedHashMap<>())
                .put(request.sectionKey(), new Pending(now, delay));
    }

    private void removePending(Request request) {
        var queued = this.pending.get(request.dimension());
        if (queued != null) {
            queued.remove(request.sectionKey());
            if (queued.isEmpty()) this.pending.remove(request.dimension());
        }
    }

    synchronized int pendingCount() {
        return this.pending.values().stream().mapToInt(Map::size).sum();
    }

    synchronized int inFlightCount() { return this.inFlight.size(); }

    synchronized void close() {
        this.closed = true;
        this.pending.clear();
        this.inFlight.clear();
        this.held.clear();
        this.confirmedMissing.clear();
    }
}
