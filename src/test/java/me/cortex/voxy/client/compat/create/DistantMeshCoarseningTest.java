package me.cortex.voxy.client.compat.create;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.nio.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static me.cortex.voxy.client.compat.create.DistantMeshFixtures.*;
class DistantMeshCoarseningTest {
    @Test void nonFiniteSeamUvsAndInterveningLayersCannotBeMergedAway() {
        var pair = transparentPair();
        // Both copies of the internal seam agree bitwise, but its UV is not affine.
        for (int vertex : new int[]{1, 2, 4, 7}) pair.putFloat(vertex * 32 + 12, Float.NaN);
        assertEquals(2, DistantQuadMerger.compact(pair, 2));
        pair = transparentPair();
        var separated = ByteBuffer.allocate(384).order(ByteOrder.nativeOrder());
        separated.put(pair.slice(0, 128)).put(pair.slice(0, 128)).put(pair.slice(128, 128)).flip();
        for (int v = 4; v < 8; v++) separated.putFloat(v * 32 + 8, .5f);
        byte[] original = separated.array().clone();
        assertEquals(3, DistantQuadMerger.compact(separated, 3));
        assertArrayEquals(original, separated.array(), "a later matching face cannot cross an intervening layer");
    }

    private static int checks;
    @Test void volumetricFixturesPreserveBoundsWindingAndMaterialWithinMemoryBudget() throws Exception {
        for (var fixture : fixtures()) verify(fixture);
        System.out.println("Coarsening attribute/bounds checks: " + checks);
    }
private static void verify(Fixture fixture) {
        var data = fixture.data();
        byte[] original = data.array().clone();
        int quads = data.remaining() / 128;
        long start = System.nanoTime();
        var lod = DistantMeshLod.build(data,quads);
        long millis = (System.nanoTime() - start) / 1000000;
        expect(lod.vertices != null && lod.grids.length > 0, fixture.name() + " needs real coarse meshes");
        expect(Arrays.equals(original,data.array()),"Original mesh changed");
        expect(lod.byteSize() < data.remaining(),"Extra coarse vertices must stay below original vertex bytes");
        float[] bounds = bounds(data);
        int previous = quads * 6;
        StringBuilder counts = new StringBuilder().append(quads * 2);
        for (int level = 0; level < lod.grids.length; level++) {
            int[] vertices = lod.vertices[level];
            int count = lod.indexCount(level);
            expect(count > 0 && count <= previous / 2,"Each coarse mesh must save at least half the triangles");
            previous = count;
            counts.append(" -> ").append(count / 3);
            for (int v = 0; v < vertices.length; v += 8) {
                for (int axis = 0; axis < 3; axis++) {
                    float p = Float.intBitsToFloat(vertices[v + axis]);
                    expect(Float.isFinite(p) && p >= bounds[axis] - 1e-5 && p <= bounds[axis + 3] + 1e-5,
                            "Coarse surface escaped original bounds");
                }
                expect(vertices[v + 7] == 73,"Material ID lost");
            }
            for (int q = 0; q < vertices.length; q += 32) {
                Vector3f a = position(vertices,q), b = position(vertices,q+8), c = position(vertices,q+16);
                var normal = b.sub(a).cross(c.sub(a));
                int face = vertices[q+5] >>> 24;
                float outward = switch (face) {
                    case 0 -> -normal.y; case 1 -> normal.y; case 2 -> -normal.z;
                    case 3 -> normal.z; case 4 -> -normal.x; default -> normal.x;
                };
                expect(outward > 0,"Collapsed or inward coarse face");
            }
        }
        var projection = new Matrix4f().perspective((float)Math.toRadians(70),16f/9,.1f,50000);
        var selection = new me.cortex.voxy.client.core.rendering.NativeLodSelection();
        selection.update(projection,projection,1920,1080,0,0,0,256,256);
        int level = selection.level(bounds[0],bounds[1],bounds[2]-512,bounds[3],bounds[4],bounds[5]-512);
        expect(level > 0 && (1 << level) >= lod.grids[0],fixture.name() + " must coarsen at 512 blocks, Standard quality");
        System.out.println(fixture.name() + ": triangles " + counts + "; bake " + millis + " ms; first grid " + lod.grids[0]);
    }
    @Test
void adjacentTransparentQuadsMergeOnlyWithIdenticalAffineAttributes() {
        var data = transparentPair();
        expect(DistantQuadMerger.compact(data,2)==1,"Coplanar transparent subdivision should merge");
        expect(data.getFloat(0)==0 && data.getFloat(32)==2,"Merged transparent extent");
        expect(data.getFloat(44)==1 && (data.get(27)&255)==96,"Transparent UV/alpha changed");
        var normal = new Vector3f(data.getFloat(32)-data.getFloat(0),0,0).cross(new Vector3f(0,1,0));
        expect(normal.z>0,"Transparent winding changed");
        for (int offset : new int[]{128+24,128+27,128+20,128+28}) {
            data = transparentPair();
            data.put(offset,(byte)(data.get(offset)+1));
            expect(DistantQuadMerger.compact(data,2)==2,"Different tint/alpha/light/material must not merge");
        }
        data = transparentPair();
        for (int v = 4; v < 8; v++) data.putFloat(v*32+8,.01f);
        expect(DistantQuadMerger.compact(data,2)==2,"Separate transparency layers must remain");
        data = transparentPair();
        data.putFloat(128+12,0);
        expect(DistantQuadMerger.compact(data,2)==2,"UV seam must remain");
    }
private static void expect(boolean condition,String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
