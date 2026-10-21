package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.testing.GpuTestContext;
import org.joml.Matrix4f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45C.*;

/** Actual shipped GLSL; adapted from TerrainLodGpuVerification and the original four-fix probe. */
@EnabledIfSystemProperty(named = "voxy.verifyTerrainGpu", matches = "true")
class TerrainLodGpuTest {
    private static GpuTestContext context;
    private static int checks;
    private static final List<Integer> programs = new ArrayList<>(), buffers = new ArrayList<>();

    @BeforeAll static void openContext() { context = GpuTestContext.open("TerrainLodGpuTest"); }
    @AfterAll static void closeContext() { if (context != null) { context.close(); context = null; } }
    @BeforeEach void resetChecks() { checks = 0; }
    @AfterEach void releaseResources(TestInfo info) {
        try {
            assertEquals(GL_NO_ERROR, glGetError(), "Terrain verification OpenGL error");
            System.out.println("GPU_CHECKS " + info.getDisplayName() + " assertionsCompleted=" + checks);
        } finally {
            glUseProgram(0);
            for (int program : programs) glDeleteProgram(program);
            for (int buffer : buffers) glDeleteBuffers(buffer);
            programs.clear(); buffers.clear();
        }
    }
    @Test void projectedZoomRequestsFinerNodesAndReturnsToCoarse() throws Exception { verifyTraversal(); }
    @Test void nativeCpuSelectionMatchesActualScreenspaceShader() throws Exception { verifyNativeSelection(); }
    @Test void fluidFlagsDoNotPolluteHeight() throws Exception { verifyFluidHeights(); }
    @Test void quadFieldsDecodeIn32BitPath() throws Exception { verifyQuadFormat(false); }
    @Test void quadFieldsDecodeIn64BitPath() throws Exception { requireInt64(); verifyQuadFormat(true); }
    @Test void glassAndFluidCornersIn32BitPath() throws Exception { verifyModels(false); }
    @Test void glassAndFluidCornersIn64BitPath() throws Exception { requireInt64(); verifyModels(true); }
    @Test void everyModelIdAndAuxPayloadIn32BitPath() throws Exception { verifyIds(false); }
    @Test void everyModelIdAndAuxPayloadIn64BitPath() throws Exception { requireInt64(); verifyIds(true); }
    @Test void sectionPositionsMatchCpuAcrossBothLayouts() throws Exception { verifyPositions(); }

    private static void requireInt64() {
        assertTrue(GL.getCapabilities().GL_ARB_gpu_shader_int64,
                "64-bit GPU checks NOT RUN: GL_ARB_gpu_shader_int64 unavailable (full suite requires it)");
    }

    private static void verifyIds(boolean wide) throws Exception {
        int count = 65536 * 16;
        String source = "#version 460 core\n"
                + (wide ? "#extension GL_ARB_gpu_shader_int64 : require\n#define QUAD_DATA_USE_64_BIT\n" : "")
                + read("lod/quad_format.glsl")
                + "\nlayout(local_size_x=64) in;\nlayout(binding=0,std430) buffer Results { uint ids[]; };\n"
                + "void main() { uint i=gl_GlobalInvocationID.x; uint id=i&65535u; uint payload=i>>16u;\n"
                + (wide ? "Quad q=(uint64_t(id)<<26u)|(uint64_t(payload)<<42u)|(uint64_t(0x2ABCDu)<<46u);\n"
                        : "Quad q=ivec2(int(id<<26u),int((id>>6u)|(payload<<10u)|(0x2ABCDu<<14u)));\n")
                + "ids[i]=extractStateId(q); }\n";
        int program = program(source);
        int buffer = buffer();
        glNamedBufferData(buffer, count * 4L, GL_STREAM_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffer);
        glUseProgram(program);
        glDispatchCompute(count / 64, 1, 1);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        int[] result = new int[count];
        glGetNamedBufferSubData(buffer, 0, result);
        for (int i = 0; i < count; i++) {
            if (result[i] != (i & 65535)) throw new AssertionError("ID decode wide=" + wide + " input=" + i + " got=" + result[i]);
            checks++;
        }
    }

