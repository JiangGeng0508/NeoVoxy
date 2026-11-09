package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.testing.GpuTestContext;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45C.*;

/** Runs the production secondary depth setup/composite against real driver depth and stencil tests. */
@EnabledIfSystemProperty(named = "voxy.verifyScreenspaceGpu", matches = "true")
class SecondaryViewportDepthGpuTest {
    @Test
    void vanillaOccludesLodAndSecondaryResizesPreserveMainDepth() {
        try (var context = GpuTestContext.open("SecondaryViewportDepthGpuTest")) {
            if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
                com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            }
            int program = sceneProgram();
            int vao = glCreateVertexArrays();
            try {
                for (boolean halfNdc : new boolean[]{true, false}) {
                    glClipControl(GL_LOWER_LEFT, halfNdc ? GL_NEGATIVE_ONE_TO_ONE : GL_ZERO_TO_ONE);
                    var properties = new RenderProperties(true, false, false);
                    var pipeline = new TestPipeline(properties);
                    var viewport = new TestViewport(properties);
                    try {
                        pipeline.fb.resize(80, 48);
                        pipeline.fb.clear(0.375f);
                        int mainDepth = pipeline.fb.getDepthTex().id;
                        for (int depthFormat : new int[]{GL_DEPTH_COMPONENT24, GL_DEPTH24_STENCIL8}) {
                            for (int width : new int[]{16, 32, 16}) {
                                verifyFrame(pipeline, viewport, program, vao, halfNdc, depthFormat, width);
                                assertEquals(mainDepth, pipeline.fb.getDepthTex().id);
                                assertEquals(80, pipeline.fb.getDepthTex().getWidth());
                                assertEquals(48, pipeline.fb.getDepthTex().getHeight());
                                float[] depth = new float[80 * 48];
                                glGetTextureImage(mainDepth, 0, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
                                for (float value : depth) assertEquals(0.375f, value, 1e-7f);
                                assertEquals(0, pipeline.debugSourceDepthTex(), "secondary passes must not replace main debug inputs");
                                assertEquals(GL_NO_ERROR, glGetError());
                            }
                        }
                    } finally {
                        viewport.delete();
                        pipeline.free();
                    }
                }
            } finally {
                glUseProgram(0);
                glDeleteProgram(program);
                glDeleteVertexArrays(vao);
            }
            secondaryEntryDrawsWaterOnlyAndCompositesOverSkyAndOpaqueLod();
            secondaryOpaqueMetadataDoesNotBlendInSky();
            secondaryStateRestoresAfterException();
        }
    }

