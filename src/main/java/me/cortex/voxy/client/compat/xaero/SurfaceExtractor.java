package me.cortex.voxy.client.compat.xaero;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class SurfaceExtractor {
    public interface Rules {
        boolean invisible(BlockState state);
        boolean overlay(BlockState state);
        int opacity(BlockState state);
    }

    /** Worker-only. Reads local data for this one tile and the finite dimension height;
     * it never requests remote LOD or enumerates storage positions. Coarse data only
     * fills unknown fine cells. Missing/legacy air remains unknown, never known sky. */
    public static SurfaceTile extract(WorldEngine engine, int cx, int cz, int bottom, int top,
                                      boolean coarse, Rules rules, BooleanSupplier cancelled) {
        int height = top - bottom;
        long[] voxels = new long[height * 256];
        byte[] detail = new byte[voxels.length];
        Arrays.fill(detail, (byte) -1);
        for (int lod = coarse ? 4 : 0; lod >= 0; lod--) {
            int shift = lod + 5;
            int sx = (cx << 4) >> shift, sz = (cz << 4) >> shift;
            for (int sy = bottom >> shift; sy <= (top - 1) >> shift; sy++) {
                if (cancelled.getAsBoolean()) return null;
                WorldSection section = engine.acquireIfCachedLocal(lod, sx, sy, sz);
                boolean fromStorage = section == null;
                if (section == null) {
                    section = WorldSection._createRawUntrackedUnsafeSection(lod, sx, sy, sz);
                    section.acquire();
                }
                try {
                    if (fromStorage && engine.storage.loadSectionLocal(section) != 0) continue;
                    synchronized (section) {
                        int lo = Math.max(bottom, sy << shift), hi = Math.min(top, (sy + 1) << shift);
                        for (int y = lo; y < hi; y++) {
                            int vy = (y >> lod) & 31;
                            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                                int vx = (((cx << 4) + x) >> lod) & 31;
                                int vz = (((cz << 4) + z) >> lod) & 31;
                                long value = section.get(WorldSection.getIndex(vx, vy, vz));
                                if (!Mapper.isAir(value) || section.isVoxelKnown(vx, vy, vz)) {
                                    int index = (y - bottom) * 256 + z * 16 + x;
                                    voxels[index] = value;
                                    detail[index] = (byte) lod;
                                }
                            }
                        }
                    }
                } finally {
                    section.release();
                }
            }
        }
        return project(cx, cz, bottom, top, voxels, detail, engine.getMapper(), rules);
    }

    static SurfaceTile project(int cx, int cz, int bottom, int top, long[] voxels, byte[] detail, Mapper mapper, Rules rules) {
        SurfaceTile.Pixel[] result = new SurfaceTile.Pixel[256];
        for (int column = 0; column < 256; column++) {
            boolean unknown = false;
            int precision = 0, surfaceTop = bottom;
            ResourceKey<Biome> biome = null;
            List<SurfaceTile.Cover> covers = new ArrayList<>();
            for (int y = top - 1; y >= bottom; y--) {
                int index = (y - bottom) * 256 + column;
                if (detail[index] < 0) { unknown = true; continue; }
                precision = Math.max(precision, detail[index]);
                long value = voxels[index];
                if (Mapper.isAir(value)) continue;
                BlockState state = mapper.getBlockStateFromBlockId(Mapper.getBlockId(value));
                if (state == null) { unknown = true; continue; }
                byte light = (byte) ((Mapper.getLightId(value) >> 4) & 15);
                var entry = mapper.getBiomeEntry(Mapper.getBiomeId(value));
                if (biome == null && entry != null) {
                    ResourceLocation id = ResourceLocation.tryParse(entry.biome);
                    if (id != null) biome = ResourceKey.create(Registries.BIOME, id);
                }
                BlockState fluid = state.getFluidState().createLegacyBlock();
                if (!fluid.isAir() && fluid != state && rules.overlay(fluid)) {
                    surfaceTop = Math.max(surfaceTop, y);
                    addCover(covers, fluid, rules.opacity(fluid), light);
                }
                if (rules.invisible(state)) continue;
                surfaceTop = Math.max(surfaceTop, y);
                if (rules.overlay(state)) {
                    addCover(covers, state, rules.opacity(state), light);
                    continue;
                }
                result[column] = new SurfaceTile.Pixel(state, y, surfaceTop, biome, light,
                        List.copyOf(covers), precision, unknown || precision > 0);
                break;
            }
            // A completely known empty column clears previous Voxy terrain. Unknown
            // columns without evidence leave existing maps untouched.
            if (result[column] == null && (!unknown || !covers.isEmpty())) {
                result[column] = new SurfaceTile.Pixel(Blocks.AIR.defaultBlockState(), bottom,
                        surfaceTop, biome, (byte) 0, List.copyOf(covers), precision, unknown || precision > 0);
            }
        }
        return new SurfaceTile(cx, cz, result);
    }

    private static void addCover(List<SurfaceTile.Cover> covers, BlockState state, int opacity, byte light) {
        if (!covers.isEmpty() && covers.getLast().state() == state) {
            var last = covers.removeLast();
            covers.add(new SurfaceTile.Cover(state, Math.min(15, last.opacity() + opacity), last.light()));
        } else if (covers.size() < 10) {
            covers.add(new SurfaceTile.Cover(state, Math.clamp(opacity, 0, 15), light));
        }
    }
    private SurfaceExtractor() {}
}
