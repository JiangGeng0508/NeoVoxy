package me.cortex.voxy.client.compat.create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class KineticSnapshots {
    private KineticSnapshots() {}

    private static final int CAPTURES_PER_TICK = 64;
    private static int captureBudget;
    private static final int REBAKES_PER_TICK = 4;
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet PRIORITY_REBAKE =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    record Snap(net.minecraft.world.level.block.state.BlockState state,
                net.minecraft.core.Direction.Axis axis, float angleRad, int sky, int block,
                boolean bakeJson, net.minecraft.core.Direction shaftHalfFacing,
                net.minecraft.core.Direction bearingFacing, float bearingTopAngleRad, boolean woodenTop,
                float[][] chains, float[] bnbChain, float[] generic, boolean gantryCarriage) {

        boolean hollow() {
            return !this.bakeJson && this.shaftHalfFacing == null && this.bearingFacing == null
                    && this.chains == null && this.bnbChain == null && this.generic == null
                    && !this.gantryCarriage;
        }
    }

    private static final boolean BNB_LOADED = net.neoforged.fml.ModList.get().isLoaded("bits_n_bobs");

    private static volatile Thread captureThread = null;

    public static boolean isCapturingOnThisThread() {
        return captureThread == Thread.currentThread();
    }

    static final class Bucket {
        final Map<BlockPos, Snap> geoms = new HashMap<>();
        DistantMesh mesh;
        boolean dirty;

        void close() {
            if (this.mesh != null) {
                this.mesh.free();
                this.mesh = null;
            }
        }
    }

    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Bucket> SECTIONS =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final Queue<BlockPos> CAPTURE_QUEUE = new ConcurrentLinkedQueue<>();
    private static final Queue<BlockPos> REMOVE_QUEUE = new ConcurrentLinkedQueue<>();
    private static ResourceLocation dim;
    private static final java.util.Set<BlockPos> BEARING_POSITIONS = new java.util.HashSet<>();

    //Diagnostics for /voxy debug trains + /voxy debug kinetics
    public static volatile int sectionCount;
    public static volatile int snapshotCount;
    private static final java.util.ArrayDeque<String> RECENT_CAPTURES = new java.util.ArrayDeque<>();

    private static void logCapture(String line) {
        synchronized (RECENT_CAPTURES) {
            if (RECENT_CAPTURES.size() >= 10) {
                RECENT_CAPTURES.removeFirst();
            }
            RECENT_CAPTURES.addLast(line);
        }
    }

    private static int hollowIn(Bucket bucket) {
        int n = 0;
        for (Snap s : bucket.geoms.values()) {
            if (s.hollow()) n++;
        }
        return n;
    }

    public static String debugDump(double camX, double camY, double camZ) {
        var sb = new StringBuilder();
        sb.append("sections=").append(SECTIONS.size())
                .append(" snapshots=").append(snapshotCount)
                .append(" capQ=").append(CAPTURE_QUEUE.size())
                .append(" remQ=").append(REMOVE_QUEUE.size())
                .append(" sweepCursor=").append(sweepCursor)
                .append(" dim=").append(dim);
        sb.append("\nrecent captures:");
        synchronized (RECENT_CAPTURES) {
            if (RECENT_CAPTURES.isEmpty()) {
                sb.append(" <none>");
            }
            for (String line : RECENT_CAPTURES) {
                sb.append("\n  ").append(line);
            }
        }
        sb.append("\nbuckets within 96 blocks:");
        int shown = 0;
        for (var entry : SECTIONS.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            double ox = (BlockPos.getX(key) << 4) + 8, oy = (BlockPos.getY(key) << 4) + 8, oz = (BlockPos.getZ(key) << 4) + 8;
            double dx = ox - camX, dy = oy - camY, dz = oz - camZ;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > 96 && shown > 0) {
                continue;
            }
            var bucket = entry.getValue();
            sb.append("\n  section(").append(BlockPos.getX(key) << 4).append(',')
                    .append(BlockPos.getY(key) << 4).append(',').append(BlockPos.getZ(key) << 4)
                    .append(") dist=").append((int) dist)
                    .append(" entries=").append(bucket.geoms.size() - hollowIn(bucket))
                    .append(hollowIn(bucket) > 0 ? "+" + hollowIn(bucket) + "skip" : "")
                    .append(" mesh=").append(bucket.mesh != null ? "yes" : "NULL")
                    .append(" dirty=").append(bucket.dirty);
            if (++shown >= 12) {
                sb.append("\n  ... (").append(SECTIONS.size() - shown).append(" more)");
                break;
            }
        }
        return sb.toString();
    }

    public static void queueCapture(BlockPos pos) {
        if (me.cortex.voxy.client.compat.ShipBorne.isShipBorne(pos)) {
            return;
        }
        CAPTURE_QUEUE.add(pos.immutable());
    }

    //Called on return to the live path or when a fresh visual spawns for the position
    public static void queueRemove(BlockPos pos) {
        REMOVE_QUEUE.add(pos.immutable());
    }

    private static final java.util.Set<BlockPos> ANCHOR_RECAPTURED = new java.util.HashSet<>();
    private static boolean inManagerRecapture;

    public static void recaptureAt(BlockPos pos) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || me.cortex.voxy.client.compat.ShipBorne.isShipBorne(pos)) {
            return;
        }
        if (!(loadedBlockEntity(mc.level, pos) instanceof KineticBlockEntity kbe)) {
            return;
        }
        //Manager-driven: don't post the anchor back, or next tick's consume would re-refresh the
        //structure one tick after this capture and reintroduce the offset
        inManagerRecapture = true;
        try {
            capture(mc.level, kbe);
        } finally {
            inManagerRecapture = false;
        }
    }

    public static boolean consumeAnchorRecapture(BlockPos pos) {
        return ANCHOR_RECAPTURED.remove(pos);
    }


    private static void enforceGpuBudget(double camX, double camY, double camZ) {
        long budget = (long) VoxyConfig.CONFIG.distantKineticGpuBudgetMiB * 1024L * 1024L;
        if (budget <= 0) {
            return;
        }
        long resident = 0;
        for (var bucket : SECTIONS.values()) {
            if (bucket.mesh != null) {
                resident += bucket.mesh.gpuByteSize();
            }
        }
        long target = (budget * 9L) / 10L;
        while (resident > budget) {
            long furthestKey = 0;
            double furthestDistSq = -1;
            boolean found = false;
            for (var entry : SECTIONS.long2ObjectEntrySet()) {
                if (entry.getValue().mesh == null) {
                    continue;
                }
                long key = entry.getLongKey();
                double dx = ((BlockPos.getX(key) << 4) + 8) - camX;
                double dy = ((BlockPos.getY(key) << 4) + 8) - camY;
                double dz = ((BlockPos.getZ(key) << 4) + 8) - camZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > furthestDistSq) {
                    furthestDistSq = distSq;
                    furthestKey = key;
                    found = true;
                }
            }
            if (!found) {
                break;
            }
            var bucket = SECTIONS.remove(furthestKey);
            if (bucket == null) {
                break;
            }
            resident -= bucket.mesh == null ? 0 : bucket.mesh.gpuByteSize();
            for (BlockPos snapPos : bucket.geoms.keySet()) {
                BEARING_POSITIONS.remove(snapPos);
            }
            bucket.close();
            me.cortex.voxy.commonImpl.PerfStats.kineticSnapshotEvicted.increment();
            if (resident <= target) {
                break;
            }
        }
    }

    public static it.unimi.dsi.fastutil.longs.Long2ObjectMap<Bucket> sections() {
        return SECTIONS;
    }

    public static void clearAll() {
        for (Bucket bucket : SECTIONS.values()) {
            bucket.close();
        }
        SECTIONS.clear();
        CAPTURE_QUEUE.clear();
        REMOVE_QUEUE.clear();
        BEARING_POSITIONS.clear();
        ANCHOR_RECAPTURED.clear();
        PRIORITY_REBAKE.clear();
        KineticCull.cachedReachSq = -1;
        sectionCount = 0;
        snapshotCount = 0;
    }

    //Client tick (render thread): drain the transition queues, capture, and rebake dirty sections
    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantKinetics) {
            if (!SECTIONS.isEmpty()) {
                    clearAll();
            }
                return;
            }
            var levelDim = level.dimension().location();
            if (!levelDim.equals(dim)) {
                clearAll();
                dim = levelDim;
            }

            BlockPos pos;
            while ((pos = REMOVE_QUEUE.poll()) != null) {
                Bucket bucket = SECTIONS.get(sectionKey(pos));
                Snap removed = bucket == null ? null : bucket.geoms.remove(pos);
                if (removed != null && !removed.hollow()) {
                    bucket.dirty = true;
                    PRIORITY_REBAKE.add(sectionKey(pos));
            }
                BEARING_POSITIONS.remove(pos);
            }


            var cam = mc.gameRenderer.getMainCamera().getPosition();
            double reach = mc.options.getEffectiveRenderDistance() * 16.0;
            double reachSq = reach * reach;
            KineticCull.cachedReachSq = reachSq;
            captureBudget = CAPTURES_PER_TICK;
            while (captureBudget > 16 && (pos = CAPTURE_QUEUE.poll()) != null) {
                //Raced back inside the live path between the queue and this tick: the visual draws it
                if (pos.distToCenterSqr(cam.x, cam.y, cam.z) < reachSq) {
                    continue;
            }
                if (loadedBlockEntity(level, pos) instanceof KineticBlockEntity be) {
                    capture(level, be);
                    captureBudget--;
            }
            }

            sweep(mc, level, cam.x, cam.y, cam.z, reachSq);

            int rebaked = 0;
            var prio = PRIORITY_REBAKE.iterator();
            while (prio.hasNext() && rebaked < REBAKES_PER_TICK) {
                Bucket bucket = SECTIONS.get(prio.nextLong());
                prio.remove();
                if (bucket != null && bucket.dirty) {
                    bucket.dirty = false;
                    rebake(bucket);
                    rebaked++;
                }
            }
            for (Bucket bucket : SECTIONS.values()) {
                if (!bucket.dirty || rebaked >= REBAKES_PER_TICK) {
                    continue;
            }
                bucket.dirty = false;
                rebake(bucket);
                rebaked++;
            }
            double maxDist = cfg.createRenderDistance(cfg.distantKineticMaxChunks) + 32.0;
            double maxDistSq = maxDist * maxDist;
            SECTIONS.long2ObjectEntrySet().removeIf(entry -> {
                long key = entry.getLongKey();
                double scx = (BlockPos.getX(key) << 4) + 8;
                double scy = (BlockPos.getY(key) << 4) + 8;
                double scz = (BlockPos.getZ(key) << 4) + 8;
                double dx = scx - cam.x, dy = scy - cam.y, dz = scz - cam.z;
                if (dx * dx + dy * dy + dz * dz > maxDistSq) {
                    Bucket bucket = entry.getValue();
                    for (BlockPos snapPos : bucket.geoms.keySet()) {
                        BEARING_POSITIONS.remove(snapPos);
                    }
                    bucket.close();
                    me.cortex.voxy.commonImpl.PerfStats.kineticSnapshotEvicted.increment();
                    return true;
            }
                return false;
            });
            enforceGpuBudget(cam.x, cam.y, cam.z);

            //Prune sections emptied by removals
            SECTIONS.values().removeIf(bucket -> {
                if (bucket.geoms.isEmpty()) {
                    bucket.close();
                    return true;
            }
                return false;
            });
            sectionCount = SECTIONS.size();
        }

        private static int sweepCursor;
        private static final int SWEEP_CHUNKS_PER_TICK = 48;

        private static void sweep(Minecraft mc, ClientLevel level, double camX, double camY, double camZ, double reachSq) {
            int radius = mc.options.getEffectiveRenderDistance() + 2;
            int diameter = radius * 2 + 1;
            int total = diameter * diameter;
            int centerX = ((int) Math.floor(camX)) >> 4;
            int centerZ = ((int) Math.floor(camZ)) >> 4;
            //Hysteresis band: capture starts past the reach, reclaim only 16 blocks inside it, so a
            //camera hovering on the boundary doesn't churn capture/remove/rebake every sweep pass
            double innerReach = Math.max(0, Math.sqrt(reachSq) - 16.0);
            double innerSq = innerReach * innerReach;
            for (int i = 0; i < SWEEP_CHUNKS_PER_TICK; i++) {
                int idx = Math.floorMod(sweepCursor++, total);
                int cx = centerX + (idx % diameter) - radius;
                int cz = centerZ + (idx / diameter) - radius;
            var chunk = level.getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
            if (!(chunk instanceof net.minecraft.world.level.chunk.LevelChunk levelChunk)) {
                continue;
            }
            if (!SECTIONS.isEmpty()) {
                for (int sy = level.getMinSection(); sy < level.getMaxSection(); sy++) {
                    long bucketKey = BlockPos.asLong(cx, sy, cz);
                    Bucket bucket = SECTIONS.get(bucketKey);
                    if (bucket == null) {
                        continue;
                    }
                    boolean wasDirty = bucket.dirty;
                    bucket.geoms.keySet().removeIf(snapPos -> {
                        if ((snapPos.getX() >> 4) != cx || (snapPos.getZ() >> 4) != cz) {
                            return false;
                        }
                        if (levelChunk.getBlockEntities().get(snapPos) instanceof KineticBlockEntity) {
                            return false;
                        }
                        bucket.dirty = true;
                        BEARING_POSITIONS.remove(snapPos);
                        return true;
                    });
                    //A ghost dropped here floats over loaded terrain until the rebake lands
                    if (bucket.dirty && !wasDirty) {
                        PRIORITY_REBAKE.add(bucketKey);
                    }
                }
            }
            //Ship membership is decided by chunk, so it is the same answer for every machine in this
            //chunk - resolving it per block entity re-walks sable's plot lookup for each one.
            boolean chunkIsShipBorne = me.cortex.voxy.client.compat.ShipBorne.isShipBorne(
                    (double) (cx << 4), (double) (cz << 4));
            for (var be : levelChunk.getBlockEntities().values()) {
                if (!(be instanceof KineticBlockEntity kbe)) {
                    continue;
                }
                BlockPos bePos = kbe.getBlockPos();
                double distSq = bePos.distToCenterSqr(camX, camY, camZ);
                if (distSq <= reachSq || chunkIsShipBorne) {
                    if (distSq <= innerSq) {
                        Bucket bucket = SECTIONS.get(sectionKey(bePos));
                        Snap removed = bucket == null ? null : bucket.geoms.remove(bePos);
                        if (removed != null) {
                            if (!removed.hollow()) {
                                bucket.dirty = true;
                                PRIORITY_REBAKE.add(sectionKey(bePos));
                            }
                            BEARING_POSITIONS.remove(bePos);
                        }
                    }
                    continue;
                }
                if (hasCurrentSnap(bePos, kbe.getBlockState())) {
                    continue;
                }
                if (captureBudget <= 0) {
                    //Budget spent mid-slice: re-run this chunk next tick instead of waiting out a full
                    //pass. Already-captured positions dedup on re-entry, so the redo is cheap.
                    sweepCursor--;
                    return;
                }
                capture(level, kbe);
                captureBudget--;
            }
        }
    }

    private static net.minecraft.world.level.block.entity.BlockEntity loadedBlockEntity(
            ClientLevel level, BlockPos pos) {
        var chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4,
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        if (!(chunk instanceof net.minecraft.world.level.chunk.LevelChunk levelChunk)) {
            return null;
        }
        return levelChunk.getBlockEntities().get(pos);
    }

    static boolean drawsSnapAt(BlockPos pos) {
        Bucket bucket = SECTIONS.get(sectionKey(pos));
        if (bucket == null || bucket.mesh == null) {
            return false;
        }
        Snap snap = bucket.geoms.get(pos);
        return snap != null && !snap.hollow();
    }

    //Present-and-current test for the dedup sites: a snap (hollow or real) for the same interned
    //BlockState needs no recapture; a state change at the position invalidates either kind.
    private static boolean hasCurrentSnap(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        Bucket bucket = SECTIONS.get(sectionKey(pos));
        if (bucket == null) {
            return false;
        }
        Snap prior = bucket.geoms.get(pos);
        return prior != null && prior.state() == state;
    }

    //Horizontal path: the chunk is unloading and its BEs are about to vanish - capture immediately.
    //Runs on the main thread from the unload event, while the block entities are still reachable.
    public static void captureUnloadingChunk(ClientLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantKinetics) {
            return;
        }
        if (level != Minecraft.getInstance().level) {
            return; //leaving the dimension entirely - clearAll handles it
        }
        for (var be : chunk.getBlockEntities().values()) {
            if (!(be instanceof KineticBlockEntity kbe)) {
                continue;
            }
            if (hasCurrentSnap(kbe.getBlockPos(), kbe.getBlockState())) {
                continue;
            }
            capture(level, kbe);
        }
    }

    private static void capture(ClientLevel level, KineticBlockEntity be) {
        BlockPos pos = be.getBlockPos();
        try {
            var state = be.getBlockState();
            var model = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);

            net.minecraft.core.Direction shaftHalfFacing = null;
            net.minecraft.core.Direction bearingFacing = null;
            float bearingTopAngle = 0.0f;
            boolean woodenTop = false;
            if (be instanceof com.simibubi.create.content.contraptions.bearing.IBearingBlockEntity bearing
                    && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
                bearingFacing = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
                shaftHalfFacing = bearingFacing.getOpposite();
                //partialTicks = 1, matching the contraption snapshot's applyLocalTransforms(1.0f) so
                //the frozen disc and the frozen structure land on the same interpolated angle
                bearingTopAngle = (float) Math.toRadians(bearing.getInterpolatedAngle(1.0f));
                woodenTop = bearing.isWoodenTop();
            }

            //Chain conveyors: shaft + wheel partials plus every chain connection (start offset, yaw,
            //pitch, length, far-end light) so the strap geometry survives at LOD range
            float[][] chains = null;
            if (be instanceof com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity conveyor) {
                var list = new java.util.ArrayList<float[]>();
                var center = net.minecraft.world.phys.Vec3.atCenterOf(pos);
                for (BlockPos rel : conveyor.connections) {
                    var stats = conveyor.connectionStats.get(rel);
                    if (stats == null) {
                        continue;
                    }
                    var diff = stats.end().subtract(stats.start());
                    double yaw = Math.toDegrees(net.minecraft.util.Mth.atan2(diff.x, diff.z));
                    double pitch = Math.toDegrees(net.minecraft.util.Mth.atan2(diff.y,
                            diff.multiply(1.0, 0.0, 1.0).length()));
                    var startOff = stats.start().subtract(center);
                    int farLight = DistantLightSampler.samplePeek(level,
                            pos.getX() + rel.getX(), pos.getY() + rel.getY() + 1, pos.getZ() + rel.getZ());
                    list.add(new float[]{(float) startOff.x, (float) startOff.y, (float) startOff.z,
                            (float) yaw, (float) pitch, stats.chainLength(),
                            DistantLightSampler.sky(farLight), DistantLightSampler.block(farLight)});
                }
                chains = list.toArray(new float[0][]);
            }

            //bits_n_bobs cogwheel chains: the controlling wheel carries the whole loop's strap geometry
            float[] bnbChain = null;
            if (BNB_LOADED) {
                try {
                    bnbChain = BnbChainSnapshots.capture(level, be);
                } catch (Throwable ignored) {
                    //the addon's internals moved - the wheel itself still snapshots via its json
                }
            }

            int light = DistantLightSampler.samplePeek(level, pos.getX(), pos.getY() + 1, pos.getZ());
            if (DistantLightSampler.sky(light) == 0 && DistantLightSampler.block(light) == 0) {
                light = DistantLightSampler.samplePeek(level, pos.getX(), pos.getY(), pos.getZ());
            }
            int skyLight = DistantLightSampler.sky(light), blockLight = DistantLightSampler.block(light);

            float[] generic = null;
            var renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
            if (renderer instanceof KineticBlockEntityRenderer<?>) {
                int sx = pos.getX() & ~15, sy = pos.getY() & ~15, sz = pos.getZ() & ~15;
                var consumer = new Capture(pos.getX() - sx, pos.getY() - sy, pos.getZ() - sz, skyLight, blockLight);
                captureThread = Thread.currentThread();
                try {
                    @SuppressWarnings("unchecked")
                    var raw = (net.minecraft.client.renderer.blockentity.BlockEntityRenderer<KineticBlockEntity>) renderer;
                    raw.render(be, 1.0f, new com.mojang.blaze3d.vertex.PoseStack(), consumer, light,
                            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
                    consumer.flush();
                    generic = consumer.toArray();
                    if (generic.length < 9 * 4) {
                        generic = null;
                    }
                } catch (Throwable e) {
                    generic = null;
                } finally {
                    captureThread = null;
                }
            }
            if (generic != null) {
                shaftHalfFacing = null;
                bearingFacing = null;
            }

            boolean bakeJson = generic == null && !chunkVisible(model, state)
                    && !(model.getQuads(null, null, net.minecraft.util.RandomSource.create(42)).isEmpty()
                    && model.getQuads(null, net.minecraft.core.Direction.UP, net.minecraft.util.RandomSource.create(42)).isEmpty());
            boolean gantryCarriage = generic == null
                    && be instanceof com.simibubi.create.content.contraptions.gantry.GantryCarriageBlockEntity;
            if (generic == null && !bakeJson && shaftHalfFacing == null && bearingFacing == null
                    && chains == null && bnbChain == null && !gantryCarriage) {
                Bucket bucket = SECTIONS.computeIfAbsent(sectionKey(pos), k -> new Bucket());
                Snap prior = bucket.geoms.put(pos.immutable(),
                        new Snap(state, null, 0.0f, 0, 0, false, null, null, 0.0f, false, null, null, null, false));
                if (prior != null && !prior.hollow()) {
                    bucket.dirty = true; //the old state had geometry; rebuild the mesh without it
                }
                logCapture(pos.toShortString() + " SKIP " + state.getBlock().getDescriptionId()
                        + " (chunk-visible or empty json, no partials)");
                return;
            }
            net.minecraft.core.Direction.Axis axis = null;
            try {
                axis = KineticBlockEntityRenderer.getRotationAxisOf(be);
            } catch (Throwable ignored) {
            }
            float angle = axis != null
                    ? KineticBlockEntityRenderer.getRotationOffsetForPosition(be, pos, axis) % 360.0f / 180.0f * (float) Math.PI
                    : 0.0f;
            Bucket bucket = SECTIONS.computeIfAbsent(sectionKey(pos), k -> new Bucket());
            bucket.geoms.put(pos.immutable(), new Snap(state, axis, angle,
                    skyLight, blockLight,
                    bakeJson, shaftHalfFacing, bearingFacing, bearingTopAngle, woodenTop, chains, bnbChain, generic,
                    gantryCarriage));
            bucket.dirty = true;
            if (bearingFacing != null) {
                BEARING_POSITIONS.add(pos.immutable());
                if (!inManagerRecapture) {
                    ANCHOR_RECAPTURED.add(pos.immutable());
                }
            }
            logCapture(pos.toShortString() + " OK block=" + state.getBlock().getDescriptionId()
                    + " generic=" + (generic != null ? generic.length / 9 + "v" : "no")
                    + " json=" + bakeJson
                    + " angle=" + String.format("%.1f", Math.toDegrees(angle)));
        } catch (Throwable e) {
            logCapture(pos.toShortString() + " ERROR " + e);
            //A broken state must not take the tick down; that block simply keeps the hidden-only cull
        }
    }

    static void rotateAboutAxis(org.joml.Matrix4f transform, net.minecraft.core.Direction.Axis axis, float angleRad) {
        if (axis == null || angleRad == 0.0f) {
            return;
        }
        transform.rotate(angleRad,
                axis == net.minecraft.core.Direction.Axis.X ? 1 : 0,
                axis == net.minecraft.core.Direction.Axis.Y ? 1 : 0,
                axis == net.minecraft.core.Direction.Axis.Z ? 1 : 0);
    }

    static void bakeGantryCarriage(DistantMeshBuilder builder, org.joml.Matrix4f transform,
                                   net.minecraft.world.level.block.state.BlockState state,
                                   BlockPos parityPos, float lx, float ly, float lz, int sky, int block) {
        var rotationAxis = ((com.simibubi.create.content.kinetics.base.IRotate) state.getBlock())
                .getRotationAxis(state);
        var shaper = Minecraft.getInstance().getModelManager().getBlockModelShaper();

        var shaftModel = shaper.getBlockModel(KineticBlockEntityRenderer.shaft(rotationAxis));
        float shaftAngle = rad(com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual
                .rotationOffset(state, rotationAxis, parityPos) % 360.0f);
        transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f);
        rotateAboutAxis(transform, rotationAxis, shaftAngle);
        transform.translate(-0.5f, -0.5f, -0.5f);
        builder.transformedModel(shaftModel, transform, sky, block);

        var facing = state.getValue(com.simibubi.create.content.contraptions.gantry.GantryCarriageBlock.FACING);
        boolean alongFirst = state.getValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE);
        var visualPos = facing.getAxisDirection() == net.minecraft.core.Direction.AxisDirection.POSITIVE
                ? parityPos : parityPos.relative(facing.getOpposite());
        float angleDeg = com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual
                .rotationOffset(state, rotationAxis, visualPos) % 360.0f;
        //The axis that is neither the rotation axis nor the facing axis decides the spin sign
        var gantryAxis = net.minecraft.core.Direction.Axis.Z;
        for (var candidate : net.minecraft.core.Direction.Axis.values()) {
            if (candidate != rotationAxis && candidate != facing.getAxis()) {
                gantryAxis = candidate;
                break;
            }
        }
        if ((gantryAxis == net.minecraft.core.Direction.Axis.X && facing == net.minecraft.core.Direction.UP)
                || (gantryAxis == net.minecraft.core.Direction.Axis.Y
                && (facing == net.minecraft.core.Direction.NORTH || facing == net.minecraft.core.Direction.EAST))) {
            angleDeg = -angleDeg;
        }
        transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f)
                .rotateY(rad(horizontalAngle(facing)))
                .rotateX(rad(facing == net.minecraft.core.Direction.UP ? 0.0f
                        : facing == net.minecraft.core.Direction.DOWN ? 180.0f : 90.0f))
                .rotateY(rad((alongFirst ^ (facing.getAxis() == net.minecraft.core.Direction.Axis.X)) ? 0.0f : 90.0f))
                .translate(0.0f, -9.0f / 16.0f, 0.0f)
                .rotateX(rad(-angleDeg))
                .translate(0.0f, 9.0f / 16.0f, 0.0f)
                .translate(-0.5f, -0.5f, -0.5f);
        builder.transformedModel(com.simibubi.create.AllPartialModels.GANTRY_COGS.get(), transform, sky, block);
    }

    private static void bakeShaftHalf(DistantMeshBuilder builder, org.joml.Matrix4f transform, Snap snap,
                                      float lx, float ly, float lz) {
        var facing = snap.shaftHalfFacing();
        transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f);
        rotateAboutAxis(transform, facing.getAxis(), snap.angleRad());
        transform.rotateY(rad(horizontalAngle(facing)))
                .rotateX(rad(verticalAngle(facing)))
                .translate(-0.5f, -0.5f, -0.5f);
        builder.transformedModel(com.simibubi.create.AllPartialModels.SHAFT_HALF.get(), transform,
                snap.sky(), snap.block());
    }

    //Reproduces BearingRenderer.renderSafe's top disc
    private static void bakeBearingTop(DistantMeshBuilder builder, org.joml.Matrix4f transform, Snap snap,
                                       float lx, float ly, float lz) {
        var facing = snap.bearingFacing();
        var opposite = facing.getOpposite();
        transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f);
        rotateAboutAxis(transform, facing.getAxis(), snap.bearingTopAngleRad());
        if (facing.getAxis().isHorizontal()) {
            transform.rotateY(rad(horizontalAngle(opposite)));
        }
        transform.rotateX(rad(-90.0f - verticalAngle(facing)))
                .translate(-0.5f, -0.5f, -0.5f);
        var top = snap.woodenTop()
                ? com.simibubi.create.AllPartialModels.BEARING_TOP_WOODEN
                : com.simibubi.create.AllPartialModels.BEARING_TOP;
        builder.transformedModel(top.get(), transform, snap.sky(), snap.block());
    }

    //Chain conveyor
    private static void bakeChainConveyor(DistantMeshBuilder builder, org.joml.Matrix4f transform, Snap snap,
                                          float lx, float ly, float lz) {
        transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f);
        rotateAboutAxis(transform, snap.axis(), snap.angleRad());
        transform.translate(-0.5f, -0.5f, -0.5f);
        builder.transformedModel(com.simibubi.create.AllPartialModels.CHAIN_CONVEYOR_SHAFT.get(), transform,
                snap.sky(), snap.block());

        transform.identity().translate(lx, ly, lz);
        builder.transformedModel(com.simibubi.create.AllPartialModels.CHAIN_CONVEYOR_WHEEL.get(), transform,
                snap.sky(), snap.block());

        var chainSprite = net.minecraft.client.Minecraft.getInstance()
                .getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
                .apply(net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/chain"));
        var vertex = new org.joml.Vector3f();
        for (float[] chain : snap.chains()) {
            float yawDeg = chain[3], pitchDeg = chain[4], length = chain[5];
            int sky2 = (int) chain[6], block2 = (int) chain[7];

            transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f)
                    .rotateY(rad(yawDeg))
                    .translate(-0.5f, -0.5f, -0.5f);
            builder.transformedModel(com.simibubi.create.AllPartialModels.CHAIN_CONVEYOR_GUARD.get(), transform,
                    snap.sky(), snap.block());

            //ChainConveyorRenderer.renderChains transform chain, then renderChain's (0.5, 0, 0.5)
            transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f)
                    .translate(chain[0], chain[1], chain[2])
                    .rotateY(rad(yawDeg))
                    .rotateX(rad(90.0f - pitchDeg))
                    .rotateY(rad(45.0f))
                    .translate(0.0f, 0.5f, 0.0f)
                    .translate(-0.5f, -0.5f, -0.5f)
                    .translate(0.5f, 0.0f, 0.5f);

            //Far-mip strap: radius 1/16, static texture window, two crossed double-sided ribbons
            float radius = 0.0625f;
            float minU = chainSprite.getU(0.1875f), maxU = chainSprite.getU(0.25f);
            float minV = chainSprite.getV(0.0f), maxV = chainSprite.getV(0.0625f);
            chainQuad(builder, transform, vertex, length, 0, radius, 0, -radius, minU, maxU, minV, maxV, snap.sky(), snap.block(), sky2, block2);
            chainQuad(builder, transform, vertex, length, 0, -radius, 0, radius, minU, maxU, minV, maxV, snap.sky(), snap.block(), sky2, block2);
            chainQuad(builder, transform, vertex, length, radius, 0, -radius, 0, minU, maxU, minV, maxV, snap.sky(), snap.block(), sky2, block2);
            chainQuad(builder, transform, vertex, length, -radius, 0, radius, 0, minU, maxU, minV, maxV, snap.sky(), snap.block(), sky2, block2);
        }
    }

    //One chain ribbon quad in ChainConveyorRenderer.renderQuad's vertex order: top(light2), bottom,
    //bottom, top - light blends from the near wheel to the far one along the strap
    private static void chainQuad(DistantMeshBuilder builder, org.joml.Matrix4f transform, org.joml.Vector3f vertex,
                                  float length, float x0, float z0, float x1, float z1,
                                  float minU, float maxU, float minV, float maxV,
                                  int sky1, int block1, int sky2, int block2) {
        emitChainVertex(builder, transform, vertex, x0, length, z0, maxU, minV, sky2, block2);
        emitChainVertex(builder, transform, vertex, x0, 0.0f, z0, maxU, maxV, sky1, block1);
        emitChainVertex(builder, transform, vertex, x1, 0.0f, z1, minU, maxV, sky1, block1);
        emitChainVertex(builder, transform, vertex, x1, length, z1, minU, minV, sky2, block2);
    }

    private static void emitChainVertex(DistantMeshBuilder builder, org.joml.Matrix4f transform, org.joml.Vector3f vertex,
                                        float x, float y, float z, float u, float v, int sky, int block) {
        vertex.set(x, y, z);
        transform.transformPosition(vertex);
        builder.rawVertex(vertex.x, vertex.y, vertex.z, u, v, sky, block, 1.0f, 1);
    }

    //catnip AngleHelper, inlined
    private static float horizontalAngle(net.minecraft.core.Direction facing) {
        if (facing.getAxis().isVertical()) {
            return 0.0f;
        }
        float angle = facing.toYRot();
        return facing.getAxis() == net.minecraft.core.Direction.Axis.X ? -angle : angle;
    }

    private static float verticalAngle(net.minecraft.core.Direction facing) {
        return facing == net.minecraft.core.Direction.UP ? -90.0f
                : facing == net.minecraft.core.Direction.DOWN ? 90.0f : 0.0f;
    }

    private static float rad(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    static boolean chunkVisible(net.minecraft.client.resources.model.BakedModel model,
                                net.minecraft.world.level.block.state.BlockState state) {
        if (state.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) {
            return false;
        }
        var random = net.minecraft.util.RandomSource.create(42);
        for (var layer : new net.minecraft.client.renderer.RenderType[]{
                net.minecraft.client.renderer.RenderType.solid(),
                net.minecraft.client.renderer.RenderType.cutoutMipped(),
                net.minecraft.client.renderer.RenderType.cutout()}) {
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                random.setSeed(42);
                if (!model.getQuads(state, direction, random,
                        net.neoforged.neoforge.client.model.data.ModelData.EMPTY, layer).isEmpty()) {
                    return true;
                }
            }
            random.setSeed(42);
            if (!model.getQuads(state, null, random,
                    net.neoforged.neoforge.client.model.data.ModelData.EMPTY, layer).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void rebake(Bucket bucket) {
        if (bucket.mesh != null) {
            bucket.mesh.free();
            bucket.mesh = null;
        }
        var builder = new DistantMeshBuilder();
        try {
            var shaper = Minecraft.getInstance().getModelManager().getBlockModelShaper();
            var transform = new org.joml.Matrix4f();
            for (var entry : bucket.geoms.entrySet()) {
                BlockPos pos = entry.getKey();
                Snap snap = entry.getValue();
                float lx = pos.getX() & 15, ly = pos.getY() & 15, lz = pos.getZ() & 15;
                if (snap.generic() != null) {
                    //Full-pass capture, already section-local
                    float[] v = snap.generic();
                    for (int i = 0; i + 9 <= v.length; i += 9) {
                        builder.rawVertex(v[i], v[i + 1], v[i + 2], v[i + 3], v[i + 4],
                                (int) v[i + 5], (int) v[i + 6], v[i + 7], (int) v[i + 8]);
                    }
                }
                if (snap.bakeJson()) {
                    var model = shaper.getBlockModel(snap.state());
                    //Section-local block offset, then the frozen spin about the block centre. The
                    //baked model already carries the blockstate's axis alignment (variant rotations).
                    transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f);
                    rotateAboutAxis(transform, snap.axis(), snap.angleRad());
                    transform.translate(-0.5f, -0.5f, -0.5f);
                    builder.transformedModel(model, transform, snap.sky(), snap.block());
                }
                if (snap.gantryCarriage()) {
                    bakeGantryCarriage(builder, transform, snap.state(), pos, lx, ly, lz,
                            snap.sky(), snap.block());
                }
                if (snap.shaftHalfFacing() != null) {
                    bakeShaftHalf(builder, transform, snap, lx, ly, lz);
                }
                if (snap.bearingFacing() != null) {
                    bakeBearingTop(builder, transform, snap, lx, ly, lz);
                }
                if (snap.chains() != null) {
                    bakeChainConveyor(builder, transform, snap, lx, ly, lz);
                }
                if (snap.bnbChain() != null) {
                    float[] v = snap.bnbChain();
                    for (int i = 0; i + 9 <= v.length; i += 9) {
                        builder.rawVertex(v[i] + lx, v[i + 1] + ly, v[i + 2] + lz, v[i + 3], v[i + 4],
                                (int) v[i + 5], (int) v[i + 6], v[i + 7], (int) v[i + 8]);
                    }
                }
            }
            bucket.mesh = builder.build();
        } catch (Throwable e) {
            builder.discard();
        }
        int count = 0;
        for (Bucket b : SECTIONS.values()) {
            for (Snap s2 : b.geoms.values()) {
                if (!s2.hollow()) {
                    count++;
                }
            }
        }
        snapshotCount = count;
    }

    private static long sectionKey(BlockPos pos) {
        return BlockPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }


    //Discards render passes the snapshot must not carry (non-block-atlas layers: chain straps on
    //standalone textures, glowing overlays)
    private static final com.mojang.blaze3d.vertex.VertexConsumer NOOP = new com.mojang.blaze3d.vertex.VertexConsumer() {
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) { return this; }
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) { return this; }
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) { return this; }
        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) { return this; }
        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) { return this; }
    };

    static final class Capture implements net.minecraft.client.renderer.MultiBufferSource, com.mojang.blaze3d.vertex.VertexConsumer {
        private final float ox, oy, oz;
        private final int sky, block;
        private final java.util.ArrayList<float[]> verts = new java.util.ArrayList<>();
        private boolean pending;
        private float x, y, z, u, v, nx, ny, nz;

        Capture(float ox, float oy, float oz, int sky, int block) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.sky = sky;
            this.block = block;
        }

        float[] toArray() {
            float[] out = new float[this.verts.size() * 9];
            for (int i = 0; i < this.verts.size(); i++) {
                System.arraycopy(this.verts.get(i), 0, out, i * 9, 9);
            }
            return out;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer getBuffer(net.minecraft.client.renderer.RenderType type) {
            String name = type.toString();
            if (name.contains("solid") || name.contains("cutout")) {
                return this;
            }
            return NOOP;
        }

        void flush() {
            if (!this.pending) {
                return;
            }
            float shade;
            int face;
            if (this.ny > 0.6f) {
                shade = 1.0f;
                face = 1;
            } else if (this.ny < -0.6f) {
                shade = 0.5f;
                face = 0;
            } else if (Math.abs(this.nz) > Math.abs(this.nx)) {
                shade = 0.8f;
                face = this.nz > 0 ? 3 : 2;
            } else {
                shade = 0.6f;
                face = this.nx > 0 ? 5 : 4;
            }
            this.verts.add(new float[]{this.x + this.ox, this.y + this.oy, this.z + this.oz,
                    this.u, this.v, this.sky, this.block, shade, face});
            this.pending = false;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
            this.flush();
            this.x = x;
            this.y = y;
            this.z = z;
            this.u = 0;
            this.v = 0;
            this.nx = 0;
            this.ny = 1;
            this.nz = 0;
            this.pending = true;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) {
            this.nx = x;
            this.ny = y;
            this.nz = z;
            return this;
        }
    }
}
