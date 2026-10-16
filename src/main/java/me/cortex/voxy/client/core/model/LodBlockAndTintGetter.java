package me.cortex.voxy.client.core.model;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/** A section-local model view, including edge/corner neighbours at the same LOD level. */
public final class LodBlockAndTintGetter implements BlockAndTintGetter, AutoCloseable {
    private final WorldEngine world;
    private final WorldSection center;
    private final long[] data;
    private final WorldSection[] neighbors = new WorldSection[27];
    private final boolean[] queried = new boolean[27];

    public LodBlockAndTintGetter(WorldEngine world, WorldSection center, long[] data) {
        this.world = world;
        this.center = center;
        this.data = data;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        int sx = x >> 5, sy = y >> 5, sz = z >> 5;
        int index = (x & 31) | ((z & 31) << 5) | ((y & 31) << 10);
        long voxel;
        if ((sx | sy | sz) == 0) {
            voxel = this.data[index];
        } else {
            if (Math.abs(sx) > 1 || Math.abs(sy) > 1 || Math.abs(sz) > 1) {
                return Blocks.AIR.defaultBlockState();
            }
            int slot = (sx + 1) + (sz + 1) * 3 + (sy + 1) * 9;
            if (!this.queried[slot]) {
                this.neighbors[slot] = this.world.acquireIfExists(this.center.lvl,
                        this.center.x + sx, this.center.y + sy, this.center.z + sz);
                this.queried[slot] = true;
            }
            WorldSection neighbor = this.neighbors[slot];
            if (neighbor == null) return Blocks.AIR.defaultBlockState();
            voxel = neighbor.get(index);
        }
        return this.world.getMapper().getBlockStateFromBlockId(Mapper.getBlockId(voxel));
    }

    @Override public FluidState getFluidState(BlockPos pos) { return this.getBlockState(pos).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public int getHeight() { return 32; }
    @Override public int getMinBuildHeight() { return 0; }
    @Override public float getShade(Direction direction, boolean shade) { return 1; }
    @Override public LevelLightEngine getLightEngine() { return null; }
    @Override public int getBrightness(LightLayer layer, BlockPos pos) { return 15; }
    @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) { return 0xFFFFFF; }

    @Override
    public void close() {
        for (int i = 0; i < this.neighbors.length; i++) {
            if (this.neighbors[i] != null) {
                this.neighbors[i].release();
                this.neighbors[i] = null;
            }
        }
    }
}