    private static void verifyFluidHeights() throws IOException {
        String source="#version 460 core\nlayout(local_size_x=10) in;\n"
                +read("lod/block_model.glsl")+"""
                layout(binding=0,std430) buffer Results { float heights[]; };
                void main() {
                    uint height=gl_GlobalInvocationID.x;
                    BlockModel model;
                    model.flagsA=4096u|8192u|16384u|(height<<8u);
                    heights[height]=modelFluidHeight(model);
                }
                """;
        int program=program(source),buffer=buffer();
        glNamedBufferData(buffer,40,GL_STREAM_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,buffer);
        glUseProgram(program); glDispatchCompute(1,1,1);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        float[] result=new float[10];
        glGetNamedBufferSubData(buffer,0,result);
        for (int h=0;h<=9;h++) expect(Math.abs(result[h]-h/9f)<1e-6,"流体标记不能污染高度");

    }

    private static void verifyTraversal() throws IOException {
        String source = """
                #version 460 core
                #define NODE_DATA_BINDING 1
                #define HIZ_BINDING 0
                layout(local_size_x=1) in;
                layout(location=0) uniform mat4 MVP;
                layout(location=4) uniform float minSSS;
                layout(location=5) uniform float invP00;
                layout(location=6) uniform float invP11;
                layout(location=7) uniform float stretchMax;
                layout(binding=0,std430) buffer Results { vec4 result; };
                ivec3 camSecPos = ivec3(0);
                vec3 camSubSecPos = vec3(0);
                uint packedHizSize = 65537u;
                float uEarthRadius = 0.0;
                float uVanillaEnd = 0.0;
                """ + read("lod/frustum.glsl") + "\nFrustum frustum;\n"
                + read("lod/hierarchical/node.glsl") + read("lod/hierarchical/screenspace.glsl") + """
                void main() {
                    for (int i=0;i<6;i++) frustum.planes[i]=vec4(0,0,0,1);
                    UnpackedNode node;
                    node.pos=ivec3(-1,-1,-32);
                    node.lodLevel=2u;
                    setupScreenspace(node);
                    result=vec4(_screenSize,float(shouldDecend()),MVP[0][0],MVP[1][1]);
                }
                """;
        int buffer=buffer();
        glNamedBufferData(buffer,16,GL_STREAM_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,buffer);
        for (String defines : new String[]{"", "#define USE_REVERSE_Z\n", "#define USE_ZERO_ONE_DEPTH\n",
                "#define USE_REVERSE_Z\n#define USE_ZERO_ONE_DEPTH\n"}) {
            int program=program(source.replace("#version 460 core", "#version 460 core\n"+defines));
            glUseProgram(program);
            for (int width : new int[]{1920,3840}) {
                float previous=0;
                for (float fov : new float[]{70,30,7,3,1,70}) {
                    var m=new Matrix4f().perspective((float)Math.toRadians(fov),16f/9,.1f,50000);
                    glUniformMatrix4fv(0,false,m.get(new float[16]));
                    glUniform1f(4,256f*256/(width*(width*9/16)));
                    glUniform1f(5,1/m.m00()); glUniform1f(6,1/m.m11());
                    glUniform1f(7,(float)Math.pow(1+1/(m.m00()*m.m00())+1/(m.m11()*m.m11()),1.5));
                    glDispatchCompute(1,1,1);
                    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
                    float[] result=new float[4];
                    glGetNamedBufferSubData(buffer,0,result);
                    if (fov==70) expect(result[1]==0,"缩小后恢复粗级判断");
                    else {
                        expect(result[0]>previous,"Terrain projection did not grow: fov="+fov+", width="+width
                                +", area="+result[0]+", previous="+previous+", P00="+result[2]+"/"+m.m00()+", GL="+glGetError());
                        if (fov<=3) expect(result[1]==1,"放大后必须请求子节点");
                    }
                    previous=result[0];
                }
            }

        }

    }

    private static String wideDefines(boolean wide) {
        return wide ? "#extension GL_ARB_gpu_shader_int64 : require\n#define QUAD_DATA_USE_64_BIT\n" : "";
    }

