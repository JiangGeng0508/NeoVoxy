package me.cortex.voxy.client.compat.seedgen;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SeedGenBytecodeTest implements Opcodes {
    @Test void meshSchedulingRetainsSeedGensActualInjectionSites() throws Exception {
        var service = new ClassNode();
        new ClassReader("me.cortex.voxy.client.core.rendering.building.RenderGenerationService").accept(service, 0);
        var process = service.methods.stream().filter(m -> m.name.equals("processJob")).findFirst().orElseThrow();
        assertEquals("(Lme/cortex/voxy/client/core/rendering/building/RenderDataFactory;Lit/unimi/dsi/fastutil/ints/IntOpenHashSet;)V", process.desc);
        int polls = 0, publishes = 0;
        for (var instruction : process.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("java/util/concurrent/PriorityBlockingQueue") && call.name.equals("poll") && call.desc.equals("()Ljava/lang/Object;")) polls++;
                if (call.owner.equals("java/util/function/Consumer") && call.name.equals("accept") && call.desc.equals("(Ljava/lang/Object;)V")) publishes++;
            }
        }
        assertEquals(1, polls, "SeedGen numbers each build at the actual queue poll");
        assertEquals(2, publishes, "SeedGen freshness wraps both empty and populated results");
        var task = new ClassNode();
        new ClassReader("me.cortex.voxy.client.core.rendering.building.RenderGenerationService$BuildTask").accept(task, 0);
        assertTrue(task.fields.stream().anyMatch(f -> f.name.equals("position") && f.desc.equals("J")));
        var priority = task.methods.stream().filter(m -> m.name.equals("updatePriority")).findFirst().orElseThrow();
        boolean min = false;
        for (var instruction : priority.instructions) if (instruction instanceof MethodInsnNode call
                && call.owner.equals("java/lang/Math") && call.name.equals("min") && call.desc.equals("(II)I")) min = true;
        assertTrue(min, "SeedGen clamps extended LOD priorities at this call");
        var queue = new ClassNode();
        new ClassReader("me.cortex.voxy.client.core.rendering.building.MeshTaskQueue").accept(queue, 0);
        var schedule = queue.methods.stream().filter(m -> m.name.equals("schedule")).findFirst().orElseThrow();
        boolean used = false;
        for (var instruction : schedule.instructions) if (instruction instanceof MethodInsnNode call
                && call.owner.equals(task.name) && call.name.equals("updatePriority")) used = true;
        assertTrue(used, "The compatible task must be used by the production state machine");
    }

    @Test void upstreamDescriptorsLinkAndFractionalDistanceSurvivesBothWidenings() throws Exception {
        var node = new ClassNode();
        node.version = V21;
        node.access = ACC_PUBLIC;
        node.name = "SeedGenAbiFixture";
        node.superName = "java/lang/Object";
        for (boolean asDouble : new boolean[]{false, true}) {
            var method = new MethodNode(ACC_PUBLIC | ACC_STATIC, asDouble ? "distanceD" : "distanceF",
                    asDouble ? "()D" : "()F", null, null);
            method.instructions.add(new FieldInsnNode(GETSTATIC, "me/cortex/voxy/client/config/VoxyConfig", "CONFIG",
                    "Lme/cortex/voxy/client/config/VoxyConfig;"));
            method.instructions.add(new FieldInsnNode(GETFIELD, "me/cortex/voxy/client/config/VoxyConfig", "sectionRenderDistance", "I"));
            method.instructions.add(new InsnNode(asDouble ? I2D : I2F));
            method.instructions.add(new InsnNode(asDouble ? DRETURN : FRETURN));
            node.methods.add(method);
        }
        var save = new MethodNode(ACC_PUBLIC | ACC_STATIC, "save",
                "(Lme/cortex/voxy/common/world/WorldEngine;Lme/cortex/voxy/common/world/WorldSection;)V", null, null);
        save.instructions.add(new VarInsnNode(ALOAD, 0));
        save.instructions.add(new VarInsnNode(ALOAD, 1));
        save.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, "me/cortex/voxy/common/world/WorldEngine", "saveSection",
                "(Lme/cortex/voxy/common/world/WorldSection;)V", false));
        save.instructions.add(new InsnNode(RETURN));
        node.methods.add(save);
        var log = new MethodNode(ACC_PUBLIC | ACC_STATIC, "log", "([Ljava/lang/Object;)V", null, null);
        log.instructions.add(new VarInsnNode(ALOAD, 0));
        log.instructions.add(new MethodInsnNode(INVOKESTATIC, "me/cortex/voxy/common/Logger", "info", "([Ljava/lang/Object;)V", false));
        log.instructions.add(new InsnNode(RETURN));
        node.methods.add(log);
        SeedGenBytecode.adapt(node);
        SeedGenBytecode.adapt(node); // Multiple mixin post-apply passes must not insert extra POPs.
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Class<?> fixture = new ClassLoader(getClass().getClassLoader()) {
            Class<?> loadFixture() { byte[] bytes = writer.toByteArray(); return defineClass(null, bytes, 0, bytes.length); }
        }.loadFixture();
        float previous = VoxyConfig.CONFIG.sectionRenderDistance;
        var engine = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend()));
        AtomicInteger saved = new AtomicInteger();
        engine.setSaveCallback((world, section, nonBlocking, acquired) -> { saved.incrementAndGet(); return true; });
        try {
            VoxyConfig.CONFIG.sectionRenderDistance = 0.625f;
            assertEquals(0.625f, fixture.getMethod("distanceF").invoke(null));
            assertEquals(0.625d, fixture.getMethod("distanceD").invoke(null));
            fixture.getMethod("save", WorldEngine.class, WorldSection.class).invoke(null, engine, null);
            assertEquals(1, saved.get());
            fixture.getMethod("log", Object[].class).invoke(null, (Object) new Object[]{"SeedGen ABI fixture"});
        } finally { VoxyConfig.CONFIG.sectionRenderDistance = previous; engine.free(); }
    }

    @Test void unrecognisedIntegerUseFailsInsteadOfProducingInvalidBytecode() {
        var node = new ClassNode();
        node.name = "UnknownSeedGen";
        var method = new MethodNode();
        method.name = "distance";
        method.instructions.add(new FieldInsnNode(GETFIELD, "me/cortex/voxy/client/config/VoxyConfig", "sectionRenderDistance", "I"));
        method.instructions.add(new InsnNode(IRETURN));
        node.methods.add(method);
        assertThrows(IllegalStateException.class, () -> SeedGenBytecode.adapt(node));
    }

    @Test void optionalFarPlaneExtensionPreservesDynamicProjection() {
        assertEquals(1000f, SeedGenCompat.projectionFarPlane(1000f, 48000f));
        assertEquals(96000f, SeedGenCompat.projectionFarPlane(1000f, 96000f));
        assertEquals(120000f, SeedGenCompat.projectionFarPlane(120000f, 96000f));
    }
}
