package me.cortex.voxy.client.compat.sable;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import me.cortex.voxy.client.compat.SectionHandoff;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.util.IrisUtil;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

public final class SableReacharoundCulling {
    private static final double HYSTERESIS_BLOCKS = 32.0D;
    private static final SableLodVisibility VISIBILITY = new SableLodVisibility();
    private static final Matrix4f MVP = new Matrix4f();

    private SableReacharoundCulling() {
    }

    public static Iterable<ClientSubLevel> filter(Iterable<ClientSubLevel> subLevels,
                                                 double cameraX, double cameraY, double cameraZ,
                                                 Matrix4f modelView, Matrix4f projection) {
        if (!SableClientRenderDistance.isVoxyRenderDistanceActive()) {
            VISIBILITY.clear();
            return subLevels;
        }

        int vanillaRenderDistanceChunks = Minecraft.getInstance().options.getEffectiveRenderDistance();
        double renderDistanceBlocks = SableClientRenderDistance.getRenderDistanceBlocks(vanillaRenderDistanceChunks) + HYSTERESIS_BLOCKS;
        if (!Double.isFinite(renderDistanceBlocks) || renderDistanceBlocks <= 0.0D) {
            VISIBILITY.clear();
            return subLevels;
        }

        var mc = Minecraft.getInstance();
        var target = mc.getMainRenderTarget();
        boolean projected = !IrisUtil.irisShadowActive() && modelView != null && projection != null;
        if (projected) MVP.set(projection).mul(modelView);
        VISIBILITY.begin(SectionHandoff.frameId(), cameraX, cameraY, cameraZ, projected ? MVP : null,
                target.width, target.height, VoxyConfig.CONFIG.subDivisionSize,
                (mc.options.renderDistance().get() + 2) * 16.0);
        var center = new Vector3d();
        List<ClientSubLevel> visibleSubLevels = new ArrayList<>();
        for (ClientSubLevel subLevel : subLevels) {
            if (!isInRenderDistance(subLevel, cameraX, cameraZ, renderDistanceBlocks, center)) continue;
            var bounds = subLevel.getPlot().getBoundingBox();
            if (bounds != null && VISIBILITY.isSubpixel(subLevel,
                    bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(),
                    subLevel.renderPose()::transformPosition)) continue;
            visibleSubLevels.add(subLevel);
        }
        return visibleSubLevels;
    }

    private static boolean isInRenderDistance(ClientSubLevel subLevel, double cameraX, double cameraZ,
                                               double renderDistanceBlocks, Vector3d center) {
        BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        if (bounds == null) {
            return true;
        }

        center.set(
                (bounds.minX() + bounds.maxX() + 1) * 0.5D,
                (bounds.minY() + bounds.maxY() + 1) * 0.5D,
                (bounds.minZ() + bounds.maxZ() + 1) * 0.5D
        );
        subLevel.renderPose().transformPosition(center);

        double halfWidth = Math.max(0.5D, bounds.width() * 0.5D);
        double halfLength = Math.max(0.5D, bounds.length() * 0.5D);
        double horizontalRadius = Math.sqrt(halfWidth * halfWidth + halfLength * halfLength);

        double dx = center.x - cameraX;
        double dz = center.z - cameraZ;
        return (dx * dx + dz * dz) <= (renderDistanceBlocks + horizontalRadius) * (renderDistanceBlocks + horizontalRadius);
    }
}