    private static void verifyNativeSelection() throws IOException {
        String source = """
                #version 460 core
                #define NODE_DATA_BINDING 1
                #define HIZ_BINDING 0
                layout(local_size_x=64) in;
                layout(location=0) uniform mat4 MVP;
                layout(location=4) uniform float minSSS;
                layout(location=5) uniform float invP00;
                layout(location=6) uniform float invP11;
                layout(location=7) uniform float stretchMax;
                layout(location=8) uniform float fullDetailDistSq;
                layout(binding=0,std430) buffer Results { uint results[]; };
                layout(binding=2,std430) readonly buffer Inputs { ivec4 inputs[]; };
                ivec3 camSecPos = ivec3(0);
                vec3 camSubSecPos = vec3(0);
                uint packedHizSize = 65537u;
                float uEarthRadius = 0.0;
                float uVanillaEnd = 0.0;
                """ + read("lod/frustum.glsl") + "\nFrustum frustum;\n"
                + read("lod/hierarchical/node.glsl") + read("lod/hierarchical/screenspace.glsl") + """
                void main() {
                    for (int i=0;i<6;i++) frustum.planes[i]=vec4(0,0,0,1);
                    uint id=gl_GlobalInvocationID.x;
                    UnpackedNode node;
                    node.pos=inputs[id].xyz;
                    node.lodLevel=uint(inputs[id].w);
                    setupScreenspace(node);
                    vec3 p=vec3(node.pos)*(32<<node.lodLevel);
                    vec2 nearest=max(p.xz,max(vec2(0),-p.xz-float(32<<node.lodLevel)));
                    bool near=fullDetailDistSq>0 && dot(nearest,nearest)<=fullDetailDistSq;
                    results[id]=uint(near||shouldDecend());
                }
                """;
        int count=4096,program=program(source),input=buffer(),output=buffer();
        var random=new java.util.Random(925);
        int[] nodes=new int[count*4],result=new int[count];
        for (int i=0;i<count;i++) {
            nodes[i*4]=random.nextInt(33)-16;
            nodes[i*4+1]=random.nextInt(17)-8;
            nodes[i*4+2]=-2-random.nextInt(120);
            nodes[i*4+3]=1+random.nextInt(4);
        }
        java.util.Arrays.fill(nodes, 0, 4, 0);
        nodes[3] = 1;
        glNamedBufferData(input,nodes,GL_STATIC_DRAW); glNamedBufferData(output,count*4L,GL_STREAM_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,output); glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,input);
        glUseProgram(program);
        var cpu=new NativeLodSelection();
        for (float nearDetail : new float[]{0,256})
        for (float fov : new float[]{110,70,30,7}) for (float quality : new float[]{28,123,256,1024}) {
            var projection=new Matrix4f().perspective((float)Math.toRadians(fov),16f/9,.1f,50000);
            cpu.update(projection,projection,1920,1080,0,0,0,quality,nearDetail);
            glUniformMatrix4fv(0,false,projection.get(new float[16]));
            glUniform1f(4,cpu.minScreenArea); glUniform1f(5,cpu.invP00); glUniform1f(6,cpu.invP11);
            glUniform1f(7,cpu.stretchMax); glUniform1f(8,cpu.fullDetailDistanceSquared);
            glDispatchCompute(count/64,1,1);
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
            glGetNamedBufferSubData(output,0,result);
            for (int i=0;i<count;i++) expect(result[i]==(cpu.shouldDescend(nodes[i*4],nodes[i*4+1],nodes[i*4+2],nodes[i*4+3])?1:0),
                    "Native GPU/CPU subdivision mismatch: node="+i+", fov="+fov+", quality="+quality);
        }

