package me.cortex.voxy.client.compat.xaero;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.*;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.LongConsumer;

/** All Xaero mutation happens on the client thread, under the same pause locks as
 * MapWriter. Disk IO stays in Xaero's loader/saver. The worker only reads Voxy. */
final class XaeroSurfaceBridge {
    private static Session current;
    private static boolean verified;
    private static java.lang.ref.WeakReference<WorldEngine> lastEngine = new java.lang.ref.WeakReference<>(null);
    private static java.lang.ref.WeakReference<ClientLevel> lastLevel = new java.lang.ref.WeakReference<>(null);
    private static String lastSubworld;

    static void tick(VoxyConfig config) throws Exception {
        if (!verified) {
            if (!((Object) new MapBlock() instanceof ProvenancedPixel)) throw new LinkageError("Xaero provenance mixins absent");
            MapProcessor.class.getMethod("getLeafMapRegion", int.class, int.class, int.class, boolean.class);
            MapTileChunk.class.getMethod("setTile", int.class, int.class, MapTile.class,
                    xaero.map.cache.BlockStateShortShapeCache.class, MapProcessor.class);
            verified = true;
        }
        var minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        WorldMapSession xaero = WorldMapSession.getCurrentSession();
        MapProcessor processor = xaero != null && xaero.isUsable() ? xaero.getMapProcessor() : null;
        WorldEngine engine = level == null || WorldIdentifier.of(level) == null ? null : WorldIdentifier.of(level).getNullable();
        boolean usable = config.xaeroMapSync && ClientSessionEvents.inSession && engine != null && processor != null
                && processor.getWorld() == level && level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling()
                && level.getMinBuildHeight() >= -2048 && level.getMaxBuildHeight() <= 2048
                && !processor.isWaitingForWorldUpdate()
                && processor.isCurrentMultiworldWritable() && !processor.isCurrentMapLocked()
                && !processor.ignoreWorld(level) && !processor.getMapWorld().isCacheOnlyMode()
                && processor.getMapWorld().getCurrentDimensionId().equals(level.dimension())
                && processor.getMapSaveLoad().isRegionDetectionComplete();
        if (current != null && (!usable || !current.matches(engine, processor, level)
                || current.coarse != config.xaeroMapCoarse)) stop();
        if (!usable) return;
        if (current == null) {
            boolean seed = engine != lastEngine.get() || level != lastLevel.get()
                    || Objects.equals(lastSubworld, processor.getCurrentMWId());
            current = new Session(engine, processor, level, config, seed);
            lastEngine = new java.lang.ref.WeakReference<>(engine);
            lastLevel = new java.lang.ref.WeakReference<>(level);
            lastSubworld = processor.getCurrentMWId();
            Logger.info("Xaero LOD map sync attached: " + level.dimension().location() + " / " + processor.getCurrentMWId());
        }
        if (current.failure != null) throw new IllegalStateException("Xaero surface worker failed", current.failure);
        current.tilesPerSecond = Math.clamp(config.xaeroMapTilesPerSecond, 1, 128);
        long deadline = System.nanoTime() + Math.clamp(config.xaeroMapCommitMillis, 1, 10) * 1_000_000L;
        int limit = 4;
        do {
            SurfaceTile tile = current.ready.peek();
            if (tile == null || !commit(current, tile)) break;
            current.ready.poll();
            current.committed++;
        } while (--limit > 0 && System.nanoTime() < deadline);
    }

    static void stop() {
        Session previous = current;
        current = null;
        if (previous != null) previous.close();
    }

