package me.cortex.voxy.client.mixin.create;

import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Contraption visuals use a virtual level and report local coordinates. Those coordinates must not
// be compared with the real client camera when deciding whether to replace a visual with LOD.
@Mixin(AbstractVisual.class)
public interface AccessorAbstractVisualLevel {
    @Accessor("level")
    Level voxy$getLevel();
}
