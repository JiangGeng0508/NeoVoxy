package me.cortex.voxy.client.mixin.seedgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Post-apply also sees the handlers SeedGen merges into the two NeoVoxy targets. */
@Pseudo
@Mixin(targets = {
        "mobius.voxyseedgen.voxy.LodLevels",
        "mobius.voxyseedgen.voxy.RenderReach",
        "mobius.voxyseedgen.voxy.CoarseRings",
        "mobius.voxyseedgen.voxy.LadderWriter",
        "me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser",
        "me.cortex.voxy.client.core.rendering.building.RenderGenerationService$BuildTask"
}, remap = false, priority = 500)
public abstract class MixinSeedGenAbi {}
