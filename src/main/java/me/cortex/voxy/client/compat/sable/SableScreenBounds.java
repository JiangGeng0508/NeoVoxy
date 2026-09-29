package me.cortex.voxy.client.compat.sable;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;

public final class SableScreenBounds {
    //Block models overhang their section (fences, banners, mounted blocks); grow the plot box before
    //using it so anything a section layer can rasterize stays inside the reported extent
    private static final double OVERHANG_BLOCKS = 2.0D;
    //A corner this close to the near plane projects to garbage - fall back rather than clip the pass
    private static final float NEAR_W_EPSILON = 1.0e-4f;
    private static final double LOD_FREE_MARGIN_BLOCKS = 64.0D;

    public static final float[] FULLSCREEN = {-1.0f, -1.0f, 1.0f, 1.0f};

    /** Why a pass needs no bracketing, kept apart so the counters say which reduction fired. */
    public enum Skip {
        /** Bracket it - {@link Result#ndc} says over which pixels */
        NONE,
        /** Every sub-level sits inside the radius LOD cannot reach into */
        ALL_NEAR,
        /** Nothing the pass draws lands on screen */
        OFFSCREEN
    }

    public record Result(float[] ndc, Skip skip) {
        static final Result ALL_NEAR = new Result(null, Skip.ALL_NEAR);
        static final Result OFFSCREEN = new Result(null, Skip.OFFSCREEN);

        public static Result allNear() {
            return ALL_NEAR;
        }
    }

    private SableScreenBounds() {
    }

    public static Result of(Iterable<ClientSubLevel> subLevels, double cameraX, double cameraY, double cameraZ,
                            Matrix4f modelView, Matrix4f projection) {
        return of(subLevels, cameraX, cameraY, cameraZ, modelView, projection, OVERHANG_BLOCKS);
    }

    /**
     * @param overhangBlocks how far past the plot box the bracketed pass can draw. Section layers stay
     *                       within a block or two of their own geometry; Flywheel visuals reach further
     *                       (piston poles, pulley ropes), so that path asks for more.
     */
    public static Result of(Iterable<ClientSubLevel> subLevels, double cameraX, double cameraY, double cameraZ,
                            Matrix4f modelView, Matrix4f projection, double overhangBlocks) {
        if (subLevels == null) {
            return Result.ALL_NEAR;
        }

        double lodFreeRadius = lodFreeRadiusBlocks();
        double lodFreeRadiusSq = lodFreeRadius * lodFreeRadius;
        Matrix4f mvp = new Matrix4f(projection).mul(modelView);
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        boolean any = false;

        Vector3d[] corners = new Vector3d[8];
        for (int i = 0; i < 8; i++) {
            corners[i] = new Vector3d();
        }
        Vector4f clip = new Vector4f();

        for (ClientSubLevel subLevel : subLevels) {
            BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
            if (bounds == null) {
                //Plot not synced yet - no box to measure or project, so assume it needs everything
                return new Result(FULLSCREEN, Skip.NONE);
            }

            double x0 = bounds.minX() - overhangBlocks;
            double y0 = bounds.minY() - overhangBlocks;
            double z0 = bounds.minZ() - overhangBlocks;
            double x1 = bounds.maxX() + 1 + overhangBlocks;
            double y1 = bounds.maxY() + 1 + overhangBlocks;
            double z1 = bounds.maxZ() + 1 + overhangBlocks;

            double farthestSq = 0.0D;
            for (int i = 0; i < 8; i++) {
                Vector3d corner = corners[i];
                corner.set((i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1);
                //renderPose maps plot space to world space, matching what the camera position is in
                subLevel.renderPose().transformPosition(corner);
                double dx = corner.x - cameraX;
                double dz = corner.z - cameraZ;
                farthestSq = Math.max(farthestSq, dx * dx + dz * dz);
            }

            if (farthestSq >= lodFreeRadiusSq) {
                any = true;
            }

            for (int i = 0; i < 8; i++) {
                Vector3d corner = corners[i];
                clip.set((float) (corner.x - cameraX), (float) (corner.y - cameraY), (float) (corner.z - cameraZ), 1.0f);
                mvp.transform(clip);
                if (clip.w <= NEAR_W_EPSILON) {
                    //Camera is inside or right against this sub-level, where it covers most of the view anyway
                    return new Result(FULLSCREEN, Skip.NONE);
                }
                float ndcX = clip.x / clip.w;
                float ndcY = clip.y / clip.w;
                minX = Math.min(minX, ndcX);
                minY = Math.min(minY, ndcY);
                maxX = Math.max(maxX, ndcX);
                maxY = Math.max(maxY, ndcY);
            }
        }

        if (!any) {
            return Result.ALL_NEAR;
        }
        if (minX > 1.0f || maxX < -1.0f || minY > 1.0f || maxY < -1.0f) {
            //Entirely off screen - the pass draws nothing, so the shim has nothing to bracket
            return Result.OFFSCREEN;
        }
        return new Result(new float[]{Math.max(minX, -1.0f), Math.max(minY, -1.0f),
                Math.min(maxX, 1.0f), Math.min(maxY, 1.0f)}, Skip.NONE);
    }

    /** Radius within which no LOD geometry can appear, so nothing inside it needs depth merging. */
    public static double lodFreeRadiusBlocks() {
        double vanillaReach = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0D;
        return Math.max(0.0D, vanillaReach - LOD_FREE_MARGIN_BLOCKS);
    }
}
