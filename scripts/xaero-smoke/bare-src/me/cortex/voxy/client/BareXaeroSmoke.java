package me.cortex.voxy.client;

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
public final class BareXaeroSmoke {
    private static int ticks;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        String output = System.getProperty("voxy.xaeroSmoke");
        if (output == null) return;
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof TitleScreen && mc.getOverlay() == null && ++ticks > 40) {
            if (ModList.get().isLoaded("xaeroworldmap")) throw new AssertionError("bare probe must not install Xaero");
            Files.writeString(Path.of(output).resolve("write.pass"), "Client reached title screen and shut down without Xaero installed.\n");
            System.out.println("XAERO_SMOKE BARE PASS");
            mc.stop();
        }
    }
}
