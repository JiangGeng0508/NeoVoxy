package me.cortex.voxy.client.compat.create;

import dev.engine_room.flywheel.api.instance.Instance;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class AzimuthBehaviourIndex {
    private AzimuthBehaviourIndex() {}

    private static final Map<Object, List<Consumer<Consumer<Instance>>>> BEHAVIOURS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    public static void register(Object parentVisual, Consumer<Consumer<Instance>> instanceWalker) {
        if (parentVisual == null) {
            return;
        }
        BEHAVIOURS.computeIfAbsent(parentVisual, k -> new CopyOnWriteArrayList<>()).add(instanceWalker);
    }

    //Applies the action to every behaviour instance registered under this visual. A walker that throws
    //(its instance already deleted mid-teardown) is dropped rather than retried forever.
    public static void apply(Object parentVisual, Consumer<Instance> action) {
        List<Consumer<Consumer<Instance>>> walkers = BEHAVIOURS.get(parentVisual);
        if (walkers == null) {
            return;
        }
        for (Consumer<Consumer<Instance>> walker : walkers) {
            try {
                walker.accept(action);
            } catch (Throwable e) {
                walkers.remove(walker);
            }
        }
    }
}
