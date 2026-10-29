package me.cortex.voxy.client.compat.seedgen;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Adapts SeedGen 0.3's upstream Voxy descriptors without changing NeoVoxy's public API. */
public final class SeedGenBytecode {
    private SeedGenBytecode() {}

    public static void adapt(ClassNode target) {
        for (MethodNode method : target.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETFIELD
                        && field.owner.equals("me/cortex/voxy/client/config/VoxyConfig")
                        && field.name.equals("sectionRenderDistance") && field.desc.equals("I")) {
                    // Every 0.3 reader immediately widens the old int. Preserve fractional distances
                    // instead of converting NeoVoxy's float to an int and silently truncating it.
                    AbstractInsnNode widening = field.getNext();
                    while (widening != null && widening.getOpcode() < 0) widening = widening.getNext();
                    if (widening == null || (widening.getOpcode() != Opcodes.I2F && widening.getOpcode() != Opcodes.I2D)) {
                        throw new IllegalStateException("Unsupported SeedGen distance read in " + target.name + "." + method.name);
                    }
                    field.desc = "F";
                    if (widening.getOpcode() == Opcodes.I2F) method.instructions.remove(widening);
                    else method.instructions.set(widening, new InsnNode(Opcodes.F2D));
                } else if (instruction instanceof MethodInsnNode call) {
                    if (call.owner.equals("me/cortex/voxy/common/world/WorldEngine")
                            && call.name.equals("saveSection")
                            && call.desc.equals("(Lme/cortex/voxy/common/world/WorldSection;)V")) {
                        call.desc = "(Lme/cortex/voxy/common/world/WorldSection;)Z";
                        method.instructions.insert(call, new InsnNode(Opcodes.POP));
                    } else if (call.owner.equals("me/cortex/voxy/common/Logger")
                            && call.name.equals("info") && call.desc.equals("([Ljava/lang/Object;)V")) {
                        call.desc = "([Ljava/lang/Object;)Ljava/lang/String;";
                        method.instructions.insert(call, new InsnNode(Opcodes.POP));
                    }
                }
            }
        }
    }
}