        System.out.println("Native screenspace.glsl and CPU section selection agree on 131072 cases");
    }

    private static void verifyQuadFormat(boolean wide) throws IOException {
        String source = "#version 460 core\n" + wideDefines(wide) + """
                layout(local_size_x=64) in;
                layout(binding=0,std430) readonly buffer Input { uvec2 inputData[]; };
                layout(binding=1,std430) writeonly buffer Output { uvec4 outputData[]; };
                """ + read("lod/quad_format.glsl") + """
                void main() {
                    uint id=gl_GlobalInvocationID.x;
                    uvec2 words=inputData[id];
                    #ifdef QUAD_DATA_USE_64_BIT
                    Quad raw=uint64_t(words.x)|(uint64_t(words.y)<<32);
                    #else
                    Quad raw=ivec2(words);
                    #endif
                    outputData[id*3u]=uvec4(extractStateId(raw),extractFace(raw),extractBiomeId(raw),extractLightId(raw));
                    outputData[id*3u+1u]=uvec4(extractFluidLowerHeight(raw),extractFluidShapePayload(raw),quadUsesBlendPalette(raw),extractBlendIdx(raw));
                    outputData[id*3u+2u]=uvec4(extractPos(raw),uint(quadHasFluidShape(raw)));
                }
                """;
        int count=4096,program=program(source),input=buffer(),output=buffer();
        int[] data=new int[count*2];
        var random=new java.util.Random(715);
        for (int i=0;i<data.length;i++) data[i]=random.nextInt();
        glNamedBufferData(input,data,GL_STATIC_DRAW);
        glNamedBufferData(output,count*48L,GL_STREAM_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,input); glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,output);
        glUseProgram(program); glDispatchCompute(count/64,1,1);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        int[] result=new int[count*12];
        glGetNamedBufferSubData(output,0,result);
        for (int i=0;i<count;i++) {
            long raw=Integer.toUnsignedLong(data[i*2])|(Integer.toUnsignedLong(data[i*2+1])<<32);
            int palette=(int)(raw>>>63),aux=(int)(raw>>>42)&15;
            int[] expected={(int)(raw>>>26)&65535,(int)raw&7,(int)(raw>>>46)&511,(int)(raw>>>55)&255,
                    palette==0?aux:0,((int)(raw>>>3)&255)|(aux<<8),palette,((int)(raw>>>46)&511)|(aux<<9),
                    (int)(raw>>>21)&31,(int)(raw>>>16)&31,(int)(raw>>>11)&31,palette==0&&aux!=0?1:0};
            for (int j=0;j<12;j++) expect(result[i*12+j]==expected[j],"Quad format mismatch: wide="+wide+", field="+j);
        }

    }

    private static void verifyModels(boolean wide) throws IOException {
        String source = """
                #version 460 core
                #define PATCHED_SHADER
                layout(local_size_x=1) in;
                mat4 MVP=mat4(1);
                ivec3 baseSectionPos=ivec3(0);
                vec3 cameraSubPos=vec3(0);
                vec2 worldCurveData=vec2(0);
                float fluidDatumY=0.75;
                """ + read("lod/block_model.glsl") + read("lod/quad_format.glsl") + """
                BlockModel modelData[1];
                uint colourData[1];
                struct Result { vec4 corners[4]; uvec4 attributes; };
                layout(binding=0,std430) buffer Results { Result results[]; };
                """ + read("lod/quad_util.glsl") + """
                void main() {
                    uint level=gl_GlobalInvocationID.x;
                    uint kind=gl_GlobalInvocationID.y;
                    uint face=gl_GlobalInvocationID.z;
                    BlockModel model;
                    for (int i=0;i<6;i++) model.faceData[i]=0xF0F0u;
                    model.flagsA=4u;
                    model.colourTint=0xDC462360u;
                    model.customId=73u;
                    if (kind!=0u) model.flagsA|=4096u|(8u<<8u);
                    if (kind==2u || kind==5u) model.flagsA|=64u;
                    if (kind==3u) model.flagsA|=16u;
                    modelData[0]=model;
                    uint shape=2u|(4u<<3u)|(6u<<6u)|(7u<<9u);
                    ivec2 words=ivec2(face,0);
                    if (kind!=0u && kind<4u && face!=0u) {
                        words.x|=int((shape&255u)<<3u);
                        words.y|=int((shape>>8u)<<10u);
                    }
                    #ifdef QUAD_DATA_USE_64_BIT
                    Quad raw=uint64_t(uint(words.x))|(uint64_t(uint(words.y))<<32);
                    #else
                    Quad raw=words;
                    #endif
                    QuadData quad;
                    setupQuad(quad,raw,uvec2(level<<28u,0),true);
                    uint id=(face*6u+kind)*5u+level;
                    for (uint i=0u;i<4u;i++) results[id].corners[i]=vec4(getQuadCornerPoint(quad,i),quad.lodScale);
                    results[id].attributes=uvec4(quad.attributeData.z,quad.fluidShape,quad.attributeData.x,quad.attributeData.w);
                }
                """;
        int program=program(source.replace("#version 460 core","#version 460 core\n"+wideDefines(wide))),buffer=buffer();
        int bytes=6*6*5*80;
        glNamedBufferData(buffer,bytes,GL_STREAM_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,buffer);
        glUseProgram(program); glDispatchCompute(5,6,6);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        float[] result=new float[bytes/4];
        glGetNamedBufferSubData(buffer,0,result);
        double[] heights={3/9.0,5/9.0,8/9.0,1};
        for (int face=0;face<6;face++) for (int kind=0;kind<6;kind++) for (int level=4;level>=0;level--) {
            int base=((face*6+kind)*5+level)*20,scale=1<<level;
            int colour=Float.floatToRawIntBits(result[base+16]);
            expect(colour==0xDC462360,"Tint changed: face="+face+", kind="+kind+", level="+level
                    +", colour="+Integer.toHexString(colour)+", scale="+result[base+3]+", GL="+glGetError());
            int shape=Float.floatToRawIntBits(result[base+17]);
            expect(shape==(kind!=0 && kind<4 && face!=0 ? 1 : 0),"只有带四角数据的流体顶面和侧面使用斜面编码");
            for (int corner=0;corner<4;corner++) {
                expect(result[base+corner*4+3]==scale,"模型尺寸使用当前地形级别");
                if (kind!=0 && kind<4 && face!=0) {
                    double expected=kind==3 && face==1 && level>0 ? .75 : scale-1+heights[corner];
                    expect(Math.abs(result[base+corner*4+1]-expected)<2e-6,"放大恢复细级流体斜面，粗级水位不膨胀");
                } else if (kind>=4 && face!=0) {
                    double y=result[base+corner*4+1],top=scale-1+8/9.0;
                    expect(Math.abs(y-top)<2e-6 || (face!=1 && Math.abs(y)<2e-6),
                            "平顶流体与侧壁高度不能包含流体标记位");
                }
            }
        }

        expect(glGetError()==GL_NO_ERROR,"地形模型验证无 GL 错误");
    }

    private static void verifyPositions() throws IOException {
        String source = "#version 460 core\nlayout(local_size_x=64) in;\n" + read("lod/pos_util.glsl") + """
                layout(binding=0,std430) readonly buffer Input { uvec2 keys[]; };
                layout(binding=1,std430) writeonly buffer Output { ivec4 positions[]; };
                void main() {
                    uint i=gl_GlobalInvocationID.x;
                    positions[i]=ivec4(getLoDPosition(keys[i]),getLoDLevel(keys[i]));
                }
                """;
        int count = 4096 * 16;
        int[] keys = new int[count * 2], expected = new int[count * 4];
        var random = new java.util.Random(0x564F5859);
        for (int i = 0; i < count; i++) {
            int y = i / 16 - 2048, level = i % 16;
            int xLimit = 1 << (y >= -128 && y <= 127 ? 23 : 22);
            int x = random.nextInt(xLimit * 2) - xLimit;
            int z = random.nextInt(1 << 24) - (1 << 23);
            // Include signed coordinate boundaries at every height.
            if (level == 0) { x = -xLimit; z = -(1 << 23); }
            if (level == 15) { x = xLimit - 1; z = (1 << 23) - 1; }
            long key = me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(level, x, y, z);
            // SectionMeta stores high word first, unlike the quad payload buffers.
            keys[i * 2] = (int)(key >>> 32); keys[i * 2 + 1] = (int)key;
            expected[i * 4] = x; expected[i * 4 + 1] = y; expected[i * 4 + 2] = z; expected[i * 4 + 3] = level;
        }
        int program = program(source), input = buffer(), output = buffer();
        glNamedBufferData(input, keys, GL_STATIC_DRAW); glNamedBufferData(output, count * 16L, GL_STREAM_READ);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, input); glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, output);
        glUseProgram(program); glDispatchCompute(count / 64, 1, 1);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        int[] result = new int[expected.length]; glGetNamedBufferSubData(output, 0, result);
        for (int i = 0; i < result.length; i++) expect(result[i] == expected[i], "Section decoder mismatch at field " + i);
    }

    private static String read(String path) throws IOException {
        String source;
        try (var stream = TerrainLodGpuTest.class.getResourceAsStream("/assets/voxy/shaders/" + path)) {
            if (stream == null) throw new IOException("Missing shipped shader: " + path);
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var matcher=Pattern.compile("#import <voxy:([^>]+)>").matcher(source);
        var result=new StringBuilder();
        int end=0;
        while (matcher.find()) {
            result.append(source,end,matcher.start());
            result.append(read(matcher.group(1)));
            end=matcher.end();
        }
        result.append(source,end,source.length());
        return result.toString();
    }

    private static int buffer() {
        int buffer = glCreateBuffers(); buffers.add(buffer); return buffer;
    }

    private static int program(String source) {
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        int program = glCreateProgram(); programs.add(program);
        try {
            // The probe does not bind the node-debug log buffer; strip only unused printf calls.
            glShaderSource(shader, source.replaceAll("printf\\([^\\n]*\\);", ""));
            glCompileShader(shader);
            assertNotEquals(0, glGetShaderi(shader, GL_COMPILE_STATUS), glGetShaderInfoLog(shader));
            glAttachShader(program, shader); glLinkProgram(program);
            assertNotEquals(0, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
            return program;
        } finally { glDeleteShader(shader); }
    }

    private static void expect(boolean condition,String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
