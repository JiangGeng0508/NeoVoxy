package me.cortex.voxy.common.world;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.util.TrackedObject;
import me.cortex.voxy.common.world.other.BeaconIndex;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyInstance;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个世界对应的 LOD 数据引擎。
 *
 * 负责把存储、活动区块缓存、模型映射和保存回调组织在一起；渲染线程只通过本类取得
 * 已经受引用计数保护的 WorldSection。
 */
public class WorldEngine {
    // LOD 层数和更新标志同时被世界更新器、渲染器及 shader 使用，不能随意重排或复用。
    public static final int MAX_LOD_LAYER = 4;

    public static final int UPDATE_TYPE_BLOCK_BIT = 1;
    public static final int UPDATE_TYPE_CHILD_EXISTENCE_BIT = 2;
    public static final int UPDATE_TYPE_DONT_SAVE = 4;
    public static final int DEFAULT_UPDATE_FLAGS = UPDATE_TYPE_BLOCK_BIT | UPDATE_TYPE_CHILD_EXISTENCE_BIT;

    public interface ISectionChangeCallback {
        void accept(WorldSection section, int updateFlags, int neighborMsk);
    }

    public interface ISectionSaveCallback {
        boolean save(WorldEngine engine, WorldSection section, boolean nonBlocking, boolean sectionAlreadyAcquired);
    }

    private final TrackedObject thisTracker = TrackedObject.createTrackedObject(this);

    public final SectionStorage storage;
    private final Mapper mapper;
    private final ActiveSectionTracker sectionTracker;
    private ISectionChangeCallback dirtyCallback;
    private ISectionSaveCallback saveCallback;
    volatile boolean isLive = true;

    public void setDirtyCallback(ISectionChangeCallback callback) {
        this.dirtyCallback = callback;
    }

    public void setSaveCallback(ISectionSaveCallback callback) {
        this.saveCallback = callback;
    }

    public Mapper getMapper() {return this.mapper;}

    private final BeaconIndex beaconIndex;

    public BeaconIndex getBeaconIndex() {
        return this.beaconIndex;
    }

    public boolean isLive() {
        return this.isLive;
    }

    public final @Nullable VoxyInstance instanceIn;
    private final AtomicInteger refCount = new AtomicInteger();
    // 世界没有引用和活动区块时，超过此时间才允许后台线程回收它。
    volatile long lastActiveTime = System.currentTimeMillis();

    public WorldEngine(SectionStorage storage) {
        this(storage, null);
    }

    public WorldEngine(SectionStorage storage, @Nullable VoxyInstance instance) {
        this.instanceIn = instance;

        int cacheSize = 1024;
        if (Runtime.getRuntime().maxMemory() >= (1L << 32) - (200L << 20)) {
            cacheSize = 2048;
        }

        this.storage = storage;
        this.mapper = new Mapper(this.storage);
        this.beaconIndex = new BeaconIndex(this.storage);
        // 6 位分片索引使活动区块缓存拥有 64 个独立映射，减少并发访问冲突。
        this.sectionTracker = new ActiveSectionTracker(6, storage::loadSection, cacheSize, this);
    }

    public WorldSection acquireIfCached(int lvl, int x, int y, int z) {
        return this.sectionTracker.acquireIfCached(getWorldSectionId(lvl, x, y, z));
    }

    public WorldSection acquireIfExists(int lvl, int x, int y, int z) {
        if (!this.isLive) throw new IllegalStateException("World is not live");
        return this.sectionTracker.acquire(lvl, x, y, z, true);
    }

    public WorldSection acquire(int lvl, int x, int y, int z) {
        if (!this.isLive) throw new IllegalStateException("World is not live");
        return this.sectionTracker.acquire(lvl, x, y, z, false);
    }

    public WorldSection acquire(long pos) {
        if (!this.isLive) throw new IllegalStateException("World is not live");
        return this.sectionTracker.acquire(pos, false);
    }

    public WorldSection acquireIfExists(long pos) {
        if (!this.isLive) throw new IllegalStateException("World is not live");
        return this.sectionTracker.acquire(pos, true);
    }

    public static final int POS_FORMAT_VERSION = 1;

    /**
     * 将 LOD 层级和三维坐标编码为缓存键。
     *
     * 低层级使用兼容旧缓存的紧凑布局；超出旧布局的高度使用带最低位标记的扩展布局。
     */
    public static long getWorldSectionId(int lvl, int x, int y, int z) {
        if (y >= Byte.MIN_VALUE && y <= Byte.MAX_VALUE) {
            return ((long) lvl << 60)
                    | ((long) (y & 0xFF) << 52)
                    | ((long) (z & 0xFFFFFF) << 28)
                    | ((long) (x & 0xFFFFFF) << 4);
        }
        if (x < -(1 << 22) || x >= (1 << 22)
                || y < -(1 << 11) || y >= (1 << 11)
                || z < -(1 << 23) || z >= (1 << 23)) {
            throw new IllegalArgumentException("World section position out of range: " + lvl + "@[" + x + ", " + y + ", " + z + "]");
        }
        return ((long) lvl << 60)
                | ((long) (y & 0xFFF) << 48)
                | ((long) (z & 0xFFFFFF) << 24)
                | ((long) (x & 0x7FFFFF) << 1)
                | 1L;
    }

