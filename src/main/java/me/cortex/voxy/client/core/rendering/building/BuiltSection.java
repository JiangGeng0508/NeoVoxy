package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.commonImpl.VoxyCommon;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BuiltSection {
    public static final boolean VERIFY_BUILT_SECTION_OFFSETS = VoxyCommon.isVerificationFlagOn("verifyBuiltSectionOffsets");
    public final long position;
    public final byte childExistence;
    public final int aabb;
    public final MemoryBuffer geometryBuffer;
    public final int[] offsets;
    public final MemoryBuffer occupancy;
    private final AtomicBoolean freed = new AtomicBoolean();
    private MeshResultBudget.Reservation reservation;

    private BuiltSection(long position, byte children) {
        this(position, children, -1, null, null, null);
    }

    public static BuiltSection empty(long position) {
        return new BuiltSection(position, (byte) 0);
    }
    public static BuiltSection emptyWithChildren(long position, byte children) {
        return new BuiltSection(position, children);
    }

    public BuiltSection(long position, byte childExistence, int aabb, MemoryBuffer geometryBuffer, int[] offsets, MemoryBuffer occupancy) {
        this.position = position;
        this.childExistence = childExistence;
        this.aabb = aabb;
        this.geometryBuffer = geometryBuffer;
        this.offsets = offsets;
        if (offsets != null && VERIFY_BUILT_SECTION_OFFSETS) {
            for (int i = 0; i < offsets.length-1; i++) {
                int delta = offsets[i+1] - offsets[i];
                if (delta<0||delta>=(1<<16)) {
                    throw new IllegalArgumentException("Offsets out of range");
                }
            }
        }
        this.occupancy = occupancy;
    }

    public BuiltSection clone() {
        return new BuiltSection(this.position, this.childExistence, this.aabb, this.geometryBuffer!=null?this.geometryBuffer.copy():null, this.offsets!=null?Arrays.copyOf(this.offsets, this.offsets.length):null, this.occupancy!=null?this.occupancy.copy():null);
    }

    void retainReservation(MeshResultBudget.Reservation reservation) {
        if (this.reservation != null || this.freed.get()) throw new IllegalStateException("Mesh is not owned by builder");
        reservation.retain((this.geometryBuffer == null ? 0 : this.geometryBuffer.size)
                + (this.occupancy == null ? 0 : this.occupancy.size),
                this.offsets == null ? 0 : (long) this.offsets.length * Integer.BYTES);
        this.reservation = reservation;
    }

    public void free() {
        if (!this.freed.compareAndSet(false, true)) return;
        try {
            if (this.geometryBuffer != null) this.geometryBuffer.free();
            if (this.occupancy != null) this.occupancy.free();
        } finally {
            if (this.reservation != null) this.reservation.close();
        }
    }

    public boolean isEmpty() {
        return this.geometryBuffer == null;
    }
}