    private void secondaryStateRestoresAfterException() {
        int texture = glCreateTextures(GL_TEXTURE_2D), buffer = glCreateBuffers(), vao = glCreateVertexArrays();
        int program = sceneProgram();
        try {
            glTextureStorage2D(texture, 1, GL_RGBA8, 4, 4);
            glNamedBufferStorage(buffer, 4096, GL_DYNAMIC_STORAGE_BIT);
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 2, buffer, 256, 512);
            glBindBufferRange(GL_UNIFORM_BUFFER, 3, buffer, 256, 512);
            glBindImageTexture(2, texture, 0, false, 0, GL_READ_ONLY, GL_RGBA8);
            glBindTextureUnit(4, texture);
            com.mojang.blaze3d.platform.GlStateManager._activeTexture(GL_TEXTURE4);
            glUseProgram(program);
            glBindVertexArray(vao);
            glViewport(3, 7, 21, 15);
            glProvokingVertex(GL_LAST_VERTEX_CONVENTION);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 24);
            glEnable(GL_SCISSOR_TEST);
            glScissor(2, 3, 4, 5);
            glEnablei(GL_BLEND, 1);
            glBlendFuncSeparatei(1, GL_ONE, GL_ZERO, GL_ZERO, GL_ONE);
            glColorMaski(1, false, true, false, true);
            var failure = assertThrows(IllegalStateException.class, () -> {
                try (var ignored = new SecondaryGlState()) {
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, 0);
                    glBindBufferBase(GL_UNIFORM_BUFFER, 3, 0);
                    glBindImageTexture(2, 0, 0, false, 0, GL_WRITE_ONLY, GL_R32F);
                    glBindTextureUnit(4, 0);
                    glUseProgram(0);
                    glBindVertexArray(0);
                    glViewport(0, 0, 1, 1);
                    glProvokingVertex(GL_FIRST_VERTEX_CONVENTION);
                    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
                    glDisable(GL_SCISSOR_TEST);
                    glScissor(0, 0, 1, 1);
                    glDisable(GL_BLEND);
                    glColorMask(true, true, true, true);
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                    throw new IllegalStateException("injected secondary render failure");
                }
            });
            assertEquals(0, failure.getSuppressed().length, java.util.Arrays.toString(failure.getSuppressed()));
            assertEquals(program, glGetInteger(GL_CURRENT_PROGRAM));
            assertEquals(GL_LAST_VERTEX_CONVENTION, glGetInteger(GL_PROVOKING_VERTEX));
            assertEquals(24, glGetInteger(GL_UNPACK_ROW_LENGTH));
            assertTrue(glIsEnabled(GL_SCISSOR_TEST));
            int[] scissor = new int[4];
            glGetIntegerv(GL_SCISSOR_BOX, scissor);
            assertArrayEquals(new int[]{2, 3, 4, 5}, scissor);
            assertEquals(vao, glGetInteger(GL_VERTEX_ARRAY_BINDING));
            assertEquals(GL_TEXTURE4, glGetInteger(GL_ACTIVE_TEXTURE));
            assertEquals(texture, glGetInteger(GL_TEXTURE_BINDING_2D));
            assertEquals(texture, glGetIntegeri(GL_IMAGE_BINDING_NAME, 2));
            assertEquals(GL_READ_ONLY, glGetIntegeri(GL_IMAGE_BINDING_ACCESS, 2));
            assertEquals(buffer, glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 2));
            assertEquals(256, glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, 2));
            assertEquals(512, glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, 2));
            assertEquals(256, glGetInteger64i(GL_UNIFORM_BUFFER_START, 3));
            assertEquals(512, glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, 3));
            assertTrue(glIsEnabledi(GL_BLEND, 1));
            assertEquals(GL_ONE, glGetIntegeri(GL_BLEND_SRC_RGB, 1));
            int[] mask = new int[4], dims = new int[4];
            glGetIntegeri_v(GL_COLOR_WRITEMASK, 1, mask);
            glGetIntegerv(GL_VIEWPORT, dims);
            assertArrayEquals(new int[]{0, 1, 0, 1}, mask);
            assertArrayEquals(new int[]{3, 7, 21, 15}, dims);
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            glUseProgram(0);
            glBindVertexArray(0);
            glDeleteProgram(program);
            glDeleteVertexArrays(vao);
            glDeleteBuffers(buffer);
            glDeleteTextures(texture);
            glDisable(GL_SCISSOR_TEST);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        }
    }

    private void secondaryEntryDrawsWaterOnlyAndCompositesOverSkyAndOpaqueLod() {
        {
            glClipControl(GL_LOWER_LEFT, GL_NEGATIVE_ONE_TO_ONE);
            var properties = new RenderProperties(true, false, false);
            int program = sceneProgram(), vao = glCreateVertexArrays();
            int source = glCreateFramebuffers(), colour = glCreateTextures(GL_TEXTURE_2D), depth = glCreateTextures(GL_TEXTURE_2D);
            var pipeline = new TestPipeline(properties);
            var viewport = new TestViewport(properties);
            try {
                int width = 16, height = 8;
                glTextureStorage2D(colour, 1, GL_RGBA8, width, height);
                glTextureStorage2D(depth, 1, GL_DEPTH_COMPONENT24, width, height);
                glNamedFramebufferTexture(source, GL_COLOR_ATTACHMENT0, colour, 0);
                glNamedFramebufferTexture(source, GL_DEPTH_ATTACHMENT, depth, 0);
                var vanilla = new Matrix4f().perspective(1.2f, 2, .05f, 256);
                var lod = new Matrix4f().perspective(1.2f, 2, 16, 32768, true);
                viewport.setScreenSize(width, height).setVanillaProjection(vanilla).setProjection(lod).update(256, 256);
                pipeline.fb.resize(64, 32);
                pipeline.fb.clear(.375f);
                int mainDepth = pipeline.fb.getDepthTex().id;
                for (boolean opaque : new boolean[]{false, true}) {
                    glBindFramebuffer(GL_FRAMEBUFFER, source);
                    glViewport(0, 0, width, height);
                    glDisable(GL_BLEND);
                    glDisable(GL_STENCIL_TEST);
                    glDisable(GL_SCISSOR_TEST);
                    glDisable(GL_CULL_FACE);
                    glEnable(GL_DEPTH_TEST);
                    glDepthFunc(GL_LEQUAL);
                    glDepthMask(true);
                    glColorMask(true, true, true, true);
                    glClearNamedFramebufferfv(source, GL_COLOR, 0, new float[]{0, 0, 1, 1});
                    glClearNamedFramebufferfv(source, GL_DEPTH, 0, new float[]{1});
                    draw(program, vao, projectDepth(vanilla, 4, true), 1, 1, 0, 0, 4, height);
                    pipeline.opaqueDraw = () -> {
                        if (opaque) draw(program, vao, projectDepth(lod, 192, true), 1, 0, 0, 4, 4, height);
                    };
                    pipeline.translucentDraw = () -> {
                        glEnable(GL_BLEND);
                        glBlendEquation(GL_FUNC_ADD);
                        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
                        // Two half-alpha layers: far red, near green. Pure sky remains visible behind them.
                        drawAlpha(program, vao, projectDepth(lod, 160, true), 1, 0, 0, .5f, 0, 12, height);
                        drawAlpha(program, vao, projectDepth(lod, 128, true), 0, 1, 0, .5f, 0, 12, height);
                        drawAlpha(program, vao, projectDepth(lod, 96, true), 1, 1, 1, 0, 12, 4, height);
                        glDisable(GL_BLEND);
                    };
                    pipeline.renderSecondary(viewport, source);
                    glBindFramebuffer(GL_READ_FRAMEBUFFER, source);
                    float[] result = readColour(width, height);
                    assertPixel(result, 1, 1, 1, 0);
                    assertArrayEquals(new float[]{opaque ? .5f : .25f, .5f, opaque ? 0 : .25f, 1},
                            java.util.Arrays.copyOfRange(result, 5 * 4, 5 * 4 + 4), .012f);
                    assertArrayEquals(new float[]{.25f, .5f, .25f, 1},
                            java.util.Arrays.copyOfRange(result, 9 * 4, 9 * 4 + 4), .012f);
                    assertPixel(result, 14, 0, 0, 1);
                    assertEquals(projectDepth(vanilla, 128, true), readDepth(width, height)[9], 2e-7f);
                    assertEquals(mainDepth, pipeline.fb.getDepthTex().id);
                    assertEquals(64, pipeline.fb.getDepthTex().getWidth());
                    float[] main = new float[64 * 32];
                    glGetTextureImage(mainDepth, 0, GL_DEPTH_COMPONENT, GL_FLOAT, main);
                    for (float value : main) assertEquals(.375f, value, 1e-7f);
                    assertEquals(GL_NO_ERROR, glGetError());
                }
                assertEquals(2, pipeline.translucentCalls, "the production secondary entry must submit translucent terrain");
            } finally {
                viewport.delete(); pipeline.free();
                glUseProgram(0); glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glDeleteFramebuffers(source); glDeleteTextures(colour); glDeleteTextures(depth);
                glDeleteProgram(program); glDeleteVertexArrays(vao);
            }
        }
    }

    private void secondaryOpaqueMetadataDoesNotBlendInSky() {
        glClipControl(GL_LOWER_LEFT, GL_NEGATIVE_ONE_TO_ONE);
        var properties = new RenderProperties(true, false, false);
        var pipeline = new TestPipeline(properties);
        var viewport = new TestViewport(properties);
        var opaqueShader = terrainColourProgram(properties, false);
        var waterShader = terrainColourProgram(properties, true);
        int vao = glCreateVertexArrays(), scene = glCreateBuffers();
        int source = glCreateFramebuffers(), colour = glCreateTextures(GL_TEXTURE_2D);
        int depth = glCreateTextures(GL_TEXTURE_2D), mask = glCreateTextures(GL_TEXTURE_2D);
        int atlas = glCreateTextures(GL_TEXTURE_2D), water = glCreateTextures(GL_TEXTURE_2D);
        try {
            int width = 20, height = 10;
            glTextureStorage2D(colour, 1, GL_RGBA8, width, height);
            glTextureStorage2D(depth, 1, GL_DEPTH_COMPONENT24, width, height);
            glTextureStorage2D(mask, 1, GL_R32F, width, height);
            glClearTexImage(mask, 0, GL_RED, GL_FLOAT, new float[]{0});
            glNamedFramebufferTexture(source, GL_COLOR_ATTACHMENT0, colour, 0);
            glNamedFramebufferTexture(source, GL_DEPTH_ATTACHMENT, depth, 0);
            for (int texture : new int[]{atlas, water}) {
                glTextureStorage2D(texture, 1, GL_RGBA8, 1, 1);
                glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            }
            glClearTexImage(atlas, 0, GL_RGBA, GL_FLOAT, new float[]{51/255f, 76/255f, 25/255f, 1});
            glClearTexImage(water, 0, GL_RGBA, GL_FLOAT, new float[]{0, 128/255f, 1, 128/255f});
            float[] uniforms = new float[256];
            glNamedBufferData(scene, uniforms, GL_DYNAMIC_DRAW);
            var vanilla = new Matrix4f().perspective(1.2f, 2, .05f, 256);
            var lod = new Matrix4f().perspective(1.2f, 2, 16, 32768, true);
            viewport.setScreenSize(width, height).setVanillaProjection(vanilla).setProjection(lod).update(256, 256);
            float opaqueDepth = projectDepth(lod, 192, true), waterDepth = projectDepth(lod, 128, true);
            // makeRemainingAttributes packs face | (lod << 3) | (hasAO << 6) into alpha.
            // Include zero: it is valid opaque metadata and must not discard a black/down face.
            for (int metadata : new int[]{0, 1, 5, 64, 65, 93, 101}) {
                glDisable(GL_CULL_FACE);
                glDisable(GL_SCISSOR_TEST);
                glBindFramebuffer(GL_FRAMEBUFFER, source);
                glColorMask(true, true, true, true);
                glDepthMask(true);
                glClearNamedFramebufferfv(source, GL_COLOR, 0, new float[]{1, 1, 1, 1});
                glClearNamedFramebufferfv(source, GL_DEPTH, 0, new float[]{1});
                // The primary path must retain its SSAO metadata, including alpha zero.
                uniforms[31] = 0; // SceneUniform byte 124; keep the existing UBO layout.
                glNamedBufferSubData(scene, 0, uniforms);
                pipeline.begin(viewport, source);
                glDisable(GL_BLEND);
                drawTerrainColour(opaqueShader, vao, scene, atlas, mask, opaqueDepth, metadata, false, 0, width, height);
                assertEquals(metadata / 255f, readColour(width, height)[3], 1e-6f,
                        "primary opaque alpha still carries SSAO metadata");

                uniforms[31] = 1;
                glNamedBufferSubData(scene, 0, uniforms);
                pipeline.opaqueDraw = () -> drawTerrainColour(opaqueShader, vao, scene, atlas, mask,
                        opaqueDepth, metadata, false, 0, 12, height);
                pipeline.translucentDraw = () -> {
                    glEnable(GL_BLEND);
                    glBlendEquation(GL_FUNC_ADD);
                    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
                    drawTerrainColour(waterShader, vao, scene, water, mask, waterDepth, 0, true, 8, 8, height);
                    glDisable(GL_BLEND);
                };
                pipeline.renderSecondary(viewport, source);
                glBindFramebuffer(GL_READ_FRAMEBUFFER, source);
                float[] result = readColour(width, height);
                float[] terrain = {51/255f, 76/255f, 25/255f};
                assertArrayEquals(new float[]{terrain[0], terrain[1], terrain[2], 1},
                        java.util.Arrays.copyOfRange(result, 5 * 4, 5 * 4 + 4), .006f,
                        "opaque terrain must not pick up white sky, metadata=" + metadata);
                float a = 128/255f;
                assertArrayEquals(new float[]{terrain[0]*(1-a), a*a + terrain[1]*(1-a), a + terrain[2]*(1-a), 1},
                        java.util.Arrays.copyOfRange(result, 9 * 4, 9 * 4 + 4), .012f,
                        "water over opaque LOD must not leak sky");
                assertArrayEquals(new float[]{1-a, a*a + 1-a, 1, 1},
                        java.util.Arrays.copyOfRange(result, 13 * 4, 13 * 4 + 4), .012f,
                        "water over sky must retain material alpha");
                assertPixel(result, 18, 1, 1, 1);
                assertEquals(GL_NO_ERROR, glGetError());
            }
        } finally {
            viewport.delete(); pipeline.free(); opaqueShader.free(); waterShader.free();
            glUseProgram(0); glBindFramebuffer(GL_FRAMEBUFFER, 0); glBindVertexArray(0);
            glDeleteFramebuffers(source); glDeleteBuffers(scene); glDeleteVertexArrays(vao);
            for (int texture : new int[]{colour, depth, mask, atlas, water}) glDeleteTextures(texture);
        }
    }

    private static Shader terrainColourProgram(RenderProperties properties, boolean translucent) {
        // Only geometry/packed vertex inputs are synthetic; colour, cutout and alpha use quads.frag.
        return Shader.make().apply(properties::apply).defineIf("TRANSLUCENT", translucent)
                .addSource(ShaderType.VERTEX, """
                        #version 460 core
                        layout(location=0) uniform float depth;
                        layout(location=1) uniform uint metadata;
                        layout(location=2) uniform uint lighting;
                        layout(location=0) out flat uvec4 interData;
                        layout(location=1) out vec2 uv;
                        layout(location=2) out float boundaryDistanceSquared;
                        void main() {
                            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                            gl_Position = vec4(p * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                            interData = uvec4(0u, lighting, 0u, metadata);
                            uv = vec2(0.5);
                            boundaryDistanceSquared = 0.0;
                        }
                        """)
                .add(ShaderType.FRAGMENT, "voxy:lod/gl46/quads.frag").compile();
    }

    private static void drawTerrainColour(Shader shader, int vao, int scene, int atlas, int mask,
                                          float depth, int metadata, boolean translucent, int x, int width, int height) {
        shader.bind();
        glBindVertexArray(vao);
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, scene);
        glBindTextureUnit(0, atlas); glBindSampler(0, 0);
        glBindTextureUnit(2, mask); glBindSampler(2, 0);
        glUniform1f(0, depth);
        glUniform1ui(1, metadata);
        glUniform1ui(2, translucent ? 0xFFFFFFFF : 0xFFFFFF00);
        glEnable(GL_SCISSOR_TEST);
        glScissor(x, 0, width, height);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glDisable(GL_SCISSOR_TEST);
    }

    private static void verifyFrame(TestPipeline pipeline, TestViewport viewport, int program, int vao,
                                    boolean halfNdc, int depthFormat, int width) {
        int height = width / 2;
        int source = glCreateFramebuffers();
        int colour = glCreateTextures(GL_TEXTURE_2D);
        int depth = glCreateTextures(GL_TEXTURE_2D);
        try {
            glTextureStorage2D(colour, 1, GL_RGBA8, width, height);
            glTextureStorage2D(depth, 1, depthFormat, width, height);
            glNamedFramebufferTexture(source, GL_COLOR_ATTACHMENT0, colour, 0);
            glNamedFramebufferTexture(source, depthFormat == GL_DEPTH24_STENCIL8
                    ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT, depth, 0);
            assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckNamedFramebufferStatus(source, GL_FRAMEBUFFER));

            float fov = (float) Math.toRadians(70);
            var vanilla = new Matrix4f().perspective(fov, 2, 0.05f, 256, !halfNdc);
            var lod = new Matrix4f().perspective(fov, 2, 16, 32768, true);
            viewport.setScreenSize(width, height).setVanillaProjection(vanilla).setProjection(lod).update(256, 256);
            float closeDepth = projectDepth(vanilla, 4, halfNdc);
            float normalDepth = projectDepth(vanilla, 64, halfNdc);
            float lodDepth = projectDepth(lod, 128, halfNdc);
            assertTrue(lodDepth < closeDepth, "raw LOD depth reproduces the old incorrect foreground ordering");

            glBindFramebuffer(GL_FRAMEBUFFER, source);
            glViewport(0, 0, width, height);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glDepthMask(true);
            glColorMask(true, true, true, true);
            glClearNamedFramebufferfv(source, GL_COLOR, 0, new float[]{0, 0, 1, 1});
            glClearNamedFramebufferfv(source, GL_DEPTH, 0, new float[]{1});
            draw(program, vao, closeDepth, 0, 1, 0, 0, width / 4, height);
            draw(program, vao, normalDepth, 0, 1, 0, width / 4, width / 4, height);
            float[] originalDepth = readDepth(width, height);
            float[] originalColour = readColour(width, height);

            int lodDepthTexture = pipeline.begin(viewport, source);
            assertNotEquals(source, glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
            float[] reprojected = readDepth(width, height);
            assertEquals(Math.max(0, projectDepth(lod, 4, halfNdc)), reprojected[0], 2e-6f);
            assertEquals(projectDepth(lod, 64, halfNdc), reprojected[width / 4], 2e-4f);
            assertEquals(1, reprojected[width / 2], 0);
            viewport.hiZBuffer.buildMipChain(lodDepthTexture, width, height);

            // Geometry stands in for an opaque LOD surface; the production target, stencil,
            // depth conversion and final shader are exercised without a running Minecraft world.
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            draw(program, vao, lodDepth, 1, 0, 0, 0, width * 3 / 4, height);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, source);
            assertArrayEquals(originalDepth, readDepth(width, height), 0, "setup/draw must not write source depth");
            assertArrayEquals(originalColour, readColour(width, height), 0, "setup/draw must not write source colour");
            pipeline.end(viewport, source);
            assertEquals(source, glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
            assertFalse(glIsEnabled(GL_STENCIL_TEST));
            float[] result = readColour(width, height);
            assertPixel(result, width / 8, 0, 1, 0);
            assertPixel(result, width * 3 / 8, 0, 1, 0);
            assertPixel(result, width * 5 / 8, 1, 0, 0);
            assertPixel(result, width * 7 / 8, 0, 0, 1);
            float[] resultDepth = readDepth(width, height);
            assertEquals(originalDepth[0], resultDepth[0], 0, "near chunks retain exact source depth");
            assertEquals(originalDepth[width / 4], resultDepth[width / 4], 0);
            assertEquals(projectDepth(vanilla, 128, halfNdc), resultDepth[width / 2], 2e-7f);
            assertEquals(1, resultDepth[width * 3 / 4], 0, "untouched sky stays clear");

            // Later vanilla geometry must test against the converted LOD depth, not its raw depth.
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            draw(program, vao, projectDepth(vanilla, 96, halfNdc), 1, 1, 0, width / 2, width / 4, height);
            assertPixel(readColour(width, height), width * 5 / 8, 1, 1, 0);

            // Reusing the target with no LOD draw must not replay its previous colour.
            float[] beforeEmpty = readColour(width, height);
            float[] depthBeforeEmpty = readDepth(width, height);
            pipeline.begin(viewport, source);
            pipeline.end(viewport, source);
            assertArrayEquals(beforeEmpty, readColour(width, height), 0);
            assertArrayEquals(depthBeforeEmpty, readDepth(width, height), 0);
        } finally {
            glDisable(GL_SCISSOR_TEST);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(source);
            glDeleteTextures(colour);
            glDeleteTextures(depth);
        }
    }

    private static float projectDepth(Matrix4f projection, float distance, boolean halfNdc) {
        var clip = projection.transform(new Vector4f(0, 0, -distance, 1));
        float ndc = clip.z / clip.w;
        return halfNdc ? ndc * 0.5f + 0.5f : ndc;
    }

    private static void draw(int program, int vao, float depth, float r, float g, float b,
                             int x, int width, int height) {
        drawAlpha(program, vao, depth, r, g, b, 1, x, width, height);
    }

    private static void drawAlpha(int program, int vao, float depth, float r, float g, float b, float alpha,
                                  int x, int width, int height) {
        glUseProgram(program);
        glBindVertexArray(vao);
        glUniform1f(0, depth);
        glUniform3f(1, r, g, b);
        glUniform1f(2, alpha);
        glEnable(GL_SCISSOR_TEST);
        glScissor(x, 0, width, height);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glDisable(GL_SCISSOR_TEST);
    }

    private static float[] readDepth(int width, int height) {
        float[] values = new float[width * height];
        glReadPixels(0, 0, width, height, GL_DEPTH_COMPONENT, GL_FLOAT, values);
        return values;
    }

    private static float[] readColour(int width, int height) {
        float[] values = new float[width * height * 4];
        glReadPixels(0, 0, width, height, GL_RGBA, GL_FLOAT, values);
        return values;
    }

    private static void assertPixel(float[] colour, int pixel, float r, float g, float b) {
        assertArrayEquals(new float[]{r, g, b, 1},
                java.util.Arrays.copyOfRange(colour, pixel * 4, pixel * 4 + 4), 1e-6f);
    }

    private static int sceneProgram() {
        int vertex = glCreateShader(GL_VERTEX_SHADER), fragment = glCreateShader(GL_FRAGMENT_SHADER);
        int program = glCreateProgram();
        try {
            glShaderSource(vertex, """
                    #version 450 core
                    void main() {
                        vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                        gl_Position = vec4(p * 2.0 - 1.0, 0.5, 1.0);
                    }
                    """);
            glShaderSource(fragment, """
                    #version 450 core
                    layout(location=0) uniform float depth;
                    layout(location=1) uniform vec3 rgb;
                    layout(location=2) uniform float alpha;
                    layout(location=0) out vec4 colour;
                    void main() { if (alpha == 0) discard; gl_FragDepth = depth; colour = vec4(rgb, alpha); }
                    """);
            for (int shader : new int[]{vertex, fragment}) {
                glCompileShader(shader);
                assertEquals(GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS), glGetShaderInfoLog(shader));
                glAttachShader(program, shader);
            }
            glLinkProgram(program);
            assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
            return program;
        } catch (Throwable error) {
            glDeleteProgram(program);
            throw error;
        } finally {
            glDeleteShader(vertex);
            glDeleteShader(fragment);
        }
    }

    private static final class TestViewport extends Viewport<TestViewport> {
        TestViewport(RenderProperties properties) { super(properties, false); }
        @Override public GlBuffer getRenderList() { return null; }
    }

    private static final class TestPipeline extends AbstractRenderPipeline {
        Runnable opaqueDraw = () -> {}, translucentDraw = () -> {};
        int translucentCalls;
        TestPipeline(RenderProperties properties) {
            super(properties, null, null, null, () -> false, false);
            this.setSectionRenderer(new AbstractSectionRenderer<TestViewport, IGeometryData>(properties, null, null) {
                @Override public void renderOpaque(TestViewport viewport) {
                    glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDepthMask(true);
                    opaqueDraw.run();
                }
                @Override public void buildDrawCalls(TestViewport viewport) {}
                @Override public void renderTemporal(TestViewport viewport) {}
                @Override public void renderTranslucent(TestViewport viewport) {
                    translucentCalls++; translucentDraw.run();
                }
                @Override public TestViewport createViewport() { throw new UnsupportedOperationException(); }
                @Override public void free() {}
            });
        }
        @Override protected void traverseSecondary(Viewport<?> viewport) {}
        int begin(Viewport<?> viewport, int source) { return this.setupSecondary(viewport, source); }
        void end(Viewport<?> viewport, int source) { this.finishSecondary(viewport, source); }
        @Override protected int setup(Viewport<?> viewport, int source, int width, int height) { throw new UnsupportedOperationException(); }
        @Override protected void postOpaquePreTranslucent(Viewport<?> viewport, int source) { throw new UnsupportedOperationException(); }
        @Override public void setupAndBindOpaque(Viewport<?> viewport) { this.bindSecondaryTarget(); }
        @Override public void setupAndBindTranslucent(Viewport<?> viewport) {}
        @Override public void free() { super.free0(); }
    }
}
