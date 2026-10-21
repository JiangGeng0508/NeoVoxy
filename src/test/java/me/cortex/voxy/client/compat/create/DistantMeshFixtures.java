package me.cortex.voxy.client.compat.create;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.nio.*;
import java.util.*;
/** Reference synthetic fixtures and OBJ resources from the pinned Create dependency. */
final class DistantMeshFixtures {

    private static final int[][] CORNERS = {
            {0,1,5,4}, {2,6,7,3}, {0,2,3,1}, {4,5,7,6}, {0,4,6,2}, {1,3,7,5}
    };


    record Fixture(String name, ByteBuffer data) {}

static List<Fixture> fixtures() throws Exception {
        var result = new ArrayList<Fixture>();
        var carriage = buffer();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) {
            if (y == 0 || y == 3 || x == 0 || x == 3) box(carriage,x,y,z,x+1,y+1,z+1);
        }
        result.add(new Fixture("block-built carriage", carriage.flip()));
        var roof = buffer();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            float y = (float) (4 * Math.sin((x + .5) * Math.PI / 16));
            box(roof,x,y,z,x+1,y+.25f,z+1);
            box(roof,x,y+.25f,z+.1f,x+.08f,y+.35f,z+.9f);
        }
        result.add(new Fixture("decorated roof fixture",roof.flip()));
        var small = buffer();
        for (int z = 0; z < 6; z++) box(small,0,0,z*.5f,1,.125f,z*.5f+.2f);
        result.add(new Fixture("small contraption below 128 quads",small.flip()));
        {
            var tie = readObj("tie.obj");
            var left = readObj("segment_left.obj");
            var right = readObj("segment_right.obj");
            var track = buffer();
            var transform = new Matrix4f();
            for (int i = 0; i < 48; i++) {
                float angle = i * .018f;
                transform.identity().translate((float) (8 * Math.sin(angle)),0,i*.5f).rotateY(angle);
                appendObj(track,tie,transform);
                appendObj(track,left,new Matrix4f(transform).translate(-.75f,0,0));
                appendObj(track,right,new Matrix4f(transform).translate(.75f,0,0));
            }
            result.add(new Fixture("Create tie/left/right OBJ curved track",track.flip()));
        }
        return result;
    }

static float[] bounds(ByteBuffer data) {
        float[] result = {Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for (int v=0;v<data.limit();v+=32) for (int axis=0;axis<3;axis++) {
            float value=data.getFloat(v+axis*4);
            result[axis]=Math.min(result[axis],value); result[axis+3]=Math.max(result[axis+3],value);
        }
        return result;
    }

static ByteBuffer buffer() { return ByteBuffer.allocate(4*1024*1024).order(ByteOrder.nativeOrder()); }

static void box(ByteBuffer out,float x0,float y0,float z0,float x1,float y1,float z1) {
        for (int face=0;face<6;face++) for (int i=0;i<4;i++) {
            int bits=CORNERS[face][i];
            vertex(out,(bits&1)==0?x0:x1,(bits&2)==0?y0:y1,(bits&4)==0?z0:z1,
                    i==1||i==2?1:0,i>=2?1:0,face);
        }
    }

static void vertex(ByteBuffer out,float x,float y,float z,float u,float v,int face) {
        out.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
        out.putInt(0x00FF8878|(face<<24)).putInt(0xFF659CB4).putInt(73);
    }

static Vector3f position(int[] data,int offset) {
        return new Vector3f(Float.intBitsToFloat(data[offset]),Float.intBitsToFloat(data[offset+1]),Float.intBitsToFloat(data[offset+2]));
    }

static void appendObj(ByteBuffer out,List<float[]> quads,Matrix4f transform) {
        var p=new Vector3f();
        for (float[] quad : quads) for (int i=0;i<4;i++) {
            transform.transformPosition(p.set(quad[i*5],quad[i*5+1],quad[i*5+2]));
            vertex(out,p.x,p.y,p.z,quad[i*5+3],quad[i*5+4],1);
        }
    }

private static List<float[]> readObj(String path) throws Exception {
        var positions=new ArrayList<float[]>(); var uv=new ArrayList<float[]>(); var quads=new ArrayList<float[]>();
        String text;
        try (var input = DistantMeshFixtures.class.getResourceAsStream("/fixtures/create/track/" + path)) {
            if (input == null) throw new AssertionError("Required Create OBJ fixture missing: " + path);
            text = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String line : text.split("\\R")) {
            String[] parts=line.trim().split("\\s+");
            if (parts[0].equals("v")) positions.add(new float[]{Float.parseFloat(parts[1]),Float.parseFloat(parts[2]),Float.parseFloat(parts[3])});
            else if (parts[0].equals("vt")) uv.add(new float[]{Float.parseFloat(parts[1]),Float.parseFloat(parts[2])});
            else if (parts[0].equals("f")) {
                if (parts.length!=5) throw new AssertionError("Expected quad OBJ face: " + path);
                float[] quad=new float[20];
                for (int i=0;i<4;i++) {
                    String[] indices=parts[i+1].split("/");
                    System.arraycopy(positions.get(Integer.parseInt(indices[0])-1),0,quad,i*5,3);
                    System.arraycopy(uv.get(Integer.parseInt(indices[1])-1),0,quad,i*5+3,2);
                }
                quads.add(quad);
            }
        }
        return quads;
    }

static ByteBuffer transparentPair() {
        var out = ByteBuffer.allocate(256).order(ByteOrder.nativeOrder());
        for (int x = 0; x < 2; x++) {
            vertex(out,x,0,0,x*.5f,0,3);
            vertex(out,x+1,0,0,(x+1)*.5f,0,3);
            vertex(out,x+1,1,0,(x+1)*.5f,1,3);
            vertex(out,x,1,0,x*.5f,1,3);
        }
        for (int v = 0; v < 8; v++) out.put(v*32+27,(byte)96);
        return out.flip();
    }

static ByteBuffer tiledPlane(int cells) {
        var buffer = ByteBuffer.allocate(cells * cells * 4 * 32).order(ByteOrder.nativeOrder());
        for (int y = 0; y < cells; y++) for (int x = 0; x < cells; x++) {
            for (int i : new int[]{0,1,3,2}) {
                buffer.putFloat((x + (i & 1)) / (float) cells).putFloat((y + (i >> 1)) / (float) cells).putFloat(0);
                buffer.putFloat((i & 1)).putFloat(i >> 1);
                buffer.putLong(-1L).putInt(0);
            }
        }
        return buffer.flip();
    }

}
