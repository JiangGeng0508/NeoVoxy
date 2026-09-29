package me.cortex.voxy.client;

import com.mojang.blaze3d.platform.InputConstants;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public final class VoxyKeyBindings {
    private static final String CATEGORY = "key.categories.voxy";
    private static final KeyMapping RELOAD = new KeyMapping("key.voxy.reload", InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(), CATEGORY);
    private static final KeyMapping TOGGLE_RENDERING = new KeyMapping("key.voxy.toggle_rendering", InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(), CATEGORY);

    private VoxyKeyBindings() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (consume(RELOAD)) {
            message(mc, VoxyCommands.reloadInstance() ? "message.voxy.reloaded" : "message.voxy.reload_unavailable");
        }
        if (consume(TOGGLE_RENDERING)) {
            VoxyConfig.CONFIG.enableRendering = !VoxyConfig.CONFIG.enableRendering;
            VoxyConfig.CONFIG.save();
            applyRenderingState(mc);
            message(mc, VoxyConfig.CONFIG.enableRendering
                    ? "message.voxy.rendering_enabled" : "message.voxy.rendering_disabled");
        }
    }

    private static void applyRenderingState(Minecraft mc) {
        var holder = (IGetVoxyRenderSystem) mc.levelRenderer;
        if (holder == null) return;
        boolean enabled = VoxyConfig.CONFIG.isRenderingEnabled();
        boolean active = holder.voxy$getRenderSystem() != null;
        if (enabled == active) return;
        if (enabled) holder.voxy$createRenderer();
        else holder.voxy$shutdownRenderer();
    }

    private static boolean consume(KeyMapping mapping) {
        if (!mapping.consumeClick()) return false;
        while (mapping.consumeClick()) {}
        return true;
    }

    private static void message(Minecraft mc, String key) {
        if (mc.player != null) mc.gui.getChat().addMessage(Component.translatable(key));
    }

    @EventBusSubscriber(modid = "voxy", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {}

        @SubscribeEvent
        public static void register(RegisterKeyMappingsEvent event) {
            event.register(RELOAD);
            event.register(TOGGLE_RENDERING);
        }
    }
}
