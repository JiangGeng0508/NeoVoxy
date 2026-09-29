package me.cortex.voxy.client.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class DistantFaceCulling {
    private DistantFaceCulling() {}

    public static boolean isFullBlock(BlockState state) {
        return isFullBlock(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    public static boolean isFullBlock(BlockState state, BlockGetter level, BlockPos pos) {
        try {
            return state != null && Block.isShapeFullBlock(state.getOcclusionShape(level, pos));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hidesSectionFace(boolean[] fullBlocks, int local, Direction direction) {
        if (!fullBlocks[local]) return false;
        int x = local & 15;
        int z = (local >>> 4) & 15;
        int y = (local >>> 8) & 15;
        int nx = x + direction.getStepX();
        int ny = y + direction.getStepY();
        int nz = z + direction.getStepZ();
        if ((nx | ny | nz) < 0 || nx > 15 || ny > 15 || nz > 15) return false;
        return fullBlocks[nx | (nz << 4) | (ny << 8)];
    }
}
