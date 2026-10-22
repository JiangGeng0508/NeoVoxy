package me.cortex.voxy.commonImpl.network;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

/** One worker per server. Capture keys without network backpressure under storage locks,
 * then release the iterator before waiting on the bounded dispatch mailbox. */
public final class LodUpdateRecovery {
    private LodUpdateRecovery() {}

    public static long sweep(Path directory, String dimension, long revision, LodUpdateQueue queue,
                             Consumer<LongConsumer> positions, Predicate<LodUpdateQueue.Key> interested)
            throws IOException, InterruptedException {
        Files.createDirectories(directory);
        Path spool = Files.createTempFile(directory, "lod-", ".keys");
        try {
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(spool)))) {
                positions.accept(section -> {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                    if (!interested.test(new LodUpdateQueue.Key(dimension, section))) return;
                    try { out.writeLong(section); }
                    catch (IOException e) { throw new UncheckedIOException(e); }
                });
            }
            long bytes = Files.size(spool);
            try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(spool)))) {
                for (long i = 0; i < bytes / Long.BYTES; i++) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    var key = new LodUpdateQueue.Key(dimension, in.readLong());
                    if (interested.test(key)) queue.recover(key);
                }
            }
            queue.repaired(dimension, revision);
            return bytes;
        } finally {
            Files.deleteIfExists(spool);
        }
    }
}
