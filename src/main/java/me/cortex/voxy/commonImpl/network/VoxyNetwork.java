package me.cortex.voxy.commonImpl.network;

import me.cortex.voxy.common.Logger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Voxy server -> client LOD sync protocol.
 *
 * <p>Design notes:
 * <ul>
 *   <li>The mod is optional: clients without voxy simply never send {@link HelloC2S},
 *       and the server only sends LOD data to players that did (tracked by UUID).</li>
 *   <li>Server is authoritative for voxel data, but block/biome id mappings are
 *       per-store local ids, so every section payload carries server-local ids and the
 *       client translates them via the mapping batches ({@link BlockMapBatchS2C} /
 *       {@link BiomeMapBatchS2C} / {@link MappingDeltaS2C}) received beforehand.</li>
 *   <li>Sections are pulled on demand: the client requests a section when its local
 *       store reports a miss, the server answers with {@link SectionDataS2C}. Live
 *       updates are pushed to all voxy clients in the same dimension.</li>
 * </ul>
 */
public final class VoxyNetwork {
    private VoxyNetwork() {}

    public static final int PROTOCOL_VERSION = 1;

    //Client bound handlers live in client-only code. Registered here via a setter so that
    // the common payload handler never forces client classes to load on a dedicated server.
    public interface ClientHandler {
        void handleHello(HelloS2C payload);
        void handleBlockMaps(BlockMapBatchS2C payload);
        void handleBiomeMaps(BiomeMapBatchS2C payload);
        void handleMappingDelta(MappingDeltaS2C payload);
        void handleSectionData(SectionDataS2C payload);
    }

    private static volatile ClientHandler clientHandler;

    public static void setClientHandler(ClientHandler handler) {
        clientHandler = handler;
    }

    // ---------- payloads ----------

