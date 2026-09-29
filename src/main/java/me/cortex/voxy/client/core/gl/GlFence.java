package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL32.*;

public class GlFence extends TrackedObject {
    private final long fence;
    private boolean signaled;

    public GlFence() {
        this.fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    private static final long SCRATCH = MemoryUtil.nmemCalloc(1,4);

    public boolean signaled() {
        if (!this.signaled) {
            MemoryUtil.memPutInt(SCRATCH, -1);
            nglGetSynciv(this.fence, GL_SYNC_STATUS, 1, 0, SCRATCH);
            int val = MemoryUtil.memGetInt(SCRATCH);
            if (val == GL_SIGNALED) {
                this.signaled = true;
            } else if (val != GL_UNSIGNALED) {
                throw new IllegalStateException("Unknown data from glGetSync: "+val);
            }
        }
        return this.signaled;
    }

    @Override
    public void free() {
        super.free0();
        glDeleteSync(this.fence);
    }
}
