package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.util.IrisUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ViewportSelector <T extends Viewport<?>> {
    public static final boolean VIVECRAFT_INSTALLED = false;

    private final Supplier<T> creator;
    private final T defaultViewport;
    private final Map<Object, T> extraViewports = new HashMap<>();//TODO should maybe be a weak hashmap with value cleanup queue thing?

    public ViewportSelector(Supplier<T> viewportCreator) {
        this.creator = viewportCreator;
        this.defaultViewport = viewportCreator.get();
        this.defaultViewport.isMainViewport = true;
    }

    private T getOrCreate(Object holder) {
        return this.extraViewports.computeIfAbsent(holder, a->this.creator.get());
    }

    private T getVivecraftViewport() {
        return null;
    }

    private static final Object IRIS_SHADOW_OBJECT = new Object();
    public T getViewport() {
        T viewport = null;
        if (viewport == null && VIVECRAFT_INSTALLED) {
            viewport = getVivecraftViewport();
        }

        if (viewport == null && IrisUtil.irisShadowActive()) {
            viewport = this.getOrCreate(IRIS_SHADOW_OBJECT);
        }

        if (viewport == null) {
            viewport = this.defaultViewport;
        }
        return viewport;
    }

    //Secondary render targets (camera mods like Vista) render at a different size than the
    // main window. Handing them the default viewport would resize its depth/HiZ buffers on
    // every alternation, wiping occlusion data and making the LOD flicker; key extra
    // viewports by target size instead so each size keeps stable buffers.
    public T getViewportForSize(int width, int height) {
        T viewport = this.getViewport();
        //An uninitialised (0-sized) default viewport must still be the main viewport; the main
        // camera is the only pass allowed to touch the shared iris depth framebuffer. If the
        // first setupViewport call created a size-keyed extra viewport here, the main camera
        // would run on a non-main viewport and skip its LOD pipeline entirely.
        if (viewport.width <= 0 || (viewport.width == width && viewport.height == height)) {
            return viewport;
        }
        return this.getOrCreate((long) width << 32 | (height & 0xFFFFFFFFL));
    }

    public void free() {
        this.defaultViewport.delete();
        this.extraViewports.values().forEach(Viewport::delete);
        this.extraViewports.clear();
    }
}
