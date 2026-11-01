package me.cortex.voxy.commonImpl.network;

import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Server-thread scheduler. A packet is charged once per recipient, including mapping batches. */
public final class LodServerScheduler {
    public static final int MAX_UPDATES = 2048;
    public static final int MAX_REQUESTS_PER_PLAYER = 512;
    public static final int PACKETS_PER_TICK = 32;
    public static final int BYTES_PER_TICK = 2 << 20;
    public static final int PLAYER_PACKETS_PER_TICK = 8;
    public static final int PLAYER_BYTES_PER_TICK = 512 << 10;
    public static final int RANGE_BLOCKS = LodViewDistance.MAX_BLOCKS;

    public record View(UUID player, String dimension, double x, double z, int radiusBlocks) {
        public View { radiusBlocks = LodViewDistance.clamp(radiusBlocks); }
        public View(UUID player, String dimension, double x, double z) { this(player, dimension, x, z, RANGE_BLOCKS); }
        View withRadius(int radius) { return new View(player, dimension, x, z, radius); }
        public boolean contains(LodUpdateQueue.Key key) {
            return this.dimension.equals(key.dimension())
                    && LodViewDistance.contains(key.section(), this.x, this.z, this.radiusBlocks);
        }
    }

    /** nextMappings returns null only when both cursors are current. Data is copied, never borrowed. */
    public interface Transport {
        boolean writable(UUID player);
        Mappings nextMappings(UUID player, int blockCursor, int biomeCursor, int maxBytes);
        VoxyNetwork.SectionDataS2C load(LodUpdateQueue.Key key);
        /** The prepared snapshot must still belong to the current world/update version. */
        default boolean current(LodUpdateQueue.Key key, VoxyNetwork.SectionDataS2C data) { return true; }
        boolean send(UUID player, CustomPacketPayload payload);
    }
    /** A null payload means preparation is pending; null Mappings means cursors are current. */
    public record Mappings(CustomPacketPayload payload, int blockEnd, int biomeEnd) {}
    public record Stats(int players, int requests, int requestPeak, long rejectedRequests,
                        long packets, long bytes, int lastPackets, int lastBytes, long unwritable) {}
    private static final class Client {
        View view;
        final LinkedHashSet<LodUpdateQueue.Key> requests = new LinkedHashSet<>();
        final Set<LodUpdateQueue.Key> deferred = new HashSet<>();
        int blockCursor, biomeCursor, packets, bytes;
        int protocol = VoxyNetwork.PROTOCOL_VERSION;
        boolean hello = true, preferPush, syncing = true;
        Client(View view) { this.view = view; }
    }

    public final LodUpdateQueue updates;
    private final LinkedHashMap<UUID, Client> clients = new LinkedHashMap<>();
    private volatile List<View> views = List.of();
    private int rotation, requestPeak, lastPackets, lastBytes;
    private long rejectedRequests, packets, bytes, unwritable;

    public LodServerScheduler() { this(MAX_UPDATES); }
    public LodServerScheduler(int updateCapacity) { this.updates = new LodUpdateQueue(updateCapacity); }

    public void hello(View view) {
        this.hello(view.withRadius(RANGE_BLOCKS), VoxyNetwork.PROTOCOL_VERSION);
    }

    public void helloV2(View view) { this.hello(view, VoxyNetwork.VIEW_PROTOCOL_VERSION); }

    private void hello(View view, int protocol) {
        Client client = this.clients.get(view.player());
        if (client == null || !client.view.dimension().equals(view.dimension())) {
            this.logout(view.player());
            this.clients.put(view.player(), new Client(view));
        } else {
            // A repeated hello requests mapping repair; unsent pulls survive it.
            client.hello = true;
            if (!client.syncing) client.blockCursor = client.biomeCursor = 0;
            client.syncing = true;
            client.view = view;
        }
        this.clients.get(view.player()).protocol = protocol;
        this.publishViews();
    }

    public void resync(View view) {
        Client client = this.clients.get(view.player());
        if (client != null) this.hello(view.withRadius(client.view.radiusBlocks()), client.protocol);
    }

    public void viewDistance(UUID player, String dimension, int radiusBlocks) {
        Client client = this.clients.get(player);
        if (client == null || client.protocol != VoxyNetwork.VIEW_PROTOCOL_VERSION
                || !client.view.dimension().equals(dimension)) return;
        client.view = client.view.withRadius(radiusBlocks);
        this.publishViews();
    }

    public void updateView(View view) {
        Client client = this.clients.get(view.player());
        if (client == null) return;
        if (!client.view.dimension().equals(view.dimension())) this.resync(view);
        else client.view = view.withRadius(client.view.radiusBlocks());
    }

    public void logout(UUID player) {
        this.clients.remove(player);
        this.updates.forget(player);
        if (this.clients.isEmpty()) this.updates.clear();
        this.publishViews();
    }

    private void publishViews() { this.views = this.clients.values().stream().map(c -> c.view).toList(); }

    public boolean interested(LodUpdateQueue.Key key) {
        for (View view : this.views) if (view.contains(key)) return true;
        return false;
    }

    public void dirty(LodUpdateQueue.Key key) {
        if (this.interested(key)) this.updates.dirty(key);
    }

