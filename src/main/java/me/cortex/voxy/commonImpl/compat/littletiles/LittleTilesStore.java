package me.cortex.voxy.commonImpl.compat.littletiles;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class LittleTilesStore {
    public static final String TABLE = "littletiles_sections";
    private static final byte FORMAT = 1;
    private static final int MAX_CELLS = 1 << 21;

    private LittleTilesStore() {}

    public static long key(int sx, int sy, int sz) {
        return BlockPos.asLong(sx, sy, sz);
    }

    public static void save(SectionStorage storage, LittleTilesCompat.SectionSnapshot snapshot) {
        if (storage == null || !storage.supportsAuxTable(TABLE)) return;
        try {
            if (snapshot.cells().isEmpty()) {
                storage.deleteAux(TABLE, key(snapshot.sx(), snapshot.sy(), snapshot.sz()));
                return;
            }
            storage.putAux(TABLE, key(snapshot.sx(), snapshot.sy(), snapshot.sz()), encode(snapshot));
        } catch (Throwable t) {
            Logger.error("Storing LittleTiles LOD section", t);
        }
    }

    public static List<LittleTilesCompat.SectionSnapshot> loadAll(SectionStorage storage) {
        var out = new ArrayList<LittleTilesCompat.SectionSnapshot>();
        if (storage == null || !storage.supportsAuxTable(TABLE)) return out;
        try {
            storage.forEachAux(TABLE, (key, bytes) -> {
                var decoded = decode(bytes);
                if (decoded != null) out.add(decoded);
            });
        } catch (Throwable t) {
            Logger.error("Reading LittleTiles LOD table", t);
            out.clear();
        }
        return out;
    }

    public static LittleTilesCompat.SectionSnapshot load(SectionStorage storage, int sx, int sy, int sz) {
        if (storage == null || !storage.supportsAuxTable(TABLE)) return null;
        try {
            return decode(storage.getAux(TABLE, key(sx, sy, sz)));
        } catch (Throwable t) {
            Logger.error("Reading LittleTiles LOD section", t);
            return null;
        }
    }

    private static byte[] encode(LittleTilesCompat.SectionSnapshot snapshot) throws Exception {
        var root = new CompoundTag();
        var states = new ListTag();
        for (var material : snapshot.materials()) {
            var tag = new CompoundTag();
            tag.put("state", BlockState.CODEC.encodeStart(NbtOps.INSTANCE, material.state())
                    .getOrThrow(error -> new IllegalStateException("Encoding LittleTiles state: " + error)));
            tag.putInt("color", material.color());
            states.add(tag);
        }
        root.put("materials", states);
        var nbtBytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, nbtBytes);

        var bytes = new ByteArrayOutputStream(snapshot.cells().size() * 7 + nbtBytes.size() + 32);
        var out = new DataOutputStream(bytes);
        out.writeByte(FORMAT);
        out.writeInt(snapshot.sx());
        out.writeInt(snapshot.sy());
        out.writeInt(snapshot.sz());
        out.writeInt(nbtBytes.size());
        out.write(nbtBytes.toByteArray());
        out.writeInt(snapshot.cells().size());
        for (var cell : snapshot.cells()) {
            out.writeInt(cell.coordinate());
            out.writeShort(cell.material());
            out.writeByte(cell.light());
        }
        out.flush();
        return bytes.toByteArray();
    }

    private static LittleTilesCompat.SectionSnapshot decode(byte[] bytes) {
        if (bytes == null || bytes.length < 18 || bytes[0] != FORMAT) return null;
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes, 1, bytes.length - 1))) {
            int sx = in.readInt(), sy = in.readInt(), sz = in.readInt();
            int nbtLength = in.readInt();
            if (nbtLength < 0 || nbtLength > bytes.length) return null;
            byte[] nbtBytes = new byte[nbtLength];
            in.readFully(nbtBytes);
            var root = NbtIo.readCompressed(new ByteArrayInputStream(nbtBytes), NbtAccounter.unlimitedHeap());
            var list = root.getList("materials", 10);
            var materials = new ArrayList<LittleTilesCompat.Material>(list.size());
            for (int i = 0; i < list.size(); i++) {
                var tag = list.getCompound(i);
                var state = BlockState.CODEC.parse(NbtOps.INSTANCE, tag.get("state")).result().orElse(null);
                if (state == null) return null;
                materials.add(new LittleTilesCompat.Material(state, tag.getInt("color")));
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_CELLS) return null;
            var cells = new ArrayList<LittleTilesCompat.Cell>(count);
            for (int i = 0; i < count; i++) {
                int coordinate = in.readInt();
                int material = in.readUnsignedShort();
                int light = in.readUnsignedByte();
                if (material >= materials.size() || (coordinate & ~0x1FFFFF) != 0) return null;
                cells.add(new LittleTilesCompat.Cell(coordinate, material, light));
            }
            long[] holders = new long[64];
            for (var cell : cells) {
                int coordinate = cell.coordinate();
                int bx = (coordinate & 127) >>> 3;
                int bz = ((coordinate >>> 7) & 127) >>> 3;
                int by = ((coordinate >>> 14) & 127) >>> 3;
                int holder = bx | (bz << 4) | (by << 8);
                holders[holder >>> 6] |= 1L << (holder & 63);
            }
            return new LittleTilesCompat.SectionSnapshot(sx, sy, sz, materials, cells, holders);
        } catch (Throwable t) {
            Logger.error("Reading LittleTiles LOD section", t);
            return null;
        }
    }
}
