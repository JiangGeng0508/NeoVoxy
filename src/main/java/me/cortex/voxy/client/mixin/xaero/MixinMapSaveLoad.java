package me.cortex.voxy.client.mixin.xaero;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.voxy.client.compat.xaero.ProvenancedPixel;
import me.cortex.voxy.client.compat.xaero.SingleplayerSurfaceStore;
import me.cortex.voxy.client.compat.xaero.SupplementedRegion;
import me.cortex.voxy.client.compat.xaero.XaeroSurfaceCompat;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.MapProcessor;
import xaero.map.biome.BiomeGetter;
import xaero.map.file.MapSaveLoad;
import xaero.map.file.RegionDetection;
import xaero.map.region.MapBlock;
import xaero.map.region.MapRegion;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@Mixin(value = MapSaveLoad.class, remap = false)
public class MixinMapSaveLoad {
    @Shadow private MapProcessor mapProcessor;

    @Inject(method = "detectRegions", at = @At("RETURN"))
    private void voxy$detectSupplement(int attempts, CallbackInfo ci) {
        if (!XaeroSurfaceCompat.supplementalReadsAvailable()) return;
        try {
            this.voxy$detectSupplement();
        } catch (Exception | LinkageError failure) {
            XaeroSurfaceCompat.reportFailure(failure);
        }
    }

    @Unique
    private void voxy$detectSupplement() {
        var processor = this.mapProcessor;
        var dimension = processor.getMapWorld().getCurrentDimension();
        if (!dimension.isUsingWorldSave() || processor.getCurrentWorldId() == null || processor.isCurrentMapLocked()) return;
        var io = (MapSaveLoad) (Object) this;
        var folder = io.getMainFolder(processor.getCurrentWorldId(), processor.getCurrentDimId()).resolve("voxy-surface");
        if (!Files.isDirectory(folder)) return;
        var layer = dimension.getLayeredMapRegions().getLayer(Integer.MAX_VALUE);
        // Enumerates Xaero's own saved map region files, never the Voxy database.
        io.detectRegionsFromFiles(dimension, processor.getCurrentWorldId(), processor.getCurrentDimId(), processor.getCurrentMWId(),
                folder, "^(-?\\d+)_(-?\\d+)\\.zip$", 1, 2, 0, 1, detected -> {
                    if (!layer.regionDetectionExists(detected.getRegionX(), detected.getRegionZ())) {
                        // Keep MCA as the primary source; the supplement is merged after it.
                        layer.addRegionDetection(new RegionDetection(detected.getWorldId(), detected.getDimId(), detected.getMwId(),
                                detected.getRegionX(), detected.getRegionZ(), null, processor.getGlobalVersion(), true));
                    }
                });
    }

    @Inject(method = "getNormalFile", at = @At("HEAD"), cancellable = true)
    private void voxy$supplementFile(MapRegion region, CallbackInfoReturnable<File> cir) throws IOException {
        if (SingleplayerSurfaceStore.applies(region) && ((SupplementedRegion) region).voxy$hasSupplement()) {
            var path = SingleplayerSurfaceStore.path((MapSaveLoad) (Object) this, region);
            Files.createDirectories(path.getParent());
            cir.setReturnValue(path.toFile());
        }
    }

    @WrapMethod(method = "saveRegion")
    private boolean voxy$saveSupplement(MapRegion region, boolean debug, int attempts, Operation<Boolean> original) {
        if (SingleplayerSurfaceStore.applies(region) && ((SupplementedRegion) region).voxy$hasSupplement()) {
            try {
                region.setResaving(true);
                return original.call(region, debug, attempts);
            } catch (Exception | LinkageError failure) {
                XaeroSurfaceCompat.reportFailure(failure);
                return false;
            }
        }
        return original.call(region, debug, attempts);
    }

    @WrapMethod(method = "loadRegion")
    private boolean voxy$loadSupplement(MapRegion region, HolderLookup<Block> blocks, Registry<Block> registry,
                                        Registry<Fluid> fluids, BiomeGetter getter, boolean debug, int attempts,
                                        Operation<Boolean> original) {
        boolean loaded = original.call(region, blocks, registry, fluids, getter, debug, attempts);
        if (SingleplayerSurfaceStore.applies(region) && XaeroSurfaceCompat.supplementalReadsAvailable()) {
            MapRegion supplement = null;
            try {
                var path = SingleplayerSurfaceStore.path((MapSaveLoad) (Object) this, region);
                if (Files.isRegularFile(path)) {
                    supplement = new MapRegion(region.getWorldId(), region.getDimId(), region.getMwId(), region.getDim(),
                            region.getRegionX(), region.getRegionZ(), Integer.MAX_VALUE, region.getInitialVersion(), true, region.getBiomeRegistry());
                    supplement.setRegionFile(path.toFile());
                    supplement.setParent(region.getParent());
                    supplement.setHasHadTerrain();
                    if (original.call(supplement, blocks, registry, fluids, getter, debug, attempts)) {
                        SingleplayerSurfaceStore.merge(region, supplement, this.mapProcessor);
                        loaded = true;
                    } else {
                        XaeroSurfaceCompat.reportFailure(new IOException("Could not read Xaero LOD map supplement: " + path));
                    }
                }
            } catch (Exception | LinkageError failure) {
                XaeroSurfaceCompat.reportFailure(failure);
            } finally {
                if (supplement != null) {
                    try {
                        SingleplayerSurfaceStore.release(supplement, this.mapProcessor);
                    } catch (Exception | LinkageError failure) {
                        XaeroSurfaceCompat.reportFailure(failure);
                    }
                }
            }
        }
        return loaded;
    }

    @WrapMethod(method = "loadPixel")
    private void voxy$loadSource(Integer next, MapBlock pixel, DataInputStream in,
                                 int minor, int major, boolean is115, HolderLookup<Block> blocks,
                                 BiomeGetter getter, Registry<Biome> biomes, Operation<Void> original) throws IOException {
        int header = next == null ? in.readInt() : next;
        original.call(header, pixel, in, minor, major, is115, blocks, getter, biomes);
        ((ProvenancedPixel) pixel).voxy$setSource(major == 6 && minor == 8 ? header & 0xE0000080 : 0);
        if (major == 6 && minor == 8 && (header >>> 29) != 0) {
            int top = (pixel.getTopHeight() & 255) | (((header >>> 2) & 15) << 8);
            pixel.setTopHeight(top << 20 >> 20);
        }
    }
}
