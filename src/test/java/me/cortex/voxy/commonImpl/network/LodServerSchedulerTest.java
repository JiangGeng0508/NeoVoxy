package me.cortex.voxy.commonImpl.network;

import io.netty.buffer.Unpooled;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class LodServerSchedulerTest {
    static final String DIM = "minecraft:overworld";
    static LodUpdateQueue.Key key(int x) {
        return new LodUpdateQueue.Key(DIM, WorldEngine.getWorldSectionId(0, x, 0, 0));
    }
    static VoxyNetwork.SectionRequestC2S request(int x) {
        return new VoxyNetwork.SectionRequestC2S(DIM, 0, x, 0, 0);
    }
    static LodServerScheduler.View view(UUID id) { return new LodServerScheduler.View(id, DIM, 0, 0); }

    static class Transport implements LodServerScheduler.Transport {
        final Map<UUID, List<CustomPacketPayload>> sent = new HashMap<>();
        final Set<UUID> blocked = new HashSet<>();
        int mappings = 2, bytes = 65560, revision = 1, loads;
        boolean failSend, failLoad;
        Consumer<LodUpdateQueue.Key> onLoad = k -> {};
        public boolean writable(UUID id) { return !blocked.contains(id); }
        public LodServerScheduler.Mappings nextMappings(UUID id, int block, int biome, int credit) {
            if (block < mappings) return new LodServerScheduler.Mappings(
                    new VoxyNetwork.BlockMapBatchS2C(block, List.of(new byte[]{(byte) block})), block + 1, biome);
            return null;
        }
        public VoxyNetwork.SectionDataS2C load(LodUpdateQueue.Key key) {
            loads++;
            onLoad.accept(key);
            if (failLoad) return null;
            var data = new byte[bytes];
            if (bytes > 0) data[0] = (byte) revision;
            return new VoxyNetwork.SectionDataS2C(key.dimension(), WorldEngine.getLevel(key.section()),
                    WorldEngine.getX(key.section()), WorldEngine.getY(key.section()), WorldEngine.getZ(key.section()), data);
        }
        public boolean send(UUID id, CustomPacketPayload payload) {
            if (failSend) return false;
            sent.computeIfAbsent(id, k -> new ArrayList<>()).add(payload);
            return true;
        }
        List<VoxyNetwork.SectionDataS2C> sections(UUID id) {
            return sent.getOrDefault(id, List.of()).stream().filter(VoxyNetwork.SectionDataS2C.class::isInstance)
                    .map(VoxyNetwork.SectionDataS2C.class::cast).toList();
        }
    }

    @Test void requestsAreBoundedDeduplicatedAndValidateDimensionRangeAndCoordinates() {
        var s = new LodServerScheduler();
        var id = UUID.randomUUID();
        assertFalse(s.request(id, request(0)));
        s.hello(view(id));
        for (int x = 0; x < 512; x++) assertTrue(s.request(id, request(x)));
        assertTrue(s.request(id, request(0)));
        assertFalse(s.request(id, request(512)));
        assertEquals(512, s.stats().requests());
        assertEquals(1, s.stats().rejectedRequests());
        assertFalse(s.request(id, new VoxyNetwork.SectionRequestC2S("minecraft:the_nether", 0, 0, 0, 0)));
        assertFalse(s.request(id, request(1025)));
        for (int level : new int[]{-1, WorldEngine.MAX_LOD_LAYER + 1, 32})
            assertFalse(s.request(id, new VoxyNetwork.SectionRequestC2S(DIM, level, 0, 0, 0)));
        assertFalse(s.request(id, new VoxyNetwork.SectionRequestC2S(DIM, 0, 0, Integer.MAX_VALUE, 0)));
        // Coarse sections intersecting the radius are retained, including at negative coordinates.
        assertTrue(view(id).contains(new LodUpdateQueue.Key(DIM, WorldEngine.getWorldSectionId(4, -65, 0, 0))));
        assertFalse(view(id).contains(new LodUpdateQueue.Key(DIM, WorldEngine.getWorldSectionId(4, -66, 0, 0))));
        s.hello(view(id));
        assertEquals(512, s.stats().requests());
        s.updateView(new LodServerScheduler.View(id, "minecraft:the_nether", 0, 0));
        assertEquals(0, s.stats().requests());
        s.logout(id);
        assertEquals(0, s.stats().players());
    }

    @Test void mappingsPrecedeSnapshotsIncludingIdsRegisteredDuringSerialization() {
        var s = new LodServerScheduler();
        var t = new Transport();
        var id = UUID.randomUUID();
        s.hello(view(id));
        s.request(id, request(0));
        t.onLoad = k -> t.mappings = 3;
        s.tick(t);
        var packets = t.sent.get(id);
        assertInstanceOf(VoxyNetwork.HelloS2C.class, packets.getFirst());
        assertEquals(3, packets.stream().filter(VoxyNetwork.BlockMapBatchS2C.class::isInstance).count());
        assertEquals(1, t.sections(id).size());
        assertInstanceOf(VoxyNetwork.SectionDataS2C.class, packets.getLast());
        assertTrue(t.loads >= 2, "snapshot with new ids must wait for its mapping");
    }

    @Test void slowPeerFailuresAndConcurrentDirtiesRetainLatestWork() {
        var s = new LodServerScheduler();
        var t = new Transport();
        var slow = UUID.randomUUID();
        var fast = UUID.randomUUID();
        s.hello(view(slow)); s.hello(view(fast));
        t.blocked.add(slow);
        s.dirty(key(0));
        t.onLoad = k -> { if (t.revision == 1) { t.revision = 2; s.dirty(k); } };
        s.tick(t);
        assertFalse(t.sections(fast).isEmpty());
        assertTrue(t.sections(slow).isEmpty());
        assertEquals(1, s.updates.stats().pending());
        t.blocked.clear(); t.failSend = true;
        s.tick(t);
        assertEquals(1, s.updates.stats().pending());
        t.failSend = false; t.failLoad = true;
        s.tick(t);
        assertEquals(1, s.updates.stats().pending());
        t.failLoad = false;
        s.tick(t);
        assertEquals(2, t.sections(slow).getLast().data()[0]);
        assertEquals(0, s.updates.stats().pending());
    }

    @Test void emptyReplyCompletesPullAndLeavingRangeDiscardsOnlyUnneededWork() {
        var s = new LodServerScheduler();
        var t = new Transport(); t.bytes = 0;
        var id = UUID.randomUUID(); s.hello(view(id));
        s.request(id, request(1)); s.tick(t);
        assertEquals(0, t.sections(id).getFirst().data().length);
        assertEquals(0, s.stats().requests());
        s.request(id, request(2)); s.dirty(key(3));
        s.updateView(new LodServerScheduler.View(id, DIM, 100000, 100000));
        s.tick(t);
        assertEquals(0, s.stats().requests());
        assertEquals(0, s.updates.stats().pending());
        assertEquals(1, t.sections(id).size());
        s.close(); s.dirty(key(4));
        assertEquals(0, s.updates.stats().pending());
    }

    @Test void multiplayerLoadSharesPacketAndByteBudgetsAndEventuallyDeliversFinalVersions() {
        var s = new LodServerScheduler();
        var t = new Transport(); t.bytes = 327696; // Maximum SaveLoadSystem3 snapshot.
        var players = new ArrayList<UUID>();
        for (int i = 0; i < 12; i++) {
            var id = new UUID(0, i); players.add(id); s.hello(view(id));
            for (int x = 0; x < 24; x++) s.request(id, request(x));
        }
        for (int x = 24; x < 48; x++) s.dirty(key(x));
        boolean reachedByteLimit = false;
        for (int tick = 0; tick < 250; tick++) {
            var before = new HashMap<UUID, Integer>();
            for (var id : players) before.put(id, t.sent.getOrDefault(id, List.of()).size());
            s.tick(t);
            assertTrue(s.stats().lastPackets() <= LodServerScheduler.PACKETS_PER_TICK);
            assertTrue(s.stats().lastBytes() <= LodServerScheduler.BYTES_PER_TICK);
            reachedByteLimit |= s.stats().lastBytes() > 1_900_000;
            for (var id : players) {
                var all = t.sent.getOrDefault(id, List.of());
                var added = all.subList(before.get(id), all.size());
                assertTrue(added.size() <= LodServerScheduler.PLAYER_PACKETS_PER_TICK);
                assertTrue(added.stream().mapToInt(VoxyNetwork::encodedSize).sum() <= LodServerScheduler.PLAYER_BYTES_PER_TICK);
            }
            if (s.stats().requests() == 0 && s.updates.stats().pending() == 0) break;
        }
        assertTrue(reachedByteLimit);
        for (var id : players) assertEquals(48, t.sections(id).stream().map(VoxyNetwork.SectionDataS2C::x).distinct().count());
        assertEquals(0, s.stats().requests()); assertEquals(0, s.updates.stats().pending());
    }

    @Test void byteAccountingMatchesActualWireCodecs() {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var hello = new VoxyNetwork.HelloS2C(-1);
            VoxyNetwork.HelloS2C.CODEC.encode(buf, hello); checkSize(buf, hello);
            var blocks = new VoxyNetwork.BlockMapBatchS2C(128, List.of(new byte[128], new byte[0]));
            VoxyNetwork.BlockMapBatchS2C.CODEC.encode(buf, blocks); checkSize(buf, blocks);
            var biomes = new VoxyNetwork.BiomeMapBatchS2C(256, List.of("example:林地", DIM));
            VoxyNetwork.BiomeMapBatchS2C.CODEC.encode(buf, biomes); checkSize(buf, biomes);
            var delta = VoxyNetwork.MappingDeltaS2C.block(65535, new byte[1024]);
            VoxyNetwork.MappingDeltaS2C.CODEC.encode(buf, delta); checkSize(buf, delta);
            var section = new VoxyNetwork.SectionDataS2C(DIM, 4, -17, -8, 300, new byte[327696]);
            VoxyNetwork.SectionDataS2C.CODEC.encode(buf, section); checkSize(buf, section);
        } finally { buf.release(); }
    }
    static void checkSize(RegistryFriendlyByteBuf buf, CustomPacketPayload payload) {
        buf.writeResourceLocation(payload.type().id());
        assertEquals(buf.readableBytes() + 16, VoxyNetwork.encodedSize(payload));
        buf.clear();
    }
}
