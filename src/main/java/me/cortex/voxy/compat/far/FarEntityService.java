package me.cortex.voxy.compat.far;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.compat.far.FarEntityProtocol.Hello;
import me.cortex.voxy.compat.far.FarEntityProtocol.ItemSnapshot;
import me.cortex.voxy.compat.far.FarEntityProtocol.PlayerBatch;
import me.cortex.voxy.compat.far.FarEntityProtocol.PlayerSnapshot;
import me.cortex.voxy.compat.far.FarEntityProtocol.PlayersPayload;
import me.cortex.voxy.compat.far.FarEntityProtocol.VehicleSnapshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FarEntityService {
    private static final int UPDATE_INTERVAL_TICKS = 10;
    private static final int MAX_DISTANCE_BLOCKS = 32768;
    private final Map<UUID, ClientSettings> subscribers = new ConcurrentHashMap<>();
    private final Map<UUID, CachedVehicleData> vehicleDataCache =
            new LinkedHashMap<>(32, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, CachedVehicleData> eldest) {
                    return this.size() > 1024;
                }
            };
    private static final java.util.Set<String> VEHICLE_DATA_WARNED = ConcurrentHashMap.newKeySet();
    private int tickCounter;

    public void handleHello(ServerPlayer player, Hello hello) {
        if (hello.version() != FarEntityProtocol.VERSION
                || !player.connection.hasChannel(PlayersPayload.TYPE)) {
            this.subscribers.remove(player.getUUID());
            return;
        }
        this.subscribers.put(player.getUUID(), new ClientSettings(
                hello.enabled(),
                hello.includeVehicles(),
                Math.clamp(hello.maximumDistanceBlocks(), 64, MAX_DISTANCE_BLOCKS),
                hello.shareSelf()
        ));
    }

    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        this.subscribers.remove(event.getEntity().getUUID());
    }

    public void onServerTick(ServerTickEvent.Post event) {
        this.tick(event.getServer());
    }

    private void tick(MinecraftServer server) {
        if (this.subscribers.isEmpty() || ++this.tickCounter < UPDATE_INTERVAL_TICKS) {
            return;
        }
        this.tickCounter = 0;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        Map<UUID, PlayerSnapshot> playerOnlyCache = new HashMap<>(players.size());
        Map<UUID, PlayerSnapshot> vehicleCache = new HashMap<>(players.size());
        for (ServerPlayer viewer : players) {
            ClientSettings settings = this.subscribers.get(viewer.getUUID());
            if (settings != null && settings.enabled() && viewer.connection.hasChannel(PlayersPayload.TYPE)) {
                this.sendSnapshot(viewer, players, settings,
                        settings.includeVehicles() ? vehicleCache : playerOnlyCache);
            }
        }
    }

    private void sendSnapshot(ServerPlayer viewer, List<ServerPlayer> onlinePlayers, ClientSettings settings,
                              Map<UUID, PlayerSnapshot> snapshotCache) {
        int maximumDistance = settings.maximumDistanceBlocks();
        double maximumDistanceSquared = (double) maximumDistance * maximumDistance;
        List<PlayerSnapshot> snapshots = new ArrayList<>(Math.max(0, onlinePlayers.size() - 1));
        for (ServerPlayer target : onlinePlayers) {
            if (target == viewer || target.level() != viewer.level()) {
                continue;
            }
            if (!target.isAlive() || target.isRemoved() || target.isSpectator() || target.isInvisible()) {
                continue;
            }
            if (viewer.distanceToSqr(target) > maximumDistanceSquared) {
                continue;
            }
            ClientSettings targetSettings = this.subscribers.get(target.getUUID());
            if (targetSettings != null && !targetSettings.shareSelf()) {
                continue;
            }

            PlayerSnapshot snapshot = snapshotCache.get(target.getUUID());
            if (snapshot == null) {
                snapshot = snapshot(target, settings.includeVehicles());
                snapshotCache.put(target.getUUID(), snapshot);
            }
            snapshots.add(settings.prepare(target.getUUID(), snapshot));
        }

        PlayerBatch batch = new PlayerBatch(
                viewer.level().dimension().location().toString(),
                snapshots
        );
        PacketDistributor.sendToPlayer(viewer, new PlayersPayload(batch));
    }

    private PlayerSnapshot snapshot(ServerPlayer target, boolean includeVehicle) {
        return new PlayerSnapshot(
                target.getUUID(),
                target.getGameProfile().getName(),
                target.getX(), target.getY(), target.getZ(),
                target.getYRot(), target.getYHeadRot(), target.getXRot(),
                target.isShiftKeyDown(), target.isFallFlying(), target.isSwimming(),
                item(target.getMainHandItem()),
                item(target.getOffhandItem()),
                item(target.getItemBySlot(EquipmentSlot.FEET)),
                item(target.getItemBySlot(EquipmentSlot.LEGS)),
                item(target.getItemBySlot(EquipmentSlot.CHEST)),
                item(target.getItemBySlot(EquipmentSlot.HEAD)),
                includeVehicle ? vehicle(target.getVehicle()) : null
        );
    }

    private static ItemSnapshot item(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemSnapshot.EMPTY;
        }
        return new ItemSnapshot(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount()
        );
    }

    private VehicleSnapshot vehicle(Entity entity) {
        if (entity == null) {
            return null;
        }
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String typeName = typeId == null ? "" : typeId.toString();
        byte[] renderData = typeId == null || "minecraft".equals(typeId.getNamespace())
                ? new byte[0] : this.vehicleRenderData(entity, typeName);
        return new VehicleSnapshot(
                entity.getUUID(),
                entity.getId(),
                typeName,
                entity.getX(), entity.getY(), entity.getZ(),
                entity.getYRot(), entity.getXRot(),
                renderData
        );
    }

    private byte[] vehicleRenderData(Entity entity, String typeName) {
        long now = entity.level().getGameTime();
        CachedVehicleData cached = this.vehicleDataCache.get(entity.getUUID());
        if (cached != null && cached.typeId().equals(typeName) && now < cached.refreshAfter()) {
            return cached.data();
        }
        byte[] result = new byte[0];
        try {
            CompoundTag tag = entity.saveWithoutId(new CompoundTag());
            // Position and simulation state are supplied separately and are either useless to a
            // renderer or disproportionately large. Mod-specific identity/model data is retained.
            tag.remove("Pos");
            tag.remove("Motion");
            tag.remove("Rotation");
            tag.remove("UUID");
            tag.remove("Passengers");
            tag.remove("Leash");
            tag.remove("Brain");
            ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
            NbtIo.writeCompressed(tag, output);
            byte[] encoded = output.toByteArray();
            if (encoded.length <= FarEntityProtocol.MAX_VEHICLE_DATA_BYTES) {
                result = encoded;
            } else if (VEHICLE_DATA_WARNED.add(typeName)) {
                Logger.warn("Skipping oversized far-vehicle render data for", typeName,
                        encoded.length, "bytes");
            }
        } catch (Throwable throwable) {
            if (VEHICLE_DATA_WARNED.add(typeName)) {
                Logger.warn("Could not capture far-vehicle render data for", typeName, throwable);
            }
        }
        this.vehicleDataCache.put(entity.getUUID(),
                new CachedVehicleData(typeName, result, now + 200L));
        return result;
    }

    private static final class ClientSettings {
        private final boolean enabled;
        private final boolean includeVehicles;
        private final int maximumDistanceBlocks;
        private final boolean shareSelf;
        private final Map<UUID, DeliveredVehicleData> deliveredVehicleData = new HashMap<>();

        private ClientSettings(boolean enabled, boolean includeVehicles,
                               int maximumDistanceBlocks, boolean shareSelf) {
            this.enabled = enabled;
            this.includeVehicles = includeVehicles;
            this.maximumDistanceBlocks = maximumDistanceBlocks;
            this.shareSelf = shareSelf;
        }

        boolean enabled() { return this.enabled; }
        boolean includeVehicles() { return this.includeVehicles; }
        int maximumDistanceBlocks() { return this.maximumDistanceBlocks; }
        boolean shareSelf() { return this.shareSelf; }

        PlayerSnapshot prepare(UUID playerUuid, PlayerSnapshot snapshot) {
            VehicleSnapshot vehicle = snapshot.vehicle();
            if (vehicle == null) {
                this.deliveredVehicleData.remove(playerUuid);
                return snapshot;
            }
            DeliveredVehicleData delivered = new DeliveredVehicleData(
                    vehicle.uuid(), vehicle.entityTypeId(), Arrays.hashCode(vehicle.renderData()));
            DeliveredVehicleData previous = this.deliveredVehicleData.put(playerUuid, delivered);
            return delivered.equals(previous)
                    ? snapshot.withVehicle(vehicle.withoutRenderData())
                    : snapshot;
        }
    }

    private record DeliveredVehicleData(UUID uuid, String typeId, int renderDataHash) {
    }

    private record CachedVehicleData(String typeId, byte[] data, long refreshAfter) {
    }
}
