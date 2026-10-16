package me.cortex.voxy.common.config.section;

import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SectionSerializationStorageTest {
    private static final long STONE = Mapper.composeMappingId((byte) 15, 1, 0);

    @Test
    void committedBatchesMakePreviouslyMissingSectionsReadable() {
        var storage = new SectionSerializationStorage(new MemoryStorageBackend());
        try (var batch = storage.createSaveBatch()) {
            // The saver reuses one batch for multiple commits. Each newly generated
            // section was first loaded as missing before its terrain was ingested.
            for (int x = 0; x < 3; x++) {
                var saved = section(x);
                assertEquals(1, storage.loadSection(saved));
                saved.setUniform(STONE);
                saved._unsafeSetNonEmptyChildren((byte) 0xFF);
                batch.add(saved);
                batch.commit();

                var reloaded = section(x);
                assertEquals(0, storage.loadSection(reloaded), "saved terrain must survive eviction from the active section cache");
                assertEquals(STONE, reloaded.get(0));
                assertEquals((byte) 0xFF, reloaded.getNonEmptyChildren());
                assertEquals(WorldSection.SECTION_VOLUME, reloaded.getNonEmptyBlockCount());
            }
        } finally {
            storage.close();
        }
    }

    @Test
    void aMissThatFinishesAfterASaveCannotHideTheSavedSection() {
        // Pause a backend miss after its read, then publish a save before that
        // miss is cached. This exercises the interleaving without timing a thread.
        var backend = new MemoryStorageBackend() {
            Runnable afterMiss;

            @Override
            public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
                var result = super.getSectionData(key, scratch);
                if (result == null && this.afterMiss != null) {
                    var callback = this.afterMiss;
                    this.afterMiss = null;
                    callback.run();
                }
                return result;
            }
        };
        var storage = new SectionSerializationStorage(backend);
        try {
            var saved = section(4);
            saved.setUniform(STONE);
            backend.afterMiss = () -> storage.saveSection(saved);
            assertEquals(1, storage.loadSection(section(4)));
            assertEquals(0, storage.loadSection(section(4)), "a stale miss must not overwrite the save's cache invalidation");
        } finally {
            storage.close();
        }
    }

    private static WorldSection section(int x) {
        return WorldSection._createRawUntrackedUnsafeSection(0, x, 2, -3);
    }
}
