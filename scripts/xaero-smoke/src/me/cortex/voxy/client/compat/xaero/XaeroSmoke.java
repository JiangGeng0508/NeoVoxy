package me.cortex.voxy.client.compat.xaero;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import xaero.map.WorldMapSession;
import xaero.map.region.MapTile;

import java.nio.file.Files;
import java.nio.file.Path;

@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public final class XaeroSmoke {
    private static int ticks, state, wait;
    private static boolean fixture;
    private static boolean baseReloadVerified;
    private static boolean realRetagged;
    private static final boolean[] sourcesReloadVerified = new boolean[4];
    private static net.minecraft.world.level.block.state.BlockState realState;
    private static int realX, realZ, realHeight;

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        String output = System.getProperty("voxy.xaeroSmoke");
        if (output == null) return;
        Path base = Path.of(output);
        var mc = Minecraft.getInstance();
        // The fixture must not wait for optional Internet update prompts.
        if (state == 0 && mc.level == null && mc.getOverlay() == null && mc.screen != null
                && mc.screen.getClass().getName().equals("xaero.lib.client.gui.GuiUpdateAll")) mc.setScreen(new TitleScreen());
        if (++ticks > 6000) throw new AssertionError("XAERO_SMOKE timeout state=" + state);
        if (ticks % 200 == 0) System.out.println("XAERO_SMOKE progress state=" + state + " screen=" + mc.screen + " level=" + mc.level);
        boolean reload = Boolean.getBoolean("voxy.xaeroReload");
        // setMultiworld opens GuiMap. Keep the test server and native writer
        // running while we wait for background commits and dimension travel.
        if (state >= 20 && mc.level != null && mc.screen != null) mc.setScreen(null);
        if (state == 0 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            state = 1;
            VoxyConfig.CONFIG.xaeroMapCoarse = false;
            VoxyConfig.CONFIG.xaeroMapTilesPerSecond = 128;
            VoxyConfig.CONFIG.ingestEnabled = false;
            if (reload) mc.createWorldOpenFlows().openWorld("fixture", () -> { throw new AssertionError("reload cancelled"); });
            else mc.createWorldOpenFlows().createFreshLevel("fixture",
                    new LevelSettings("Xaero isolated fixture", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(42, false, false),
                    registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                    new TitleScreen());
        }
        if (state == 1 && mc.level != null && mc.player != null) {
            mc.setScreen(null);
            var id = WorldIdentifier.of(mc.level);
            if (id == null) return;
            var engine = id.getOrCreateEngine();
            var session = WorldMapSession.getCurrentSession();
            if (session == null || !session.isUsable()) return;
            var processor = session.getMapProcessor();
            if (!processor.getMapSaveLoad().isRegionDetectionComplete() || processor.getWorld() != mc.level) return;
            if (reload) {
                if (!baseReloadVerified) {
                // Creating a leaf consumes its active detection. Check the persistent
                // discovery index on subsequent ticks while the native loader runs.
                if (processor.getMapWorld().getCurrentDimension().getLayeredMapRegions().getLayer(Integer.MAX_VALUE)
                        .getCompleteRegionDetection(-2, -1) == null)
                    throw new AssertionError("supplement missing from native map discovery");
                // No LOD notification: this must be restored from Xaero's own file.
                var region = processor.getLeafMapRegion(Integer.MAX_VALUE, -2, -1, true);
                if (region == null) return;
                region.setBeingWritten(true); // Force source pixels rather than a texture-only cache.
                if (region.getLoadState() != 2) {
                    if (ticks % 200 == 0) System.out.println("XAERO_SMOKE reload region state=" + region.getLoadState()
                            + " resting=" + region.isResting() + " cache=" + region.shouldCache() + " requested=" + region.reloadHasBeenRequested());
                    if (region.isResting()) {
                        region.setShouldCache(true, "isolated reload requires source pixels");
                        processor.getMapSaveLoad().requestLoad(region, "isolated reload probe", false);
                    }
                    return;
                }
                MapTile tile = processor.getMapTile(Integer.MAX_VALUE, -33, -1);
                if (tile == null) throw new AssertionError("native map tile was not saved");
                verify(tile);
                baseReloadVerified = true;
                }
                int[] chunks = {-96, -162, -200, -240};
                net.minecraft.world.level.block.Block[] expected = {Blocks.GOLD_BLOCK, Blocks.EMERALD_BLOCK, Blocks.IRON_BLOCK, Blocks.REDSTONE_BLOCK};
                for (int i = 0; i < chunks.length; i++) {
                    if (sourcesReloadVerified[i]) continue;
                    int c = chunks[i];
                    var saved = processor.getLeafMapRegion(Integer.MAX_VALUE, c >> 5, c >> 5, true);
                    if (saved.getLoadState() != 2) {
                        if (saved.isResting()) {
                            saved.setBeingWritten(true);
                            saved.setShouldCache(true, "isolated reload requires source pixels");
                            processor.getMapSaveLoad().requestLoad(saved, "isolated source reload probe", false);
                        }
                        return;
                    }
                    var restored = processor.getMapTile(Integer.MAX_VALUE, c, c);
                    if (restored == null || !restored.getBlock(0, 0).getState().is(expected[i]))
                        throw new AssertionError("saved fixture missing at " + c);
                    int ownership = ((ProvenancedPixel) restored.getBlock(0, 0)).voxy$getSource();
                    if (SurfaceProvenance.source(ownership) != 1 || SurfaceProvenance.mayReplace(ownership, 4, true))
                        throw new AssertionError("saved precision did not protect fixture at " + c);
                    if (i == 1 && !SurfaceProvenance.provisional(ownership))
                        throw new AssertionError("legacy unknown sky lost its provisional marker");
                    sourcesReloadVerified[i] = true;
                    System.out.println("XAERO_SMOKE source reload verified at " + c);
                }
                var coarse = processor.getMapTile(Integer.MAX_VALUE, -95, -96);
                if (coarse == null || coarse.getBlock(0, 0).getHeight() != 79
                        || ((ProvenancedPixel) coarse.getBlock(0, 0)).voxy$getSource() != SurfaceProvenance.encode(5, true))
                    throw new AssertionError("saved coarse-only source lost");
                Files.writeString(base.resolve("coarse-reload.pass"), "Coarse-only LOD4 terrain and provisional precision retained across restart.\n");
                Files.writeString(base.resolve("reload.pass"), "Native Xaero region reload retained height, water, biome and LOD0 provenance.\n");
                state = 17;
            } else {
                if (!fixture) {
                    fixture = true;
                    // Outside vanilla scan range, spanning y=288 and negative region bounds.
                    int sand = engine.getMapper().getIdForBlockState(Blocks.SAND.defaultBlockState());
                    int water = engine.getMapper().getIdForBlockState(Blocks.WATER.defaultBlockState());
                    int biome = engine.getMapper().getOrCreateBiomeId("minecraft:swamp");
                    for (int cy = mc.level.getMinSection(); cy < mc.level.getMaxSection(); cy++) {
                        var data = VoxelizedSection.createEmpty().setPosition(-33, cy, -1).uniformAir(Mapper.airWithLight(15));
                        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            int by = cy * 16 + y;
                            if (by == 285 || by > 285 && by <= 300)
                                data.section[y * 256 + z * 16 + x] = Mapper.composeMappingId((byte) 15, by == 285 ? sand : water, biome);
                        }
                        data.lvl0NonAirCount = cy == 17 ? 3 * 256 : cy == 18 ? 13 * 256 : 0;
                        WorldVoxilizedSectionMipper.mipSection(data, engine.getMapper());
                        WorldUpdater.insertUpdate(engine, data);
                    }
                    System.out.println("XAERO_SMOKE LOD0 fixture inserted");
                }
                MapTile tile = processor.getMapTile(Integer.MAX_VALUE, -33, -1);
                if (tile == null || !tile.isLoaded() || tile.getBlock(0, 0).getTopHeight() != 300) return;
                verify(tile);
                var chunk = processor.getMapChunk(Integer.MAX_VALUE, -33 >> 2, -1 >> 2);
                if (chunk.getLeafTexture().getHeight(48, 48) != 285) throw new AssertionError("native texture height was not refreshed");
                if (!chunk.getLeafTexture().isCachePrepared()) {
                    // Color buffer update precedes the asynchronous native cache preparation.
                    if (++wait < 40) return;
                }
                Files.writeString(base.resolve("write.pass"), "LOD0 -> native map tile/texture height; top=300, ground=285; known source, swamp, water overlay.\n");
                state = 10;
            }
        }
        if (state >= 10 && state < 20 && mc.level != null) {
            var engine = WorldIdentifier.ofEngine(mc.level);
            var processor = WorldMapSession.getCurrentSession().getMapProcessor();
            if (state == 10) {
                VoxyConfig.CONFIG.xaeroMapCoarse = true;
                var section = engine.acquire(4, -3, 0, -3);
                section.materialize()[me.cortex.voxy.common.world.WorldSection.getIndex(0, 4, 0)] = engine.getMapper().getBaseId((byte) 15,
                        Blocks.STONE.defaultBlockState(), mc.level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS));
                section.materialize()[me.cortex.voxy.common.world.WorldSection.getIndex(1, 4, 0)] = section.get(me.cortex.voxy.common.world.WorldSection.getIndex(0, 4, 0));
                engine.markDirty(section);
                section.release();
                state = 11;
            } else if (state == 11) {
                var tile = processor.getMapTile(Integer.MAX_VALUE, -96, -96);
                if (tile == null || tile.getBlock(0, 0).getHeight() != 79) return;
                if (((ProvenancedPixel) tile.getBlock(0, 0)).voxy$getSource() != SurfaceProvenance.encode(5, true)) throw new AssertionError("coarse source lost");
                plane(engine, -96, -96, 63, Blocks.GOLD_BLOCK.defaultBlockState());
                state = 12;
            } else if (state == 12) {
                var tile = processor.getMapTile(Integer.MAX_VALUE, -96, -96);
                if (tile == null || !tile.getBlock(0, 0).getState().is(Blocks.GOLD_BLOCK)) return;
                // The worker can snapshot the first fine section before the rest
                // of the column is supplied. Allow that provisional improvement
                // to converge before checking the final confirmed result.
                if (SurfaceProvenance.provisional(((ProvenancedPixel) tile.getBlock(0, 0)).voxy$getSource())) return;
                if (tile.getBlock(0, 0).getHeight() != 63 || ((ProvenancedPixel) tile.getBlock(0, 0)).voxy$getSource() != SurfaceProvenance.encode(1, false)) throw new AssertionError("fine replacement failed");
                Files.writeString(base.resolve("precision.pass"), "Real map: LOD4 provisional stone at 79 replaced by confirmed LOD0 gold at 63.\n");
                realX = mc.player.chunkPosition().x;
                realZ = mc.player.chunkPosition().z;
                var real = processor.getMapTile(Integer.MAX_VALUE, realX, realZ);
                if (real == null || !real.isLoaded()) return;
                realState = real.getBlock(0, 0).getState();
                realHeight = real.getBlock(0, 0).getHeight();
                if (((ProvenancedPixel) real.getBlock(0, 0)).voxy$getSource() != 0) throw new AssertionError("native scanner did not own map");
                plane(engine, realX, realZ, 100, Blocks.DIAMOND_BLOCK.defaultBlockState());
                state = 13; wait = 0;
            } else if (state == 13 && ++wait > 120) {
                var realTile = processor.getMapTile(Integer.MAX_VALUE, realX, realZ);
                var real = realTile.getBlock(0, 0);
                if (real.getState() != realState || real.getHeight() != realHeight || ((ProvenancedPixel) real).voxy$getSource() != 0) throw new AssertionError("LOD overwrote native scanned map");
                Files.writeString(base.resolve("real-map.pass"), "Native scanned map protected against conflicting LOD0 update.\n");
                VoxyConfig.CONFIG.xaeroMapSync = false;
                state = 19; wait = 0;
            } else if (state == 19 && ++wait > 10) {
                var realTile = processor.getMapTile(Integer.MAX_VALUE, realX, realZ);
                if (!realRetagged) {
                    // Identical source pixels must become real-owned even if
                    // MapWriter's equality check keeps the original MapBlock.
                    realTile.setWrittenOnce(false);
                    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                        ((ProvenancedPixel) realTile.getBlock(x, z)).voxy$setSource(SurfaceProvenance.encode(1, false));
                    xaero.map.core.XaeroWorldMapCore.chunkCleanField.set(mc.level.getChunk(realX, realZ), false);
                    realRetagged = true; wait = 0;
                    return;
                }
                if (!realTile.wasWrittenOnce()) return;
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                    if (((ProvenancedPixel) realTile.getBlock(x, z)).voxy$getSource() != 0)
                        throw new AssertionError("native scan retained LOD ownership on identical pixels");
                Files.writeString(base.resolve("real-handoff.pass"), "Actual MapWriter scan removed LOD ownership from identical pixels.\n");
                VoxyConfig.CONFIG.xaeroMapSync = true;
                var old = me.cortex.voxy.common.world.WorldSection._createRawUntrackedUnsafeSection(0, -81, 4, -81);
                old.setUniform(Mapper.composeMappingId((byte) 15, engine.getMapper().getIdForBlockState(Blocks.EMERALD_BLOCK.defaultBlockState()), 0));
                engine.storage.saveSection(old);
                // Deliberately only the ordinary cache acquisition notification, no markDirty.
                engine.acquireIfExists(old.key).release();
                state = 14;
            } else if (state == 14) {
                var tile = processor.getMapTile(Integer.MAX_VALUE, -162, -162);
                if (tile == null || !tile.getBlock(0, 0).getState().is(Blocks.EMERALD_BLOCK)) return;
                if (((ProvenancedPixel) tile.getBlock(0, 0)).voxy$getSource() != SurfaceProvenance.encode(1, true)) throw new AssertionError("legacy air was treated as complete sky");
                Files.writeString(base.resolve("cache.pass"), "Ordinary local cache read supplied provisional legacy surface without dirty callback.\n");
                importFixture(base, engine);
                state = 15;
            } else if (state == 15) {
                var tile = processor.getMapTile(Integer.MAX_VALUE, -200, -200);
                if (tile == null || !tile.getBlock(0, 0).getState().is(Blocks.IRON_BLOCK)) return;
                if (tile.getBlock(0, 0).getHeight() != 79) throw new AssertionError("imported surface height");
                Files.writeString(base.resolve("import.pass"), "Synthetic MCA imported through WorldImporter -> Xaero surface.\n");
                networkFixture(engine);
                state = 16;
            } else if (state == 16) {
                var tile = processor.getMapTile(Integer.MAX_VALUE, -240, -240);
                if (tile == null || !tile.getBlock(0, 0).getState().is(Blocks.REDSTONE_BLOCK)) return;
                Files.writeString(base.resolve("receive.pass"), "Real client payload mapping and injection handler -> Xaero surface (in-process payload, not a socket test).\n");
                var coarse = processor.getMapTile(Integer.MAX_VALUE, -95, -96);
                if (coarse == null || coarse.getBlock(0, 0).getHeight() != 79) return;
                state = 2;
            } else if (state == 17) {
                // Make the native region drop its pooled tiles, then submit a
                // change against its saved source. Cached colours alone cannot
                // decide ownership or protect unrelated pixels.
                var region = processor.getLeafMapRegion(Integer.MAX_VALUE, -3, -3, true);
                synchronized (processor.renderThreadPauseSync) {
                    synchronized (region.writerThreadPauseSync) {
                        synchronized (region) {
                            if (!region.isResting() || region.isWritingPaused()) return;
                            region.clearRegion(processor);
                        }
                    }
                }
                plane(engine, -96, -96, -17, Blocks.LAPIS_BLOCK.defaultBlockState());
                state = 18;
            } else if (state == 18) {
                var updated = processor.getMapTile(Integer.MAX_VALUE, -96, -96);
                if (updated == null || !updated.getBlock(0, 0).getState().is(Blocks.LAPIS_BLOCK)
                        || SurfaceProvenance.provisional(((ProvenancedPixel) updated.getBlock(0, 0)).voxy$getSource())) return;
                if (updated.getBlock(0, 0).getHeight() != -17) throw new AssertionError("negative surface height lost");
                var untouched = processor.getMapTile(Integer.MAX_VALUE, -95, -96);
                if (untouched == null || untouched.getBlock(0, 0).getHeight() != 79
                        || ((ProvenancedPixel) untouched.getBlock(0, 0)).voxy$getSource() != SurfaceProvenance.encode(5, true))
                    throw new AssertionError("cache-only promotion lost saved neighbour");
                Files.writeString(base.resolve("eviction.pass"), "LOD update reloaded native source after tile eviction; negative height and adjacent saved provenance retained.\n");
                state = 20;
            }
        }
        if (state == 20) {
            // No previous-session work may leak into a different Xaero subworld.
            var p = WorldMapSession.getCurrentSession().getMapProcessor();
            p.setMultiworld(p.getMapWorld().getCurrentDimension(), "voxy-isolated-other");
            state = 21; wait = 0;
        } else if (state == 21) {
            var p = WorldMapSession.getCurrentSession().getMapProcessor();
            if (!"voxy-isolated-other".equals(p.getCurrentMWId()) || p.isWaitingForWorldUpdate()) return;
            if (++wait < 60) return;
            if (p.getMapTile(Integer.MAX_VALUE, -33, -1) != null) throw new AssertionError("previous subworld work leaked");
            Files.writeString(base.resolve("subworld.pass"), "Xaero subworld switch isolated previous map tasks.\n");
            p.getMapWorld().getCurrentDimension().confirmMultiworldUnsynced();
            state = 26;
        } else if (state == 26) {
            var p = WorldMapSession.getCurrentSession().getMapProcessor();
            if (!p.isCurrentMultiworldWritable() || p.isWaitingForWorldUpdate()) return;
            var field = XaeroSurfaceBridge.class.getDeclaredField("current"); field.setAccessible(true);
            if (field.get(null) == null) return;
            plane(WorldIdentifier.ofEngine(mc.level), -300, -300, -20, Blocks.COPPER_BLOCK.defaultBlockState());
            state = 23;
        } else if (state == 23) {
            var p = WorldMapSession.getCurrentSession().getMapProcessor();
            var tile = p.getMapTile(Integer.MAX_VALUE, -300, -300);
            if (ticks % 200 == 0) {
                var region = p.getLeafMapRegion(Integer.MAX_VALUE, -300 >> 5, -300 >> 5, true);
                System.out.println("XAERO_SMOKE ZIP state=" + region.getLoadState() + " resting=" + region.isResting()
                        + " tile=" + (tile == null ? null : tile.getBlock(0, 0).getState())
                        + " source=" + (tile == null ? 0 : ((ProvenancedPixel) tile.getBlock(0, 0)).voxy$getSource()));
            }
            if (tile == null || !tile.getBlock(0, 0).getState().is(Blocks.COPPER_BLOCK)
                    || SurfaceProvenance.provisional(((ProvenancedPixel) tile.getBlock(0, 0)).voxy$getSource())) return;
            p.setMultiworld(p.getMapWorld().getCurrentDimension(), "");
            state = 24; wait = 0;
        } else if (state == 24) {
            var p = WorldMapSession.getCurrentSession().getMapProcessor();
            if (!"".equals(p.getCurrentMWId()) || p.isWaitingForWorldUpdate() || ++wait < 60) return;
            p.setMultiworld(p.getMapWorld().getCurrentDimension(), "voxy-isolated-other");
            state = 25;
        } else if (state == 25) {
            var p = WorldMapSession.getCurrentSession().getMapProcessor();
            if (!"voxy-isolated-other".equals(p.getCurrentMWId()) || p.isWaitingForWorldUpdate()
                    || !p.getMapSaveLoad().isRegionDetectionComplete()) return;
            var region = p.getLeafMapRegion(Integer.MAX_VALUE, -300 >> 5, -300 >> 5, true);
            if (region.getLoadState() != 2) {
                if (region.isResting()) {
                    region.setBeingWritten(true);
                    region.setShouldCache(true, "isolated native ZIP requires source pixels");
                    p.getMapSaveLoad().requestLoad(region, "isolated native ZIP reload", false);
                }
                return;
            }
            var tile = p.getMapTile(Integer.MAX_VALUE, -300, -300);
            if (tile == null || !tile.getBlock(0, 0).getState().is(Blocks.COPPER_BLOCK)
                    || tile.getBlock(0, 0).getHeight() != -20
                    || ((ProvenancedPixel) tile.getBlock(0, 0)).voxy$getSource() != SurfaceProvenance.encode(1, false))
                throw new AssertionError("native ZIP reload lost height or provenance");
            if (!region.isNormalMapData()) throw new AssertionError("custom subworld must use native ZIP source");
            Files.writeString(base.resolve("native-zip.pass"), "Custom subworld native ZIP save/load retained negative height and precision.\n");
            mc.getConnection().sendCommand("execute in minecraft:the_nether run tp @s 0 80 0");
            state = 22;
        } else if (state == 22 && mc.level != null && mc.level.dimension() == Level.NETHER) {
            var field = XaeroSurfaceBridge.class.getDeclaredField("current"); field.setAccessible(true);
            if (field.get(null) != null) return;
            Files.writeString(base.resolve("dimension.pass"), "Dimension switch stopped surface worker in Nether.\n");
            state = 2;
        }
        if (state == 2) {
            state = 3;
            mc.level.disconnect();
            mc.disconnect(new TitleScreen());
            wait = 0;
        }
        if (state == 3 && ++wait > 100) {
            System.out.println("XAERO_SMOKE PASS reload=" + reload);
            mc.stop();
        }
    }

    private static void plane(WorldEngine engine, int cx, int cz, int height, net.minecraft.world.level.block.state.BlockState block) {
        var level = Minecraft.getInstance().level;
        int id = engine.getMapper().getIdForBlockState(block);
        for (int sy = level.getMinSection(); sy < level.getMaxSection(); sy++) {
            var data = VoxelizedSection.createEmpty().setPosition(cx, sy, cz).uniformAir(Mapper.airWithLight(15));
            if ((height >> 4) == sy) {
                for (int i = 0; i < 256; i++) data.section[(height & 15) * 256 + i] = Mapper.composeMappingId((byte) 15, id, 0);
                data.lvl0NonAirCount = 256;
            }
            WorldVoxilizedSectionMipper.mipSection(data, engine.getMapper());
            WorldUpdater.insertUpdate(engine, data);
        }
    }

    private static void importFixture(Path base, WorldEngine engine) throws Exception {
        Path folder = base.resolve("import-fixture"); Files.createDirectories(folder);
        var chunk = new net.minecraft.nbt.CompoundTag();
        chunk.putString("Status", "minecraft:full"); chunk.putInt("xPos", -200); chunk.putInt("zPos", -200);
        chunk.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        var sections = new net.minecraft.nbt.ListTag();
        for (int y = -4; y < 20; y++) {
            var section = new net.minecraft.nbt.CompoundTag(); section.putByte("Y", (byte) y);
            var states = new net.minecraft.nbt.CompoundTag(); var palette = new net.minecraft.nbt.ListTag();
            var block = new net.minecraft.nbt.CompoundTag(); block.putString("Name", y == 4 ? "minecraft:iron_block" : "minecraft:air");
            palette.add(block); states.put("palette", palette); section.put("block_states", states); sections.add(section);
        }
        chunk.put("sections", sections);
        try (var region = new net.minecraft.world.level.chunk.storage.RegionFile(
                new net.minecraft.world.level.chunk.storage.RegionStorageInfo("fixture", Level.OVERWORLD, "chunk"), folder.resolve("r.-7.-7.mca"), folder, true)) {
            try (var out = region.getChunkDataOutputStream(new ChunkPos(-200, -200))) { net.minecraft.nbt.NbtIo.write(chunk, out); }
        }
        var importer = new me.cortex.voxy.commonImpl.importers.WorldImporter(engine, Minecraft.getInstance().level,
                engine.instanceIn.getServiceManager(), () -> true);
        importer.importRegionDirectoryAsync(folder.toFile());
        if (!engine.instanceIn.getImportManager().tryRunImport(importer)) throw new AssertionError("importer rejected fixture");
    }

    private static void networkFixture(WorldEngine engine) throws Exception {
        var constructor = me.cortex.voxy.client.VoxyClientNetwork.class.getDeclaredConstructor(); constructor.setAccessible(true);
        var handler = constructor.newInstance();
        String dim = "minecraft:overworld";
        handler.handleBlockMaps(new me.cortex.voxy.commonImpl.network.VoxyNetwork.BlockMapBatchS2C(1,
                java.util.List.of(new Mapper.StateEntry(1, Blocks.REDSTONE_BLOCK.defaultBlockState()).serialize())));
        handler.handleBiomeMaps(new me.cortex.voxy.commonImpl.network.VoxyNetwork.BiomeMapBatchS2C(0, java.util.List.of("minecraft:plains")));
        var section = me.cortex.voxy.common.world.WorldSection._createRawUntrackedUnsafeSection(0, -120, 2, -120);
        section.setUniform(Mapper.composeMappingId((byte) 15, 1, 0));
        var bytes = me.cortex.voxy.common.world.SaveLoadSystem3.serialize(section);
        byte[] payload = new byte[(int) bytes.size]; org.lwjgl.system.MemoryUtil.memByteBuffer(bytes.address, payload.length).get(payload);
        handler.handleSectionData(new me.cortex.voxy.commonImpl.network.VoxyNetwork.SectionDataS2C(dim, 0, -120, 2, -120, payload));
    }

    private static void verify(MapTile tile) {
        var pixel = tile.getBlock(0, 0);
        if (!pixel.getState().is(Blocks.SAND) || pixel.getHeight() != 285 || pixel.getTopHeight() != 300
                || pixel.getNumberOfOverlays() != 1 || !pixel.getOverlays().getFirst().getState().is(Blocks.WATER)
                || !pixel.getBiome().location().toString().equals("minecraft:swamp")
                || ((ProvenancedPixel) pixel).voxy$getSource() != SurfaceProvenance.encode(1, false)) {
            throw new AssertionError("unexpected native map pixel: " + pixel.getState() + " " + pixel.getHeight() + "/" + pixel.getTopHeight()
                    + " source=" + ((ProvenancedPixel) pixel).voxy$getSource());
        }
    }
}
