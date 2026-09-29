package me.cortex.voxy.client.mixin.create;

import dev.engine_room.flywheel.impl.visualization.storage.Storage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(Storage.class)
public interface AccessorFlywheelStorage {
    @Accessor("visuals")
    Map<?, ?> voxy$getVisuals();
}


