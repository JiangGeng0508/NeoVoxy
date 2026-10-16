package me.cortex.voxy.client.core.rendering;

import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VisibleSectionIndexTest {
    @Test
    void anUninitializedOrEmptyDrawSetOwnsNoSections() {
        var index = new ChunkBoundRenderer.VisibleSectionIndex();
        assertFalse(index.contains(0, new int[0], -1, SectionPos.asLong(0, 0, 0)));
        assertFalse(index.contains(1, new int[0], 0, SectionPos.asLong(0, 0, 0)));
    }

    @Test
    void packedNegativeCoordinatesAndUnusedBufferCapacityDoNotInventVisibleSections() {
        var index = new ChunkBoundRenderer.VisibleSectionIndex();
        long negative = SectionPos.asLong(-7, -4, -2);
        long positive = SectionPos.asLong(7, 4, 2);
        long unused = SectionPos.asLong(50, 0, 50);
        int[] uploaded = pack(negative, positive, negative, unused);
        assertTrue(index.contains(1, uploaded, 6, negative));
        assertTrue(index.contains(1, uploaded, 6, positive));
        assertFalse(index.contains(1, uploaded, 6, unused));
        assertFalse(index.contains(1, uploaded, 6, SectionPos.asLong(-7, -3, -2)));
    }

    @Test
    void replacingOrClearingTheDrawSetReleasesOldOwnership() {
        var index = new ChunkBoundRenderer.VisibleSectionIndex();
        long old = SectionPos.asLong(-2, 1, 3);
        long replacement = SectionPos.asLong(9, -2, -7);
        int[] uploaded = pack(old);
        assertTrue(index.contains(1, uploaded, 2, old));
        uploaded = pack(replacement);
        assertFalse(index.contains(2, uploaded, 2, old));
        assertTrue(index.contains(2, uploaded, 2, replacement));
        assertFalse(index.contains(3, uploaded, 0, replacement), "reset must discard cached ownership");
        assertTrue(index.contains(4, pack(old), 2, old), "a rebuilt section can acquire ownership again");
    }

    private static int[] pack(long... positions) {
        int[] packed = new int[positions.length * 2];
        for (int i = 0; i < positions.length; i++) {
            packed[i * 2] = (int) positions[i];
            packed[i * 2 + 1] = (int) (positions[i] >>> 32);
        }
        return packed;
    }
}
