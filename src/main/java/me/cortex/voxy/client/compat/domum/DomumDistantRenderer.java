package me.cortex.voxy.client.compat.domum;

import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.create.DistantLightSampler;
import me.cortex.voxy.client.compat.create.DistantMesh;
import me.cortex.voxy.client.compat.create.DistantMeshBuilder;
import me.cortex.voxy.client.compat.create.DistantShaders;
import me.cortex.voxy.client.compat.create.DistantVisibility;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.compat.DisguiseStore;
import me.cortex.voxy.commonImpl.compat.DomumOrnamentumCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_EQUAL;
import static org.lwjgl.opengl.GL11C.GL_KEEP;
import static org.lwjgl.opengl.GL11C.GL_REPLACE;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11C.glDepthFunc;
import static org.lwjgl.opengl.GL11C.glDepthMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glStencilFunc;
import static org.lwjgl.opengl.GL11C.glStencilOp;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

/** 使用 Domum 的真实烘焙模型绘制远景网格，避免六面代理模型丢失瓦片形状。 */
public final class DomumDistantRenderer implements LodPipelineHooks.Renderer {
    private static final int MAX_BAKES_PER_TICK = 1;
    private static volatile DomumDistantRenderer active;

    private final ConcurrentLinkedQueue<Update> updates = new ConcurrentLinkedQueue<>();
    private final Map<Long, Entry> sections = new HashMap<>();
    private final ArrayDeque<Long> bakeQueue = new ArrayDeque<>();
    private final HashSet<Long> queued = new HashSet<>();
    private SectionStorage storage;
    private ClientLevel level;
    private int lastScanX = Integer.MIN_VALUE;
    private int lastScanZ = Integer.MIN_VALUE;

    // ---- 生命周期、队列与绘制 -----------------------------------------

    public DomumDistantRenderer() {
        active = this;
        DomumOrnamentumCompat.sectionListener = DomumDistantRenderer::changed;
    }

    private static void changed(SectionStorage storage, int sx, int sy, int sz) {
        DomumDistantRenderer renderer = active;
        if (renderer != null) renderer.updates.add(new Update(storage, DisguiseStore.keyOf(sx, sy, sz)));
    }

    @SubscribeEvent
    public void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var engine = WorldIdentifier.ofEngineNullable(mc.level);
        if (engine == null) return;
        if (this.storage != engine.storage) {
            clearMeshes();
            this.updates.clear();
            this.storage = engine.storage;
            this.level = mc.level;
            this.storage.forEachAux(DomumOrnamentumCompat.DISGUISE_TABLE,
                    (key, value) -> install(key, value, false));
        }

        drainUpdates(128);
        var camera = mc.gameRenderer.getMainCamera().getPosition();
        double maxDistance = VoxyConfig.CONFIG.createRenderDistance(VoxyConfig.CONFIG.distantDomumMaxChunks);
        if (!VoxyConfig.CONFIG.distantDomum) return;
        double maxDistanceSq = maxDistance * maxDistance;
        int cx = ((int) Math.floor(camera.x)) >> 4;
        int cz = ((int) Math.floor(camera.z)) >> 4;
        if (this.lastScanX == Integer.MIN_VALUE || Math.abs(cx - this.lastScanX) >= 4
                || Math.abs(cz - this.lastScanZ) >= 4) {
            this.lastScanX = cx;
            this.lastScanZ = cz;
            double farSq = (maxDistance + 256.0) * (maxDistance + 256.0);
            for (var item : this.sections.entrySet()) {
                Entry entry = item.getValue();
                double distanceSq = distanceSq(item.getKey(), camera.x, camera.y, camera.z);
                if (entry.mesh == null && distanceSq <= maxDistanceSq && this.queued.add(item.getKey())) {
                    this.bakeQueue.add(item.getKey());
                } else if (entry.mesh != null && distanceSq > farSq) {
                    entry.mesh.free();
                    entry.mesh = null;
                }
            }
        }