    private static boolean commit(Session session, SurfaceTile data) {
        MapProcessor processor = session.processor;
        synchronized (processor.renderThreadPauseSync) {
            if (processor.isWritingPaused() || processor.isWaitingForWorldUpdate() || !session.matches(session.engine, processor, session.level)) return false;
            MapRegion region = processor.getLeafMapRegion(Integer.MAX_VALUE, data.chunkX() >> 5, data.chunkZ() >> 5, true);
            if (region == null) return false;
            MapTileChunk changedChunk;
            synchronized (region.writerThreadPauseSync) {
                if (region.isWritingPaused()) return false;
                synchronized (region) {
                    if (region.getLoadState() != 2) {
                        if (region.isResting()) {
                            region.setBeingWritten(true);
                            region.setShouldCache(true, "Voxy surface requires source pixels");
                            processor.getMapSaveLoad().requestLoad(region, "Voxy surface", false);
                        }
                        return false;
                    }
                    if (!region.isResting()) return false;
                    int tx = (data.chunkX() >> 2) & 7, tz = (data.chunkZ() >> 2) & 7;
                    MapTileChunk chunk = region.getChunk(tx, tz);
                    if (chunk != null && (chunk.getLoadState() != 2 || chunk.getLeafTexture().shouldDownloadFromPBO())) return false;
                    int ix = data.chunkX() & 3, iz = data.chunkZ() & 3;
                    MapTile tile = chunk == null ? null : chunk.getTile(ix, iz);
                    // A loaded foreign/real tile is protected pixel by pixel. New empty
                    // filler pixels carry their own marker, also across save/reload.
                    boolean changed = false;
                    for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        var incoming = data.pixels()[z * 16 + x];
                        if (incoming == null) continue;
                        MapBlock previous = tile == null ? null : tile.getBlock(x, z);
                        if (previous != null && !SurfaceProvenance.mayReplace(((ProvenancedPixel) previous).voxy$getSource(), incoming.lod(), incoming.provisional())) continue;
                        if (tile == null) tile = emptyTile(data.chunkX(), data.chunkZ(), session.level.getMinBuildHeight());
                        MapBlock block = new MapBlock();
                        block.prepareForWriting(session.level.getMinBuildHeight());
                        block.write(incoming.state(), incoming.height(), incoming.top(), incoming.biome(), incoming.light(),
                                processor.getMapWriter().isGlowing(incoming.state()), false);
                        for (var cover : incoming.covers()) {
                            Overlay overlay = new Overlay(cover.state(), cover.light(), processor.getMapWriter().isGlowing(cover.state()));
                            overlay.increaseOpacity(cover.opacity());
                            block.addOverlay(processor.getOverlayManager().getOriginal(overlay));
                        }
                        ((ProvenancedPixel) block).voxy$setSource(SurfaceProvenance.encode(incoming.lod() + 1, incoming.provisional()));
                        tile.setBlock(x, z, block);
                        changed = true;
                    }
                    if (!changed) return true;
                    if (chunk == null) {
                        chunk = new MapTileChunk(region, data.chunkX() >> 2, data.chunkZ() >> 2);
                        chunk.setLoadState((byte) 2);
                        region.setChunk(tx, tz, chunk);
                    }
                    tile.setWorldInterpretationVersion(1);
                    tile.setWrittenCave(Integer.MAX_VALUE, 0);
                    tile.setLoaded(true);
                    // setWrittenOnce is exclusively reserved for genuine MapWriter scans.
                    chunk.setTile(ix, iz, tile, processor.getBlockStateShortShapeCache(), processor);
                    chunk.setHasHadTerrain();
                    chunk.setChanged(true);
                    region.setHasHadTerrain();
                    if (SingleplayerSurfaceStore.applies(region)) ((SupplementedRegion) region).voxy$setSupplement(true);
                    region.setBeingWritten(true);
                    region.setAllCachePrepared(false);
                    region.registerVisit();
                    processor.addToProcess(region);
                    invalidateSlopes(chunk);
                    chunk.updateBuffers(processor, processor.getWorldBlockTintProvider(), processor.getOverlayManager(), false,
                            processor.getBlockStateShortShapeCache(), new MapUpdateFastConfig(processor));
                    chunk.setChanged(false);
                    changedChunk = chunk;
                }
            }
            // Do not hold two region locks. The native refresh path recomputes
            // neighbouring slopes, colours and parent textures on its own schedule.
            for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                MapTileChunk neighbour = changedChunk.getNeighbourTileChunk(dx, dz, processor, true);
                if (neighbour == null) continue;
                MapRegion neighbourRegion = neighbour.getInRegion();
                synchronized (neighbourRegion.writerThreadPauseSync) {
                    synchronized (neighbourRegion) {
                        if (!neighbourRegion.isWritingPaused() && neighbourRegion.isResting() && neighbour.getLoadState() == 2)
                            neighbourRegion.requestRefresh(processor, false);
                    }
                }
            }
            return true;
        }
    }

    private static void invalidateSlopes(MapTileChunk chunk) {
        for (int tx = 0; tx < 4; tx++) for (int tz = 0; tz < 4; tz++) {
            MapTile tile = chunk.getTile(tx, tz);
            if (tile == null || !tile.isLoaded()) continue;
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                MapBlock block = tile.getBlock(x, z);
                if (block != null) block.setSlopeUnknown(true);
            }
        }
    }

    private static MapTile emptyTile(int x, int z, int bottom) {
        MapTile tile = new MapTile(null, x, z);
        for (int ix = 0; ix < 16; ix++) for (int iz = 0; iz < 16; iz++) {
            MapBlock empty = new MapBlock();
            empty.prepareForWriting(bottom);
            ((ProvenancedPixel) empty).voxy$setSource(SurfaceProvenance.encode(SurfaceProvenance.EMPTY, true));
            tile.setBlock(ix, iz, empty);
        }
        return tile;
    }

    private static final class Session implements AutoCloseable {
        final WorldEngine engine;
        final MapProcessor processor;
        final ClientLevel level;
        final String world, dimension, subworld;
        final boolean coarse;
        final SurfaceQueue queue;
        final ArrayBlockingQueue<SurfaceTile> ready = new ArrayBlockingQueue<>(8);
        final Thread worker;
        final LongConsumer listener;
        volatile boolean cancelled;
        volatile Throwable failure;
        volatile int tilesPerSecond;
        long committed;

        Session(WorldEngine engine, MapProcessor processor, ClientLevel level, VoxyConfig config, boolean seed) {
            this.engine = engine;
            this.processor = processor;
            this.level = level;
            this.world = processor.getCurrentWorldId();
            this.dimension = processor.getCurrentDimId();
            this.subworld = processor.getCurrentMWId();
            this.coarse = config.xaeroMapCoarse;
            this.queue = new SurfaceQueue(Math.clamp(config.xaeroMapQueueRegions, 16, 4096));
            this.tilesPerSecond = Math.clamp(config.xaeroMapTilesPerSecond, 1, 128);
            this.worker = new Thread(this::run, "Voxy Xaero surface extractor");
            this.worker.setDaemon(true);
            this.worker.setPriority(Thread.MIN_PRIORITY);
            this.listener = key -> {
                if (!this.cancelled && Thread.currentThread() != this.worker && (this.coarse || WorldEngine.getLevel(key) == 0)) this.queue.offer(key);
            };
            engine.acquireRef();
            try {
                engine.addDataListener(this.listener);
                // A manual Xaero subworld switch must not replay the previous binding's cache.
                if (seed) engine.iterateCachedPositions(this.listener);
                this.worker.start();
            } catch (RuntimeException | Error problem) {
                engine.removeDataListener(this.listener);
                engine.releaseRef();
                throw problem;
            }
        }

        boolean matches(WorldEngine engine, MapProcessor processor, ClientLevel level) {
            return this.engine == engine && this.processor == processor && this.level == level
                    && Objects.equals(this.world, processor.getCurrentWorldId())
                    && Objects.equals(this.dimension, processor.getCurrentDimId())
                    && Objects.equals(this.subworld, processor.getCurrentMWId());
        }

        void run() {
            try {
                var writer = this.processor.getMapWriter();
                boolean flowers = this.processor.isFlowersConfig();
                SurfaceExtractor.Rules rules = new SurfaceExtractor.Rules() {
                    public boolean invisible(BlockState state) { return writer.isInvisible(state, state.getBlock(), flowers); }
                    public boolean overlay(BlockState state) {
                        return writer.shouldOverlay(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
                                ? state.getFluidState() : state);
                    }
                    public int opacity(BlockState state) { return state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO); }
                };
                while (!this.cancelled) {
                    var next = this.queue.poll();
                    if (next == null) { Thread.sleep(50); continue; }
                    long start = System.nanoTime();
                    SurfaceTile tile = SurfaceExtractor.extract(this.engine, next.x(), next.z(), this.level.getMinBuildHeight(),
                            this.level.getMaxBuildHeight(), this.coarse, rules, () -> this.cancelled);
                    if (tile != null && tile.hasEvidence() && !this.cancelled) this.ready.put(tile);
                    long remaining = 1_000_000_000L / this.tilesPerSecond - (System.nanoTime() - start);
                    if (remaining > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception | LinkageError problem) {
                this.failure = problem;
            } finally {
                this.engine.releaseRef();
            }
        }

        public void close() {
            this.cancelled = true;
            this.engine.removeDataListener(this.listener);
            this.worker.interrupt();
            this.queue.clear();
            this.ready.clear();
            Logger.info("Xaero LOD map sync detached: " + this.committed + " tiles submitted; " + this.queue.overflowCount()
                    + " region notifications evicted (revisited LOD will retry).");
        }
    }
    private XaeroSurfaceBridge() {}
}
