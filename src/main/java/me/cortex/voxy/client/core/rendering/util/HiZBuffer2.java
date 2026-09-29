package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.GL_TEXTURE_FETCH_BARRIER_BIT;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL33.glGenSamplers;
import static org.lwjgl.opengl.GL33C.glDeleteSamplers;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL42C.*;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;
import static org.lwjgl.opengl.GL45C.glTextureBarrier;

/** Shared-memory Hi-Z reduction without subgroup extension/layout assumptions. */
public final class HiZBuffer2 implements HiZBufferAccess {
    private static final int LEVELS_PER_DISPATCH = 6;
    private static final int TILE = 64;
    private static boolean fallbackAnnounced;
    private static boolean computeBroken;
    private final Shader hizMip;
    private final Shader hizInitial;
    private final GlFramebuffer fb = new GlFramebuffer().name("HiZ Compute");
    private final int sampler = glGenSamplers();
    private GlTexture texture;
    private int levels, width, height;
    private final RenderProperties properties;

    public static HiZBufferAccess createOrFallback(RenderProperties properties) {
        try {
            if (computeBroken) return fallback(properties);
            return new HiZBuffer2(properties);
        } catch (RuntimeException error) {
            computeBroken = true;
            Logger.error("Shared-memory Hi-Z unavailable; using draw-chain Hi-Z fallback", error);
            announceFallback();
            return fallback(properties);
        }
    }

    private static HiZBuffer fallback(RenderProperties properties) {
        return new HiZBuffer(properties) {
            @Override public String describe() { return "draw(compute fallback)"; }
        };
    }

    private static void announceFallback() {
        if (fallbackAnnounced) return;
        fallbackAnnounced = true;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.gui != null) mc.gui.getChat().addMessage(
                    Component.translatable("voxy.experimental.hiZCompute.fallback"));
        });
    }

    public HiZBuffer2(RenderProperties properties) {
        this.properties = properties;
        Shader mip = null;
        try {
        glNamedFramebufferDrawBuffer(this.fb.id, GL_COLOR_ATTACHMENT0);
        mip = Shader.make().apply(properties::apply)
                .add(ShaderType.COMPUTE, "voxy:hiz/hiz.comp").compile().name("HiZ Compute Mips");
        this.hizInitial = Shader.make().apply(properties::apply).define("OUTPUT_COLOUR")
                .add(ShaderType.VERTEX, "voxy:hiz/blit.vsh")
                .add(ShaderType.FRAGMENT, "voxy:hiz/blit.fsh").compile().name("HiZ Compute Level0");
        this.hizMip = mip;
        } catch (RuntimeException error) {
            if (mip != null) mip.free();
            this.fb.free();
            glDeleteSamplers(this.sampler);
            throw error;
        }
    }

    private void alloc(int width, int height) {
        this.levels = Math.max(1, (int) Math.ceil(Math.log(Math.max(width, height)) / Math.log(2)));
        this.texture = new GlTexture().store(GL_R32F, this.levels, width, height).name("HiZ Compute");
        glTextureParameteri(texture.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glTextureParameteri(texture.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTextureParameteri(texture.id, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glTextureParameteri(texture.id, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTextureParameteri(texture.id, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(sampler, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glSamplerParameteri(sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        this.width = width; this.height = height;
        fb.bind(GL_COLOR_ATTACHMENT0, texture, 0).verify();
    }

    @Override public void buildMipChain(int srcDepthTex, int width, int height) {
        int w = Integer.highestOneBit(width), h = Integer.highestOneBit(height);
        if (this.width != w || this.height != h) { if (texture != null) texture.free(); alloc(w, h); }
        int boundFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        boolean blend = glIsEnabled(GL_BLEND), depth = glIsEnabled(GL_DEPTH_TEST);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        boolean maskR, maskG, maskB, maskA;
        try (var stack = MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            glGetBooleanv(GL_COLOR_WRITEMASK, mask);
            maskR = mask.get(0) != 0;
            maskG = mask.get(1) != 0;
            maskB = mask.get(2) != 0;
            maskA = mask.get(3) != 0;
        }
        try {
            glBindVertexArray(GlVertexArray.STATIC_VAO);
            hizInitial.bind(); glBindFramebuffer(GL_FRAMEBUFFER, fb.id);
            glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glColorMask(true, true, true, true);
            glBindTextureUnit(0, srcDepthTex); glBindSampler(0, sampler); glUniform1i(0, 0);
            glViewport(0, 0, this.width, this.height); glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
            glTextureBarrier(); glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
            glBindFramebuffer(GL_FRAMEBUFFER, boundFB); hizMip.bind();
            glBindTextureUnit(0, texture.id); glBindSampler(0, sampler);
            int src = 0, srcW = this.width, srcH = this.height;
            while (src < levels - 1) {
                int out = Math.min(LEVELS_PER_DISPATCH, levels - 1 - src);
                for (int i = 1; i <= LEVELS_PER_DISPATCH; i++)
                    glBindImageTexture(i, i <= out ? texture.id : 0, i <= out ? src + i : 0,
                            false, 0, GL_WRITE_ONLY, GL_R32F);
                glUniform1i(0, src); glUniform1i(1, out);
                glDispatchCompute((srcW + TILE - 1) / TILE, (srcH + TILE - 1) / TILE, 1);
                glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
                src += out; srcW = Math.max(srcW >> out, 1); srcH = Math.max(srcH >> out, 1);
            }
        } finally {
            for (int i = 1; i <= LEVELS_PER_DISPATCH; i++) glBindImageTexture(i, 0, 0, false, 0, GL_WRITE_ONLY, GL_R32F);
            glBindSampler(0, 0); glBindTextureUnit(0, 0); glBindFramebuffer(GL_FRAMEBUFFER, boundFB);
            glViewport(0, 0, width, height); glColorMask(maskR, maskG, maskB, maskA);
            if (blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
            // Match the draw-chain postconditions inherited by the traversal pass.
            glDisable(GL_DEPTH_TEST);
            glDepthFunc(this.properties.closerEqualDepthCompare());
            glDepthMask(true); glBindVertexArray(0);
        }
    }

    @Override public void free() { fb.free(); if (texture != null) texture.free(); glDeleteSamplers(sampler); hizInitial.free(); hizMip.free(); }
    @Override public int getHizTextureId() { return texture.id; }
    @Override public String describe() { return "compute"; }
    @Override public int getPackedLevels() { return (width << 16) | height; }
}
