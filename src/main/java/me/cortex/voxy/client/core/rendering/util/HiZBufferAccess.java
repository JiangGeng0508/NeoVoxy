package me.cortex.voxy.client.core.rendering.util;

/** Common Hi-Z texture contract used by the traversal pass. */
public interface HiZBufferAccess {
    void buildMipChain(int srcDepthTex, int width, int height);
    void free();
    int getHizTextureId();
    int getPackedLevels();
    default String describe() { return "draw"; }
}
