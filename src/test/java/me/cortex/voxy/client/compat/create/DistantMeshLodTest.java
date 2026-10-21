package me.cortex.voxy.client.compat.create;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.nio.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static me.cortex.voxy.client.compat.create.DistantMeshFixtures.*;
class DistantMeshLodTest {
    private static int checks;
    @Test void planarLevelsPreserveAreaWindingSourceAndIndexBudget() {

        ByteBuffer vertices = tiledPlane(64);
        byte[] original = vertices.array().clone();
        var lod = DistantMeshLod.build(vertices, 4096);
        expect(lod.grids.length == 3, "密集平面应生成三个有效级别");
        expect(java.util.Arrays.equals(original, vertices.array()), "不能改写原始顶点和材质属性");
        expect(lod.byteSize() <= 4096 * 6L * 4, "附加索引内存上限");
        int previous = 4096 * 6;
        for (int level = 0; level < lod.grids.length; level++) {
            int[] indices = lod.indices[level];
            expect(indices.length <= previous / 2 && indices.length > 0, "保留级别至少减半且不为空");
            previous = indices.length;
            double area = 0;
            for (int t = 0; t < indices.length; t += 3) {
                int a = indices[t] * 32, b = indices[t + 1] * 32, c = indices[t + 2] * 32;
                float ax = snap(vertices.getFloat(a), lod.grids[level]), ay = snap(vertices.getFloat(a + 4), lod.grids[level]);
                float bx = snap(vertices.getFloat(b), lod.grids[level]), by = snap(vertices.getFloat(b + 4), lod.grids[level]);
                float cx = snap(vertices.getFloat(c), lod.grids[level]), cy = snap(vertices.getFloat(c + 4), lod.grids[level]);
                double triangle = ((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)) * 0.5;
                expect(triangle > 0, "保持绕序和有效三角形");
                area += triangle;
            }
            expect(Math.abs(area - 1) < 1e-6, "简化平面仍覆盖完整面积");
        }
        expect(DistantMeshLod.build(tiledPlane(4), 16) == DistantMeshLod.EMPTY, "简单模型不增加索引缓存");
        ByteBuffer invalid = tiledPlane(16);
        invalid.putFloat(0, Float.NaN);
        expect(DistantMeshLod.build(invalid, 256) == DistantMeshLod.EMPTY, "无效坐标保留原始路径");
        System.out.println("Plane triangles: 8192 -> " + lod.indexCount(0)/3 + " -> " + lod.indexCount(1)/3 + " -> " + lod.indexCount(2)/3);
    }
    @Test
void subpixelSelectionPreservesLongThinGeometryAndRestoresZoomedGeometry() {
        var projection = new Matrix4f().perspective((float) Math.toRadians(70), 16f/9, .1f, 50000);
        assertFalse(DistantMeshLod.isSubpixel(projection,-.001f,-10,-100,.001f,10,-99,1920,1080,1,0));
        assertTrue(DistantMeshLod.isSubpixel(projection,-.001f,-.001f,-1000,.001f,.001f,-999,1920,1080,1,0));
        var normal = new Matrix4f().perspective((float) Math.toRadians(70), 16f/9, .1f, 50000);
        var zoom = new Matrix4f().perspective((float) Math.toRadians(7), 16f/9, .1f, 50000);
        expect(DistantMeshLod.isSubpixel(normal,-.125f,-.125f,-1000,.125f,.125f,-999,1920,1080,.5,64),
                "缩小的透明小模型可剔除");
        expect(!DistantMeshLod.isSubpixel(zoom,-.125f,-.125f,-1000,.125f,.125f,-999,1920,1080,.5,64),
                "放大后透明小模型立即恢复，不依赖相机移动");
        expect(!DistantMeshLod.isSubpixel(normal,-.001f,-.001f,-1000,.001f,.001f,-999,0,0,.5,64),
                "窗口暂时无尺寸时保守保留");
    }
private static float snap(float value, float grid) { return DistantMeshLod.snap(value, grid); }
private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
