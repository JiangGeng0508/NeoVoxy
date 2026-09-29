package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ClientSessionEvents;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Last-chance ownership barrier before Minecraft recursively deletes a save.
 *
 * Normally clearClientLevel already closes Voxy.  Keeping this at the actual deletion boundary also
 * covers aborted disconnects and modded world-selection screens, and guarantees that native database
 * handles are released before Windows starts deleting the directory tree.
 */
@Mixin(LevelStorageSource.LevelStorageAccess.class)
public class MixinLevelStorageAccess {
    @Inject(method = "deleteLevel", at = @At("HEAD"))
    private void voxy$closeStorageBeforeDeletingWorld(CallbackInfo ci) {
        ClientSessionEvents.sessionEnd();
    }
}
