package me.cortex.voxy.client.mixin.fakesight;

import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(IntegratedServer.class)
public abstract class MixinIntegratedServer {
    @Unique private static final int VOXY_EXPANSION_INTERVAL_TICKS = 40;
    @Unique private static final int VOXY_EXPANSION_STEP = 8;
    @Unique private static final int VOXY_MOVEMENT_PAUSE_TICKS = 80;

    @Unique private int voxy$currentRequestDistance = -1;
    @Unique private int voxy$expansionTicks;
    @Unique private int voxy$movementPauseTicks;
    @Unique private long voxy$lastPlayerChunk = Long.MIN_VALUE;
    @Unique private int voxy$lastDimensionHash;

    @ModifyArg(
            method = "tickServer",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Math;max(II)I",
                    ordinal = 0
            ),
            index = 1,
            require = 1
    )
    private int voxy$modifyIntegratedServerRenderDistance(int originalDistance) {
        if (!VoxyConfig.CONFIG.enableExtendedRequestDistance) {
            this.voxy$resetRequestExpansion();
            return originalDistance;
        }
        int safeRequestDistance = Math.max(originalDistance, VoxyConfig.CONFIG.getRequestDistance());

        if (this.voxy$currentRequestDistance < originalDistance) {
            this.voxy$currentRequestDistance = originalDistance;
        } else if (this.voxy$currentRequestDistance > safeRequestDistance) {
            this.voxy$currentRequestDistance = safeRequestDistance;
        }

        var server = (IntegratedServer) (Object) this;
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return this.voxy$currentRequestDistance;
        }
        var player = players.getFirst();
        ChunkPos chunk = player.chunkPosition();
        long chunkKey = ChunkPos.asLong(chunk.x, chunk.z);
        int dimensionHash = player.serverLevel().dimension().hashCode();
        if (chunkKey != this.voxy$lastPlayerChunk || dimensionHash != this.voxy$lastDimensionHash) {
            this.voxy$lastPlayerChunk = chunkKey;
            this.voxy$lastDimensionHash = dimensionHash;
            this.voxy$movementPauseTicks = VOXY_MOVEMENT_PAUSE_TICKS;
            this.voxy$expansionTicks = 0;
        }

        if (this.voxy$movementPauseTicks > 0) {
            this.voxy$movementPauseTicks--;
        } else if (this.voxy$currentRequestDistance < safeRequestDistance
                && ++this.voxy$expansionTicks >= VOXY_EXPANSION_INTERVAL_TICKS) {
            this.voxy$currentRequestDistance = Math.min(safeRequestDistance,
                    this.voxy$currentRequestDistance + VOXY_EXPANSION_STEP);
            this.voxy$expansionTicks = 0;
        }

        return this.voxy$currentRequestDistance;
    }

    @Unique
    private void voxy$resetRequestExpansion() {
        this.voxy$currentRequestDistance = -1;
        this.voxy$expansionTicks = 0;
        this.voxy$movementPauseTicks = 0;
        this.voxy$lastPlayerChunk = Long.MIN_VALUE;
        this.voxy$lastDimensionHash = 0;
    }
}
