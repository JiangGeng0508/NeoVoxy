package me.cortex.voxy.client.compat.seedgen;

import me.cortex.voxy.common.Logger;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class SeedGenCompat {
    private SeedGenCompat() {}

    /** SeedGen's far-plane mixin replaces its historic 48000-block baseline only when extended. */
    public static float projectionFarPlane(float nativeDistance, float seedGenDistance) {
        return seedGenDistance > 48_000 ? Math.max(nativeDistance, seedGenDistance) : nativeDistance;
    }

    /** Only yield while SeedGen is generating; unsupported dimensions retain the LIGHT fallback. */
    public static boolean ownsGeneration(ResourceKey<Level> dimension) {
        if (!ModList.get().isLoaded("voxyseedgen")) return false;
        return Access.ownsGeneration(dimension);
    }

    private static final class Access {
        private static Method runningIn;
        private static Field realChunkJob;
        private static Field realChunkLevel;
        private static volatile boolean failed;
        static {
            try {
                runningIn = Class.forName("mobius.voxyseedgen.voxy.SeedFill").getMethod("runningIn", ResourceKey.class);
                Class<?> type = Class.forName("mobius.voxyseedgen.chunks.RealChunks");
                realChunkJob = type.getDeclaredField("job");
                realChunkJob.setAccessible(true);
                realChunkLevel = realChunkJob.getType().getDeclaredField("level");
                realChunkLevel.setAccessible(true);
            } catch (ReflectiveOperationException | LinkageError error) {
                fail(error);
            }
        }

        static boolean ownsGeneration(ResourceKey<Level> dimension) {
            if (failed) return false;
            try {
                if ((boolean) runningIn.invoke(null, dimension)) return true;
                // The global realChunks setting does not mean every dimension has a job.
                Object job = realChunkJob.get(null);
                return job != null && ((ServerLevel) realChunkLevel.get(job)).dimension().equals(dimension);
            } catch (ReflectiveOperationException | LinkageError error) {
                fail(error);
                return false;
            }
        }

        private static void fail(Throwable error) {
            failed = true;
            Logger.warn("Could not query SeedGen generation; retaining NeoVoxy distant generation", error);
        }
    }
}