        for (int baked = 0; baked < MAX_BAKES_PER_TICK && !this.bakeQueue.isEmpty();) {
            long key = this.bakeQueue.removeFirst();
            this.queued.remove(key);
            Entry entry = this.sections.get(key);
            if (entry == null || entry.mesh != null || distanceSq(key, camera.x, camera.y, camera.z) > maxDistanceSq) {
                continue;
            }
            entry.mesh = bake(key, entry.blocks, engine.getMapper(), mc.level);
            baked++;
        }
    }

    @SubscribeEvent
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        clearMeshes();
        this.updates.clear();
        this.storage = null;
        this.level = null;
    }

    @Override
    public void render(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        if (this.sections.isEmpty() || !VoxyConfig.CONFIG.isRenderingEnabled()
                || !VoxyConfig.CONFIG.distantDomum) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != this.level) return;
        pipeline.setupAndBindOpaque(viewport);

        double vanillaReach = Math.max(0.0, mc.options.getEffectiveRenderDistance() * 16.0 - 14.0);
        double handoffSq = vanillaReach * vanillaReach;
        double maxDistance = VoxyConfig.CONFIG.createRenderDistance(VoxyConfig.CONFIG.distantDomumMaxChunks);
        double maxDistanceSq = maxDistance * maxDistance;
        boolean bound = false;
        var transform = new Matrix4f();
        try {
            for (var item : this.sections.entrySet()) {
                Entry entry = item.getValue();
                if (entry.mesh == null) continue;
                long key = item.getKey();
                double ox = BlockPos.getX(key) * 16.0;
                double oy = BlockPos.getY(key) * 16.0;
                double oz = BlockPos.getZ(key) * 16.0;
                double dx = ox + 8.0 - viewport.cameraX;
                double dy = oy + 8.0 - viewport.cameraY;
                double dz = oz + 8.0 - viewport.cameraZ;
                double nearSq = dx * dx + dz * dz;
                if (nearSq < handoffSq || dx * dx + dy * dy + dz * dz > maxDistanceSq) continue;
                if (!DistantVisibility.isBoxVisible(viewport, ox - 4, oy - 4, oz - 4,
                        ox + 20, oy + 20, oz + 20)) continue;
                if (!bound) {
                    DistantShaders.forPipeline(pipeline, false).bind();
                    DistantShaders.bindTextures();
                    glEnable(GL_DEPTH_TEST);
                    glDepthFunc(depthFunc);
                    glDepthMask(true);
                    glDisable(GL_CULL_FACE);
                    glEnable(GL_STENCIL_TEST);
                    glStencilFunc(GL_ALWAYS, 3, 0xFF);
                    glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
                    bound = true;
                }
                transform.set(viewport.MVP).translate((float) (ox - viewport.cameraX),
                        (float) (oy - viewport.cameraY), (float) (oz - viewport.cameraZ));
                DistantShaders.uploadTransform(transform);
                entry.mesh.draw();
            }
            if (bound) {
                glBindVertexArray(0);
                glUseProgram(0);
            }
        } finally {
            if (bound) {
                glStencilFunc(GL_EQUAL, 1, 0x1);
                glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            }
        }
    }

    private void drainUpdates(int limit) {
        for (int applied = 0; applied < limit;) {
            Update update = this.updates.poll();
            if (update == null) return;
            if (update.storage != this.storage) continue;
            applied++;
            install(update.key, this.storage.getAux(DomumOrnamentumCompat.DISGUISE_TABLE, update.key), true);
        }
    }

    private void install(long key, byte[] value, boolean urgent) {
        int[] pairs = decode(value);
        Entry old = this.sections.remove(key);
        if (old != null && old.mesh != null) old.mesh.free();
        this.queued.remove(key);
        this.bakeQueue.remove(key);
        if (pairs.length == 0) return;

        this.sections.put(key, new Entry(pairs));

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == this.level) {
            var camera = mc.gameRenderer.getMainCamera().getPosition();
            double maxDistance = VoxyConfig.CONFIG.createRenderDistance(VoxyConfig.CONFIG.distantDomumMaxChunks);
            if (distanceSq(key, camera.x, camera.y, camera.z) <= maxDistance * maxDistance
                    && this.queued.add(key)) {
                if (urgent) this.bakeQueue.addFirst(key);
                else this.bakeQueue.addLast(key);
            }
        }
    }

    private static int[] decode(byte[] value) {
        var values = new it.unimi.dsi.fastutil.ints.IntArrayList();
        DisguiseStore.decode(value, (index, id) -> {
            values.add(index);
            values.add(id);
        });
        return values.toIntArray();
    }

    private static DistantMesh bake(long key, int[] blocks,
                                    me.cortex.voxy.common.world.other.Mapper mapper, ClientLevel level) {
        int sx = BlockPos.getX(key), sy = BlockPos.getY(key), sz = BlockPos.getZ(key);
        var builder = new DistantMeshBuilder();
        try {
            var blockRenderer = Minecraft.getInstance().getBlockRenderer();
            var colors = Minecraft.getInstance().getBlockColors();
            boolean[] fullBlocks = new boolean[4096];
            for (int i = 0; i < blocks.length; i += 2) {
                int local = blocks[i];
                fullBlocks[local] = me.cortex.voxy.client.compat.create.DistantFaceCulling.isFullBlock(
                        mapper.getBlockStateFromBlockId(blocks[i + 1]));
            }
            for (int i = 0; i < blocks.length; i += 2) {
                int local = blocks[i], blockId = blocks[i + 1];
                var plan = DomumOrnamentumCompat.getBakePlan(mapper, blockId);
                if (plan.isEmpty() || !plan.detailedMesh()) continue;
                int x = local & 15, z = (local >>> 4) & 15, y = (local >>> 8) & 15;
                int wx = sx * 16 + x, wy = sy * 16 + y, wz = sz * 16 + z;
                var state = mapper.getBlockStateFromBlockId(blockId);
                var model = blockRenderer.getBlockModel(state);
                int tint = 0xFFFFFF;
                if (plan.colourState() != null) {
                    int sampled = colors.getColor(plan.colourState(), level, new BlockPos(wx, wy, wz), 0);
                    if (sampled != -1) tint = sampled & 0xFFFFFF;
                }
                int light = DistantLightSampler.sample(level, wx, wy, wz);
                builder.blockModel(state, model, x, y, z,
                        DistantLightSampler.sky(light), DistantLightSampler.block(light),
                        direction -> me.cortex.voxy.client.compat.create.DistantFaceCulling
                                .hidesSectionFace(fullBlocks, local, direction),
                        tint, plan.modelData());
            }
            return builder.build();
        } catch (Throwable t) {
            builder.discard();
            Logger.error("Baking Domum Ornamentum detailed LOD mesh", t);
            return null;
        }
    }

    private static double distanceSq(long key, double x, double y, double z) {
        double dx = BlockPos.getX(key) * 16.0 + 8.0 - x;
        double dy = BlockPos.getY(key) * 16.0 + 8.0 - y;
        double dz = BlockPos.getZ(key) * 16.0 + 8.0 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private void clearMeshes() {
        for (Entry entry : this.sections.values()) if (entry.mesh != null) entry.mesh.free();
        this.sections.clear();
        this.bakeQueue.clear();
        this.queued.clear();
        this.lastScanX = this.lastScanZ = Integer.MIN_VALUE;
    }

    private record Update(SectionStorage storage, long key) {}

    private static final class Entry {
        final int[] blocks;
        DistantMesh mesh;

        Entry(int[] blocks) {
            this.blocks = blocks;
        }
    }
}
