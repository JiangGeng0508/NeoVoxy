package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.model.IdNotYetComputedException;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.RenderDataFactory;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import java.util.Arrays;

/** Opt-in real-model regression before timed frames; never writes fixture geometry to the world. */
final class SectionSnapshotCheck {
    private static final String[] CASES = {"minecraft:air", "minecraft:stone", "minecraft:glass", "minecraft:water", "create:andesite_casing"};
    private static RenderDataFactory builder;
    private static WorldEngine world;
    private static ModelBakerySubsystem models;
    private static int next;

    static boolean step(VoxyRenderSystem system) throws Exception {
        if (next == CASES.length) return true;
        if (builder == null) {
            var worldField = VoxyRenderSystem.class.getDeclaredField("worldIn"); worldField.setAccessible(true);
            var modelsField = VoxyRenderSystem.class.getDeclaredField("modelService"); modelsField.setAccessible(true);
            world = (WorldEngine) worldField.get(system); models = (ModelBakerySubsystem) modelsField.get(system);
            builder = new RenderDataFactory(world, models.factory, false);
        }
        var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(CASES[next])).defaultBlockState();
        int block = world.getMapper().getIdForBlockState(state);
        models.requestBlockBake(block);
        var uniform = WorldSection._createRawUntrackedUnsafeSection(0, 100000, 0, 100000);
        var dense = WorldSection._createRawUntrackedUnsafeSection(0, 100000, 0, 100000);
        uniform.acquire(); dense.acquire();
        long value = Mapper.composeMappingId((byte) 15, block, 0);
        uniform.setUniform(value); dense.setUniform(value); dense.materialize();
        uniform._unsafeSetNonEmptyChildren((byte) 57); dense._unsafeSetNonEmptyChildren((byte) 57);
        // A missing disk section returns null once, then cached placeholder air on later reads.
        // Pin the same 26 neighbours for both representations so lighting/availability is identical.
        var neighbors = new java.util.ArrayList<WorldSection>();
        for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
            if ((x | y | z) != 0) neighbors.add(world.acquire(0, uniform.x + x, y, uniform.z + z));
        }
        BuiltSection a = null, b = null;
        try {
            a = builder.generateMesh(uniform);
            b = builder.generateMesh(dense);
            check(uniform.isUniform() && uniform.get(0) == value, "uniform source was expanded or modified");
            check(a.childExistence == 57 && b.childExistence == 57 && a.aabb == b.aabb, "snapshot metadata changed");
            check(Arrays.equals(a.offsets, b.offsets) && a.isEmpty() == b.isEmpty(), "geometry layout changed");
            if (!a.isEmpty()) check(a.geometryBuffer.asByteBuffer().equals(b.geometryBuffer.asByteBuffer()), "geometry bytes changed");
            for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) check(dense.get(i) == value, "substitution modified source");
            System.out.println("SECTION_SNAPSHOT case=" + CASES[next] + " geometryBytes=" + (a.isEmpty() ? 0 : a.geometryBuffer.size));
            next++;
        } catch (IdNotYetComputedException missing) {
            if (missing.isIdBlockId) models.requestBlockBake(missing.id);
            if (missing.substitutedSection != null) {
                var ids = new java.util.HashSet<Integer>();
                for (long voxel : missing.substitutedSection) ids.add(Mapper.getBlockId(voxel));
                for (int id : ids) models.requestBlockBake(id);
            }
        } finally {
            if (a != null) a.free(); if (b != null) b.free(); uniform.release(); dense.release();
            for (var neighbor : neighbors) neighbor.release(WorldSection.RELEASE_HINT_DONT_CACHE);
        }
        if (next != CASES.length) return false;
        builder.free(); builder = null; world = null; models = null;
        System.out.println("SECTION_SNAPSHOT_PASS cases=" + CASES.length);
        return true;
    }

    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
