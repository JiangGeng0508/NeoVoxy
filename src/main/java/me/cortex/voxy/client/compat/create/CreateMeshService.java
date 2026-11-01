package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** One queue/budget per client instance, shared by carriage, bogey and kinetic meshes. */
public final class CreateMeshService {
    private static VoxyInstance owner;
    private static CreateMeshQueue queue;

    private CreateMeshService() {}

    static boolean request(Object key, Supplier<DistantMeshBuilder.CpuMesh> capture,
                           Consumer<DistantMesh> publish, Runnable abandoned) {
        var instance = VoxyCommon.getInstance();
        if (instance == null || !instance.isRunning()) return false;
        if (instance != owner) {
            stop(owner);
            owner = instance; queue = new CreateMeshQueue(instance.getServiceManager());
        }
        return queue.request(key, capture, publish, abandoned);
    }

    static void cancel(Object key) { if (queue != null) queue.cancel(key); }

    public static void frame() {
        if (queue == null) return;
        if (owner != VoxyCommon.getInstance() || !owner.isRunning()) { stop(owner); return; }
        if (VoxyConfig.CONFIG.isRenderingEnabled()) queue.frame();
    }

    public static void stop(VoxyInstance instance) {
        if (instance != owner || queue == null) return;
        var previous = queue; queue = null; owner = null;
        previous.close();
    }

    public static void reload() {
        stop(owner);
        DistantTrainManager.invalidateMeshes();
        DistantBogeyMeshes.clearAll();
        KineticSnapshots.clearAll();
        DistantContraptionManager.invalidateMeshes();
    }

    public static String debug() { return queue == null ? "Create mesh queue: idle" : "Create mesh queue: " + queue.stats(); }
    static CreateMeshQueue.Stats stats() { return queue == null ? null : queue.stats(); }
}