    public boolean request(UUID player, VoxyNetwork.SectionRequestC2S request) {
        Client client = this.clients.get(player);
        if (client == null || request.lvl() < 0 || request.lvl() > WorldEngine.MAX_LOD_LAYER) return false;
        long key;
        try {
            key = WorldEngine.getWorldSectionId(request.lvl(), request.x(), request.y(), request.z());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        if (WorldEngine.getX(key) != request.x() || WorldEngine.getY(key) != request.y()
                || WorldEngine.getZ(key) != request.z()) return false;
        var requested = new LodUpdateQueue.Key(request.dimension(), key);
        if (!client.view.contains(requested)) return false;
        if (client.requests.contains(requested)) return true;
        if (client.requests.size() >= MAX_REQUESTS_PER_PLAYER) {
            this.rejectedRequests++;
            return false; // No empty reply: the existing client timeout retries capacity refusals.
        }
        client.requests.add(requested);
        this.requestPeak = Math.max(this.requestPeak, client.requests.size());
        return true;
    }

    private Set<UUID> audience(LodUpdateQueue.Key key) {
        var players = new HashSet<UUID>();
        for (View view : this.views) if (view.contains(key)) players.add(view.player());
        return players;
    }

    public void tick(Transport transport) {
        this.publishViews();
        this.lastPackets = this.lastBytes = 0;
        var order = new ArrayList<>(this.clients.entrySet());
        if (order.isEmpty()) return;
        for (Client client : this.clients.values()) {
            client.packets = client.bytes = 0;
            client.deferred.clear();
            client.requests.removeIf(key -> !client.view.contains(key));
        }
        int start = Math.floorMod(this.rotation++, order.size());
        // Bounded attempts also bound preparation admission and ready-result lookups.
        for (int round = 0, attempts = 0; round < PLAYER_PACKETS_PER_TICK && attempts < PACKETS_PER_TICK; round++) {
            for (int offset = 0; offset < order.size() && attempts < PACKETS_PER_TICK; offset++) {
                if (this.lastPackets >= PACKETS_PER_TICK || this.lastBytes >= BYTES_PER_TICK) return;
                var entry = order.get((start + offset) % order.size());
                UUID player = entry.getKey();
                Client client = entry.getValue();
                int credit = Math.min(BYTES_PER_TICK - this.lastBytes, PLAYER_BYTES_PER_TICK - client.bytes);
                if (credit < 1024 || client.packets == PLAYER_PACKETS_PER_TICK) continue;
                if (!transport.writable(player)) { this.unwritable++; continue; }
                attempts++;
                try {
                if (client.hello) {
                    CustomPacketPayload hello = client.protocol == VoxyNetwork.VIEW_PROTOCOL_VERSION
                            ? new VoxyNetwork.HelloV2S2C(VoxyNetwork.VIEW_PROTOCOL_VERSION)
                            : new VoxyNetwork.HelloS2C(VoxyNetwork.PROTOCOL_VERSION);
                    if (this.send(transport, player, client, hello, credit)) client.hello = false;
                    continue;
                }
                Mappings mapping = transport.nextMappings(player, client.blockCursor, client.biomeCursor, credit);
                if (mapping != null) {
                    client.syncing = true;
                    if (mapping.payload() == null) continue;
                    if (this.send(transport, player, client, mapping.payload(), credit)) {
                        client.blockCursor = mapping.blockEnd();
                        client.biomeCursor = mapping.biomeEnd();
                    }
                    continue;
                }
                client.syncing = false;
                var push = this.updates.next(player, client.view::contains, this::audience, client.deferred);
                var pull = client.requests.stream().filter(k -> !client.deferred.contains(k)).findFirst().orElse(null);
                boolean pushing = push != null && (pull == null || client.preferPush);
                LodUpdateQueue.Key key = pushing ? push.key() : pull;
                if (key == null) continue;
                VoxyNetwork.SectionDataS2C data = transport.load(key);
                if (data == null) {
                    client.deferred.add(key);
                    continue; // Pending/failed preparation is never "missing terrain".
                }
                // Preparation can observe newly registered ids. Re-check before sending this snapshot.
                if (transport.nextMappings(player, client.blockCursor, client.biomeCursor, credit) != null) continue;
                if (!transport.current(key, data)) { client.deferred.add(key); continue; }
                if (this.send(transport, player, client, data, credit)) {
                    if (pushing) this.updates.complete(player, push);
                    else client.requests.remove(key);
                    client.preferPush = !pushing;
                } else {
                    client.deferred.add(key);
                }
                } catch (Exception e) {
                    // Keep work pending and isolate a failed peer/store from the other clients.
                    // Log at most once per player per tick.
                    client.packets = PLAYER_PACKETS_PER_TICK;
                    me.cortex.voxy.common.Logger.error("Voxy LOD dispatch failed for " + player, e);
                }
            }
        }
    }

    private boolean send(Transport transport, UUID player, Client client, CustomPacketPayload payload, int credit) {
        int size = VoxyNetwork.encodedSize(payload);
        if (size > credit || !transport.send(player, payload)) return false;
        this.lastPackets++; client.packets++; this.packets++;
        this.lastBytes += size; client.bytes += size; this.bytes += size;
        return true;
    }

    public Stats stats() {
        return new Stats(this.clients.size(), this.clients.values().stream().mapToInt(c -> c.requests.size()).sum(),
                this.requestPeak, this.rejectedRequests, this.packets, this.bytes, this.lastPackets, this.lastBytes, this.unwritable);
    }

    public void close() {
        this.clients.clear();
        this.views = List.of();
        this.updates.close();
    }
}
