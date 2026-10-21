package me.cortex.voxy.client.compat.littletiles;

import me.cortex.voxy.client.compat.create.DistantMeshBuilder;
import me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static me.cortex.voxy.testing.Reflect.*;
import static org.junit.jupiter.api.Assertions.*;

class LittleTilesMeshTest {
    @Test void opaqueCellsBuildCoarseLevelsWhileGlassPreservesAttributesAndBounds() throws Exception {
        for (boolean glass : new boolean[]{false, true}) {
            var cells = new ArrayList<LittleTilesCompat.Cell>();
            for (int z=0;z<8;z++) for (int y=0;y<8;y++) for (int x=0;x<8;x++)
                cells.add(new LittleTilesCompat.Cell(x | z<<7 | y<<14, glass ? 1 : 0, 0xA3));
            Object pair = bake(cells);
            try {
                DistantMeshBuilder.CpuMesh cpu = get(pair, glass ? "translucent" : "opaque");
                assertNotNull(cpu);
                assertNull(get(pair, glass ? "opaque" : "translucent"));
                assertEquals(0,cpu.minX); assertEquals(1,cpu.maxX);
                assertEquals(0,cpu.minY); assertEquals(1,cpu.maxY);
                assertEquals(0,cpu.minZ); assertEquals(1,cpu.maxZ);
                float[] grids = get(get(cpu,"lod"),"grids");
                assertEquals(!glass, grids.length > 0);
                ByteBuffer vertices = get(cpu,"buffer");
                for (int v=0;v<vertices.limit();v+=32) {
                    assertEquals(glass ? 96 : 255,Byte.toUnsignedInt(vertices.get(v+27)));
                    assertEquals(glass ? 74 : 73,vertices.getInt(v+28));
                    assertEquals(0xA8,Byte.toUnsignedInt(vertices.get(v+21)));
                    assertEquals(0x58,Byte.toUnsignedInt(vertices.get(v+20)),"material emission must survive");
                    assertEquals(glass ? 0.75f : 0.25f,vertices.getFloat(v+12));
                    assertEquals(0.5f,vertices.getFloat(v+16));
                }
                if (glass) assertTrue(cpu.quadCount < 384,"safe adjacent coplanar faces should merge");
            } finally { call(pair,"free"); }
        }
    }

    @Test void glassNeverRemovesSolidFacesAndDifferentGlassRetainsItsInterface() throws Exception {
        for (int axis=0;axis<3;axis++) for (int neighbor : new int[]{0,1,2}) {
            int offset=1 << (axis==0 ? 0 : axis==1 ? 14 : 7);
            int center=10 | 10<<7 | 10<<14;
            Object pair=bake(List.of(new LittleTilesCompat.Cell(center,0,0xA3),
                    new LittleTilesCompat.Cell(center+offset,neighbor,0xA3)));
            try {
                DistantMeshBuilder.CpuMesh opaque=get(pair,"opaque");
                assertEquals(neighbor==0 ? 10 : 6,opaque.quadCount,
                        "the complete solid cube must remain visible behind transparent neighbours");
            } finally {call(pair,"free");}
            if (neighbor==0) continue;
            pair=bake(List.of(new LittleTilesCompat.Cell(center,1,0xA3),
                    new LittleTilesCompat.Cell(center+offset,neighbor,0xA3)));
            try {
                DistantMeshBuilder.CpuMesh glass=get(pair,"translucent");
                assertEquals(neighbor==1 ? 10 : 12,glass.quadCount,
                        "different glass colours keep both sides of their interface");
            } finally {call(pair,"free");}
        }
    }

    @Test void cellCoordinatesDoNotWrapAcrossSectionEdges() throws Exception {
        Object pair=bake(List.of(new LittleTilesCompat.Cell(127,0,0xA3),new LittleTilesCompat.Cell(128,0,0xA3)));
        try { assertEquals(12,((DistantMeshBuilder.CpuMesh)get(pair,"opaque")).quadCount); }
        finally {call(pair,"free");}
    }

    static Object bake(List<LittleTilesCompat.Cell> cells) throws Exception {
        var snapshot=snapshot(cells);
        var type=Class.forName(LittleTilesDistantRenderer.class.getName()+"$MaterialSample");
        var ctor=type.getDeclaredConstructors()[0];ctor.setAccessible(true);
        Object materials=Array.newInstance(type,3);
        for (int i=0;i<3;i++) {
            float[] u=new float[6],v=new float[6];java.util.Arrays.fill(u,i==0?.25f:.75f);java.util.Arrays.fill(v,.5f);
            Array.set(materials,i,ctor.newInstance(u,v,i==2?0xCC3322:0x336699,i==0?255:96,i!=0,5,73+i));
        }
        var method=LittleTilesDistantRenderer.class.getDeclaredMethod("bake",LittleTilesCompat.SectionSnapshot.class,materials.getClass());
        method.setAccessible(true);
        Object pair=method.invoke(null,snapshot,materials);assertNotNull(pair);return pair;
    }

    static LittleTilesCompat.SectionSnapshot snapshot(List<LittleTilesCompat.Cell> cells) {
        return new LittleTilesCompat.SectionSnapshot(0,4,-64,List.of(
                new LittleTilesCompat.Material(Blocks.STONE.defaultBlockState(),0xFF336699),
                new LittleTilesCompat.Material(Blocks.GLASS.defaultBlockState(),0x60336699),
                new LittleTilesCompat.Material(Blocks.GLASS.defaultBlockState(),0x60CC3322)),cells,new long[64]);
    }
}
