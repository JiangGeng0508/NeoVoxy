package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.client.compat.powergrid.PowerGridWireRenderer;
import me.cortex.voxy.testing.Reflect;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class DistantPolylineLodTest {
    static Vec3[] curve() {
        var points = new Vec3[65];
        for (int i = 0; i < points.length; i++) points[i] = new Vec3(0, (i - 32) * (i - 32) / 256.0, i);
        return points;
    }

    // Use PowerGrid's actual four-side emitter, including its changing local frame,
    // light, shade, face and color encoding, without requiring the optional mod.
    static DistantMeshBuilder builder(Vec3[] points) throws Exception {
        var result = new DistantMeshBuilder(false);
        try {
            var emit = PowerGridWireRenderer.class.getDeclaredMethod("emitPrism", DistantMeshBuilder.class,
                    Vec3.class, Vec3.class, float.class, float.class, float.class, int.class, int.class, int.class);
            emit.setAccessible(true);
            for (int i = 1; i < points.length; i++) {
                emit.invoke(null, result, points[i - 1], points[i], .05f, .375f, .625f, i % 16, 7, 0x123456);
            }
            return result;
        } catch (Exception | Error failure) {
            result.discard();
            throw failure;
        }
    }

    static ByteBuffer wireFixture() throws Exception {
        var builder = builder(curve());
        var cpu = builder.assemble();
        try {
            ByteBuffer source = Reflect.get(cpu, "buffer");
            return ByteBuffer.allocate(source.remaining()).order(ByteOrder.nativeOrder()).put(source.duplicate()).flip();
        } finally { cpu.free(); builder.discard(); }
    }

    @Test void curvedWireKeepsCrossSectionsContinuityAndBoundedError() throws Exception {
        verify(curve(), true);
    }

    @Test void straightVerticalAndSharpTurningWiresKeepTheirEndpoints() throws Exception {
        var straight = new Vec3[129];
        var vertical = new Vec3[129];
        var zigzag = new Vec3[65];
        for (int i = 0; i < straight.length; i++) {
            straight[i] = new Vec3(-64 + i, 0, 0);
            vertical[i] = new Vec3(0, -64 + i, 0);
        }
        for (int i = 0; i < zigzag.length; i++) zigzag[i] = new Vec3((i % 4 < 2 ? 1 : -1) * 3, i % 2, i);
        verify(straight, true); verify(vertical, true); verify(zigzag, true);
    }

    @Test void emptySingleSegmentDegenerateAndNonfiniteInputsKeepOriginalGeometry() throws Exception {
        var empty = new DistantMeshBuilder(true);
        assertNull(empty.assemblePolyline());
        empty.discard();
        var one = builder(new Vec3[]{Vec3.ZERO, new Vec3(0, 0, 1)});
        var cpu = one.assemblePolyline();
        try { assertSame(DistantMeshLod.EMPTY, Reflect.get(cpu, "lod")); }
        finally { cpu.free(); one.discard(); }
        assertSame(DistantMeshLod.EMPTY, DistantPolylineLod.build(ByteBuffer.allocate(1024), 8));
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            var source = wireFixture();
            source.putFloat(5 * 32 + 4, invalid); // A corner not used for arc-length measurement.
            assertSame(DistantMeshLod.EMPTY, DistantPolylineLod.build(source, source.remaining() / 128));
        }
        var source = wireFixture();
        assertSame(DistantMeshLod.EMPTY, DistantPolylineLod.build(source, 9));
    }

    @Test void polylineAssemblyOwnsOriginalAndCoarseMemoryAndDoesNotRunVoxelSimplification() throws Exception {
        var builder = builder(curve());
        var cpu = builder.assemblePolyline();
        try {
            DistantMeshLod lod = Reflect.get(cpu, "lod");
            ByteBuffer source = Reflect.get(cpu, "buffer");
            assertTrue(lod.grids.length > 0);
            assertEquals(source.remaining() + lod.byteSize(), cpu.byteSize());
            builder.discard(); // Ownership has transferred.
            assertEquals(256, cpu.quadCount);
            assertEquals(-.025f, cpu.minX, 1e-6);
            assertEquals(.025f, cpu.maxX, 1e-6);
            assertNotNull(Reflect.get(cpu, "buffer"));
        } finally { cpu.free(); cpu.free(); builder.discard(); }
        assertEquals(0, cpu.byteSize());
    }

    private static void verify(Vec3[] points, boolean reduced) throws Exception {
        var builder = builder(points);
        var cpu = builder.assemble();
        try {
            ByteBuffer source = Reflect.get(cpu, "buffer");
            byte[] before = new byte[source.remaining()]; source.duplicate().get(before);
            var lod = DistantPolylineLod.build(source, cpu.quadCount);
            if (reduced) assertTrue(lod.grids.length > 0);
            byte[] after = new byte[source.remaining()]; source.duplicate().get(after);
            assertArrayEquals(before, after);
            assertTrue(lod.byteSize() < source.remaining());
            int previous = points.length - 1;
            for (int level = 0; level < lod.grids.length; level++) {
                var data = lod.vertices[level];
                int count = data.length / 128;
                assertTrue(count > 0 && count <= previous / 2);
                previous = count;
                int first = 0;
                for (int segment = 0; segment < count; segment++) {
                    Vec3 a = center(data, segment, false), b = center(data, segment, true);
                    assertTrue(a.distanceTo(points[first]) < 1e-5, "no omitted segment or centerline gap");
                    int last = first + 1;
                    while (last < points.length && b.distanceTo(points[last]) > 1e-5) last++;
                    assertTrue(last < points.length, "end section must come from the source");
                    for (int face = 0; face < 4; face++) for (int corner = 0; corner < 4; corner++) {
                        int sourceSegment = corner < 2 ? first : last - 1;
                        int original = ((sourceSegment * 4 + face) * 4 + corner) * 32;
                        int target = segment * 128 + face * 32 + corner * 8;
                        for (int word = 0; word < 8; word++) assertEquals(source.getInt(original + word * 4), data[target + word],
                                "endpoint geometry and all vertex attributes are exact");
                    }
                    // Independent point-to-chord check over the original centerline.
                    Vec3 chord = b.subtract(a);
                    for (int i = first; i <= last; i++) {
                        double t = Math.clamp(points[i].subtract(a).dot(chord) / chord.lengthSqr(), 0, 1);
                        assertTrue(points[i].distanceTo(a.add(chord.scale(t))) <= Math.sqrt(3) * lod.grids[level] + .05,
                                "coarse wire exceeds its positional error budget");
                    }
                    first = last;
                }
                assertEquals(points.length - 1, first, "covers the complete wire");
            }
            System.out.println("Wire triangles: " + cpu.quadCount * 2 + " -> "
                    + Arrays.toString(java.util.stream.IntStream.range(0, lod.grids.length).map(i -> lod.indexCount(i) / 3).toArray()));
        } finally { cpu.free(); builder.discard(); }
    }

    private static Vec3 center(int[] data, int segment, boolean end) {
        double x = 0, y = 0, z = 0;
        for (int face = 0; face < 4; face++) {
            int offset = segment * 128 + face * 32 + (end ? 24 : 0);
            x += Float.intBitsToFloat(data[offset]); y += Float.intBitsToFloat(data[offset + 1]); z += Float.intBitsToFloat(data[offset + 2]);
        }
        return new Vec3(x / 4, y / 4, z / 4);
    }
}
