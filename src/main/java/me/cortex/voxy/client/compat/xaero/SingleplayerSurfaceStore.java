package me.cortex.voxy.client.compat.xaero;

import xaero.map.MapProcessor;
import xaero.map.file.MapSaveLoad;
import xaero.map.region.MapBlock;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTile;
import xaero.map.region.MapTileChunk;

import java.nio.file.Path;

/** Singleplayer Xaero normally rebuilds from MCA and discards source pixels. Store
 * a native Xaero ZIP supplement in its map folder, then merge AFTER the MCA read.
 * MCA/live pixels always win. This never writes the Minecraft region files. */
public final class SingleplayerSurfaceStore {
    public static boolean applies(MapRegion region) {
        return !region.isNormalMapData() && region.getCaveLayer() == Integer.MAX_VALUE && region.getWorldId() != null;
    }

    public static Path path(MapSaveLoad io, MapRegion region) {
        return io.getMainFolder(region.getWorldId(), region.getDimId()).resolve("voxy-surface")
                .resolve(region.getRegionX() + "_" + region.getRegionZ() + ".zip");
    }

    public static void merge(MapRegion destination, MapRegion supplement, MapProcessor processor) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
            MapTileChunk source = supplement.getChunk(x, z);
            if (source == null) continue;
            MapTileChunk target = destination.getChunk(x, z);
            for (int tx = 0; tx < 4; tx++) for (int tz = 0; tz < 4; tz++) {
                MapTile stored = source.getTile(tx, tz);
                if (stored == null || !stored.isLoaded()) continue;
                MapTile tile = target == null ? null : target.getTile(tx, tz);
                if (tile == null) tile = new MapTile(null, stored.getChunkX(), stored.getChunkZ());
                for (int px = 0; px < 16; px++) for (int pz = 0; pz < 16; pz++) {
                    MapBlock existing = tile.getBlock(px, pz);
                    if (existing == null || SurfaceProvenance.source(((ProvenancedPixel) existing).voxy$getSource()) == SurfaceProvenance.EMPTY) {
                        tile.setBlock(px, pz, stored.getBlock(px, pz));
                        // Xaero's tile pool reuses MapBlocks. Detach moved objects.
                        stored.setBlock(px, pz, null);
                    }
                }
                if (target == null) {
                    target = new MapTileChunk(destination, source.getX(), source.getZ());
                    destination.setChunk(x, z, target);
                }
                tile.setWorldInterpretationVersion(1);
                tile.setWrittenCave(Integer.MAX_VALUE, 0);
                tile.setLoaded(true);
                target.setTile(tx, tz, tile, processor.getBlockStateShortShapeCache(), processor);
                target.setLoadState((byte) 2);
                target.setToUpdateBuffers(true);
                target.setHasHadTerrain();
            }
        }
        destination.setHasHadTerrain();
        destination.setSaveExists(true);
        destination.restoreBufferUpdateObjects();
        // A world-save region without an MCA can still be restored entirely
        // from the supplement. In particular, a previously evicted region is
        // state 4; Xaero's loader only promotes states 0/1 to source-ready 2.
        destination.setLoadState((byte) 1);
        ((SupplementedRegion) destination).voxy$setSupplement(true);
    }

    public static void release(MapRegion temporary, MapProcessor processor) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
            MapTileChunk chunk = temporary.getChunk(x, z);
            if (chunk == null) continue;
            chunk.clean(processor);
            chunk.getLeafTexture().deleteTexturesAndBuffers();
        }
    }
    private SingleplayerSurfaceStore() {}
}
