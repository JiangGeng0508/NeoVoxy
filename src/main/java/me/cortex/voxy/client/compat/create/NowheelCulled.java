package me.cortex.voxy.client.compat.create;

import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

public final class NowheelCulled {
    private static final boolean PRESENT = ModList.get() != null && ModList.get().isLoaded("entityculling");
    private static boolean unavailable;

    private NowheelCulled() {}

    public static boolean isCulled(Entity entity) {
        if (!PRESENT || unavailable) {
            return false;
        }
        try {
            return entity instanceof dev.tr7zw.entityculling.versionless.access.Cullable cullable
                    && cullable.isCulled();
        } catch (LinkageError e) {
            unavailable = true;
            return false;
        }
    }

    public static void uncull(Entity entity) {
        if (!PRESENT || unavailable) {
            return;
        }
        try {
            if (entity instanceof dev.tr7zw.entityculling.versionless.access.Cullable cullable) {
                cullable.setCulled(false);
            }
        } catch (LinkageError e) {
            unavailable = true;
        }
    }
}
