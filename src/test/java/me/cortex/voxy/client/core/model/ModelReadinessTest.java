package me.cortex.voxy.client.core.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.*;

class ModelReadinessTest {
    private static ModelReadiness models() {
        int[] blocks = new int[16], fluids = new int[16];
        Arrays.fill(blocks, -1); Arrays.fill(fluids, -1);
        return new ModelReadiness(blocks, fluids);
    }
    @Test void sharedModelsPublishOneNotificationForEachBlockState() {
        var models = models(); var notices = new ArrayList<Long>();
        models.addListener(notices::add);
        models.publishBlock(1, 7); models.publishBlock(2, 7);
        assertEquals(7, models.block(1)); assertEquals(7, models.block(2));
        assertEquals(java.util.List.of(1L, 2L), notices);
    }
    @Test void fluidCompletionAndAirFallbackBothMakeTheirDependenciesReady() {
        var models = models();
        assertFalse(models.ready(ModelReadiness.fluidDependency(3)));
        models.publishFluid(3, 4);
        assertTrue(models.ready(ModelReadiness.fluidDependency(3)));
        assertFalse(models.ready(ModelReadiness.blockDependency(3)));
        models.publishBlock(3, 0);
        assertTrue(models.ready(ModelReadiness.blockDependency(3)));
        assertEquals(0, models.block(3));
    }
    @Test void notificationObservesPublishedMappingAndRemovingListenerStopsDelivery() {
        var models = models(); var count = new java.util.concurrent.atomic.AtomicInteger();
        LongConsumer listener = id -> { assertTrue(models.ready(id)); count.incrementAndGet(); };
        models.addListener(listener); models.publishBlock(1, 2);
        models.removeListener(listener); models.publishBlock(2, 3);
        assertEquals(1, count.get());
    }
}
