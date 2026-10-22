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
    public static final int RANGE_BLOCKS = 32768; // Maximum supported client LoD distance (2048 chunks).

    public record View(UUID player, String dimension, double x, double z) {
        public boolean contains(LodUpdateQueue.Key key) {
            if (!this.dimension.equals(key.dimension())) return false;
            long width = 32L << WorldEngine.getLevel(key.section());
            long x0 = (long) WorldEngine.getX(key.section()) * width;
            long z0 = (long) WorldEngine.getZ(key.section()) * width;
            double dx = Math.max(0, Math.max(x0 - this.x, this.x - (x0 + width)));
            double dz = Math.max(0, Math.max(z0 - this.z, this.z - (z0 + width)));
            return dx * dx + dz * dz <= (double) RANGE_BLOCKS * RANGE_BLOCKS;
        }
    }

    /** nextMappings returns null only when both cursors are current. Data is copied, never borrowed. */
    public interface Transport {
        boolean writable(UUID player);
        Mappings nextMappings(UUID player, int blockCursor, int biomeCursor, int maxBytes);
        VoxyNetwork.SectionDataS2C load(LodUpdateQueue.Key key);
        boolean send(UUID player, CustomPacketPayload payload);
    }
    public record Mappings(CustomPacketPayload payload, int blockEnd, int biomeEnd) {}
    public record Stats(int players, int requests, int requestPeak, long rejectedRequests,
                        long packets, long bytes, int lastPackets, int lastBytes, long unwritable) {}
    private static final class Client {
        View view;
        final LinkedHashSet<LodUpdateQueue.Key> requests = new LinkedHashSet<>();
        int blockCursor, biomeCursor, packets, bytes;
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
        this.publishViews();
    }

    public void updateView(View view) {
        Client client = this.clients.get(view.player());
        if (client == null) return;
        if (!client.view.dimension().equals(view.dimension())) this.hello(view);
        else client.view = view;
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
            client.requests.removeIf(key -> !client.view.contains(key));
        }
        int start = Math.floorMod(this.rotation++, order.size());
        // Bounded attempts also bound disk reads/serialization when remaining byte credit is small.
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
                    if (this.send(transport, player, client, new VoxyNetwork.HelloS2C(VoxyNetwork.PROTOCOL_VERSION), credit)) client.hello = false;
                    continue;
                }
                Mappings mapping = transport.nextMappings(player, client.blockCursor, client.biomeCursor, credit);
                if (mapping != null) {
                    client.syncing = true;
                    if (this.send(transport, player, client, mapping.payload(), credit)) {
                        client.blockCursor = mapping.blockEnd();
                        client.biomeCursor = mapping.biomeEnd();
                    }
                    continue;
                }
                client.syncing = false;
                var push = this.updates.next(player, client.view::contains, this::audience);
                var pull = client.requests.isEmpty() ? null : client.requests.getFirst();
                boolean pushing = push != null && (pull == null || client.preferPush);
                LodUpdateQueue.Key key = pushing ? push.key() : pull;
                if (key == null) continue;
                VoxyNetwork.SectionDataS2C data = transport.load(key);
                if (data == null) continue; // Failed read is transient, never "missing terrain".
                // Serialization can observe newly registered ids. Re-check before sending this snapshot.
                if (transport.nextMappings(player, client.blockCursor, client.biomeCursor, credit) != null) continue;
                if (this.send(transport, player, client, data, credit)) {
                    if (pushing) this.updates.complete(player, push);
                    else client.requests.remove(key);
                    client.preferPush = !pushing;
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
