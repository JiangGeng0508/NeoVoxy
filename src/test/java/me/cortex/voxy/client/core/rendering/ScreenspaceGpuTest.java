package me.cortex.voxy.client.core.rendering;

import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import me.cortex.voxy.testing.GpuTestContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45C.*;

/** Executes the shipped projection/Hi-Z shader, including the local curvature expansion. */
@EnabledIfSystemProperty(named = "voxy.verifyScreenspaceGpu", matches = "true")
class ScreenspaceGpuTest {
    private static GpuTestContext context;
    private static int output, depth;
    private static final int[] programs = new int[8];
    private static final float MIN_AREA = 0.01f;

    @BeforeAll
    static void openContext() throws Exception {
        context = GpuTestContext.open("ScreenspaceGpuTest");
        output = glCreateBuffers();
        glNamedBufferData(output, 48, GL_STREAM_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, output);
        depth = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(depth, 1, GL_R32F, 1, 1);
        glBindTextureUnit(0, depth);
        for (int variant = 0; variant < programs.length; variant++) {
            programs[variant] = program(variant);
        }
    }

    @AfterAll
    static void closeContext() {
        if (context == null) return;
        for (int program : programs) if (program != 0) glDeleteProgram(program);
        glDeleteBuffers(output);
        glDeleteTextures(depth);
        context.close();
        context = null;
    }

    @Test
    void everyCornerCanTriggerConservativeBounds() {
        for (int variant = 0; variant < programs.length; variant++) {
            for (int corner = 0; corner < 8; corner++) {
                // Exactly one corner has w=-0.5; all other corners have positive w.
                var matrix = affine();
                matrix.m03((corner & 1) == 0 ? 1 : -1);
                matrix.m13((corner & 2) == 0 ? 1 : -1);
                matrix.m23((corner & 4) == 0 ? 1 : -1);
                matrix.m33(32 * Integer.bitCount(corner) - 0.5f);
                assertConservative(run(variant, matrix, 0, 0, 0, 0, 0, MIN_AREA, 0, false), MIN_AREA);
            }
        }
    }

    @Test
    void zeroNegativeAndTinyWNeverReachPerspectiveDivision() {
        for (int variant = 0; variant < programs.length; variant++) {
            for (float w : new float[]{-1, -0.0001f, 0, 0.00005f, 0.0001f}) {
                assertConservative(run(variant, affine().m33(w), 0, 0, 0, 0, 0, MIN_AREA, 0, false), MIN_AREA);
            }
            float[] justOutside = run(variant, affine().m33(0.0002f), 0, 0, 0, 0, 0, MIN_AREA, 0, false);
            assertNotEquals(MIN_AREA + 1, justOutside[3], "positive w above the threshold uses normal projection");
        }
    }

    @Test
    void curvatureIsAppliedBeforeTheWGuardAndStillExpandsOrdinaryBounds() {
        for (int variant = 0; variant < programs.length; variant++) {
            var tilted = affine().m13(1).m33(0.1f);
            float[] flat = run(variant, tilted, 1, 0, 0, 0, 0, MIN_AREA, 0, false);
            assertNotEquals(MIN_AREA + 1, flat[3]);
            // Curvature lowers the bottom corners by roughly half a block, making w negative.
            assertConservative(run(variant, tilted, 1, 0, 0, 0, 0, MIN_AREA, 1000, false), MIN_AREA);

            var ordinary = affine();
            flat = run(variant, ordinary, 1, 0, 0, 0, 0, MIN_AREA, 0, false);
            float[] curved = run(variant, ordinary, 1, 0, 0, 0, 0, MIN_AREA, 1000, false);
            assertTrue(curved[1] < flat[1], "the downward curvature expansion must remain in the screen bounds");
            assertEquals(flat[5], curved[5], 1e-6f, "top corners remain unchanged");
        }
    }

    @Test
    void validProjectionAndFrustumRejectionRetainTheirExistingBehavior() {
        for (int variant = 0; variant < programs.length; variant++) {
            float[] result = run(variant, affine(), 0, 0, 0, 0, 0, MIN_AREA, 0, false);
            float shiftX = (variant & 4) != 0 ? 0.002f : 0;
            float shiftY = (variant & 4) != 0 ? -0.003f : 0;
            assertEquals(0.5f + shiftX, result[0], 1e-6f);
            assertEquals(0.5f + shiftY, result[1], 1e-6f);
            assertEquals(0.66f + shiftX, result[4], 1e-6f);
            assertEquals(0.82f + shiftY, result[5], 1e-6f);
            assertEquals((variant & 2) != 0 ? 0.25f : 0.625f, result[2], 1e-6f);
            assertEquals((variant & 2) != 0 ? 0.41f : 0.705f, result[6], 1e-6f);
            assertEquals(0.0512f, result[3], 1e-6f);
            assertEquals(1, result[9], "an ordinary box behind a fully occluding Hi-Z texture still gets culled");

            result = run(variant, affine().m33(0), 0, 0, 0, 0, 0, MIN_AREA, 0, true);
            assertEquals(1, result[8], "an out-of-frustum box stays rejected before projection");
        }
    }

