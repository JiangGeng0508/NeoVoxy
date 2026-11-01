package me.cortex.voxy.common.world;

import java.util.Arrays;

/** Exclusive worker scratch. Never retains a section reference or its backing array. */
public final class SectionSnapshot {
    private long[] data;
    private boolean uniform;
    private long uniformValue;
    private byte children;

    public SectionSnapshot capture(WorldSection section) {
        synchronized (section) {
            long[] source = section._rawOrNull();
            this.uniform = source == null;
            this.uniformValue = section.getUniformValue();
            this.children = section.getNonEmptyChildren();
            if (source != null) {
                if (this.data == null) this.data = new long[WorldSection.SECTION_VOLUME];
                System.arraycopy(source, 0, this.data, 0, source.length);
            }
        }
        return this;
    }

    public long get(int index) { return this.uniform ? this.uniformValue : this.data[index]; }
    public boolean isUniform() { return this.uniform; }
    public byte children() { return this.children; }

    /** Expands only private scratch; the next capture reuses this array. */
    public long[] data() {
        if (this.data == null) this.data = new long[WorldSection.SECTION_VOLUME];
        if (this.uniform) {
            Arrays.fill(this.data, this.uniformValue);
            this.uniform = false;
        }
        return this.data;
    }

    public long allocatedBytes() { return this.data == null ? 0 : (long) this.data.length * Long.BYTES; }
}
