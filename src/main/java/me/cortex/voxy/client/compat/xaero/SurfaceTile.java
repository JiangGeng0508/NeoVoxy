package me.cortex.voxy.client.compat.xaero;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Detached from WorldSection and safe to hand to the client thread. */
public record SurfaceTile(int chunkX, int chunkZ, Pixel[] pixels) {
    public boolean hasEvidence() {
        for (var pixel : pixels) if (pixel != null) return true;
        return false;
    }
    public record Cover(BlockState state, int opacity, byte light) {}
    public record Pixel(BlockState state, int height, int top, ResourceKey<Biome> biome,
                        byte light, List<Cover> covers, int lod, boolean provisional) {}
}