    public static int getLevel(long id) {
        return (int) ((id >> 60) & 0xf);
    }

    public static int getX(long id) {
        if ((id & 1L) != 0) {
            return (int) ((id << 40) >> 41);
        }
        return (int) ((id << 36) >> 40);
    }

    public static int getY(long id) {
        if ((id & 1L) != 0) {
            return (int) ((id << 4) >> 52);
        }
        return (int) ((id << 4) >> 56);
    }

    public static int getZ(long id) {
        if ((id & 1L) != 0) {
            return (int) ((id << 16) >> 40);
        }
        return (int) ((id << 12) >> 40);
    }

    public static String pprintPos(long pos) {
        return getLevel(pos) + "@[" + getX(pos) + ", " + getY(pos) + ", " + getZ(pos) + "]";
    }

    /** 标记区块需要保存，并通知渲染侧重建对应几何。 */
    public void markDirty(WorldSection section) {
        this.markDirty(section, DEFAULT_UPDATE_FLAGS, 0);
    }

    public void markDirty(WorldSection section, int changeState, int neighborMsk) {
        if (!this.isLive) {
            throw new IllegalStateException("World is not live");
        }
        if (section.tracker != this.sectionTracker) {
            throw new IllegalStateException("Section is not from here");
        }
        if (this.dirtyCallback != null) {
            this.dirtyCallback.accept(section, changeState, neighborMsk);
        }
        if ((changeState & UPDATE_TYPE_DONT_SAVE) == 0) {
            section.markDirty();
        }
    }

    public void addDebugData(List<String> debug) {
        debug.add("ACC/SCC: " + this.sectionTracker.getLoadedCacheCount()
                + "/" + this.sectionTracker.getSecondaryCacheSize());
    }

    public int getActiveSectionCount() {
        return this.sectionTracker.getLoadedCacheCount();
    }

    public void free() {
        if (!this.isLive) {
            throw new IllegalStateException();
        }
        this.isLive = false;
        VarHandle.fullFence();
        // 先确认活动区块已释放，避免关闭存储后仍有线程访问它。
        if (this.sectionTracker.getLoadedCacheCount() != 0) {
            throw new IllegalStateException();
        }

        this.thisTracker.free();
        try {
            this.mapper.close();
        } catch (Exception error) {
            Logger.error(error);
        }
        try {
            this.storage.flush();
        } catch (Exception error) {
            Logger.error(error);
        }
        // 关闭顺序保证最后一批映射先写入，再释放底层存储。
        try {
            this.storage.close();
        } catch (Exception error) {
            Logger.error(error);
        }
    }

    private static final long TIMEOUT_MILLIS = 10_000;

    public boolean isWorldUsed() {
        if (!this.isLive) {
            throw new IllegalStateException();
        }
        return this.refCount.get() != 0 || this.sectionTracker.getLoadedCacheCount() != 0;
    }

    public boolean isWorldIdle() {
        if (this.isWorldUsed()) {
            this.lastActiveTime = System.currentTimeMillis();
            VarHandle.fullFence();
            return false;
        }
        return TIMEOUT_MILLIS < (System.currentTimeMillis() - this.lastActiveTime);
    }

    public void markActive() {
        if (!this.isLive) {
            throw new IllegalStateException();
        }
        this.lastActiveTime = System.currentTimeMillis();
    }

    public void acquireRef() {
        if (!this.isLive) {
            throw new IllegalStateException();
        }
        this.refCount.incrementAndGet();
        this.lastActiveTime = System.currentTimeMillis();
    }

    public void releaseRef() {
        if (!this.isLive) {
            throw new IllegalStateException();
        }
        if (this.refCount.decrementAndGet() < 0) {
            throw new IllegalStateException("ref count less than 0");
        }
        this.lastActiveTime = System.currentTimeMillis();
    }

    public boolean saveSection(WorldSection section) {
        return this.saveSection(section, false, false);
    }

    public boolean saveSection(WorldSection section, boolean nonBlocking, boolean sectionAlreadyAcquired) {
        if (this.saveCallback != null) {
            return this.saveCallback.save(this, section, nonBlocking, sectionAlreadyAcquired);
        }
        return false;
    }
}
