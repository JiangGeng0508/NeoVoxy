package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.testing.GpuTestContext;
import me.cortex.voxy.testing.Reflect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45C.*;

@EnabledIfSystemProperty(named = "voxy.verifyScreenspaceGpu", matches = "true")
class HiZBufferGpuTest {
    @Test void singletonAndNarrowTargetsAllocateValidMipStorage() throws Exception {
        try (var context = GpuTestContext.open("HiZBufferGpuTest")) {
            for (int[] size : new int[][]{{1, 1}, {1, 16}, {16, 1}, {64, 32}}) {
                var hiz = new HiZBuffer(new RenderProperties(true, false, false));
                int texture = 0;
                try {
                    // Execute the allocation that failed in the actual 1x1 game window, including
                    // the production framebuffer completeness check and shader compilation.
                    Reflect.call(hiz, "alloc", size[0], size[1]);
                    texture = hiz.getHizTextureId();
                    int expectedLevels = Math.max(1, Integer.numberOfTrailingZeros(Math.max(size[0], size[1])));
                    assertEquals(expectedLevels, glGetTextureParameteri(texture, GL_TEXTURE_IMMUTABLE_LEVELS));
                    assertEquals(size[0], glGetTextureLevelParameteri(texture, 0, GL_TEXTURE_WIDTH));
                    assertEquals(size[1], glGetTextureLevelParameteri(texture, 0, GL_TEXTURE_HEIGHT));
                    assertEquals((size[0] << 16) | size[1], hiz.getPackedLevels());
                    assertEquals(GL_NO_ERROR, glGetError());
                } finally { hiz.free(); }
                assertFalse(glIsTexture(texture));
            }
        }
    }
}
