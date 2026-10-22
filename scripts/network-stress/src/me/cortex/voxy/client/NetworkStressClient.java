package me.cortex.voxy.client;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.network.VoxyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public final class NetworkStressClient {
    private static VoxyNetwork.ClientHandler delegate;
    private static int tick, worldTicks, responses, fixtures;
    private static long bytes;
    private static final Set<Long> latest = new HashSet<>(), absent = new HashSet<>();
    private static int emptyReplies, airReplies;
    private static boolean passed;

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        String output = System.getProperty("voxy.networkStress");
        if (output == null) return;
        var mc = Minecraft.getInstance();
        if (++tick > 7200) throw new AssertionError("NETWORK_STRESS client timeout");
        if (delegate == null) {
            var field = VoxyNetwork.class.getDeclaredField("clientHandler"); field.setAccessible(true);
            delegate = (VoxyNetwork.ClientHandler) field.get(null);
            VoxyNetwork.setClientHandler(new VoxyNetwork.ClientHandler() {
                public boolean acceptsConnection(Connection c) { return delegate.acceptsConnection(c); }
                public void handleHello(VoxyNetwork.HelloS2C p) { delegate.handleHello(p); }
                public void handleBlockMaps(VoxyNetwork.BlockMapBatchS2C p) { delegate.handleBlockMaps(p); }
                public void handleBiomeMaps(VoxyNetwork.BiomeMapBatchS2C p) { delegate.handleBiomeMaps(p); }
                public void handleMappingDelta(VoxyNetwork.MappingDeltaS2C p) { delegate.handleMappingDelta(p); }
                public void handleSectionData(VoxyNetwork.SectionDataS2C p) {
                    delegate.handleSectionData(p);
                    responses++; bytes += VoxyNetwork.encodedSize(p);
                    if (p.lvl() != 0 || p.y() != 0) return;
                    long key = WorldEngine.getWorldSectionId(p.lvl(), p.x(), p.y(), p.z());
                    if (p.x() >= 400 && p.x() < 500 && p.z() >= 400 && p.z() < 406) {
                        if (p.data().length == 0) emptyReplies++;
                        else {
                            var section = WorldIdentifier.ofEngineNullable(mc.level).acquireIfExists(key);
                            if (section == null) throw new AssertionError("air response was not applied");
                            try {
                                for (int i = 0; i < 32768; i++) if (!Mapper.isAir(section.get(i)))
                                    throw new AssertionError("expected absent/air terrain at " + key);
                            } finally { section.release(); }
                            airReplies++;
                        }
                        absent.add(key);
                    }
                    if (p.x() < 200 || p.x() >= 248 || p.z() < 200 || p.z() >= 248 || p.data().length == 0) return;
                    fixtures++;
                    var engine = WorldIdentifier.ofEngineNullable(mc.level);
                    var section = engine.acquireIfExists(key);
                    if (section == null) throw new AssertionError("received snapshot was not applied");
                    try {
                        int expected = engine.getMapper().getIdForBlockState(Blocks.GOLD_BLOCK.defaultBlockState());
                        if (Mapper.getBlockId(section.get(0)) == expected && Mapper.getBlockId(section.get(32767)) == expected
                                && Mapper.getLightId(section.get(0)) == 15 && Mapper.getLightId(section.get(32767)) == 15) latest.add(key);
                        else latest.remove(key);
                    } finally { section.release(); }
                }
            });
        }
        if (mc.level == null || mc.player == null || !VoxyClientNetwork.isServerAuthoritative()) return;
        worldTicks++;
        String name = mc.getUser().getName();
        Path base = Path.of(output);
        if (worldTicks == 40) {
            var listener = VoxyClientNetwork.missListener("minecraft:overworld");
            for (int i = 0; i < 600; i++) listener.onMissing(WorldEngine.getWorldSectionId(0, 400 + i % 100, 0, 400 + i / 100));
            Files.writeString(base.resolve(name + ".ready"), "ready");
            if (name.equals("Slow")) {
                mc.getConnection().getConnection().channel().config().setAutoRead(false);
                System.out.println("NETWORK_STRESS paused socket reads");
            }
        }
        if (worldTicks == 160 && name.equals("Slow")) {
            mc.getConnection().getConnection().channel().config().setAutoRead(true);
            System.out.println("NETWORK_STRESS resumed socket reads");
        }
        if (worldTicks % 100 == 0) System.out.println("NETWORK_STRESS client=" + name + " latest=" + latest.size() + " absent=" + absent.size()
                + " responses=" + responses + " fixtures=" + fixtures + " autoRead=" + mc.getConnection().getConnection().channel().config().isAutoRead()
                + " emptyReplies=" + emptyReplies + " airReplies=" + airReplies);
        if (!passed && latest.size() == 2304 && absent.size() == 600) {
            String result = "NETWORK_STRESS CLIENT PASS name=" + name + " latest=" + latest.size()
                    + " absent=" + absent.size() + " emptyReplies=" + emptyReplies + " airReplies=" + airReplies
                    + " responses=" + responses + " bytes=" + bytes;
            Files.writeString(base.resolve(name + ".pass"), result); System.out.println(result); passed = true;
        }
        if (passed && Files.exists(base.resolve("server.pass"))) {
            if (latest.size() != 2304) throw new AssertionError("late stale update replaced final terrain");
            var engine = WorldIdentifier.ofEngineNullable(mc.level);
            int gold = engine.getMapper().getIdForBlockState(Blocks.GOLD_BLOCK.defaultBlockState());
            for (int i = 0; i < 2304; i++) {
                var section = engine.acquireIfExists(0, 200 + i % 48, 0, 200 + i / 48);
                if (section == null) throw new AssertionError("final section missing from local store");
                try {
                    if (Mapper.getBlockId(section.get(0)) != gold || Mapper.getBlockId(section.get(32767)) != gold)
                        throw new AssertionError("final terrain differs from last received version");
                } finally { section.release(); }
            }
            System.out.println("NETWORK_STRESS FINAL_STORE PASS sections=2304");
            mc.stop();
        }
    }
}
