package me.cortex.voxy.common.world;

import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SectionAvailabilityTest {
    @Test void coarseAirRequiresEveryContributingSubsectionAndCoverageSurvivesReload() {
        var section = WorldSection._createRawUntrackedUnsafeSection(2, -1, 0, -1);
        for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
            boolean complete = section.markKnownOctant(x, y, z);
            assertEquals(x == 3 && y == 3 && z == 3, complete);
            assertFalse(section.markKnownOctant(x, y, z), "duplicates must not advance completeness");
        }
        assertTrue(section.isVoxelKnown(15, 15, 15));
        assertFalse(section.isVoxelKnown(16, 15, 15));
        var restored = WorldSection._createRawUntrackedUnsafeSection(2, -1, 0, -1);
        assertTrue(SaveLoadSystem3.deserialize(restored, SaveLoadSystem3.serialize(section)));
        assertTrue(restored.isVoxelKnown(15, 15, 15));
        assertFalse(restored.isVoxelKnown(16, 15, 15));
    }

    @Test void dirtyConsumersCoexistAndDiskAndSecondaryLoadsNotifyWithoutFabricatingAir() {
        var storage = new SectionSerializationStorage(new MemoryStorageBackend());
        var engine = new WorldEngine(storage);
        var changes = new ArrayList<Long>();
        AtomicInteger rendered = new AtomicInteger();
        engine.addDataListener(changes::add);
        engine.setDirtyCallback((section, flags, neighbours) -> rendered.incrementAndGet());
        try {
            assertNull(engine.acquireIfExists(0, -1, 0, 0));
            var fabricated = engine.acquire(0, -1, 0, 0);
            fabricated.release();
            assertTrue(changes.isEmpty(), "missing/LRU fabricated air is not a successful data load");
            var disk = WorldSection._createRawUntrackedUnsafeSection(0, 4, 0, 0);
            disk.setUniform(Mapper.AIR);
            disk.setKnownOctants(255);
            storage.saveSection(disk);
            var loaded = engine.acquireIfExists(disk.key);
            assertNotNull(loaded);
            assertEquals(java.util.List.of(disk.key), changes);
            loaded.release();
            engine.acquireIfExists(disk.key).release();
            assertEquals(2, changes.size(), "secondary-cache reuse is also availability");
            var changed = engine.acquire(0, 5, 0, 0);
            engine.markDirty(changed, WorldEngine.UPDATE_TYPE_BLOCK_BIT | WorldEngine.UPDATE_TYPE_DONT_SAVE, 0);
            changed.release();
            assertEquals(1, rendered.get());
            assertEquals(3, changes.size());
        } finally { engine.free(); }
    }

    @Test void confirmedAirOctantsSurviveSerializationAndLegacyHeadersStayUnknown() {
        var section = WorldSection._createRawUntrackedUnsafeSection(0, -1, 0, -1);
        section.setUniform(Mapper.AIR);
        assertTrue(section.markKnownOctant(-1, 0, -1));
        assertFalse(section.markKnownOctant(-1, 0, -1));
        assertTrue(section.isVoxelKnown(31, 0, 31));
        assertFalse(section.isVoxelKnown(31, 16, 31));
        var bytes = SaveLoadSystem3.serialize(section);
        var reloaded = WorldSection._createRawUntrackedUnsafeSection(0, -1, 0, -1);
        assertTrue(SaveLoadSystem3.deserialize(reloaded, bytes));
        assertEquals(section.getKnownOctants(), reloaded.getKnownOctants());
        // The same payload with the historic header layout is still accepted,
        // but none of its air becomes proof of a completed column.
        MemoryUtil.memPutLong(bytes.address + 8, MemoryUtil.memGetLong(bytes.address + 8) & 0xFFFFFFL);
        assertTrue(SaveLoadSystem3.deserialize(reloaded, bytes));
        assertEquals(0, reloaded.getKnownOctants());
    }

    @Test void unchangedAirIngestionStillPublishesCoverageForGenerationAndImport() {
        var storage = new SectionSerializationStorage(new MemoryStorageBackend());
        var engine = new WorldEngine(storage);
        engine.setSaveCallback((world, section, nonBlocking, acquired) -> {
            storage.saveSection(section);
            section.setNotDirty();
            return false;
        });
        var notifications = new ArrayList<Long>();
        engine.addDataListener(notifications::add);
        try {
            WorldUpdater.insertUpdate(engine, VoxelizedSection.createEmpty().setPosition(-1, 2, -1).uniformAir(Mapper.airWithLight(15)));
            long key = WorldEngine.getWorldSectionId(0, -1, 1, -1);
            assertTrue(notifications.contains(key));
            var loaded = engine.acquire(key);
            try { assertTrue(loaded.isVoxelKnown(31, 0, 31)); }
            finally { loaded.release(); }
        } finally { engine.free(); }
    }
}