    @Test
    void realPerspectiveAcrossViewportSizesKeepsTheCurrentPassThreshold() {
        for (int variant = 0; variant < programs.length; variant++) {
            float[] first = null;
            for (int[] size : new int[][]{{1920, 1080}, {256, 128}, {1024, 1024}, {1920, 1080}}) {
                float threshold = 256f * 256 / (size[0] * size[1]);
                var matrix = new Matrix4f().perspective((float) Math.toRadians(70), (float) size[0] / size[1], 0.1f, 50000);
                // The box spans z=-16..16, intersecting the camera plane.
                float[] result = run(variant, matrix, 0, 0, -1, 0, -16, threshold, 0, false);
                assertConservative(result, threshold);
                if (size[0] == 1920) {
                    if (first == null) first = result;
                    else assertArrayEquals(first, result, 0, "switching viewport inputs must not leave stale bounds");
                }
            }
        }
    }

    private static Matrix4f affine() {
        return new Matrix4f().zero().m00(0.01f).m11(0.02f).m22(0.005f).m32(0.25f).m33(1);
    }

    private static float[] run(int variant, Matrix4f mvp, int x, int y, int z, int lod,
                               float cameraZ, float threshold, float radius, boolean reject) {
        glUseProgram(programs[variant]);
        glUniformMatrix4fv(0, false, mvp.get(new float[16]));
        glUniform1f(4, threshold);
        glUniform1f(5, 1 / mvp.m00());
        glUniform1f(6, 1 / mvp.m11());
        glUniform1f(7, 1);
        glUniform1f(8, radius);
        glUniform4i(9, x, y, z, lod);
        glUniform3f(10, 0, 0, cameraZ);
        glUniform1i(11, reject ? 1 : 0);
        // Force occlusion so conservative cases must bypass Hi-Z rather than pass by accident.
        glTextureSubImage2D(depth, 0, 0, 0, 1, 1, GL_RED, GL_FLOAT, new float[]{(variant & 1) != 0 ? 1 : 0});
        glDispatchCompute(1, 1, 1);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        float[] result = new float[12];
        glGetNamedBufferSubData(output, 0, result);
        for (float value : result) assertTrue(Float.isFinite(value), "projection output must be finite");
        assertEquals(GL_NO_ERROR, glGetError());
        return result;
    }

    private static void assertConservative(float[] result, float threshold) {
        assertArrayEquals(new float[]{0, 0, 0, threshold + 1, 1, 1, 1, 1, 0, 0, 0, 0}, result, 1e-6f);
    }

    private static int program(int variant) throws IOException {
        String defines = ((variant & 1) != 0 ? "#define USE_REVERSE_Z\n" : "")
                + ((variant & 2) != 0 ? "#define USE_ZERO_ONE_DEPTH\n" : "")
                + ((variant & 4) != 0 ? "#define TAA\n" : "");
        String source = "#version 460 core\n" + defines + """
                #define NODE_DATA_BINDING 1
                #define HIZ_BINDING 0
                layout(local_size_x=1) in;
                layout(location=0) uniform mat4 MVP;
                layout(location=4) uniform float minSSS;
                layout(location=5) uniform float invP00;
                layout(location=6) uniform float invP11;
                layout(location=7) uniform float stretchMax;
                layout(location=8) uniform float uEarthRadius;
                layout(location=9) uniform ivec4 nodeInput;
                layout(location=10) uniform vec3 camSubSecPos;
                layout(location=11) uniform bool rejectFrustum;
                ivec3 camSecPos = ivec3(0);
                uint packedHizSize = 65537u;
                float uVanillaEnd = 0;
                layout(binding=0,std430) buffer Results { vec4 boundsMin; vec4 boundsMax; vec4 decisions; };
                """ + read("lod/frustum.glsl") + "\nFrustum frustum;\n"
                + read("lod/hierarchical/node.glsl") + read("lod/hierarchical/screenspace.glsl") + """
                #ifdef TAA
                vec2 getTAA() { return vec2(0.004, -0.006); }
                #endif
                void main() {
                    for (int i=0;i<6;i++) frustum.planes[i]=vec4(0,0,0,rejectFrustum ? -1 : 1);
                    UnpackedNode node;
                    node.pos=nodeInput.xyz;
                    node.lodLevel=uint(nodeInput.w);
                    setupScreenspace(node);
                    boundsMin=vec4(_minBB,_screenSize);
                    boundsMax=vec4(_maxBB,float(shouldDecend()));
                    decisions=vec4(float(outsideFrustum()),float(!outsideFrustum() && isCulledByHiz()),0,0);
                }
                """;
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        int program = glCreateProgram();
        try {
            // Only remove the unused node debug print; projection and depth code are loaded unchanged.
            glShaderSource(shader, source.replaceAll("printf\\([^\\n]*\\);", ""));
            glCompileShader(shader);
            assertEquals(GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS), glGetShaderInfoLog(shader));
            glAttachShader(program, shader);
            glLinkProgram(program);
            assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
            return program;
        } catch (Throwable error) {
            glDeleteProgram(program);
            throw error;
        } finally {
            glDeleteShader(shader);
        }
    }

    private static String read(String path) throws IOException {
        String source;
        try (var stream = ScreenspaceGpuTest.class.getResourceAsStream("/assets/voxy/shaders/" + path)) {
            if (stream == null) throw new IOException("Missing shader " + path);
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var matcher = Pattern.compile("#import <voxy:([^>]+)>").matcher(source);
        var expanded = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            expanded.append(source, end, matcher.start()).append(read(matcher.group(1)));
            end = matcher.end();
        }
        return expanded.append(source, end, source.length()).toString();
    }
}
