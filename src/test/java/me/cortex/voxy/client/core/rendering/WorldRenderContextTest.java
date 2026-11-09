package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.compat.vista.VistaCompat;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldRenderContextTest {
    @Test void nestedExceptionRestoresParentAndCapturesMatricesByValue() {
        var projection = new Matrix4f().perspective(1.2f, 2, .05f, 1024);
        try (var main = new WorldRenderContext(null, false, projection, new Matrix4f(), 1, 2, 3)) {
            projection.identity();
            assertNotEquals(projection, main.projection);
            assertFalse(main.secondary);
            assertThrows(IllegalArgumentException.class, () -> {
                try (var feed = new WorldRenderContext(null, true, projection, new Matrix4f(), 100, 90, 80)) {
                    assertSame(feed, WorldRenderContext.current());
                    assertTrue(feed.secondary);
                    throw new IllegalArgumentException("simulated render failure");
                }
            });
            assertSame(main, WorldRenderContext.current());
            assertEquals(1, main.x);
        }
        assertNull(WorldRenderContext.current());
    }

    @Test void matrixHistoryIsPerViewAndDoesNotAdvanceOnUniformReads() {
        var main = new ViewHistory();
        var feed = new ViewHistory();
        var p = new Matrix4f().perspective(1.2f, 2, .05f, 1024);
        main.update(p, new Matrix4f().rotateY(.2f), false);
        assertEquals(main.modelView, main.previousModelView);
        Matrix4f oldMain = new Matrix4f(main.modelView);
        main.update(p, new Matrix4f().rotateY(.3f), false);
        feed.update(p, new Matrix4f().rotateY(2f), false);
        feed.update(p, new Matrix4f().rotateY(2.1f), false);
        assertEquals(oldMain, main.previousModelView);
        assertNotEquals(feed.previousModelView, main.previousModelView);
        assertEquals(oldMain, main.previousModelView);
        feed.update(p, new Matrix4f().rotateY(3f), true);
        assertEquals(feed.modelView, feed.previousModelView);
    }

    @Test void vistaAliasUsesRealProgramsWithoutChangingOtherDimensions() {
        assertEquals("overworld", VistaCompat.shaderDimension("vista_live_feed_overworld"));
        assertEquals("the_nether", VistaCompat.shaderDimension("vista_live_feed_the_nether"));
        assertEquals("the_end", VistaCompat.shaderDimension("vista_live_feed_the_end"));
        assertEquals("custom_world", VistaCompat.shaderDimension("custom_world"));
    }
}
