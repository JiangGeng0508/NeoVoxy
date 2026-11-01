package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.NativeLodSelection;
import org.joml.Matrix4fc;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11C.glDrawElements;
import static org.lwjgl.opengl.GL15C.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL20C.glUniform3f;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL45C.glCreateBuffers;
import static org.lwjgl.opengl.GL45C.glCreateVertexArrays;
import static org.lwjgl.opengl.GL45C.glEnableVertexArrayAttrib;
import static org.lwjgl.opengl.GL45C.glNamedBufferData;
import static org.lwjgl.opengl.GL45C.glNamedBufferSubData;
import static org.lwjgl.opengl.GL45C.glVertexArrayAttribBinding;
import static org.lwjgl.opengl.GL45C.glVertexArrayAttribFormat;
import static org.lwjgl.opengl.GL45C.glVertexArrayAttribIFormat;
import static org.lwjgl.opengl.GL45C.glVertexArrayElementBuffer;
import static org.lwjgl.opengl.GL45C.glVertexArrayVertexBuffer;

public final class DistantMesh {
    public static final int STRIDE = 32;

    // Render-thread counters across all dedicated-mesh passes/views in renderLevel.
    // Terrain and native Sable geometry do not use DistantMesh and are not counted.
    private static long submittedTriangles, fullDetailTriangles, depthReplayTriangles;
    private static int coarseDraws, detailedDraws, culledDraws;

    public record FrameStatistics(long submittedTriangles, long fullDetailTriangles,
                                  int coarseDraws, int detailedDraws, int culledDraws,
                                  long depthReplayTriangles) {}

    public static void beginFrame() {
        submittedTriangles = fullDetailTriangles = depthReplayTriangles = 0;
        coarseDraws = detailedDraws = culledDraws = 0;
    }

    public static FrameStatistics frameStatistics() {
        return new FrameStatistics(submittedTriangles, fullDetailTriangles, coarseDraws,
                detailedDraws, culledDraws, depthReplayTriangles);
    }

    public static void addDebug(java.util.List<String> lines) {
        lines.add("Distant mesh triangles submitted/full: " + submittedTriangles + "/" + fullDetailTriangles
                + " (all passes, depth replay " + depthReplayTriangles + ")");
        lines.add("Distant mesh draws coarse/full/culled: " + coarseDraws + "/" + detailedDraws + "/" + culledDraws);
    }

    private static int sharedIndexBuffer;
    private static int sharedIndexQuadCapacity;

    private int vao;
    private int vbo;
    private final int quadCount;
    private int lodBuffer;
    private int coarseVbo;
    private int privateIndexBuffer;
    private long privateIndexBytes;
    private boolean freed;
    private final float[] grids;
    private final int[] lodCounts;
    private final long[] lodOffsets;
    private final long lodBytes;
    private int boundElementBuffer;
    private int boundLevel;
    private int lastDrawLevel;
    private float lastDrawBlend;
    private NativeLodSelection cachedSelection;
    private int cachedRevision, cachedLevel;
    private double cachedX, cachedY, cachedZ;

    public int lastDrawLevel() { return this.lastDrawLevel; }
    public float lastDrawBlend() { return this.lastDrawBlend; }

    public int quadCount() {
        return this.quadCount;
    }

    //Vertex bytes held on the GPU. The shared index buffer is not counted - it is one allocation for
    //every mesh in the game, so charging it per mesh would say the wrong thing about what a mesh costs.
    public long gpuByteSize() {
        return (long) this.quadCount * 4L * STRIDE + this.lodBytes + this.privateIndexBytes;
    }

    //Mesh-local extent, so a draw can frustum-test without the caller having to know what went in
    public float minX, minY, minZ, maxX, maxY, maxZ;

    DistantMesh(ByteBuffer vertexData, int quadCount, DistantMeshLod lod) {
        this(vertexData, quadCount, lod, false);
    }

    private DistantMesh(ByteBuffer vertexData, int quadCount, DistantMeshLod lod, boolean staged) {
        this.quadCount = quadCount;
        this.grids = lod.grids;
        this.lodBytes = lod.byteSize();
        this.lodCounts = new int[lod.grids.length];
        this.lodOffsets = new long[lod.grids.length];
        try {
            if (!staged) this.upload(vertexData, lod);
        } catch (RuntimeException | Error failure) {
            this.free();
            throw failure;
        }
    }

