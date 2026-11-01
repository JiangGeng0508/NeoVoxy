package me.cortex.voxy.client.core.rendering.building;

/** Nonblocking admission for building and retained CPU mesh results, per renderer. */
public final class MeshResultBudget {
    public static final long NATIVE_LIMIT = 64L << 20;
    public static final long HEAP_LIMIT = 32L << 10;
    public static final int ENTRY_LIMIT = 1024;
    // Eight bounded quad buckets plus the largest serialized occupancy set; eight offsets.
    static final long BUILD_NATIVE_BYTES = RenderDataFactory.MAX_GEOMETRY_BYTES + OccupancySet.MAX_SERIALIZED_BYTES;
    static final long BUILD_HEAP_BYTES = 8L * Integer.BYTES;

    private final long nativeLimit, heapLimit;
    private final int entryLimit;
    private long nativeBytes, heapBytes;
    private long resultNativeBytes, resultHeapBytes;
    private int entries, building;

    public MeshResultBudget() { this(NATIVE_LIMIT, HEAP_LIMIT, ENTRY_LIMIT); }

    MeshResultBudget(long nativeLimit, long heapLimit, int entryLimit) {
        if (nativeLimit < BUILD_NATIVE_BYTES || heapLimit < BUILD_HEAP_BYTES || entryLimit < 1) {
            throw new IllegalArgumentException("Budget must fit at least one mesh build");
        }
        this.nativeLimit = nativeLimit;
        this.heapLimit = heapLimit;
        this.entryLimit = entryLimit;
    }

    public synchronized boolean canReserve() {
        return entries < entryLimit && nativeBytes <= nativeLimit - BUILD_NATIVE_BYTES
                && heapBytes <= heapLimit - BUILD_HEAP_BYTES;
    }

    synchronized Reservation tryReserve() {
        if (!canReserve()) return null;
        entries++;
        building++;
        nativeBytes += BUILD_NATIVE_BYTES;
        heapBytes += BUILD_HEAP_BYTES;
        return new Reservation();
    }

    public synchronized Stats stats() {
        return new Stats(nativeBytes, heapBytes, resultNativeBytes, resultHeapBytes,
                entries, building, nativeLimit, heapLimit, entryLimit);
    }

    public record Stats(long nativeBytes, long heapBytes, long resultNativeBytes, long resultHeapBytes,
                        int entries, int building, long nativeLimit, long heapLimit, int entryLimit) {
        public double pressure() {
            return Math.max((double) entries / entryLimit,
                    Math.max((double) nativeBytes / nativeLimit, (double) heapBytes / heapLimit));
        }
    }

    final class Reservation implements AutoCloseable {
        private long nativeSize = BUILD_NATIVE_BYTES, heapSize = BUILD_HEAP_BYTES;
        private boolean result, closed;

        void retain(long nativeSize, long heapSize) {
            synchronized (MeshResultBudget.this) {
                if (closed || result) throw new IllegalStateException("Reservation already transferred or closed");
                if (nativeSize < 0 || nativeSize > this.nativeSize || heapSize < 0 || heapSize > this.heapSize) {
                    throw new IllegalArgumentException("Mesh exceeds its build reservation");
                }
                nativeBytes -= this.nativeSize - nativeSize;
                heapBytes -= this.heapSize - heapSize;
                this.nativeSize = nativeSize;
                this.heapSize = heapSize;
                resultNativeBytes += nativeSize;
                resultHeapBytes += heapSize;
                building--;
                result = true;
            }
        }

        @Override public void close() {
            synchronized (MeshResultBudget.this) {
                if (closed) return;
                closed = true;
                nativeBytes -= nativeSize;
                heapBytes -= heapSize;
                entries--;
                if (result) {
                    resultNativeBytes -= nativeSize;
                    resultHeapBytes -= heapSize;
                } else building--;
            }
        }
    }
}
