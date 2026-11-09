package me.cortex.voxy.client.compat.vista;

import me.cortex.voxy.common.Logger;
import net.neoforged.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Optional Vista API: never link its classes on installations without Vista. */
public final class VistaCompat {
    private static boolean resolved;
    private static MethodHandle isRendering;

    private VistaCompat() {}

    public static boolean isRenderingFeed() {
        if (!resolved) {
            resolved = true;
            if (ModList.get().isLoaded("vista")) {
                try {
                    Class<?> renderer = Class.forName("net.mehvahdjukaar.vista.client.renderer.VistaLevelRenderer");
                    isRendering = MethodHandles.publicLookup().findStatic(renderer,
                            "isRenderingLiveFeed", MethodType.methodType(boolean.class));
                } catch (ReflectiveOperationException | LinkageError e) {
                    Logger.error("Vista camera API unavailable", e);
                }
            }
        }
        if (isRendering == null) return false;
        try {
            return (boolean) isRendering.invokeExact();
        } catch (Throwable e) {
            isRendering = null;
            Logger.error("Vista camera API failed; disabling bridge", e);
            return false;
        }
    }

    /** Keep Iris's separate pipeline key, but load the real dimension's shader programs. */
    public static String shaderDimension(String name) {
        String prefix = "vista_live_feed_";
        return name.startsWith(prefix) ? name.substring(prefix.length()) : name;
    }
}
