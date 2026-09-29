package me.cortex.voxy.client.compat.create;

import net.minecraft.world.entity.Entity;

public final class FlywheelVisuals {
    private static boolean unavailable;

    private FlywheelVisuals() {}

    public static boolean hasVisual(Entity entity) {
        if (unavailable) {
            return true;
        }
        try {
            var manager = dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl.get(entity.level());
            if (manager == null) {
                return false;
            }
            var entities = (dev.engine_room.flywheel.impl.visualization.VisualManagerImpl<?, ?>) manager.entities();
            return ((me.cortex.voxy.client.mixin.create.AccessorFlywheelStorage) entities.getStorage())
                    .voxy$getVisuals().containsKey(entity);
        } catch (LinkageError | ClassCastException e) {
            unavailable = true;
            return true;
        }
    }
}


