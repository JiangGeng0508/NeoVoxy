package me.cortex.voxy.client.compat.sable;

import me.cortex.voxy.client.compat.create.DistantMeshLod;
import me.cortex.voxy.client.core.rendering.LodViewState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;

import java.util.IdentityHashMap;
import java.util.function.Consumer;

/** Screen-size policy independent of the optional Sable runtime. Render-thread only. */
final class SableLodVisibility {
    private final IdentityHashMap<Object, Boolean> subpixel = new IdentityHashMap<>();
    private final LodViewState view = new LodViewState();
    private final Matrix4f transform = new Matrix4f();
    private final Vector3d corner = new Vector3d();
    private long frame;
    private double cameraX, cameraY, cameraZ, pixels, nearDetail;
    private int width, height;
    private boolean projected;

    void clear() {
        this.subpixel.clear();
        this.view.invalidate();
        this.projected = false;
    }

    // A null transform denotes shadow rendering or a phase without projection.
    // Neither may reuse the main camera's small-target decision.
    void begin(long frame, double x, double y, double z, Matrix4fc transform,
               int width, int height, float subdivision, double nearDetail) {
        if (transform == null) {
            this.clear();
            return;
        }
        if (this.frame != frame || this.cameraX != x || this.cameraY != y || this.cameraZ != z
                || !this.view.matches(transform, width, height, subdivision, nearDetail, 0)) {
            this.subpixel.clear();
        }
        this.frame = frame;
        this.cameraX = x; this.cameraY = y; this.cameraZ = z;
        this.width = width; this.height = height;
        this.pixels = DistantMeshLod.visibilityPixels(subdivision);
        this.nearDetail = nearDetail;
        this.transform.set(transform);
        this.view.set(transform, width, height, subdivision, nearDetail);
        this.projected = true;
    }

    boolean isSubpixel(Object key, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                       Consumer<Vector3d> pose) {
        if (!this.projected) return false;
        Boolean cached = this.subpixel.get(key);
        if (cached != null) return cached;
        double x0 = Double.POSITIVE_INFINITY, y0 = x0, z0 = x0;
        double x1 = Double.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
        for (int i = 0; i < 8; i++) {
            // Include block entities and attached components around the native plot.
            this.corner.set((i & 1) == 0 ? minX - 16.0 : maxX + 17.0,
                    (i & 2) == 0 ? minY - 16.0 : maxY + 17.0,
                    (i & 4) == 0 ? minZ - 16.0 : maxZ + 17.0);
            pose.accept(this.corner);
            x0 = Math.min(x0, this.corner.x); x1 = Math.max(x1, this.corner.x);
            y0 = Math.min(y0, this.corner.y); y1 = Math.max(y1, this.corner.y);
            z0 = Math.min(z0, this.corner.z); z1 = Math.max(z1, this.corner.z);
        }
        boolean small = DistantMeshLod.isSubpixel(this.transform,
                (float) (x0 - this.cameraX), (float) (y0 - this.cameraY), (float) (z0 - this.cameraZ),
                (float) (x1 - this.cameraX), (float) (y1 - this.cameraY), (float) (z1 - this.cameraZ),
                this.width, this.height, this.pixels, this.nearDetail);
        this.subpixel.put(key, small);
        return small;
    }
}
