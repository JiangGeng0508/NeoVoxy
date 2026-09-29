package me.cortex.voxy.common.world;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;

import static me.cortex.voxy.common.world.WorldEngine.*;

public class WorldUpdater {

    /** 将一个底层体素区写入存储，并把必要的变化向上冒泡到所有 LOD 层。 */
    public static void insertUpdate(WorldEngine into, VoxelizedSection section) {
        // Mine in Abyss 的世界坐标需要先映射到有限的存储范围。
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (section.x+512)>>10;
            section.setPosition(section.x-(sector<<10), section.y+16+(256-32-sector*30), section.z);//Note sector size mult is 30 because the top chunk is replicated (and so is bottom chunk)
        }

        if (!into.isLive) throw new IllegalStateException("World is not live");
        boolean shouldCheckEmptiness = false;
        WorldSection previousSection = null;
        for (int lvl = 0; lvl <= MAX_LOD_LAYER; lvl++) {
            var worldSection = into.acquire(lvl, section.x >> (lvl + 1), section.y >> (lvl + 1), section.z >> (lvl + 1));

            int emptinessStateChange = 0;
            // 把子层的存在状态传递给父层；previousSection 在此处仍由上一轮持有。
            if (lvl != 0 && shouldCheckEmptiness) {
                emptinessStateChange = worldSection.updateEmptyChildState(previousSection);
                previousSection.release();
                previousSection = null;
            }

            long status = insertSectionLvlIntoWorld(into, section, worldSection);
            boolean didStateChange = (status&1)==1;
            int nonAirCount = (int) ((status>>1)&0x1FFF);


            if (lvl == 0) {
                int nonAirCountDelta = section.lvl0NonAirCount-nonAirCount;
                if (nonAirCountDelta != 0) {
                    worldSection.addNonEmptyBlockCount(nonAirCountDelta);
                    emptinessStateChange = worldSection.updateLvl0State() ? 2 : 0;
                }
            }

            if (didStateChange || emptinessStateChange != 0) {
                int neighbors = didStateChange ? neighborMask(section, lvl) : 0;
                into.markDirty(worldSection, (didStateChange?UPDATE_TYPE_BLOCK_BIT:0)|(emptinessStateChange!=0?UPDATE_TYPE_CHILD_EXISTENCE_BIT:0), neighbors);
            }

            if (didStateChange || emptinessStateChange == 2) {
                if (emptinessStateChange == 2) {
                    // 只有从空到非空（或反向）的变化才需要继续检查父层。
                    shouldCheckEmptiness = true;
                    previousSection = worldSection;
                } else {
                    shouldCheckEmptiness = false;
                    previousSection = null;
                    worldSection.release();
                }
            } else {
                // 父层仍需比较新聚合值，不能因为当前层无变化就提前退出。
                shouldCheckEmptiness = false;
                previousSection = null;
                worldSection.release();
            }
        }