    public record HelloC2S(int version) implements CustomPacketPayload {
        public static final Type<HelloC2S> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "hello_c2s"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HelloC2S> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeVarInt(p.version),
                buf -> new HelloC2S(buf.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record HelloS2C(int version) implements CustomPacketPayload {
        public static final Type<HelloS2C> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "hello_s2c"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HelloS2C> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeVarInt(p.version),
                buf -> new HelloS2C(buf.readVarInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record SectionRequestC2S(String dimension, int lvl, int x, int y, int z) implements CustomPacketPayload {
        public static final Type<SectionRequestC2S> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "sec_req"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SectionRequestC2S> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.dimension, 256);
                    buf.writeVarInt(p.lvl);
                    buf.writeInt(p.x);
                    buf.writeInt(p.y);
                    buf.writeInt(p.z);
                },
                buf -> new SectionRequestC2S(buf.readUtf(256), buf.readVarInt(), buf.readInt(), buf.readInt(), buf.readInt()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record BlockMapBatchS2C(int startId, List<byte[]> entries) implements CustomPacketPayload {
        public static final Type<BlockMapBatchS2C> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "block_map"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BlockMapBatchS2C> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.startId);
                    buf.writeVarInt(p.entries.size());
                    for (var e : p.entries) buf.writeByteArray(e);
                },
                buf -> {
                    int start = buf.readVarInt();
                    int n = buf.readVarInt();
                    var list = new ArrayList<byte[]>(Math.min(n, 4096));
                    for (int i = 0; i < n; i++) list.add(buf.readByteArray());
                    return new BlockMapBatchS2C(start, list);
                });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record BiomeMapBatchS2C(int startId, List<String> entries) implements CustomPacketPayload {
        public static final Type<BiomeMapBatchS2C> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "biome_map"));
        public static final StreamCodec<RegistryFriendlyByteBuf, BiomeMapBatchS2C> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.startId);
                    buf.writeVarInt(p.entries.size());
                    for (var e : p.entries) buf.writeUtf(e, 256);
                },
                buf -> {
                    int start = buf.readVarInt();
                    int n = buf.readVarInt();
                    var list = new ArrayList<String>(Math.min(n, 1024));
                    for (int i = 0; i < n; i++) list.add(buf.readUtf(256));
                    return new BiomeMapBatchS2C(start, list);
                });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Incremental mapping update for ids registered after the initial full sync. */
    public record MappingDeltaS2C(boolean isBlock, int id, byte[] data) implements CustomPacketPayload {
        public static final Type<MappingDeltaS2C> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "map_delta"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MappingDeltaS2C> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.isBlock);
                    buf.writeVarInt(p.id);
                    buf.writeByteArray(p.data);
                },
                buf -> new MappingDeltaS2C(buf.readBoolean(), buf.readVarInt(), buf.readByteArray()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public String biomeString() {
            return new String(this.data, java.nio.charset.StandardCharsets.UTF_8);
        }

        public static MappingDeltaS2C block(int id, byte[] nbt) {
            return new MappingDeltaS2C(true, id, nbt);
        }

        public static MappingDeltaS2C biome(int id, String biome) {
            return new MappingDeltaS2C(false, id, biome.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * Serialized LOD section. {@code data} is the raw {@code SaveLoadSystem3} serialized
     * bytes (server-local mapping ids in the LUT); an empty array means "no data".
     */
    public record SectionDataS2C(String dimension, int lvl, int x, int y, int z, byte[] data) implements CustomPacketPayload {
        public static final Type<SectionDataS2C> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "sec_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SectionDataS2C> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.dimension, 256);
                    buf.writeVarInt(p.lvl);
                    buf.writeInt(p.x);
                    buf.writeInt(p.y);
                    buf.writeInt(p.z);
                    buf.writeByteArray(p.data);
                },
                buf -> new SectionDataS2C(buf.readUtf(256), buf.readVarInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readByteArray()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---------- registration ----------

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(HelloC2S.TYPE, HelloC2S.CODEC, VoxyNetwork::handleHelloC2S);
        registrar.playToServer(SectionRequestC2S.TYPE, SectionRequestC2S.CODEC, VoxyNetwork::handleSectionRequest);
        registrar.playToClient(HelloS2C.TYPE, HelloS2C.CODEC, VoxyNetwork::handleHelloS2C);
        registrar.playToClient(BlockMapBatchS2C.TYPE, BlockMapBatchS2C.CODEC, VoxyNetwork::handleBlockMaps);
        registrar.playToClient(BiomeMapBatchS2C.TYPE, BiomeMapBatchS2C.CODEC, VoxyNetwork::handleBiomeMaps);
        registrar.playToClient(MappingDeltaS2C.TYPE, MappingDeltaS2C.CODEC, VoxyNetwork::handleMappingDelta);
        registrar.playToClient(SectionDataS2C.TYPE, SectionDataS2C.CODEC, VoxyNetwork::handleSectionData);
    }

    // ---------- server-bound handlers (run on logical server) ----------

    private static void handleHelloC2S(HelloC2S payload, IPayloadContext ctx) {
        VoxyServerNet.onClientHello(ctx, payload);
    }

    private static void handleSectionRequest(SectionRequestC2S payload, IPayloadContext ctx) {
        VoxyServerNet.onSectionRequest(ctx, payload);
    }

    // ---------- client-bound handlers (run on logical client, may not have the mod classes otherwise) ----------

    private static void dispatchClient(Consumer<ClientHandler> action) {
        var handler = clientHandler;
        if (handler == null) {
            return;
        }
        try {
            action.accept(handler);
        } catch (Exception e) {
            Logger.error("Voxy client network handler failed", e);
        }
    }

    private static void handleHelloS2C(HelloS2C payload, IPayloadContext ctx) {
        dispatchClient(h -> h.handleHello(payload));
    }

    private static void handleBlockMaps(BlockMapBatchS2C payload, IPayloadContext ctx) {
        dispatchClient(h -> h.handleBlockMaps(payload));
    }

    private static void handleBiomeMaps(BiomeMapBatchS2C payload, IPayloadContext ctx) {
        dispatchClient(h -> h.handleBiomeMaps(payload));
    }

    private static void handleMappingDelta(MappingDeltaS2C payload, IPayloadContext ctx) {
        dispatchClient(h -> h.handleMappingDelta(payload));
    }

    private static void handleSectionData(SectionDataS2C payload, IPayloadContext ctx) {
        dispatchClient(h -> h.handleSectionData(payload));
    }

    // ---------- send helpers ----------

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        try {
            PacketDistributor.sendToPlayer(player, payload);
        } catch (Exception e) {
            Logger.error("Voxy failed sending packet to player", e);
        }
    }

    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
}
