package me.cortex.voxy.common.world;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SectionSnapshotTest {
    @Test void uniformExpansionIsPrivateAndDenseCapturesReuseTheSameScratch() {
        var section = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        section.acquire(); section.setUniform(123); section._unsafeSetNonEmptyChildren((byte) 57);
        var snapshot = new SectionSnapshot().capture(section);
        assertTrue(snapshot.isUniform()); assertEquals(0, snapshot.allocatedBytes());
        long[] scratch = snapshot.data();
        assertTrue(section.isUniform()); assertEquals(57, snapshot.children());
        for (long value : scratch) assertEquals(123, value);
        scratch[0] = 456;
        assertEquals(123, section.get(0), "render-only substitutions cannot touch storage");
        synchronized (section) { Arrays.fill(section.materialize(), 789); }
        assertEquals(456, snapshot.get(0), "captured data is independent of later writes");
        assertSame(scratch, snapshot.capture(section).data()); assertEquals(789, scratch[0]);
        section.release();
        // A pooled backing array can be reused immediately without affecting the snapshot.
        var other = WorldSection._createRawUntrackedUnsafeSection(0, 1, 0, 0);
        other.acquire(); Arrays.fill(other.materialize(), 42); other.release();
        assertEquals(789, snapshot.get(0));
    }

    @Test void allFacesKeepTheirCoordinateLayoutAndDoNotExpandUniformStorage() {
        var section = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        section.acquire(); section.setUniform(19);
        long[] faces = new long[6 * 1024 + 2]; Arrays.fill(faces, -1);
        for (int face = 0; face < 6; face++) assertTrue(section.copyFaceInto(face, faces, 1 + face * 1024));
        assertTrue(section.isUniform()); assertEquals(-1, faces[0]); assertEquals(-1, faces[faces.length - 1]);
        for (int i = 1; i < faces.length - 1; i++) assertEquals(19, faces[i]);
        long[] data = section.materialize();
        for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            data[WorldSection.getIndex(x, y, z)] = x + z * 100 + y * 10000;
        }
        for (int face = 0; face < 6; face++) {
            assertFalse(section.copyFaceInto(face, faces, 1 + face * 1024));
            for (int i = 0; i < 1024; i++) {
                int x = face < 2 ? (face == 0 ? 0 : 31) : i & 31;
                int y = face == 2 ? 0 : face == 3 ? 31 : i >> 5;
                int z = face < 2 ? i & 31 : face < 4 ? i >> 5 : face == 4 ? 0 : 31;
                assertEquals(x + z * 100 + y * 10000, faces[1 + face * 1024 + i]);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> section.copyFaceInto(6, faces, 0));
        assertThrows(IllegalArgumentException.class, () -> section.copyFaceInto(0, faces, faces.length - 1023));
        section.release();
    }

    @Test void snapshotsAndFacesCannotObserveHalfOfALockedWriteBatch() throws Exception {
        var section = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        section.acquire(); section.materialize();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writing = executor.submit(() -> {
                for (int i = 1; i <= 500; i++) synchronized (section) { Arrays.fill(section.materialize(), i); }
            });
            var snapshot = new SectionSnapshot(); long[] face = new long[1024];
            for (int i = 0; i < 500; i++) {
                long[] data = snapshot.capture(section).data();
                for (long value : data) assertEquals(data[0], value);
                section.copyFaceInto(i % 6, face, 0);
                for (long value : face) assertEquals(face[0], value);
            }
            writing.get(10, TimeUnit.SECONDS);
        } finally { section.release(); }
    }
}