        if (previousSection != null) {
            previousSection.release();
        }
    }

    private static int neighborMask(VoxelizedSection section, int level) {
        int shift = level + 1;
        int neighbors = 0;
        neighbors |= ((section.y ^ (section.y - 1)) >> shift) == 0 ? 0 : 1 << 0;
        neighbors |= ((section.y ^ (section.y + 1)) >> shift) == 0 ? 0 : 1 << 1;
        neighbors |= ((section.x ^ (section.x - 1)) >> shift) == 0 ? 0 : 1 << 2;
        neighbors |= ((section.x ^ (section.x + 1)) >> shift) == 0 ? 0 : 1 << 3;
        neighbors |= ((section.z ^ (section.z - 1)) >> shift) == 0 ? 0 : 1 << 4;
        neighbors |= ((section.z ^ (section.z + 1)) >> shift) == 0 ? 0 : 1 << 5;
        return neighbors;
    }

    /** 更新单个 LOD 层；返回值低位为数据变化标志，其余位保存非空气数量。 */
    private static long insertSectionLvlIntoWorld(WorldEngine into, VoxelizedSection section, WorldSection worldSection) {
        final long[] vdat = section.section;
        final int lvl = worldSection.lvl;

        final int msk = (1<<(lvl+1))-1;
        final int bx = (section.x&msk)<<(4-lvl);
        final int by = (section.y&msk)<<(4-lvl);
        final int bz = (section.z&msk)<<(4-lvl);

        int nonAirCount = 0;
        boolean didStateChange = false;
        WorldSection belowWorldSection = null;
        boolean belowDidStateChange = false;



        // 均匀块无需物化整段数组，避免无意义的写入和缓存抖动。
        {
            long[] existing = worldSection._rawOrNull();
            if (existing == null) {
                long uniform = worldSection.getUniformValue();
                boolean allSame = true;
                if (lvl == 0) {
                    for (int i = 0; i <= 0xFFF; i++) {
                        if (vdat[i] != uniform) { allSame = false; break; }
                    }
                } else {
                    int baseVIdx = VoxelizedSection.getBaseIndexForLevel(lvl);
                    for (int i = baseVIdx; i <= (0xFFF >> (lvl * 3)) + baseVIdx; i++) {
                        if (Mapper.isSurfaceCarrier(vdat[i]) || vdat[i] != uniform) { allSame = false; break; }
                    }
                }
                if (allSame) {
                    long unchangedStatus = 0;//didStateChange = false
                    if (lvl == 0 && !Mapper.isAir(uniform)) {
                        unchangedStatus |= Integer.toUnsignedLong(4096) << 1;
                    }
                    me.cortex.voxy.commonImpl.PerfStats.sectionUniformWriteSkipped.increment();
                    return unchangedStatus;
                }
            }
        }

        // 按 WorldSection 的 Morton/平面布局写入，保持底层数组顺序不变。
        {
            var secD = worldSection.materialize();
            int baseSec = bx | (bz << 5) | (by << 10);
            if (lvl == 0) {
                final int secMsk = 0b1100|(0xf << 5) | (0xf << 10);
                final int iSecMsk1 = (~secMsk) + 1;

                int secIdx = 0;

                // i.e. instead of doing 4 consecutive blocks, which would all be in the same cache line
                // do 4 seperate rows so they are in different cache lines, should allow
                // more instruction pipelining (in theory)
                for (int i = 0; i <= 0xFFF; i+=4) {
                    int cSecIdx = secIdx + baseSec;
                    secIdx = (secIdx + iSecMsk1) & secMsk;

                    long oldId0 = secD[cSecIdx+0]; secD[cSecIdx+0] = vdat[i+0];
                    long oldId1 = secD[cSecIdx+1]; secD[cSecIdx+1] = vdat[i+1];
                    long oldId2 = secD[cSecIdx+2]; secD[cSecIdx+2] = vdat[i+2];
                    long oldId3 = secD[cSecIdx+3]; secD[cSecIdx+3] = vdat[i+3];

                    nonAirCount += Mapper.isNotAirInt(oldId0); didStateChange |= vdat[i+0] != oldId0;
                    nonAirCount += Mapper.isNotAirInt(oldId1); didStateChange |= vdat[i+1] != oldId1;
                    nonAirCount += Mapper.isNotAirInt(oldId2); didStateChange |= vdat[i+2] != oldId2;
                    nonAirCount += Mapper.isNotAirInt(oldId3); didStateChange |= vdat[i+3] != oldId3;
                }
            } else {
                int baseVIdx = VoxelizedSection.getBaseIndexForLevel(lvl);

                int secMsk = 0xF >> lvl;
                secMsk |= (secMsk << 5) | (secMsk << 10);
                int iSecMsk1 = (~secMsk) + 1;

                int secIdx = 0;
                for (int i = baseVIdx; i <= (0xFFF >> (lvl * 3)) + baseVIdx; i++) {
                    int cSecIdx = secIdx + baseSec;
                    secIdx = (secIdx + iSecMsk1) & secMsk;
                    long newId = vdat[i];
                    if (Mapper.isSurfaceCarrier(newId)) {
                        if (((cSecIdx >> 10) & 31) != 0 && !Mapper.isAir(secD[cSecIdx - (1 << 10)])) {
                            int belowIndex = cSecIdx - (1 << 10);
                            long below = Mapper.applySurfaceCarrier(secD[belowIndex], newId);
                            didStateChange |= below != secD[belowIndex];
                            secD[belowIndex] = below;
                            newId = Mapper.clearSurfaceCarrier(newId);
                        } else if (((cSecIdx >> 10) & 31) == 0) {
                            if (belowWorldSection == null) {
                                belowWorldSection = into.acquire(lvl, worldSection.x, worldSection.y - 1, worldSection.z);
                            }
                            int belowIndex = (31 << 10) | (cSecIdx & 0x3FF);
                            long oldBelow = belowWorldSection.get(belowIndex);
                            if (!Mapper.isAir(oldBelow)) {
                                long below = Mapper.applySurfaceCarrier(oldBelow, newId);
                                if (below != oldBelow) {
                                    belowWorldSection.materialize()[belowIndex] = below;
                                    belowDidStateChange = true;
                                }
                                newId = Mapper.clearSurfaceCarrier(newId);
                            } else {
                                newId = Mapper.restoreSurfaceCarrier(newId);
                            }
                        } else {
                            newId = Mapper.restoreSurfaceCarrier(newId);
                        }
                    }
                    long oldId = secD[cSecIdx];
                    didStateChange |= newId != oldId;
                    secD[cSecIdx] = newId;
                }
            }
        }

        if (belowWorldSection != null) {
            if (belowDidStateChange) {
                into.markDirty(belowWorldSection, UPDATE_TYPE_BLOCK_BIT, 1 << 1);
            }
            belowWorldSection.release();
        }

        long status = 0;
        status |= didStateChange?1:0;
        status |= Integer.toUnsignedLong(nonAirCount)<<1;//13 bits are required because the value can be 4096
        return status;
    }
}
