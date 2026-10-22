package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
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
        }
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
        glUseProgram(program);
        glBindVertexArray(vao);
        glUniform1f(0, depth);
        glUniform3f(1, r, g, b);
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
                    layout(location=0) out vec4 colour;
                    void main() { gl_FragDepth = depth; colour = vec4(rgb, 1); }
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
        TestPipeline(RenderProperties properties) {
            super(properties, null, null, null, () -> false, false);
            this.setSectionRenderer(new AbstractSectionRenderer<TestViewport, IGeometryData>(properties, null, null) {
                @Override public void renderOpaque(TestViewport viewport) {}
                @Override public void buildDrawCalls(TestViewport viewport) {}
                @Override public void renderTemporal(TestViewport viewport) {}
                @Override public void renderTranslucent(TestViewport viewport) {}
                @Override public TestViewport createViewport() { throw new UnsupportedOperationException(); }
                @Override public void free() {}
            });
        }
        int begin(Viewport<?> viewport, int source) { return this.setupSecondary(viewport, source); }
        void end(Viewport<?> viewport, int source) { this.finishSecondary(viewport, source); }
        @Override protected int setup(Viewport<?> viewport, int source, int width, int height) { throw new UnsupportedOperationException(); }
        @Override protected void postOpaquePreTranslucent(Viewport<?> viewport, int source) { throw new UnsupportedOperationException(); }
        @Override public void setupAndBindOpaque(Viewport<?> viewport) {}
        @Override public void setupAndBindTranslucent(Viewport<?> viewport) {}
        @Override public void free() { super.free0(); }
    }
}
