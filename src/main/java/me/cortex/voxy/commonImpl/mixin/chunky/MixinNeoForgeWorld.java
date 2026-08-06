package me.cortex.voxy.commonImpl.mixin.chunky;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.popcraft.chunky.platform.NeoForgeWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

@Mixin(value = NeoForgeWorld.class, remap = false)
public class MixinNeoForgeWorld {
    @WrapOperation(
            method = "getChunkAtAsync",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;getChunkFutureMainThread(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;",
                    remap = true
            ),
            remap = false
    )
    private CompletableFuture<ChunkResult<ChunkAccess>> voxy$captureGeneratedChunk(ServerChunkCache instance,
                                                                                   int x,
                                                                                   int z,
                                                                                   ChunkStatus chunkStatus,
                                                                                   boolean load,
                                                                                   Operation<CompletableFuture<ChunkResult<ChunkAccess>>> original) {
        var future = original.call(instance, x, z, chunkStatus, load);
        return future.thenApply(result -> {
            result.ifSuccess(chunk -> {
                if (chunk instanceof LevelChunk levelChunk) {
                    VoxelIngestService.tryAutoIngestChunk(levelChunk);
                }
            });
            return result;
        });
    }
}
