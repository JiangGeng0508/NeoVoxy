package me.cortex.voxy.client.compat.pingwheel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.nio.file.Files;
import java.nio.file.Path;

@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public final class PingWheelSmokeEntry {
    private static int ticks;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        String output = System.getProperty("voxy.pingwheelSmoke");
        if (output == null) return;
        var mc = Minecraft.getInstance();
        if (Boolean.getBoolean("voxy.pingwheelBare")) {
            if (mc.screen instanceof TitleScreen && mc.getOverlay() == null && ++ticks > 40) {
                if (ModList.get().isLoaded("pingwheel") || PingWheelGate.supported()) throw new AssertionError("bare gate");
                PingWheelCompat.stop();
                Files.writeString(Path.of(output).resolve("bare.pass"), "Title screen and shutdown without PingWheel.\n");
                mc.stop();
            }
        } else PingWheelSmoke.tick(Path.of(output));
    }
}
