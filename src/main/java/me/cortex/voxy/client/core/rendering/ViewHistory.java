package me.cortex.voxy.client.core.rendering;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Advances once per world render, never when a uniform happens to be queried. */
public final class ViewHistory {
    public final Matrix4f projection = new Matrix4f(), modelView = new Matrix4f(), viewProjection = new Matrix4f();
    public final Matrix4f previousProjection = new Matrix4f(), previousModelView = new Matrix4f(), previousViewProjection = new Matrix4f();
    private boolean valid;

    public void update(Matrix4fc projection, Matrix4fc modelView, boolean reset) {
        previousProjection.set(this.projection);
        previousModelView.set(this.modelView);
        previousViewProjection.set(this.viewProjection);
        this.projection.set(projection);
        this.modelView.set(modelView);
        this.viewProjection.set(projection).mul(modelView);
        if (!valid || reset) {
            previousProjection.set(this.projection);
            previousModelView.set(this.modelView);
            previousViewProjection.set(this.viewProjection);
        }
        valid = true;
    }
}