    private void upload(ByteBuffer vertexData, DistantMeshLod lod) {
        this.lodBuffer = this.lodBytes == 0 || lod.vertices != null ? 0 : glCreateBuffers();
        this.coarseVbo = lod.vertices == null ? 0 : glCreateBuffers();
        if (this.lodBytes != 0) {
            int buffer = this.coarseVbo != 0 ? this.coarseVbo : this.lodBuffer;
            glNamedBufferData(buffer, this.lodBytes, GL_STATIC_DRAW);
            long offset = 0;
            for (int i = 0; i < lod.grids.length; i++) {
                int[] data = lod.vertices == null ? lod.indices[i] : lod.vertices[i];
                this.lodCounts[i] = lod.indexCount(i);
                this.lodOffsets[i] = offset;
                glNamedBufferSubData(buffer, offset, data);
                offset += data.length * 4L;
            }
        }
        ensureIndexCapacity(quadCount);

        this.vbo = glCreateBuffers();
        glNamedBufferData(this.vbo, vertexData, GL_STATIC_DRAW);

        this.setupVao();
    }

    private void setupVao() {
        this.vao = glCreateVertexArrays();
        glVertexArrayVertexBuffer(this.vao, 0, this.vbo, 0, STRIDE);
        this.boundElementBuffer = this.privateIndexBuffer != 0 ? this.privateIndexBuffer : sharedIndexBuffer;
        glVertexArrayElementBuffer(this.vao, this.boundElementBuffer);

        glEnableVertexArrayAttrib(this.vao, 0);
        glVertexArrayAttribFormat(this.vao, 0, 3, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(this.vao, 0, 0);

        glEnableVertexArrayAttrib(this.vao, 1);
        glVertexArrayAttribFormat(this.vao, 1, 2, GL_FLOAT, false, 12);
        glVertexArrayAttribBinding(this.vao, 1, 0);

        glEnableVertexArrayAttrib(this.vao, 2);
        glVertexArrayAttribFormat(this.vao, 2, 2, GL_UNSIGNED_BYTE, true, 20);
        glVertexArrayAttribBinding(this.vao, 2, 0);

        glEnableVertexArrayAttrib(this.vao, 3);
        glVertexArrayAttribFormat(this.vao, 3, 1, GL_UNSIGNED_BYTE, true, 22);
        glVertexArrayAttribBinding(this.vao, 3, 0);

        glEnableVertexArrayAttrib(this.vao, 4);
        glVertexArrayAttribIFormat(this.vao, 4, 1, GL_UNSIGNED_BYTE, 23);
        glVertexArrayAttribBinding(this.vao, 4, 0);

        glEnableVertexArrayAttrib(this.vao, 5);
        glVertexArrayAttribFormat(this.vao, 5, 4, GL_UNSIGNED_BYTE, true, 24);
        glVertexArrayAttribBinding(this.vao, 5, 0);

        glEnableVertexArrayAttrib(this.vao, 6);
        glVertexArrayAttribIFormat(this.vao, 6, 1, GL_UNSIGNED_INT, 28);
        glVertexArrayAttribBinding(this.vao, 6, 0);
    }

    //Grows the shared quad->triangles index buffer (0,1,2, 2,3,0 per quad)
    private static void ensureIndexCapacity(int quads) {
        if (quads <= sharedIndexQuadCapacity) {
            return;
        }
        int capacity = Math.max(quads, Math.max(sharedIndexQuadCapacity * 2, 4096));
        int[] indices = new int[capacity * 6];
        for (int q = 0; q < capacity; q++) {
            int base = q * 4, i = q * 6;
            indices[i] = base;
            indices[i + 1] = base + 1;
            indices[i + 2] = base + 2;
            indices[i + 3] = base + 2;
            indices[i + 4] = base + 3;
            indices[i + 5] = base;
        }
        // 保持对象名，已有 VAO 自动看到扩容后的缓冲，避免滞留多份旧索引。
        if (sharedIndexBuffer == 0) sharedIndexBuffer = glCreateBuffers();
        glNamedBufferData(sharedIndexBuffer, indices, GL_STATIC_DRAW);
        sharedIndexQuadCapacity = capacity;
    }

    //Caller is responsible for program, uniforms, textures and depth state
    public void draw() {
        this.draw(0, 0);
    }

    public void drawAt(Viewport<?> viewport, double x, double y, double z) {
        this.drawAt(viewport.lodSelection, x, y, z);
    }

    void drawAt(NativeLodSelection selection, double x, double y, double z) {
        if (this.grids.length == 0) { this.draw(); return; }
        if (this.cachedSelection != selection || this.cachedRevision != selection.revision()
                || this.cachedX != x || this.cachedY != y || this.cachedZ != z) {
            int terrainLevel = selection.level(x + this.minX, y + this.minY, z + this.minZ,
                    x + this.maxX, y + this.maxY, z + this.maxZ);
            this.cachedLevel = this.selectLevel(terrainLevel == 0 ? 0 : 1 << terrainLevel);
            this.cachedSelection = selection;
            this.cachedRevision = selection.revision();
            this.cachedX = x; this.cachedY = y; this.cachedZ = z;
        }
        this.draw(this.cachedLevel, 0);
    }

    public void drawModel(Viewport<?> viewport, Matrix4fc model, double x, double y, double z) {
        this.drawModel(viewport.lodSelection, model, x, y, z);
    }

    void drawModel(NativeLodSelection selection, Matrix4fc model, double x, double y, double z) {
        float grid = this.grids.length == 0 ? 0 : selection.gridSize(model, x, y, z,
                this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ);
        this.draw(this.selectLevel(grid), 0);
    }

    private int selectLevel(float grid) {
        int level = 0;
        // 原生零级保留专属模型细节；缺少对应粗级时选更细的已有网格。
        while (level < this.grids.length && grid >= this.grids[level]) level++;
        return level;
    }

    public void drawTranslucent(Viewport<?> viewport, Matrix4fc transform) {
        this.drawTranslucent(viewport.lodSelection, transform, viewport.width, viewport.height);
    }

    void drawTranslucent(NativeLodSelection selection, Matrix4fc transform, int width, int height) {
        // 半透明层保留原有面数与顺序，只跳过整体小于一个像素的网格。
        if (!this.skipSubpixel(selection, transform, width, height)) this.draw();
    }

    public void drawThin(Viewport<?> viewport, Matrix4fc transform, double x, double y, double z) {
        if (!this.skipSubpixel(viewport.lodSelection, transform, viewport.width, viewport.height)) this.drawAt(viewport, x, y, z);
    }

    private boolean skipSubpixel(NativeLodSelection selection, Matrix4fc transform, int width, int height) {
        boolean skip = DistantMeshLod.isSubpixel(transform, this.minX, this.minY, this.minZ,
                this.maxX, this.maxY, this.maxZ, width, height,
                selection.visibilityPixels, selection.fullDetailDistance);
        if (skip) {
            fullDetailTriangles += this.quadCount * 2L;
            culledDraws++;
        }
        return skip;
    }

    /** 深度回放必须复用颜色阶段的级别和变形进度。 */
    public void draw(int level, float blend) {
        this.draw(level, blend, false);
    }

    public void drawDepth(int level, float blend) {
        this.draw(level, blend, true);
    }

    private void draw(int level, float blend, boolean depthReplay) {
        if (this.freed) throw new IllegalStateException("Mesh has been freed");
        if (level < 0 || level > this.grids.length || !Float.isFinite(blend) || blend < 0 || blend > 1) {
            throw new IllegalArgumentException("Invalid mesh LOD level or blend");
        }
        if (this.coarseVbo != 0) blend = 0;
        this.lastDrawLevel = level;
        this.lastDrawBlend = blend;
        float grid = level == 0 ? 0 : this.grids[level - 1];
        float next = level < this.grids.length ? this.grids[level] : grid;
        // 粗外壳已在烘焙时成形，颜色与深度回写必须绑定同一份顶点。
        glUniform3f(6, this.coarseVbo == 0 ? grid : 0, this.coarseVbo == 0 ? next : 0, blend);
        if (this.coarseVbo != 0 && this.boundLevel != level) {
            glVertexArrayVertexBuffer(this.vao, 0, level == 0 ? this.vbo : this.coarseVbo,
                    level == 0 ? 0 : this.lodOffsets[level - 1], STRIDE);
            this.boundLevel = level;
        }
        int elementBuffer = level == 0 || this.coarseVbo != 0
                ? (this.privateIndexBuffer != 0 ? this.privateIndexBuffer : sharedIndexBuffer) : this.lodBuffer;
        if (this.boundElementBuffer != elementBuffer) {
            glVertexArrayElementBuffer(this.vao, elementBuffer);
            this.boundElementBuffer = elementBuffer;
        }
        int count = level == 0 ? this.quadCount * 6 : this.lodCounts[level - 1];
        glBindVertexArray(this.vao);
        glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_INT, level == 0 || this.coarseVbo != 0 ? 0 : this.lodOffsets[level - 1]);
        submittedTriangles += count / 3;
        fullDetailTriangles += this.quadCount * 2L;
        if (level == 0) detailedDraws++; else coarseDraws++;
        if (depthReplay) depthReplayTriangles += count / 3;
    }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        glDeleteVertexArrays(this.vao);
        glDeleteBuffers(this.vbo);
        if (this.lodBuffer != 0) glDeleteBuffers(this.lodBuffer);
        if (this.coarseVbo != 0) glDeleteBuffers(this.coarseVbo);
        if (this.privateIndexBuffer != 0) glDeleteBuffers(this.privateIndexBuffer);
        this.cachedSelection = null;
    }

    /** Render-thread upload with unpublished buffers. Each step transfers at most 64 KiB. */
    static final class Upload implements CreateMeshQueue.Upload {
        private DistantMesh mesh;
        private final ByteBuffer vertices;
        private final DistantMeshLod lod;
        private int vertexOffset, level, levelOffset, indexOffset;

        Upload(ByteBuffer vertices, int quads, DistantMeshLod lod,
               float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            this.vertices = vertices; this.lod = lod;
            this.mesh = new DistantMesh(vertices, quads, lod, true);
            try {
                var mesh = this.mesh;
                mesh.minX = minX; mesh.minY = minY; mesh.minZ = minZ;
                mesh.maxX = maxX; mesh.maxY = maxY; mesh.maxZ = maxZ;
                mesh.vbo = glCreateBuffers(); glNamedBufferData(mesh.vbo, vertices.limit(), GL_STATIC_DRAW);
                if (lod.byteSize() != 0) {
                    int buffer = glCreateBuffers();
                    if (lod.vertices == null) mesh.lodBuffer = buffer; else mesh.coarseVbo = buffer;
                    glNamedBufferData(buffer, lod.byteSize(), GL_STATIC_DRAW);
                    long offset = 0;
                    for (int i = 0; i < lod.grids.length; i++) {
                        mesh.lodOffsets[i] = offset; mesh.lodCounts[i] = lod.indexCount(i);
                        offset += (lod.vertices == null ? lod.indices[i] : lod.vertices[i]).length * 4L;
                    }
                }
                // Reuse existing shared indices when possible. Growing the shared buffer in pieces
                // would invalidate already-visible meshes, so larger staged uploads own their indices.
                if (quads > sharedIndexQuadCapacity) {
                    mesh.privateIndexBuffer = glCreateBuffers(); mesh.privateIndexBytes = quads * 24L;
                    glNamedBufferData(mesh.privateIndexBuffer, mesh.privateIndexBytes, GL_STATIC_DRAW);
                }
            } catch (RuntimeException | Error failure) { this.close(); throw failure; }
        }

        @Override public long step(long budget) {
            int count = (int) Math.min(64 * 1024, budget) & ~3;
            if (count == 0 || this.done()) return 0;
            if (this.vertexOffset < this.vertices.limit()) {
                count = Math.min(count, this.vertices.limit() - this.vertexOffset);
                var part = this.vertices.duplicate(); part.position(this.vertexOffset).limit(this.vertexOffset + count);
                glNamedBufferSubData(this.mesh.vbo, this.vertexOffset, part);
                this.vertexOffset += count;
            } else if (this.level < this.lod.grids.length) {
                int[] source = this.lod.vertices == null ? this.lod.indices[this.level] : this.lod.vertices[this.level];
                count = Math.min(count, source.length * 4 - this.levelOffset);
                int[] part = java.util.Arrays.copyOfRange(source, this.levelOffset / 4, (this.levelOffset + count) / 4);
                glNamedBufferSubData(this.mesh.coarseVbo != 0 ? this.mesh.coarseVbo : this.mesh.lodBuffer,
                        this.mesh.lodOffsets[this.level] + this.levelOffset, part);
                this.levelOffset += count;
                if (this.levelOffset == source.length * 4) { this.level++; this.levelOffset = 0; }
            } else {
                count = (int) Math.min(count, this.mesh.privateIndexBytes - this.indexOffset);
                int[] part = new int[count / 4];
                for (int i = 0; i < part.length; i++) {
                    int index = this.indexOffset / 4 + i;
                    part[i] = index / 6 * 4 + switch (index % 6) { case 0, 5 -> 0; case 1 -> 1; case 2, 3 -> 2; default -> 3; };
                }
                glNamedBufferSubData(this.mesh.privateIndexBuffer, this.indexOffset, part);
                this.indexOffset += count;
            }
            return count;
        }

        @Override public boolean done() {
            return this.vertexOffset == this.vertices.limit() && this.level == this.lod.grids.length
                    && this.indexOffset == this.mesh.privateIndexBytes;
        }

        @Override public DistantMesh finish() {
            if (!this.done()) throw new IllegalStateException("Upload is incomplete");
            this.mesh.setupVao();
            var result = this.mesh; this.mesh = null;
            return result;
        }

        @Override public void close() { if (this.mesh != null) { this.mesh.free(); this.mesh = null; } }
    }
}
