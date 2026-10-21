package me.cortex.voxy.client.compat.create;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static me.cortex.voxy.testing.Reflect.*;
import static me.cortex.voxy.client.compat.create.DistantMeshFixtures.*;

class DistantMeshBuilderTest {
    @Test void mixedBlockLayersKeepOriginalGeometry() throws Exception {
        var solid = net.minecraft.client.renderer.RenderType.solid();
        var cutout = net.minecraft.client.renderer.RenderType.cutout();
        var mipped = net.minecraft.client.renderer.RenderType.cutoutMipped();
        var translucent = net.minecraft.client.renderer.RenderType.translucent();
        for (var layer : new net.minecraft.client.renderer.RenderType[]{solid, cutout, mipped, translucent}) {
            var model = (net.minecraft.client.resources.model.BakedModel) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{net.minecraft.client.resources.model.BakedModel.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getRenderTypes" -> net.neoforged.neoforge.client.ChunkRenderTypeSet.of(layer);
                        case "getQuads" -> java.util.List.of();
                        default -> throw new AssertionError(method.getName());
                    });
            for (boolean explicitLayer : new boolean[]{false, true}) {
                var builder = new DistantMeshBuilder(true);
                DistantMeshBuilder.CpuMesh cpu = null;
                try {
                    var original = tiledPlane(64);
                    for (int v = 0; v < original.limit(); v += DistantMesh.STRIDE) {
                        original.put(v + 20, (byte) 248).put(v + 21, (byte) 248);
                    }
                    append(builder, original);
                    if (explicitLayer) builder.blockModelLayer(null, model, 0, 0, 0, 15, 0, layer,
                            quad -> 0xFFFFFF, net.neoforged.neoforge.client.model.data.ModelData.EMPTY);
                    else builder.blockModel(null, model, 0, 0, 0, 15, 0, null);
                    cpu = builder.assemble();
                    DistantMeshLod lod = get(cpu, "lod");
                    assertEquals(layer == translucent ? 0 : 3, lod.grids.length);
                    assertEquals(original, (ByteBuffer) get(cpu, "buffer"), "classification must not alter source vertices");
                } finally { builder.discard(); if (cpu != null) cpu.free(); }
            }
        }
    }

    @Test void alphaOrUnknownCaptureCanDisableLodAfterOpaqueVertices() throws Exception {
        for (boolean alpha : new boolean[]{false, true}) {
            var builder = new DistantMeshBuilder(true);
            DistantMeshBuilder.CpuMesh cpu = null;
            try {
                append(builder, tiledPlane(64));
                if (alpha) append(builder, transparentPair());
                else builder.preserveGeometry();
                cpu = builder.assemble();
                assertSame(DistantMeshLod.EMPTY, get(cpu, "lod"));
                assertEquals(alpha ? 4098 : 4096, cpu.quadCount);
                if (alpha) {
                    ByteBuffer data = get(cpu, "buffer");
                    assertEquals(96, Byte.toUnsignedInt(data.get(4096 * 128 + 27)));
                    assertEquals(73, data.getInt(4096 * 128 + 28));
                }
            } finally { builder.discard(); if (cpu != null) cpu.free(); }
        }
    }

    @Test void assemblyFailureReleasesTheBuildersNativeBuffer() throws Exception {
        var builder = new DistantMeshBuilder(true);
        try {
            append(builder, tiledPlane(2));
            // Force a failed assembly before ownership can move to a CpuMesh.
            set(builder, "vertexCount", Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class, builder::assemble);
            assertNull(get(builder, "buffer"));
            assertNull(builder.assemble());
        } finally { builder.discard(); }
    }

    @Test void lodIsExplicitAndCpuOwnershipSurvivesBuilderDiscard() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            var builder = enabled ? new DistantMeshBuilder(true) : new DistantMeshBuilder();
            DistantMeshBuilder.CpuMesh cpu = null;
            try {
                append(builder, tiledPlane(64));
                cpu = builder.assemble();
                assertNotNull(cpu);
                builder.discard();
                assertNull(builder.assemble(), "ownership was already transferred");
                assertEquals(4096, cpu.quadCount);
                DistantMeshLod lod = get(cpu, "lod");
                assertEquals(enabled ? 3 : 0, lod.grids.length);
                assertEquals(4096 * 128L + lod.byteSize(), cpu.byteSize());
                ByteBuffer source = get(cpu, "buffer");
                assertEquals(0, source.getFloat(0));
            } finally {
                builder.discard();
                if (cpu != null) {
                    cpu.free(); cpu.free();
                    assertEquals(0, cpu.byteSize());
                    assertNull(get(cpu, "buffer"));
                    assertNull(get(cpu, "lod"));
                }
            }
        }
    }

    @Test void translucentBuilderMergesOnlySafeFacesAndNeverBuildsCoarseLevels() throws Exception {
        var builder = DistantMeshBuilder.translucent();
        DistantMeshBuilder.CpuMesh cpu = null;
        try {
            append(builder, transparentPair());
            cpu = builder.assemble();
            assertEquals(1, cpu.quadCount);
            assertEquals(128, cpu.byteSize());
            assertSame(DistantMeshLod.EMPTY, get(cpu, "lod"));
            ByteBuffer vertices = get(cpu, "buffer");
            assertEquals(2, vertices.getFloat(32));
            assertEquals(96, Byte.toUnsignedInt(vertices.get(27)));
            assertEquals(73, vertices.getInt(28));
        } finally {
            builder.discard();
            if (cpu != null) cpu.free();
        }
    }

    static void append(DistantMeshBuilder builder, ByteBuffer vertices) {
        for (int i = 0; i < vertices.limit(); i += 32) {
            int tint = (Byte.toUnsignedInt(vertices.get(i + 24)) << 16)
                    | (Byte.toUnsignedInt(vertices.get(i + 25)) << 8) | Byte.toUnsignedInt(vertices.get(i + 26));
            builder.rawVertex(vertices.getFloat(i), vertices.getFloat(i + 4), vertices.getFloat(i + 8),
                    vertices.getFloat(i + 12), vertices.getFloat(i + 16),
                    Byte.toUnsignedInt(vertices.get(i + 21)) / 16, Byte.toUnsignedInt(vertices.get(i + 20)) / 16,
                    Byte.toUnsignedInt(vertices.get(i + 22)) / 255f, Byte.toUnsignedInt(vertices.get(i + 23)),
                    tint, Byte.toUnsignedInt(vertices.get(i + 27)), vertices.getInt(i + 28));
        }
    }
}
