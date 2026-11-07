package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.common.util.MemoryBuffer;

import java.util.ArrayDeque;
import java.util.HashSet;

/** Reserves before GPU readback and retains ownership through CPU processing. */
final class NodeBatchQueue implements AutoCloseable {
    record Stats(long nativeBytes, long reservedBytes, long byteLimit, int entries, int queued,
                 int entryLimit, long refused, long peakBytes) {
        double pressure() {
            return Math.max((double) reservedBytes / byteLimit, (double) entries / entryLimit);
        }
    }

    private final long byteLimit;
    private final int entryLimit;
    private final Runnable ready;
    private final HashSet<Batch> owned = new HashSet<>();
    private final ArrayDeque<Batch> queue = new ArrayDeque<>();
    private long reservedBytes, nativeBytes, refused, peakBytes;
    private boolean closed;

    NodeBatchQueue(long byteLimit, int entryLimit, Runnable ready) {
        if (byteLimit <= 0 || entryLimit <= 0) throw new IllegalArgumentException("Invalid node batch budget");
        this.byteLimit = byteLimit;
        this.entryLimit = entryLimit;
        this.ready = ready;
    }

    synchronized Batch reserve(long bytes) {
        if (bytes <= 0) throw new IllegalArgumentException("Empty node batch");
        if (closed) return null;
        if (bytes > byteLimit - reservedBytes || owned.size() >= entryLimit) {
            refused++;
            return null;
        }
        var batch = new Batch(bytes);
        owned.add(batch);
        reservedBytes += bytes;
        peakBytes = Math.max(peakBytes, reservedBytes);
        return batch;
    }

    synchronized Batch poll() { return queue.pollFirst(); }

    synchronized Stats stats() {
        return new Stats(nativeBytes, reservedBytes, byteLimit, owned.size(), queue.size(), entryLimit, refused, peakBytes);
    }

    /** Call after the consumer has stopped. Late GPU callbacks become no-ops. */
    @Override public synchronized void close() {
        closed = true;
        for (var batch : owned.toArray(Batch[]::new)) batch.close();
        queue.clear();
    }

    final class Batch implements AutoCloseable {
        private long bytes;
        private MemoryBuffer buffer;
        private boolean released;

        private Batch(long bytes) { this.bytes = bytes; }

        /** Copies only after admission. The optional count replaces the GPU header in the copy. */
        boolean publish(long address, long size, Integer count) {
            synchronized (NodeBatchQueue.this) {
                if (released) return false;
                if (buffer != null) throw new IllegalStateException("Node batch already published");
                if (size <= 0 || size > bytes || (count != null && (count < 0 || size < 8L + count * 8L))) {
                    throw new IllegalArgumentException("Invalid node batch size");
                }
                buffer = new MemoryBuffer(size).cpyFrom(address);
                if (count != null) org.lwjgl.system.MemoryUtil.memPutInt(buffer.address, count);
                reservedBytes -= bytes - size;
                bytes = size;
                nativeBytes += size;
                queue.addLast(this);
                try { ready.run(); }
                catch (RuntimeException | Error failure) {
                    queue.removeLastOccurrence(this);
                    close();
                    throw failure;
                }
                return true;
            }
        }

        MemoryBuffer buffer() { return buffer; }

        @Override public void close() {
            synchronized (NodeBatchQueue.this) {
                if (released) return;
                released = true;
                queue.remove(this);
                owned.remove(this);
                reservedBytes -= bytes;
                if (buffer != null) {
                    nativeBytes -= buffer.size;
                    buffer.free();
                    buffer = null;
                }
            }
        }
    }
}
