package me.cortex.voxy.client.core.rendering;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ViewportSelector <T extends Viewport<?>> {
    private final Supplier<T> creator;
    private final T defaultViewport;
    private final Map<Object, T> extraViewports = new HashMap<>();

    public ViewportSelector(Supplier<T> viewportCreator) {
        this.creator = viewportCreator;
        this.defaultViewport = viewportCreator.get();
        this.defaultViewport.isMainViewport = true;
    }

    private T getOrCreate(Object holder) {
        return this.extraViewports.computeIfAbsent(holder, a->this.creator.get());
    }

    public T getViewport() {
        return this.defaultViewport;
    }

    //Secondary render targets (camera mods like Vista, mirrors...) render at a different size than
    //the main window. Handing them the default viewport would resize its depth/HiZ buffers on every
    //alternation, wiping occlusion data and making the LOD flicker; key extra viewports by target
    //size instead so each size keeps stable buffers. An uninitialised (0-sized) default viewport
    //must stay the main viewport: only the main camera may touch the shared iris depth framebuffer.
    public T getViewportForSize(int width, int height) {
        T viewport = this.getViewport();
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
