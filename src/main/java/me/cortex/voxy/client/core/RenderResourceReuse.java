package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.ThreadUtils;
import me.cortex.voxy.common.util.TrackedObject;

import java.util.ArrayList;

import static org.lwjgl.opengl.ARBSparseBuffer.GL_SPARSE_STORAGE_BIT_ARB;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11C.GL_OUT_OF_MEMORY;
import static org.lwjgl.opengl.GL11C.glGetError;

/** 复用渲染器生命周期之间的几何缓冲和模型纹理分配。 */
public class RenderResourceReuse {
    private static final ArrayList<GlTexture> MODEL_TEXTURE_CACHE = new ArrayList<>();
    private static final ArrayList<GlBuffer> GEOMETRY_BUFFER_CACHE = new ArrayList<>();

    /** 实例完全销毁时清空缓存，调用者必须保证没有渲染线程继续使用这些资源。 */
    public static void clearResources() {
        MODEL_TEXTURE_CACHE.forEach(TrackedObject::free);
        GEOMETRY_BUFFER_CACHE.forEach(TrackedObject::free);
        MODEL_TEXTURE_CACHE.clear();
        GEOMETRY_BUFFER_CACHE.clear();
    }


    public static GlTexture getOrCreateModelStoreTextureAtlas() {
        if (!MODEL_TEXTURE_CACHE.isEmpty()) {
            return MODEL_TEXTURE_CACHE.removeFirst().zero();
        }
        return new GlTexture().store(GL_RGBA8,
                        Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE),
                        ModelFactory.MODEL_TEXTURE_SIZE * 3 * 256,
                        ModelFactory.MODEL_TEXTURE_SIZE * 2 * 256)
                .name("ModelTextures");
    }
    public static void giveBackModelStoreTextureAtlas(GlTexture texture) {
        MODEL_TEXTURE_CACHE.add(texture);
    }

    static GlBuffer getOrCreateGeometryBuffer() {
        GlBuffer buffer = null;
        if (!GEOMETRY_BUFFER_CACHE.isEmpty()) {
            buffer = GEOMETRY_BUFFER_CACHE.removeFirst();
        } else {
            long capacity = getGeometryBufferSize();
            long driverMemory = -1;
            if (Capabilities.INSTANCE.canQueryGpuMemory) {
                driverMemory = Capabilities.INSTANCE.getFreeDedicatedGpuMemory();
            }

            glGetError();//Clear any errors
            if (!(Capabilities.INSTANCE.isNvidia&& ThreadUtils.isWindows&&Capabilities.INSTANCE.sparseBuffer)) {
                buffer = new GlBuffer(capacity, false);//Only do this if we are not on nvidia
                // or dont zero it at all
            } else {
                Logger.info("Running on nvidia, using workaround sparse buffer allocation");
            }
            int error = glGetError();
            if (error != GL_NO_ERROR || buffer == null) {
                if ((buffer == null || error == GL_OUT_OF_MEMORY) && Capabilities.INSTANCE.sparseBuffer) {
                    if (buffer != null) {
                        Logger.error("Failed to allocate geometry buffer, attempting workaround with sparse buffers");
                        buffer.free();
                    }
                    buffer = new GlBuffer(capacity, GL_SPARSE_STORAGE_BIT_ARB);
                    error = glGetError();
                    if (error != GL_NO_ERROR) {
                        buffer.free();
                        throw new IllegalStateException("Unable to allocate geometry buffer using workaround, got gl error "
                                + error + ". Failed to allocate buffer of size " + capacity);
                    }
                } else {
                    throw new IllegalStateException("Unable to allocate geometry buffer, got gl error "
                            + error + ". Failed to allocate buffer of size " + capacity);
                }
            }
            String extra = "";
            if (driverMemory != -1) {
                extra = ", driver stated " + (driverMemory/(1024*1024)) + "Mb of free memory";
            }
            Logger.info("Allocated new geometry buffer: " + buffer.size() + ", isSparse: " + buffer.isSparse() + extra);
        }
        return buffer;
    }

    public static void giveBackGeometryBuffer(GlBuffer geometryBuffer) {
        GEOMETRY_BUFFER_CACHE.add(geometryBuffer);
    }

    private static long getGeometryBufferSize() {
        long geometryCapacity = Math.min((1L<<(64-Long.numberOfLeadingZeros(Capabilities.INSTANCE.ssboMaxSize-1)))<<1, 1L<<32)-1024/*(1L<<32)-1024*/;
        if (Capabilities.INSTANCE.isIntel) {
            geometryCapacity = Math.max(geometryCapacity, 1L<<30);//intel moment, force min 1gb
        }
        if (Capabilities.INSTANCE.isNvidia && ThreadUtils.isLinux) {
            geometryCapacity = Math.min(geometryCapacity, 2000L*1024L*1024L);//nvidia linux moment, force max 2gb heap
        }

        geometryCapacity = Math.max(512*1024*1024, geometryCapacity);//min of 512 mb

        //Limit to available dedicated memory if possible
        if (Capabilities.INSTANCE.canQueryGpuMemory) {
            //512mb less than avalible,
            long limit = Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - (long)(1.5*1024*1024*1024);//1.5gb vram buffer
            // Give a minimum of 512 mb requirement
            limit = Math.max(512*1024*1024, limit);

            geometryCapacity = Math.min(geometryCapacity, limit);
        }
        //geometryCapacity = 1<<28;
        //geometryCapacity = 1<<30;//1GB test
        var override = System.getProperty("voxy.geometryBufferSizeOverrideMB", "");
        if (!override.isEmpty()) {
            geometryCapacity = Long.parseLong(override)*1024L*1024L;
        }
        return geometryCapacity;
    }
}
