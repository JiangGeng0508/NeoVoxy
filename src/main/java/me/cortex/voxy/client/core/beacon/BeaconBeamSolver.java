package me.cortex.voxy.client.core.beacon;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.util.FastColor;
import net.minecraft.world.level.block.BeaconBeamBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class BeaconBeamSolver {
    //Vanilla stops at the build limit; the beam is drawn far past it but the scan has to end somewhere
    static final int MAX_SCAN_HEIGHT = 1024;

    private BeaconBeamSolver() {}

    //One run of constant colour. Heights are absolute world Y.
    public record Segment(int colorRgb, int yBottom, int yTop) {}

    public record Result(List<Segment> segments, boolean lookupFailed, boolean cacheMissed) {
        static final Result EMPTY = new Result(List.of(), false, false);
        static final Result RETRY = new Result(List.of(), true, false);
        static final Result DEFERRED = new Result(List.of(), false, true);
    }

    public static Result solve(WorldEngine engine, int bx, int by, int bz, int maxBuildY) {
        int top = by + MAX_SCAN_HEIGHT;
        int walkTop = Math.min(top, maxBuildY);
        boolean[] lookupFailed = new boolean[1];
        boolean[] cacheMissed = new boolean[1];
        if (!hasBase(engine, bx, by, bz, lookupFailed, cacheMissed)) {
            if (cacheMissed[0]) {
                BeaconColumnWarmer.warm(engine, bx, by, bz, walkTop);
                return Result.DEFERRED;
            }
            return lookupFailed[0] ? Result.RETRY : Result.EMPTY;
        }
        var segments = new ArrayList<Segment>();
        int currentColor = 0xFFFFFF;
        int segmentBottom = by + 1;
        boolean anyColorSeen = false;

        var mapper = engine.getMapper();

        //One acquire per section rather than per block: the column walks 16 blocks of a section before
        //it needs the next one, and acquire/release is the expensive part
        int y = by + 1;
        while (y <= walkTop) {
            int sectionY = y >> 5;
            var section = engine.acquireIfCached(0, bx >> 5, sectionY, bz >> 5);
            if (section == null) {
                BeaconColumnWarmer.warm(engine, bx, by, bz, walkTop);
                return Result.DEFERRED;
            }
            try {
                int lx = bx & 31, lz = bz & 31;
                while (y <= walkTop && (y >> 5) == sectionY) {
                    long voxel = section.get(lx | (lz << 5) | ((y & 31) << 10));
                    if (voxel != 0 && !Mapper.isAir(voxel)) {
                        int blockId = Mapper.getBlockId(voxel);
                        BlockState state;
                        try {
                            state = mapper.getBlockStateFromBlockId(blockId);
                        } catch (Exception e) {
                            state = null;
                            lookupFailed[0] = true;
                        }
                        if (state != null && isRedirector(state)) {
                            return Result.EMPTY;
                        }
                        Integer tint = state == null ? null : tintOf(state);
                        if (tint != null) {
                            //Vanilla's rule: the first coloured block replaces white outright, and only
                            //later changes average into what came before
                            int next = anyColorSeen ? FastColor.ARGB32.average(currentColor, tint) : tint;
                            anyColorSeen = true;
                            if (next != currentColor) {
                                if (y > segmentBottom) {
                                    segments.add(new Segment(currentColor, segmentBottom, y));
                                }
                                currentColor = next;
                                segmentBottom = y;
                            }
                        } else if (state != null && isBeamStopper(mapper, blockId, state)) {
                            //Vanilla clears checkingBeamSections here rather than keeping what it has,
                            //so an obstructed beacon shows no beam at all rather than one cut off at the
                            //ceiling. A beacon under a roof is the ordinary case.
                            return Result.EMPTY;
                        }
                    }
                    y++;
                }
            } finally {
                section.release();
            }
        }

        if (top > segmentBottom) {
            segments.add(new Segment(currentColor, segmentBottom, top));
        }
        return new Result(segments, lookupFailed[0], false);
    }

    //Only the first pyramid layer, because only levels != 0 matters here - the higher layers change the
    //powers on offer, not whether there is a beam. Vanilla's updateBase walks 3x3 up to 9x9 for the same
    //first answer.
    private static boolean hasBase(WorldEngine engine, int bx, int by, int bz,
                                   boolean[] lookupFailed, boolean[] cacheMissed) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!isBaseBlock(engine, bx + dx, by - 1, bz + dz, lookupFailed, cacheMissed)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isBaseBlock(WorldEngine engine, int x, int y, int z,
                                       boolean[] lookupFailed, boolean[] cacheMissed) {
        var section = engine.acquireIfCached(0, x >> 5, y >> 5, z >> 5);
        if (section == null) {
            cacheMissed[0] = true;
            return false;
        }
        try {
            long voxel = section.get((x & 31) | ((z & 31) << 5) | ((y & 31) << 10));
            if (voxel == 0 || Mapper.isAir(voxel)) {
                return false;
            }
            return engine.getMapper().getBlockStateFromBlockId(Mapper.getBlockId(voxel))
                    .is(net.minecraft.tags.BlockTags.BEACON_BASE_BLOCKS);
        } catch (Exception e) {
            lookupFailed[0] = true;
            return false;
        } finally {
            section.release();
        }
    }

    //The vanilla hook for "this block tints a beacon beam" - stained glass and panes implement it, and so
    //do modded blocks that opt in, without any of them needing a Level to ask
    private static Integer tintOf(BlockState state) {
        if (state.getBlock() instanceof BeaconBeamBlock beam) {
            return beam.getColor().getTextureDiffuseColor();
        }
        return null;
    }

    private static boolean isRedirector(BlockState state) {
        if (state.is(net.minecraft.world.level.block.Blocks.AMETHYST_CLUSTER)) {
            return true;
        }
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key != null && key.getPath().endsWith("corundum_cluster");
    }

    //Vanilla kills the beam on anything that blocks all light, bedrock excepted
    private static boolean isBeamStopper(Mapper mapper, int blockId, BlockState state) {
        if (state.is(Blocks.BEDROCK)) {
            return false;
        }
        return mapper.getBlockStateOpacity(blockId) >= 15;
    }
}
