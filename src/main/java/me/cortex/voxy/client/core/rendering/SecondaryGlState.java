package me.cortex.voxy.client.core.rendering;

import com.mojang.blaze3d.platform.GlStateManager;
import static org.lwjgl.opengl.GL45C.*;
import static org.lwjgl.opengl.ARBIndirectParameters.*;

/** State borrowed from the caller of a secondary LOD draw, including exceptional exits. */
public final class SecondaryGlState implements AutoCloseable {
    private final int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING), read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
    private final int program = glGetInteger(GL_CURRENT_PROGRAM), vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
    private final int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
    private final int depthFunc = glGetInteger(GL_DEPTH_FUNC), frontFace = glGetInteger(GL_FRONT_FACE);
    private final int provokingVertex = glGetInteger(GL_PROVOKING_VERTEX);
    private final boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
    private final int[] scissorBox = new int[4];
    private static final int[] PIXEL_STORES = {GL_UNPACK_ROW_LENGTH, GL_UNPACK_IMAGE_HEIGHT,
            GL_UNPACK_SKIP_PIXELS, GL_UNPACK_SKIP_ROWS, GL_UNPACK_SKIP_IMAGES};
    private final int[] pixelStores = new int[PIXEL_STORES.length];
    private final boolean depth = glIsEnabled(GL_DEPTH_TEST), blend = glIsEnabled(GL_BLEND), cull = glIsEnabled(GL_CULL_FACE);
    private final boolean stencil = glIsEnabled(GL_STENCIL_TEST), depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
    private final int srcRgb = glGetInteger(GL_BLEND_SRC_RGB), dstRgb = glGetInteger(GL_BLEND_DST_RGB);
    private final int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
    private final int eqRgb = glGetInteger(GL_BLEND_EQUATION_RGB), eqAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA);
    private final int[] viewport = new int[4], textures = new int[16], samplers = new int[16], ssbo = new int[16], ubo = new int[8];
    private final int[] colourMask = new int[4];
    private final int[] stencilState = new int[7], backStencilState = new int[7];
    private final long[][] ssboRanges = new long[16][2], uboRanges = new long[8][2];
    private static final int[] OTHER_TEXTURE_TARGETS = {GL_TEXTURE_1D, GL_TEXTURE_3D, GL_TEXTURE_1D_ARRAY,
            GL_TEXTURE_2D_ARRAY, GL_TEXTURE_RECTANGLE, GL_TEXTURE_CUBE_MAP, GL_TEXTURE_CUBE_MAP_ARRAY,
            GL_TEXTURE_BUFFER, GL_TEXTURE_2D_MULTISAMPLE, GL_TEXTURE_2D_MULTISAMPLE_ARRAY};
    private static final int[] OTHER_TEXTURE_BINDINGS = {GL_TEXTURE_BINDING_1D, GL_TEXTURE_BINDING_3D,
            GL_TEXTURE_BINDING_1D_ARRAY, GL_TEXTURE_BINDING_2D_ARRAY, GL_TEXTURE_BINDING_RECTANGLE,
            GL_TEXTURE_BINDING_CUBE_MAP, GL_TEXTURE_BINDING_CUBE_MAP_ARRAY, GL_TEXTURE_BINDING_BUFFER,
            GL_TEXTURE_BINDING_2D_MULTISAMPLE, GL_TEXTURE_BINDING_2D_MULTISAMPLE_ARRAY};
    private final int[][] otherTextures = new int[16][OTHER_TEXTURE_TARGETS.length];
    private final int[][] imageUnits = new int[6][6];
    private final int[][] indexedBlend = new int[glGetInteger(GL_MAX_DRAW_BUFFERS)][11];
    private static final int[] BUFFER_TARGETS = {GL_ARRAY_BUFFER, GL_DRAW_INDIRECT_BUFFER,
            GL_DISPATCH_INDIRECT_BUFFER, GL_PARAMETER_BUFFER_ARB, GL_SHADER_STORAGE_BUFFER, GL_UNIFORM_BUFFER};
    private static final int[] BUFFER_BINDINGS = {GL_ARRAY_BUFFER_BINDING, GL_DRAW_INDIRECT_BUFFER_BINDING,
            GL_DISPATCH_INDIRECT_BUFFER_BINDING, GL_PARAMETER_BUFFER_BINDING_ARB,
            GL_SHADER_STORAGE_BUFFER_BINDING, GL_UNIFORM_BUFFER_BINDING};
    private final int[] buffers = new int[BUFFER_TARGETS.length];

    public SecondaryGlState() {
        glGetIntegerv(GL_VIEWPORT, viewport);
        glGetIntegerv(GL_SCISSOR_BOX, scissorBox);
        for (int i = 0; i < PIXEL_STORES.length; i++) pixelStores[i] = glGetInteger(PIXEL_STORES[i]);
        glGetIntegerv(GL_COLOR_WRITEMASK, colourMask);
        int[] names = {GL_STENCIL_FUNC, GL_STENCIL_REF, GL_STENCIL_VALUE_MASK, GL_STENCIL_WRITEMASK,
                GL_STENCIL_FAIL, GL_STENCIL_PASS_DEPTH_FAIL, GL_STENCIL_PASS_DEPTH_PASS};
        for (int i = 0; i < names.length; i++) stencilState[i] = glGetInteger(names[i]);
        int[] backNames = {GL_STENCIL_BACK_FUNC, GL_STENCIL_BACK_REF, GL_STENCIL_BACK_VALUE_MASK,
                GL_STENCIL_BACK_WRITEMASK, GL_STENCIL_BACK_FAIL, GL_STENCIL_BACK_PASS_DEPTH_FAIL, GL_STENCIL_BACK_PASS_DEPTH_PASS};
        for (int i = 0; i < backNames.length; i++) backStencilState[i] = glGetInteger(backNames[i]);
        for (int i = 0; i < buffers.length; i++) buffers[i] = glGetInteger(BUFFER_BINDINGS[i]);
        for (int i = 0; i < imageUnits.length; i++) {
            int[] namesImage = {GL_IMAGE_BINDING_NAME, GL_IMAGE_BINDING_LEVEL, GL_IMAGE_BINDING_LAYERED,
                    GL_IMAGE_BINDING_LAYER, GL_IMAGE_BINDING_ACCESS, GL_IMAGE_BINDING_FORMAT};
            for (int j = 0; j < namesImage.length; j++) imageUnits[i][j] = glGetIntegeri(namesImage[j], i);
        }
        for (int i = 0; i < indexedBlend.length; i++) {
            int[] b = indexedBlend[i];
            b[0] = glIsEnabledi(GL_BLEND, i) ? 1 : 0;
            int[] namesBlend = {GL_BLEND_SRC_RGB, GL_BLEND_DST_RGB, GL_BLEND_SRC_ALPHA,
                    GL_BLEND_DST_ALPHA, GL_BLEND_EQUATION_RGB, GL_BLEND_EQUATION_ALPHA};
            for (int j = 0; j < namesBlend.length; j++) b[j + 1] = glGetIntegeri(namesBlend[j], i);
            int[] mask = new int[4];
            glGetIntegeri_v(GL_COLOR_WRITEMASK, i, mask);
            System.arraycopy(mask, 0, b, 7, 4);
        }
        for (int i = 0; i < textures.length; i++) {
            // Vanilla caches only 12 units; Iris expands that cache when installed.
            if (i < 12) GlStateManager._activeTexture(GL_TEXTURE0 + i);
            glActiveTexture(GL_TEXTURE0 + i);
            textures[i] = glGetInteger(GL_TEXTURE_BINDING_2D);
            for (int j = 0; j < OTHER_TEXTURE_TARGETS.length; j++) otherTextures[i][j] = glGetInteger(OTHER_TEXTURE_BINDINGS[j]);
            samplers[i] = glGetIntegeri(GL_SAMPLER_BINDING, i);
            ssbo[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
            ssboRanges[i][0] = glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, i);
            ssboRanges[i][1] = glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, i);
        }
        for (int i = 0; i < ubo.length; i++) {
            ubo[i] = glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, i);
            uboRanges[i][0] = glGetInteger64i(GL_UNIFORM_BUFFER_START, i);
            uboRanges[i][1] = glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, i);
        }
        GlStateManager._activeTexture(activeTexture);
        glActiveTexture(activeTexture);
    }

    @Override public void close() {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        glUseProgram(program);
        GlStateManager._glBindVertexArray(vao);
        glBindVertexArray(vao);
        for (int i = 0; i < textures.length; i++) {
            if (i < 12) GlStateManager._activeTexture(GL_TEXTURE0 + i);
            glActiveTexture(GL_TEXTURE0 + i);
            if (i < 12) GlStateManager._bindTexture(textures[i]);
            glBindTexture(GL_TEXTURE_2D, textures[i]);
            for (int j = 0; j < OTHER_TEXTURE_TARGETS.length; j++) glBindTexture(OTHER_TEXTURE_TARGETS[j], otherTextures[i][j]);
            glBindSampler(i, samplers[i]);
            restoreBuffer(GL_SHADER_STORAGE_BUFFER, i, ssbo[i], ssboRanges[i]);
        }
        for (int i = 0; i < ubo.length; i++) restoreBuffer(GL_UNIFORM_BUFFER, i, ubo[i], uboRanges[i]);
        for (int i = 0; i < buffers.length; i++) glBindBuffer(BUFFER_TARGETS[i], buffers[i]);
        for (int i = 0; i < imageUnits.length; i++) {
            int[] u = imageUnits[i];
            glBindImageTexture(i, u[0], u[1], u[2] != 0, u[3], u[4], u[5]);
        }
        GlStateManager._activeTexture(activeTexture);
        glActiveTexture(activeTexture);
        GlStateManager._depthFunc(depthFunc);
        GlStateManager._depthMask(depthMask);
        GlStateManager._colorMask(colourMask[0] != 0, colourMask[1] != 0, colourMask[2] != 0, colourMask[3] != 0);
        GlStateManager._blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        glBlendEquationSeparate(eqRgb, eqAlpha);
        if (depth) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
        if (blend) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
        if (cull) GlStateManager._enableCull(); else GlStateManager._disableCull();
        // LOD uses raw GL calls too: repair driver state even when Minecraft's cache
        // already equals the saved state and its setters therefore do nothing.
        glDepthFunc(depthFunc); glDepthMask(depthMask);
        glColorMask(colourMask[0] != 0, colourMask[1] != 0, colourMask[2] != 0, colourMask[3] != 0);
        glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        if (depth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
        if (blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        if (cull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
        for (int i = 0; i < indexedBlend.length; i++) {
            int[] b = indexedBlend[i];
            if (b[0] != 0) glEnablei(GL_BLEND, i); else glDisablei(GL_BLEND, i);
            glBlendFuncSeparatei(i, b[1], b[2], b[3], b[4]);
            glBlendEquationSeparatei(i, b[5], b[6]);
            glColorMaski(i, b[7] != 0, b[8] != 0, b[9] != 0, b[10] != 0);
        }
        glFrontFace(frontFace);
        glProvokingVertex(provokingVertex);
        glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        if (scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
        for (int i = 0; i < PIXEL_STORES.length; i++) glPixelStorei(PIXEL_STORES[i], pixelStores[i]);
        glStencilFunc(stencilState[0], stencilState[1], stencilState[2]);
        glStencilMask(stencilState[3]);
        glStencilOp(stencilState[4], stencilState[5], stencilState[6]);
        glStencilFuncSeparate(GL_BACK, backStencilState[0], backStencilState[1], backStencilState[2]);
        glStencilMaskSeparate(GL_BACK, backStencilState[3]);
        glStencilOpSeparate(GL_BACK, backStencilState[4], backStencilState[5], backStencilState[6]);
        if (stencil) glEnable(GL_STENCIL_TEST); else glDisable(GL_STENCIL_TEST);
    }
    private static void restoreBuffer(int target, int index, int buffer, long[] range) {
        if (buffer != 0 && range[1] > 0) glBindBufferRange(target, index, buffer, range[0], range[1]);
        else glBindBufferBase(target, index, buffer);
    }
}
